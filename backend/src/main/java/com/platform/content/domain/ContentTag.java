package com.platform.content.domain;

import java.time.LocalDateTime;

public record ContentTag(
        Long id,
        String name,
        LocalDateTime createdAt
) {}
