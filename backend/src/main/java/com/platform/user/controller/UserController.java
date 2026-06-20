package com.platform.user.controller;

import com.platform.common.exception.PlatformException;
import com.platform.common.response.ApiResponse;
import com.platform.common.security.CurrentUser;
import com.platform.user.application.UserCommandService;
import com.platform.user.application.UserQueryService;
import com.platform.user.dto.UpdateUserProfileCommand;
import com.platform.user.dto.UserProfileResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserCommandService userCommandService;
    private final UserQueryService userQueryService;

    public UserController(UserCommandService userCommandService, UserQueryService userQueryService) {
        this.userCommandService = userCommandService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserProfileResponse> getPublicProfile(@PathVariable Long userId) {
        return ApiResponse.ok(userQueryService.getPublicProfile(userId));
    }

    @GetMapping("/by-username/{username}")
    public ApiResponse<UserProfileResponse> getPublicProfileByUsername(@PathVariable String username) {
        return ApiResponse.ok(userQueryService.getPublicProfileByUsername(username));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getCurrentUser(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(userQueryService.getCurrentUser(requireCurrentUser(currentUser).userId()));
    }

    @PutMapping("/me/profile")
    public ApiResponse<UserProfileResponse> updateMyProfile(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestBody UpdateUserProfileCommand command
    ) {
        Long userId = requireCurrentUser(currentUser).userId();
        userCommandService.updateProfile(userId, command);
        return ApiResponse.ok(userQueryService.getCurrentUser(userId));
    }

    private static CurrentUser requireCurrentUser(CurrentUser currentUser) {
        if (currentUser == null) {
            throw new PlatformException("current user is required");
        }
        return currentUser;
    }
}
