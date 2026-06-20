package com.platform.user.infrastructure;

import com.platform.user.domain.UserProfile;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class UserProfileRow {
    private Long userId;
    private String displayName;
    private String avatarUrl;
    private String bio;
    private String location;
    private String website;
    private LocalDate birthday;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserProfileRow fromDomain(UserProfile profile) {
        UserProfileRow row = new UserProfileRow();
        row.setUserId(profile.userId());
        row.setDisplayName(profile.displayName());
        row.setAvatarUrl(profile.avatarUrl());
        row.setBio(profile.bio());
        row.setLocation(profile.location());
        row.setWebsite(profile.website());
        row.setBirthday(profile.birthday());
        row.setCreatedAt(profile.createdAt());
        row.setUpdatedAt(profile.updatedAt());
        return row;
    }

    public UserProfile toDomain() {
        return new UserProfile(userId, displayName, avatarUrl, bio, location, website, birthday, createdAt, updatedAt);
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public void setBirthday(LocalDate birthday) {
        this.birthday = birthday;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
