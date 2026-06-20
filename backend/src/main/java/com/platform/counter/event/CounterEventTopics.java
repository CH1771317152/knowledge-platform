package com.platform.counter.event;

/** Default topic/group names (mirror application.yml defaults). NOT property placeholders. */
public final class CounterEventTopics {
    public static final String EVENTS = "counter-events";
    public static final String RETRY = "counter-retry";
    public static final String DLQ = "counter-dlq";
    public static final String CONSUMER_GROUP = "counter-aggregate-group";
    public static final String RETRY_CONSUMER_GROUP = "counter-aggregate-retry-group";
    public static final String RELATION_CONSUMER_GROUP = "counter-relation-group";
    public static final String CONTENT_CONSUMER_GROUP = "counter-content-group";
    private CounterEventTopics() {}
}
