package com.platform.counter.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platform.counter.repository.CounterSnapshotOutboxRepository;
import com.platform.counter.repository.CounterSnapshotOutboxRepository.OutboxRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Mirrors {@code ContentOutboxRelayTest}: unpublished rows are forwarded to Kafka verbatim and then
 * marked published. The Kafka key is {@code ARTICLE:{postId}} (partition affinity), and the payload is
 * the exact string stored in the outbox — the relay never re-serializes.
 */
class CounterSnapshotRelayTest {

    @Test
    void publishesUnpublishedSnapshotsAndMarksPublished() {
        CounterSnapshotOutboxRepository repo = mock(CounterSnapshotOutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxRow row = new OutboxRow(
                7L,
                "ARTICLE",
                123L,
                "{\"eventId\":\"e1\",\"entityType\":\"ARTICLE\",\"entityId\":123,\"likeCount\":10}");
        when(repo.findUnpublished(100)).thenReturn(List.of(row));

        CounterSnapshotRelay relay = new CounterSnapshotRelay(repo, kafka, "counter-snapshot-events", 100);
        relay.flushOnce();

        verify(kafka).send("counter-snapshot-events", "ARTICLE:123", row.payloadJson());
        verify(repo).markPublished(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyOutboxSendsNothing() {
        CounterSnapshotOutboxRepository repo = mock(CounterSnapshotOutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(repo.findUnpublished(100)).thenReturn(List.of());
        CounterSnapshotRelay relay = new CounterSnapshotRelay(repo, kafka, "counter-snapshot-events", 100);

        relay.flushOnce();

        org.mockito.Mockito.verifyNoInteractions(kafka);
    }
}
