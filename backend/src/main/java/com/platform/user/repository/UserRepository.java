package com.platform.user.repository;

import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import java.util.Optional;

public interface UserRepository {

    UserAccount save(UserAccount account, UserProfile profile);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Optional<UserAccount> findAccountById(Long userId);

    Optional<UserAccount> findAccountByUsername(String username);

    Optional<UserAccount> findAccountByUsernameOrEmail(String usernameOrEmail);

    Optional<UserAccount> findAccountByEmail(String email);

    Optional<UserAccount> findAccountByPhone(String phone);

    Optional<UserProfile> findProfileByUserId(Long userId);

    UserProfile updateProfile(UserProfile profile);

    void updatePasswordHash(Long userId, String passwordHash);

    void markEmailVerified(Long userId);

    void markPhoneVerified(Long userId);

    void updateLastLoginAt(Long userId);
}
