package com.platform.auth.dto;

import com.platform.auth.domain.TokenPair;
import com.platform.user.dto.UserProfileResponse;

public record AuthResponse(TokenPair tokenPair, UserProfileResponse currentUser) {
}
