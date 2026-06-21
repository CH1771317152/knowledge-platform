package com.platform.content.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Unified content-post lifecycle event, emitted (in-process, via
 * {@code ApplicationEventPublisher}) from {@code ContentCommandService} on each <em>actual</em>
 * state transition — i.e. NOT on idempotent no-op paths. Carries an {@link ContentPostEventType}
 * discriminator so downstream consumers (the counter module via the Kafka publisher, and any
 * future consumers) can switch on {@link #eventType()} rather than on instanceof.
 *
 * <p>Replaces the older {@link PostPublishedEvent}, which only carried the first-publish case.
 * The old type is retained as a deprecated alias so existing references compile during the
 * rollout, but the canonical in-process event is now this record.
 *
 * <p>Use the static factories ({@link #published}, {@link #unpublished}, {@link #deleted},
 * {@link #edited}, {@link #visibilityChanged}) rather than the raw constructor: they pin the
 * correct {@link ContentPostEventType} and (for {@link #visibilityChanged}) the
 * {@code changes} map shape.
 *
 * @param eventId     unique id for this event (UUID recommended); downstream consumers use this for
 *                    idempotency (counter dedup ledger, Kafka consumer offset replay)
 * @param eventType   the lifecycle transition (never null)
 * @param postId      the affected post
 * @param authorId    the post's author — the counter {@code USER}/{@code POSTS} target
 * @param occurredAt  wall-clock time the transition happened
 * @param changes     optional payload; non-empty only for {@link ContentPostEventType#POST_VISIBILITY_CHANGED},
 *                    where it carries {@code oldVisibility} + {@code newVisibility}. Empty map
 *                    ({@link Map#of()}) for the other types.
 */
public record ContentPostEvent(
        String eventId,
        ContentPostEventType eventType,
        Long postId,
        Long authorId,
        LocalDateTime occurredAt,
        Map<String, Object> changes) {

    public ContentPostEvent {
        if (changes == null) {
            changes = Map.of();
        }
    }

    public static ContentPostEvent published(String eventId, Long postId, Long authorId, LocalDateTime at) {
        return new ContentPostEvent(eventId, ContentPostEventType.POST_PUBLISHED, postId, authorId, at, Map.of());
    }

    public static ContentPostEvent unpublished(String eventId, Long postId, Long authorId, LocalDateTime at) {
        return new ContentPostEvent(eventId, ContentPostEventType.POST_UNPUBLISHED, postId, authorId, at, Map.of());
    }

    public static ContentPostEvent deleted(String eventId, Long postId, Long authorId, LocalDateTime at) {
        return new ContentPostEvent(eventId, ContentPostEventType.POST_DELETED, postId, authorId, at, Map.of());
    }

    public static ContentPostEvent edited(String eventId, Long postId, Long authorId, LocalDateTime at) {
        return new ContentPostEvent(eventId, ContentPostEventType.POST_EDITED, postId, authorId, at, Map.of());
    }

    /**
     * Visibility-change event. Carries the {@code oldVisibility}/{@code newVisibility} pair (as
     * {@link Enum#name()} strings, e.g. {@code "PUBLIC"} / {@code "PRIVATE"}) in the
     * {@code changes} map so the Kafka payload is self-describing.
     */
    public static ContentPostEvent visibilityChanged(String eventId, Long postId, Long authorId, LocalDateTime at,
                                                     String oldVisibility, String newVisibility) {
        return new ContentPostEvent(eventId, ContentPostEventType.POST_VISIBILITY_CHANGED, postId, authorId, at,
                Map.of("oldVisibility", oldVisibility, "newVisibility", newVisibility));
    }
}
