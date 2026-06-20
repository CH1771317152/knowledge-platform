package com.platform.user.domain;

public enum UserStatus {
    ACTIVE,
    DISABLED,
    LOCKED,
    DELETED;

    public boolean canLogin() {
        return this == ACTIVE;
    }
}
