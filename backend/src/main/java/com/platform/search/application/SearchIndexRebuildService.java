package com.platform.search.application;

import com.platform.content.event.ContentOutboxEvent;
import com.platform.content.repository.ContentOutboxRepository;
import com.platform.content.repository.ContentPostRepository;
import com.platform.search.config.SearchProperties;
import com.platform.search.domain.SearchPostDocument;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Rebuilds the search index from scratch while catching up on outbox events written during the scan.
 *
 * <p>The rebuild is the authoritative repair path for the search read model — used on initial bootstrap,
 * after a mapping change, after consumer drift, or to recover from index corruption. The four-step
 * order is what makes it correct under concurrent writes:
 *
 * <ol>
 *   <li><b>Record the outbox high watermark</b> BEFORE the scan starts. Any event with
 *       {@code id > watermark} was written while (or after) the scan ran, so it must be replayed.</li>
 *   <li><b>Create a new physical index</b> (e.g. {@code knowledge-posts-v2}). The live read/write
 *       aliases still point at the old index, so queries keep serving from the old read model.</li>
 *   <li><b>Bulk-scan</b> public+published posts from MySQL in ascending-id keyset batches, build a
 *       document for each, and bulk-upsert into the NEW index.</li>
 *   <li><b>Replay outbox events after the watermark</b> against the new index — each event re-checks
 *       the current source of truth (upsert if still public+published, delete otherwise). This closes
 *       the window of writes that happened during the scan.</li>
 *   <li><b>Atomically switch the read/write aliases</b> to the new index, making it live.</li>
 * </ol>
 *
 * <p>Switching aliases only AFTER the replay guarantees the new index reflects every publish/edit/
 * delete/visibility-change that landed during the scan. A small residual window of events written
 * between the last replayed id and the switch is covered by the steady-state content event consumer
 * (which writes to the alias, now pointing at the new index).
 *
 * <p>Unit tests construct the service directly with fakes for the outbox, the index, and a document
 * supplier, asserting the step order recorded by a recording index — no Spring, ES, or MySQL needed.
 */
@Service
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchIndexRebuildService {

    private final ContentOutboxRepository outboxRepository;
    private final ContentPostRepository contentRepository;
    private final SearchPostDocumentBuilder builder;
    private final SearchPostIndexRepository indexRepository;
    private final SearchProperties properties;

    public SearchIndexRebuildService(ContentOutboxRepository outboxRepository,
                                     ContentPostRepository contentRepository,
                                     SearchPostDocumentBuilder builder,
                                     SearchPostIndexRepository indexRepository,
                                     SearchProperties properties) {
        this.outboxRepository = outboxRepository;
        this.contentRepository = contentRepository;
        this.builder = builder;
        this.indexRepository = indexRepository;
        this.properties = properties;
    }

    /**
     * Rebuild into {@code newIndex}: record watermark → create index → bulk scan → replay after
     * watermark → switch aliases.
     */
    public void rebuild(String newIndex) {
        long highWatermark = outboxRepository.currentHighWatermark();
        indexRepository.createIndex(newIndex);
        scanAndBulkUpsert(newIndex);
        replayAfter(highWatermark, newIndex);
        indexRepository.switchAliases(newIndex);
    }

    private void scanAndBulkUpsert(String targetIndex) {
        long afterId = 0L;
        int scanBatch = properties.rebuild().scanBatchSize();
        while (true) {
            List<com.platform.content.domain.ContentPost> posts =
                    contentRepository.findPublicPublishedAfterId(afterId, scanBatch);
            if (posts.isEmpty()) {
                break;
            }
            List<SearchPostDocument> docs = new ArrayList<>();
            for (com.platform.content.domain.ContentPost post : posts) {
                builder.build(post.id()).ifPresent(docs::add);
                afterId = post.id();
            }
            indexRepository.bulkUpsertToIndex(targetIndex, docs);
        }
    }

    private void replayAfter(long highWatermark, String targetIndex) {
        long afterId = highWatermark;
        int scanBatch = properties.rebuild().scanBatchSize();
        while (true) {
            List<ContentOutboxEvent> events = outboxRepository.findAfterId(afterId, scanBatch);
            if (events.isEmpty()) {
                break;
            }
            for (ContentOutboxEvent event : events) {
                Optional<SearchPostDocument> doc = builder.build(event.aggregateId());
                if (doc.isPresent()) {
                    indexRepository.upsertToIndex(targetIndex, doc.get());
                } else {
                    indexRepository.deleteFromIndex(targetIndex, event.aggregateId());
                }
                afterId = event.id();
            }
        }
    }
}
