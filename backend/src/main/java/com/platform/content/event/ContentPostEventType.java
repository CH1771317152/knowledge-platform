package com.platform.content.event;

/**
 * Discriminates the lifecycle transitions captured by {@link ContentPostEvent}. The
 * {@code @TransactionalEventListener(AFTER_COMMIT)} Kafka publisher forwards this to the
 * {@code content-events} topic; the counter module switches on {@link #name()} to apply (or undo)
 * the author's {@code posts_count}.
 *
 * <ul>
 *   <li>{@link #POST_PUBLISHED} — first-publish of a DRAFT post (idempotent re-publish does NOT emit).</li>
 *   <li>{@link #POST_UNPUBLISHED} — PUBLISHED → DRAFT transition.</li>
 *   <li>{@link #POST_DELETED} — soft-delete of an active post.</li>
 *   <li>{@link #POST_EDITED} — metadata (title/summary/cover/files/tags) updated after confirmation.</li>
 *   <li>{@link #POST_VISIBILITY_CHANGED} — visibility changed via {@code updateMetadata}; co-emitted
 *       with {@link #POST_EDITED} and carries {@code oldVisibility}/{@code newVisibility} in the
 *       event's {@code changes} map.</li>
 * </ul>
 */
public enum ContentPostEventType {
    POST_PUBLISHED, POST_UNPUBLISHED, POST_DELETED, POST_EDITED, POST_VISIBILITY_CHANGED
}
