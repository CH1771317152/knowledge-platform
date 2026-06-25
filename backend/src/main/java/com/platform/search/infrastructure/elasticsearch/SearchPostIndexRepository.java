package com.platform.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.search.config.SearchProperties;
import com.platform.search.domain.SearchPostDocument;
import com.platform.search.domain.SearchPostQuery;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Adapter over the Elasticsearch client for the search index. All writes target the
 * {@code writeAlias} and all reads target the {@code readAlias}; an alias indirection lets the
 * rebuild task swap indices atomically.
 *
 * <p>Every method is declared non-final and package-private-overridable so unit tests can subclass
 * with an in-memory fake that records calls, without standing up a real Elasticsearch. The
 * production paths that actually call {@link ElasticsearchClient} are isolated in private helpers
 * that the overrides bypass.
 */
@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchPostIndexRepository {

    protected final ElasticsearchClient client;
    protected final SearchProperties properties;

    public SearchPostIndexRepository(ElasticsearchClient client, SearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** Idempotently creates the initial index + read/write aliases if they do not yet exist. */
    public void ensureInitialIndex() {
        try {
            String initial = properties.index().initialIndex();
            boolean exists = client.indices().exists(e -> e.index(initial)).value();
            if (!exists) {
                createIndex(initial);
            }
            switchAliases(initial);
        } catch (IOException e) {
            throw new IllegalStateException("failed to ensure initial search index", e);
        }
    }

    /** Creates the named index from the bundled mapping resource. */
    public void createIndex(String indexName) {
        try {
            client.indices().create(c -> c.index(indexName).withJson(mappingResource()));
        } catch (IOException e) {
            throw new IllegalStateException("failed to create index " + indexName, e);
        }
    }

    /** Upserts a single document under its {@code postId} (the document id). */
    public void upsert(SearchPostDocument document) {
        try {
            client.index(i -> i
                    .index(properties.index().writeAlias())
                    .id(String.valueOf(document.postId()))
                    .document(document));
        } catch (IOException e) {
            throw new IllegalStateException("failed to upsert post " + document.postId(), e);
        }
    }

    /** Upserts to an explicit index name (used by rebuild before the alias switch). */
    public void upsertToIndex(String indexName, SearchPostDocument document) {
        try {
            client.index(i -> i
                    .index(indexName)
                    .id(String.valueOf(document.postId()))
                    .document(document));
        } catch (IOException e) {
            throw new IllegalStateException("failed to upsert post " + document.postId(), e);
        }
    }

    /** Bulk upsert into the write alias. */
    public void bulkUpsert(List<SearchPostDocument> documents) {
        bulkUpsertToIndex(properties.index().writeAlias(), documents);
    }

    /** Bulk upsert into an explicit index name (used by rebuild). */
    public void bulkUpsertToIndex(String indexName, List<SearchPostDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder();
            for (SearchPostDocument doc : documents) {
                bulk.operations(BulkOperation.of(op -> op.index(idx -> idx
                        .index(indexName)
                        .id(String.valueOf(doc.postId()))
                        .document(doc))));
            }
            client.bulk(bulk.build());
        } catch (IOException e) {
            throw new IllegalStateException("failed to bulk upsert documents", e);
        }
    }

    /**
     * Deletes the document. A missing document is treated as success — the caller's intent
     * ("this post is no longer public") is already the case, so no error.
     */
    public void delete(Long postId) {
        try {
            client.delete(d -> d
                    .index(properties.index().writeAlias())
                    .id(String.valueOf(postId)));
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete post " + postId, e);
        }
    }

    /** Delete from an explicit index name (used by rebuild). */
    public void deleteFromIndex(String indexName, Long postId) {
        try {
            client.delete(d -> d.index(indexName).id(String.valueOf(postId)));
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete post " + postId, e);
        }
    }

    /**
     * Partial update of the five counter fields. Crucially this does NOT auto-create a missing
     * document — a counter snapshot for a post that was never indexed (or was deleted) is a no-op,
     * otherwise we'd resurrect a hidden document with no title/body.
     */
    public void updateCounters(Long postId, ArticleCountersResponse counters) {
        try {
            Map<String, Object> partial = Map.of(
                    "like_count", counters.like(),
                    "favorite_count", counters.fav(),
                    "view_count", counters.view(),
                    "comment_count", counters.comment(),
                    "share_count", counters.share());
            client.update(u -> u
                            .index(properties.index().writeAlias())
                            .id(String.valueOf(postId))
                            .doc(partial),
                    Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("failed to update counters for post " + postId, e);
        }
    }

    /**
     * Runs the search query against the read alias and maps hits to Feed-style items plus the
     * next-page sort values for cursor pagination.
     */
    public SearchResultPage search(SearchPostQuery query) {
        try {
            var response = client.search(s -> s
                            .index(properties.index().readAlias())
                            .size(query.size())
                            .query(buildQuery(query))
                            .sort(sort -> sort.field(f -> f.field("publish_time").order(
                                    co.elastic.clients.elasticsearch._types.SortOrder.Desc))),
                    SearchPostDocument.class);

            List<FeedItemResponse> items = new ArrayList<>();
            List<Object> nextSort = null;
            for (Hit<SearchPostDocument> hit : response.hits().hits()) {
                SearchPostDocument doc = hit.source();
                if (doc == null) {
                    continue;
                }
                items.add(toFeedItem(doc));
                if (hit.sort() != null) {
                    nextSort = new ArrayList<>(hit.sort());
                }
            }
            boolean hasMore = response.hits().hits().size() == query.size() && nextSort != null;
            return new SearchResultPage(items, hasMore, nextSort);
        } catch (IOException e) {
            throw new IllegalStateException("failed to search index", e);
        }
    }

    /** Atomically swaps the read and write aliases to the new index. */
    public void switchAliases(String newIndex) {
        try {
            String readAlias = properties.index().readAlias();
            String writeAlias = properties.index().writeAlias();
            client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add
                            .index(newIndex).alias(readAlias).isWriteIndex(false)))
                    .actions(a -> a.add(add -> add
                            .index(newIndex).alias(writeAlias).isWriteIndex(true))));
        } catch (IOException e) {
            throw new IllegalStateException("failed to switch aliases to " + newIndex, e);
        }
    }

    private Query buildQuery(SearchPostQuery query) {
        return Query.of(q -> q.bool(b -> {
            if (query.keyword() != null && !query.keyword().isBlank()) {
                b.must(m -> m.multiMatch(mm -> mm
                        .query(query.keyword())
                        .fields("title^" + properties.rank().titleBoost(),
                                "description^" + properties.rank().descriptionBoost(),
                                "body_text^" + properties.rank().bodyBoost())));
            }
            if (query.tag() != null && !query.tag().isBlank()) {
                b.filter(f -> f.term(t -> t.field("tags").value(query.tag())));
            }
            if (query.contentType() != null && !query.contentType().isBlank()) {
                b.filter(f -> f.term(t -> t.field("content_type").value(query.contentType())));
            }
            b.filter(f -> f.term(t -> t.field("status").value("PUBLISHED")));
            b.filter(f -> f.term(t -> t.field("visibility").value("PUBLIC")));
            return b;
        }));
    }

    private java.io.Reader mappingResource() {
        return new java.io.InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("elasticsearch/post-index-mapping.json"),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static FeedItemResponse toFeedItem(SearchPostDocument doc) {
        LocalDateTime publishedAt = doc.publishTime() == null
                ? null : LocalDateTime.ofInstant(doc.publishTime(), ZoneOffset.UTC);
        return new FeedItemResponse(
                doc.postId(),
                doc.authorId(),
                doc.authorName(),
                doc.coverObjectKey(),
                doc.title(),
                doc.description(),
                publishedAt,
                doc.likeCount(),
                doc.favoriteCount(),
                doc.viewCount(),
                doc.commentCount(),
                doc.shareCount(),
                null,
                null);
    }

    /** Search result page: mapped items + the next cursor's sort values (null if no next page). */
    public record SearchResultPage(
            List<FeedItemResponse> items,
            boolean hasMore,
            List<Object> nextSortValues) {}
}
