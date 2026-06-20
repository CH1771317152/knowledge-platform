package com.platform.counter.event;

import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import java.time.LocalDateTime;

public record CounterEventPayload(
        String eventId,
        CounterEntityType etype,
        CounterMetric metric,
        Long eid,
        long delta,
        Long authorId,          // nullable; present for content interactions to fan out to author counts
        LocalDateTime occurredAt) {
}
