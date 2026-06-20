package com.platform.relation.event;

/**
 * Default Kafka topic and consumer-group NAME constants for the relation module.
 *
 * <p>These are plain default-name constants, useful as documentation and for tests that construct
 * consumers directly. They intentionally do NOT contain Spring {@code ${...}} property
 * placeholders: such placeholders are <em>not</em> resolved inside arbitrary {@code static final
 * String} fields. The actual wiring resolves the property elsewhere:
 *
 * <ul>
 *   <li>{@code @KafkaListener(topics = "${platform.relation.kafka.events-topic}")} (Task 5) —
 *       Spring resolves the placeholder on the annotation.
 *   <li>Programmatic sends use names injected via {@code @Value} from
 *       {@code application.yml}, where the defaults below mirror the {@code RELATION_*} env
 *       defaults.
 * </ul>
 *
 * <p>The defaults here mirror the {@code :relation-events} / {@code :relation-follower-retry} /
 * etc. defaults declared in {@code application.yml} under
 * {@code platform.relation.kafka.*}.
 */
public final class RelationEventTopics {

    /** Default events topic name. Configured via {@code platform.relation.kafka.events-topic}. */
    public static final String EVENTS = "relation-events";

    /** Default follower retry topic name. Configured via {@code platform.relation.kafka.follower-retry-topic}. */
    public static final String FOLLOWER_RETRY = "relation-follower-retry";

    /** Default follower DLQ topic name. Configured via {@code platform.relation.kafka.follower-dlq-topic}. */
    public static final String FOLLOWER_DLQ = "relation-follower-dlq";

    /** Default follower-projector consumer group. Configured via {@code platform.relation.kafka.follower-consumer-group}. */
    public static final String FOLLOWER_CONSUMER_GROUP = "relation-follower-projector-group";

    /** Default follower-retry consumer group. Configured via {@code platform.relation.kafka.follower-retry-consumer-group}. */
    public static final String FOLLOWER_RETRY_CONSUMER_GROUP = "relation-follower-retry-group";

    private RelationEventTopics() {}
}
