package com.platform.user.domain;

import java.time.LocalDateTime;

public record UserAccount(
        Long id,
        String username,
        String email,
        String phone,
        String passwordHash,
        UserStatus status,
        UserRole role,
        boolean emailVerified,
        boolean phoneVerified,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public UserAccount {
        if (isBlank(username)) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (isBlank(email)) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (isBlank(passwordHash)) {
            throw new IllegalArgumentException("passwordHash must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
