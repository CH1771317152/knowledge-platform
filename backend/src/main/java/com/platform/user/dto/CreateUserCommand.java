package com.platform.user.dto;

public record CreateUserCommand(
        String username,
        String email,
        String phone,
        String passwordHash
) {
}
