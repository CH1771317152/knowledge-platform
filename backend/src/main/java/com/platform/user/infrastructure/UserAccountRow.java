package com.platform.user.infrastructure;

import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import java.time.LocalDateTime;

public class UserAccountRow {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private String passwordHash;
    private String status;
    private String role;
    private Boolean emailVerified;
    private Boolean phoneVerified;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserAccountRow fromDomain(UserAccount account) {
        UserAccountRow row = new UserAccountRow();
        row.setId(account.id());
        row.setUsername(account.username());
        row.setEmail(account.email());
        row.setPhone(account.phone());
        row.setPasswordHash(account.passwordHash());
        row.setStatus(account.status().name());
        row.setRole(account.role().name());
        row.setEmailVerified(account.emailVerified());
        row.setPhoneVerified(account.phoneVerified());
        row.setLastLoginAt(account.lastLoginAt());
        row.setCreatedAt(account.createdAt());
        row.setUpdatedAt(account.updatedAt());
        return row;
    }

    public UserAccount toDomain() {
        return new UserAccount(id, username, email, phone, passwordHash,
                UserStatus.valueOf(status), UserRole.valueOf(role),
                Boolean.TRUE.equals(emailVerified), Boolean.TRUE.equals(phoneVerified),
                lastLoginAt, createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(Boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Boolean getPhoneVerified() {
        return phoneVerified;
    }

    public void setPhoneVerified(Boolean phoneVerified) {
        this.phoneVerified = phoneVerified;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
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
