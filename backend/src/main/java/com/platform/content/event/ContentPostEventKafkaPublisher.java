package com.platform.content.event;

/**
 * Retired. Content lifecycle events are now emitted transactionally through {@code content_outbox}
 * (written by {@code ContentCommandService} via {@code ContentOutboxAppender}) and forwarded to the
 * {@code content-events} Kafka topic by {@link ContentOutboxRelay}, instead of via an in-process
 * {@code @TransactionalEventListener}.
 *
 * <p>Retained as an empty deprecated marker so historical references remain searchable during the
 * rollout. Do not reintroduce direct Kafka publishing here — the outbox is the reliable path.
 */
@Deprecated
public final class ContentPostEventKafkaPublisher {
    private ContentPostEventKafkaPublisher() {}
}
