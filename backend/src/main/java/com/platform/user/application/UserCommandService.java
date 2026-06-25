package com.platform.user.application;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.common.util.Strings;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.dto.CreateUserCommand;
import com.platform.user.dto.UpdateUserProfileCommand;
import com.platform.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserCommandService {

    private final UserRepository userRepository;

    public UserCommandService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserAccount createUser(CreateUserCommand command) {
        String username = normalizeRequired(command.username(), "username");
        String email = normalizeRequired(command.email(), "email");
        String passwordHash = normalizeRequired(command.passwordHash(), "passwordHash");

        if (userRepository.existsByUsername(username)) {
            throw new PlatformException("username already exists");
        }
        if (userRepository.existsByEmail(email)) {
            throw new PlatformException("email already exists");
        }

        UserAccount account = new UserAccount(null, username, email, Strings.trimToNull(command.phone()),
                passwordHash, UserStatus.ACTIVE, UserRole.USER, false, false, null, null, null);
        UserProfile profile = new UserProfile(null, username, null, null, null, null, null, null, null);
        return userRepository.save(account, profile);
    }

    @Transactional
    public UserProfile updateProfile(Long userId, UpdateUserProfileCommand command) {
        userRepository.findAccountById(userId)
                .orElseThrow(() -> new PlatformException("user not found"));

        UserProfile existing = userRepository.findProfileByUserId(userId)
                .orElseThrow(() -> new PlatformException("user profile not found"));
        UserProfile updated = new UserProfile(userId,
                normalizeRequired(command.displayName(), "displayName"),
                Strings.trimToNull(command.avatarUrl()),
                Strings.trimToNull(command.bio()),
                Strings.trimToNull(command.location()),
                Strings.trimToNull(command.website()),
                command.birthday(),
                existing.createdAt(),
                existing.updatedAt());

        if (updated.equals(existing)) {
            return existing;
        }
        return userRepository.updateProfile(updated);
    }

    @Transactional
    public void updatePasswordHash(Long userId, String passwordHash) {
        userRepository.findAccountById(userId)
                .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
        userRepository.updatePasswordHash(userId, normalizeRequired(passwordHash, "passwordHash"));
    }

    @Transactional
    public void markVerified(Long userId, boolean emailVerified, boolean phoneVerified) {
        userRepository.findAccountById(userId)
                .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
        if (emailVerified) {
            userRepository.markEmailVerified(userId);
        }
        if (phoneVerified) {
            userRepository.markPhoneVerified(userId);
        }
    }

    @Transactional
    public void recordSuccessfulLogin(Long userId) {
        userRepository.findAccountById(userId)
                .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
        userRepository.updateLastLoginAt(userId);
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = Strings.trimToNull(value);
        if (normalized == null) {
            throw new PlatformException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
