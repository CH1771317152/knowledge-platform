package com.platform.content.event;

import java.time.LocalDateTime;

/**
 * Emitted (in-process, via {@code ApplicationEventPublisher}) when a post transitions to PUBLISHED
 * for the first time — i.e. {@code publishedAt} was null and the post becomes PUBLISHED. It is NOT
 * emitted on an idempotent re-publish of an already-published post.
 *
 * <p>Consumed by the counter module to increment the author's {@code posts_count}. A
 * {@code @TransactionalEventListener(AFTER_COMMIT)} Kafka publisher serializes this event to the
 * {@code content-events} Kafka topic after the publish transaction commits.
 */
public record PostPublishedEvent(
        String eventId,
        Long postId,
        Long authorId,
        LocalDateTime occurredAt) {
}
