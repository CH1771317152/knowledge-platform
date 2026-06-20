package com.platform.user.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UserProfile(
        Long userId,
        String displayName,
        String avatarUrl,
        String bio,
        String location,
        String website,
        LocalDate birthday,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public UserProfile {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
