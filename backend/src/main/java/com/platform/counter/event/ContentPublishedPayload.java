package com.platform.counter.event;

/**
 * The raw-JSON payload the content module publishes to {@code content-events} on a post publish
 * (see {@code ContentPostEventKafkaPublisher}). Unlike the relation module's Canal-wrapped
 * {@code relation_outbox} events, content publishes <em>directly</em> from an
 * {@code AFTER_COMMIT} {@code @TransactionalEventListener}, so the Kafka value is a plain JSON
 * object — not a Canal flat message — and is parsed directly with Jackson. Only {@code eventId}
 * (for idempotency) and {@code authorId} (the {@code POSTS} agg target) are consumed by counter.
 *
 * @param eventId    event-unique id (drives the dedup ledger)
 * @param postId     the published post (unused by counter, but present in the payload)
 * @param authorId   the post's author — target of the {@code USER}/{@code POSTS} +1
 * @param eventType  discriminates the content event ({@code POST_PUBLISHED}, {@code POST_UNPUBLISHED},
 *                   {@code POST_DELETED}, {@code POST_EDITED}, {@code POST_VISIBILITY_CHANGED});
 *                   defaults to {@code "POST_PUBLISHED"} via the 4-arg constructor.
 * @param occurredAt ISO-8601 timestamp string, as emitted by the publisher
 */
public record ContentPublishedPayload(
        String eventId,
        Long postId,
        Long authorId,
        String eventType,
        String occurredAt
) {
    /**
     * Backward-compat constructor for publishers/tests that don't carry an {@code eventType} yet;
     * defaults to {@code "POST_PUBLISHED"} (the original publish-only contract).
     */
    public ContentPublishedPayload(String eventId, Long postId, Long authorId, String occurredAt) {
        this(eventId, postId, authorId, "POST_PUBLISHED", occurredAt);
    }
}
