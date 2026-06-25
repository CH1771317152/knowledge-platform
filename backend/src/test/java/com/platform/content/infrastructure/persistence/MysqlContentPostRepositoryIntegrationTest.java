package com.platform.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.ContentTag;
import com.platform.content.domain.PostBodyFormat;
import com.platform.content.domain.PostFileUsageType;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.infrastructure.id.ContentIdGenerator;
import com.platform.content.repository.ContentPostRepository;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class MysqlContentPostRepositoryIntegrationTest {

    private static final String USERNAME = "it_content_user";
    private static final String EMAIL = "it_content_user@example.com";
    private static final String PHONE = "13700000420";

    @Autowired
    private ContentPostRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentIdGenerator idGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long authorId;
    private Long postId;

    @BeforeEach
    void createUserAndPostId() {
        jdbcTemplate.update("""
                DELETE FROM content_post_tag
                WHERE post_id IN (SELECT id FROM content_post
                    WHERE author_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?))
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM content_post_file
                WHERE post_id IN (SELECT id FROM content_post
                    WHERE author_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?))
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM content_post_body
                WHERE post_id IN (SELECT id FROM content_post
                    WHERE author_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?))
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM content_post
                WHERE author_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?)
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM user_profile
                WHERE user_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?)
                """, USERNAME, EMAIL);
        jdbcTemplate.update("DELETE FROM user_account WHERE username = ? OR email = ?", USERNAME, EMAIL);

        UserAccount account = new UserAccount(null, USERNAME, EMAIL, PHONE, "hash",
                UserStatus.ACTIVE, UserRole.USER, false, false, null, null, null);
        UserProfile profile = new UserProfile(null, "Content User", null, "bio", "Shanghai",
                "https://example.com", LocalDate.of(1995, 5, 20), null, null);
        UserAccount saved = userRepository.save(account, profile);
        this.authorId = saved.id();
        this.postId = idGenerator.nextId();
    }

    @Test
    void savesDraftPersistsBodyAndAdvancesThroughPublishLifecycle() {
        String clientRequestId = "req-" + postId;
        ContentPost draft = new ContentPost(postId, authorId, clientRequestId, "Draft Title", "summary",
                null, PostStatus.DRAFT, PostVisibility.PRIVATE, PublishStage.DRAFT_CREATED, null, null, null, 0L);
        ContentPostBody body = new ContentPostBody(postId, PostBodyFormat.MARKDOWN, "kp-bucket", null,
                null, null, 0L, 1, null, null, null, null);

        ContentPost savedDraft = repository.saveDraft(draft, body);

        // Step 3: post + body rows exist with DB-generated timestamps.
        assertThat(savedDraft.createdAt()).isNotNull();
        assertThat(savedDraft.updatedAt()).isNotNull();
        assertThat(repository.findPostById(postId)).contains(savedDraft);
        ContentPostBody savedBody = repository.findBodyByPostId(postId).orElseThrow();
        assertThat(savedBody.bodyBucket()).isEqualTo("kp-bucket");
        assertThat(savedBody.bodyFormat()).isEqualTo(PostBodyFormat.MARKDOWN);

        // Step 4: updateBodyUploadUrl persists objectKey/expiresAt and advances stage.
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        repository.updateBodyUploadUrl(postId, "kp-bucket", "posts/" + postId + "/body.md", expiresAt,
                PublishStage.BODY_URL_ISSUED);
        ContentPostBody afterUpload = repository.findBodyByPostId(postId).orElseThrow();
        assertThat(afterUpload.bodyObjectKey()).isEqualTo("posts/" + postId + "/body.md");
        assertThat(afterUpload.uploadUrlExpiresAt()).isNotNull();
        ContentPost postAfterUpload = repository.findPostById(postId).orElseThrow();
        assertThat(postAfterUpload.publishStage()).isEqualTo(PublishStage.BODY_URL_ISSUED);

        // Step 5: confirmBody persists etag/sha256/size/confirmedAt.
        LocalDateTime confirmedAt = LocalDateTime.now();
        repository.confirmBody(postId, "posts/" + postId + "/body.md", "etag-abc",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 1234L, confirmedAt);
        ContentPostBody confirmed = repository.findBodyByPostId(postId).orElseThrow();
        assertThat(confirmed.bodyEtag()).isEqualTo("etag-abc");
        assertThat(confirmed.bodySha256()).isEqualTo(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        assertThat(confirmed.bodySizeBytes()).isEqualTo(1234L);
        assertThat(confirmed.confirmedAt()).isNotNull();

        // Step 6: updateMetadata.
        repository.updateMetadata(postId, "Real Title", "Real summary", PostVisibility.PUBLIC,
                "covers/" + postId + ".png");
        ContentPost withMeta = repository.findPostById(postId).orElseThrow();
        assertThat(withMeta.title()).isEqualTo("Real Title");
        assertThat(withMeta.summary()).isEqualTo("Real summary");
        assertThat(withMeta.visibility()).isEqualTo(PostVisibility.PUBLIC);
        assertThat(withMeta.coverObjectKey()).isEqualTo("covers/" + postId + ".png");

        // Step 7: replaceFiles with one COVER and one ATTACHMENT.
        repository.replaceFiles(postId, List.of(
                new ContentPostFile(postId, "covers/" + postId + ".png", PostFileUsageType.COVER,
                        "image/png", 50_000L, 0, null),
                new ContentPostFile(postId, "attach/" + postId + "/doc.pdf", PostFileUsageType.ATTACHMENT,
                        "application/pdf", 200_000L, 1, null)
        ));
        List<ContentPostFile> files = repository.findFilesByPostId(postId);
        assertThat(files).hasSize(2);
        assertThat(files).extracting(ContentPostFile::usageType)
                .containsExactlyInAnyOrder(PostFileUsageType.COVER, PostFileUsageType.ATTACHMENT);

        // Step 8: replaceTags dedups ["Java","Spring","Java"] -> 2 associations.
        repository.replaceTags(postId, List.of("Java", "Spring", "Java"));
        List<ContentTag> tags = repository.findTagsByPostId(postId);
        assertThat(tags).hasSize(2);
        assertThat(tags).extracting(ContentTag::name).containsExactlyInAnyOrder("java", "spring");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM content_post_tag WHERE post_id = ?", Integer.class, postId))
                .isEqualTo(2);

        // Step 9: publish -> appears in findPublicPublished.
        LocalDateTime publishedAt = LocalDateTime.now();
        repository.updateStatusAndStage(postId, PostStatus.PUBLISHED, PublishStage.PUBLISHED, publishedAt);
        List<ContentPost> feed = repository.findPublicPublished(10, 0);
        assertThat(feed).extracting(ContentPost::id).contains(postId);

        // Step 10: softDelete removes from feed AND from author listing.
        repository.softDelete(postId);
        assertThat(repository.findPublicPublished(10, 0)).extracting(ContentPost::id).doesNotContain(postId);
        assertThat(repository.findByAuthor(authorId, 10, 0)).extracting(ContentPost::id).doesNotContain(postId);
    }

    @Test
    void findByAuthorExcludesDeletedAndOrdersByCreatedAtDesc() {
        Long first = idGenerator.nextId();
        Long second = idGenerator.nextId();
        Long third = idGenerator.nextId();

        saveSimpleDraft(first, "first");
        saveSimpleDraft(second, "second");
        saveSimpleDraft(third, "third");

        repository.softDelete(second);

        List<ContentPost> byAuthor = repository.findByAuthor(authorId, 10, 0);
        assertThat(byAuthor).extracting(ContentPost::id).doesNotContain(second);
        assertThat(byAuthor).extracting(ContentPost::id).contains(first, third);
        // newest (largest created_at) first
        assertThat(byAuthor.get(0).id()).isEqualTo(third);
    }

    private void saveSimpleDraft(Long id, String clientRequestId) {
        ContentPost draft = new ContentPost(id, authorId, clientRequestId, "T-" + clientRequestId, null,
                null, PostStatus.DRAFT, PostVisibility.PRIVATE, PublishStage.DRAFT_CREATED, null, null, null, 0L);
        ContentPostBody body = new ContentPostBody(id, PostBodyFormat.MARKDOWN, null, null, null, null,
                0L, 1, null, null, null, null);
        repository.saveDraft(draft, body);
    }

    @Test
    void findPublicPublishedOrdersByPublishedAtDescAndExcludesNonPublic() {
        Long olderPublicId = idGenerator.nextId();
        Long newerPublicId = idGenerator.nextId();
        Long privatePublishedId = idGenerator.nextId();
        Long draftId = idGenerator.nextId();

        saveSimpleDraft(olderPublicId, "req-older-public");
        saveSimpleDraft(newerPublicId, "req-newer-public");
        saveSimpleDraft(privatePublishedId, "req-private-published");
        saveSimpleDraft(draftId, "req-draft");

        LocalDateTime now = LocalDateTime.now();
        // Distinct published_at values so ordering is deterministic.
        LocalDateTime olderPublicAt = now.minusMinutes(10);
        LocalDateTime newerPublicAt = now.minusMinutes(1);

        repository.updateStatusAndStage(olderPublicId, PostStatus.PUBLISHED, PublishStage.PUBLISHED,
                olderPublicAt);
        repository.updateStatusAndStage(newerPublicId, PostStatus.PUBLISHED, PublishStage.PUBLISHED,
                newerPublicAt);
        // Flip the visibility of the two public posts to PUBLIC (saveSimpleDraft defaults PRIVATE).
        repository.updateMetadata(olderPublicId, "Older", null, PostVisibility.PUBLIC, null);
        repository.updateMetadata(newerPublicId, "Newer", null, PostVisibility.PUBLIC, null);

        // PUBLISHED but still PRIVATE -> must be excluded.
        repository.updateStatusAndStage(privatePublishedId, PostStatus.PUBLISHED, PublishStage.PUBLISHED,
                now.minusMinutes(5));
        // DRAFT -> must be excluded (leave it as drafted, never published).
        // saveSimpleDraft already left draftId as DRAFT+PRIVATE.

        List<ContentPost> feed = repository.findPublicPublished(10, 0);

        // Only the two PUBLISHED+PUBLIC posts are returned, newest published_at first.
        assertThat(feed).extracting(ContentPost::id).containsExactlyInAnyOrder(olderPublicId, newerPublicId);
        assertThat(feed).extracting(ContentPost::id).doesNotContain(privatePublishedId, draftId);

        // ORDER BY published_at DESC: the newer-public (minusMinutes(1)) precedes the older (minusMinutes(10)).
        assertThat(feed).extracting(ContentPost::publishedAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(feed.get(0).id()).isEqualTo(newerPublicId);
        assertThat(feed.get(1).id()).isEqualTo(olderPublicId);
    }
}
