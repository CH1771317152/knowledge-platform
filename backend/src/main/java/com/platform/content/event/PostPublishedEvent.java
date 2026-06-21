package com.platform.content.event;

import java.time.LocalDateTime;

/**
 * @deprecated replaced by {@link ContentPostEvent} (the unified lifecycle event with an
 * {@link ContentPostEventType} discriminator). The canonical in-process event is now
 * {@link ContentPostEvent#published(String, Long, Long, LocalDateTime)}; the Kafka publisher
 * now listens for {@link ContentPostEvent}. This type is retained as a thin deprecated alias
 * so any out-of-tree references compile during the rollout — new code MUST NOT use it.
 */
@Deprecated(forRemoval = true)
public record PostPublishedEvent(
        String eventId,
        Long postId,
        Long authorId,
        LocalDateTime occurredAt) {

    /**
     * Convenience factory that returns the new canonical {@link ContentPostEvent} for the
     * first-publish transition. Existing call sites that constructed this record directly can
     * switch to this method without further changes.
     */
    @Deprecated(forRemoval = true)
    public static ContentPostEvent asContentPostEvent(String eventId, Long postId, Long authorId, LocalDateTime at) {
        return ContentPostEvent.published(eventId, postId, authorId, at);
    }
}
