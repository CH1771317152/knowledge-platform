package com.platform.user.dto;

import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record UserProfileResponse(
        Long userId,
        String username,
        String email,
        String displayName,
        String avatarUrl,
        String bio,
        String location,
        String website,
        LocalDate birthday,
        UserStatus status,
        LocalDateTime accountCreatedAt,
        LocalDateTime profileUpdatedAt
) {
    public static UserProfileResponse publicProfile(UserAccount account, UserProfile profile) {
        return new UserProfileResponse(account.id(), account.username(), null, profile.displayName(),
                profile.avatarUrl(), profile.bio(), profile.location(), profile.website(), profile.birthday(),
                account.status(), account.createdAt(), profile.updatedAt());
    }

    public static UserProfileResponse currentUser(UserAccount account, UserProfile profile) {
        return new UserProfileResponse(account.id(), account.username(), account.email(), profile.displayName(),
                profile.avatarUrl(), profile.bio(), profile.location(), profile.website(), profile.birthday(),
                account.status(), account.createdAt(), profile.updatedAt());
    }
}
