package com.platform.user.application;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.dto.UserProfileResponse;
import com.platform.user.repository.UserRepository;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserQueryService {

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getPublicProfile(Long userId) {
        UserAccount account = findAccountById(userId);
        UserProfile profile = findProfileByUserId(userId);
        return UserProfileResponse.publicProfile(account, profile);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUser(Long userId) {
        UserAccount account = findAccountById(userId);
        UserProfile profile = findProfileByUserId(userId);
        return UserProfileResponse.currentUser(account, profile);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getPublicProfileByUsername(String username) {
        UserAccount account = userRepository.findAccountByUsername(username)
                .orElseThrow(() -> new PlatformException("user not found"));
        UserProfile profile = findProfileByUserId(account.id());
        return UserProfileResponse.publicProfile(account, profile);
    }

    @Transactional(readOnly = true)
    public UserAccount findAccountByUsernameOrEmail(String usernameOrEmail) {
        return userRepository.findAccountByUsernameOrEmail(usernameOrEmail)
                .orElseThrow(() -> new PlatformException("user not found"));
    }

    @Transactional(readOnly = true)
    public UserAccount findAccountByEmail(String email) {
        String normalized = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findAccountByEmail(normalized)
                .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
    }

    @Transactional(readOnly = true)
    public UserAccount findAccountByPhone(String phone) {
        String normalized = phone == null ? null : phone.trim();
        return userRepository.findAccountByPhone(normalized)
                .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
    }

    /**
     * Loads a user account by id, throwing if absent. Public so the auth module can resolve
     * username/role when minting a fresh access token during refresh-token rotation.
     */
    @Transactional(readOnly = true)
    public UserAccount findAccountById(Long userId) {
        return userRepository.findAccountById(userId)
                .orElseThrow(() -> new PlatformException("user not found"));
    }

    private UserProfile findProfileByUserId(Long userId) {
        return userRepository.findProfileByUserId(userId)
                .orElseThrow(() -> new PlatformException("user profile not found"));
    }
}
