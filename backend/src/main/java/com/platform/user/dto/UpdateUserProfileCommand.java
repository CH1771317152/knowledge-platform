package com.platform.user.dto;

import java.time.LocalDate;

public record UpdateUserProfileCommand(
        String displayName,
        String avatarUrl,
        String bio,
        String location,
        String website,
        LocalDate birthday
) {
}
