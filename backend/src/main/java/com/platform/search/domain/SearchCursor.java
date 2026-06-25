package com.platform.search.domain;

import java.time.Instant;
import java.util.List;

public record SearchCursor(
        String queryHash,
        Instant rankNow,
        Instant expiresAt,
        List<Object> sortValues) {}
