package com.platform.search.application;

import com.platform.common.exception.PlatformException;
import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentTag;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.repository.ContentPostRepository;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.search.domain.SearchPostDocument;
import com.platform.search.config.SearchProperties;
import com.platform.storage.application.ObjectStorageService;
import com.platform.user.application.UserQueryService;
import com.platform.user.dto.UserProfileResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the current {@link SearchPostDocument} for a post from the four source-of-truth modules
 * (Content, Storage, User, Counter) so the search index always reflects current facts rather than the
 * stale event payload that triggered the rebuild.
 *
 * <p>Visibility gate: only {@code PUBLISHED + PUBLIC} posts are indexed. A post that is draft, private,
 * or soft-deleted resolves to {@link Optional#empty()} so the caller deletes it from the index rather
 * than upserting a hidden document.
 *
 * <p>Failure semantics: the OSS body read is the one external dependency that can fail transiently. A
 * read failure is allowed to propagate (wrapped in an {@link IllegalStateException}) so the content
 * event consumer routes the message to retry/DLQ rather than silently skipping the index update.
 *
 * <p>Unit tests construct the builder directly via its package-private constructor, injecting fakes
 * for the content/storage/counter/user dependencies so no Spring or real OSS is required.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchPostDocumentBuilder {

    private static final String CONTENT_TYPE_ARTICLE = "ARTICLE";

    private final ContentPostRepository contentRepository;
    private final ObjectStorageService objectStorageService;
    private final CounterReadService counterReadService;
    private final UserQueryService userQueryService;
    private final MarkdownTextExtractor markdownTextExtractor;

    public SearchPostDocumentBuilder(
            ContentPostRepository contentRepository,
            ObjectStorageService objectStorageService,
            CounterReadService counterReadService,
            UserQueryService userQueryService,
            SearchProperties properties) {
        this(contentRepository,
                objectStorageService,
                counterReadService,
                userQueryService,
                new MarkdownTextExtractor(properties.index().bodyMaxChars()));
    }

    SearchPostDocumentBuilder(
            ContentPostRepository contentRepository,
            ObjectStorageService objectStorageService,
            CounterReadService counterReadService,
            UserQueryService userQueryService,
            MarkdownTextExtractor markdownTextExtractor) {
        this.contentRepository = contentRepository;
        this.objectStorageService = objectStorageService;
        this.counterReadService = counterReadService;
        this.userQueryService = userQueryService;
        this.markdownTextExtractor = markdownTextExtractor;
    }

    /**
     * Builds the search document for {@code postId} from current facts.
     *
     * @return the document, or {@link Optional#empty()} if the post is missing, not published, not
     *         public, or has no confirmed body — in all those cases the caller should delete any
     *         stale index entry rather than upsert.
     * @throws IllegalStateException if the OSS body read fails (so the consumer routes to retry/DLQ).
     */
    public Optional<SearchPostDocument> build(Long postId) {
        ContentPost post = contentRepository.findPostById(postId).orElse(null);
        if (post == null
                || post.status() != PostStatus.PUBLISHED
                || post.visibility() != PostVisibility.PUBLIC) {
            return Optional.empty();
        }
        ContentPostBody body = contentRepository.findBodyByPostId(postId).orElse(null);
        if (body == null || body.bodyObjectKey() == null) {
            return Optional.empty();
        }

        String markdown = readBodyUtf8(body.bodyObjectKey());
        String bodyText = markdownTextExtractor.extract(markdown);

        ArticleCountersResponse counters = counterReadService.getArticleCounters(postId);

        List<String> tags = contentRepository.findTagsByPostId(postId).stream()
                .map(ContentTag::name)
                .toList();

        AuthorSnapshot author = authorSnapshot(post.authorId());

        return Optional.of(new SearchPostDocument(
                post.id(),
                CONTENT_TYPE_ARTICLE,
                post.status().name(),
                post.visibility().name(),
                post.authorId(),
                author.displayName(),
                author.avatarUrl(),
                post.title(),
                post.summary(),
                bodyText,
                post.coverObjectKey(),
                tags,
                tagsJson(tags),
                toInstant(post.publishedAt()),
                toInstant(post.updatedAt()),
                counters.like(),
                counters.fav(),
                counters.view(),
                counters.comment(),
                counters.share(),
                post.sourceVersion(),
                Instant.now()));
    }

    private String readBodyUtf8(String objectKey) {
        try (InputStream in = objectStorageService.readObject(objectKey)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Any failure here — OSS transport error, missing object, or byte-read I/O — must
            // propagate so the content event consumer routes the message to retry/DLQ rather than
            // silently dropping the index update. Wrapped to keep the consumer's catch-all simple.
            throw new IllegalStateException("failed to read post body from storage: " + objectKey, e);
        }
    }

    private AuthorSnapshot authorSnapshot(Long authorId) {
        if (userQueryService == null) {
            return new AuthorSnapshot(null, null);
        }
        try {
            UserProfileResponse profile = userQueryService.getPublicProfile(authorId);
            return new AuthorSnapshot(profile.displayName(), profile.avatarUrl());
        } catch (PlatformException missing) {
            // Author profile absent: index the post with a null byline rather than failing the rebuild.
            return new AuthorSnapshot(null, null);
        }
    }

    private static Map<String, Object> tagsJson(List<String> tags) {
        // The ES mapping uses a `flattened` field for tag facets; an object keyed by tag name keeps
        // it queryable without exploding the mapping. Insertion order is preserved.
        Map<String, Object> json = new LinkedHashMap<>();
        for (String tag : tags) {
            json.put(tag, Boolean.TRUE);
        }
        return json;
    }

    private static Instant toInstant(LocalDateTime at) {
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }

    private record AuthorSnapshot(String displayName, String avatarUrl) {}
}
