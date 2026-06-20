package com.platform.user.infrastructure;

import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.repository.UserRepository;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class MysqlUserRepository implements UserRepository {

    private final UserMapper userMapper;

    public MysqlUserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserAccount save(UserAccount account, UserProfile profile) {
        UserAccountRow accountRow = UserAccountRow.fromDomain(account);
        userMapper.insertAccount(accountRow);

        UserProfileRow profileRow = UserProfileRow.fromDomain(new UserProfile(accountRow.getId(),
                profile.displayName(), profile.avatarUrl(), profile.bio(), profile.location(), profile.website(),
                profile.birthday(), profile.createdAt(), profile.updatedAt()));
        userMapper.insertProfile(profileRow);

        return findAccountById(accountRow.getId()).orElseThrow();
    }

    @Override
    public boolean existsByUsername(String username) {
        return userMapper.countByUsername(username) > 0;
    }

    @Override
    public boolean existsByEmail(String email) {
        return userMapper.countByEmail(email) > 0;
    }

    @Override
    public Optional<UserAccount> findAccountById(Long userId) {
        return userMapper.findAccountById(userId).map(UserAccountRow::toDomain);
    }

    @Override
    public Optional<UserAccount> findAccountByUsername(String username) {
        return userMapper.findAccountByUsername(username).map(UserAccountRow::toDomain);
    }

    @Override
    public Optional<UserAccount> findAccountByUsernameOrEmail(String usernameOrEmail) {
        return userMapper.findAccountByUsernameOrEmail(usernameOrEmail).map(UserAccountRow::toDomain);
    }

    @Override
    public Optional<UserAccount> findAccountByEmail(String email) {
        return userMapper.findAccountByEmail(email).map(UserAccountRow::toDomain);
    }

    @Override
    public Optional<UserAccount> findAccountByPhone(String phone) {
        return userMapper.findAccountByPhone(phone).map(UserAccountRow::toDomain);
    }

    @Override
    public Optional<UserProfile> findProfileByUserId(Long userId) {
        return userMapper.findProfileByUserId(userId).map(UserProfileRow::toDomain);
    }

    @Override
    public UserProfile updateProfile(UserProfile profile) {
        userMapper.updateProfile(UserProfileRow.fromDomain(profile));
        return profile;
    }

    @Override
    public void updatePasswordHash(Long userId, String passwordHash) {
        userMapper.updatePasswordHash(userId, passwordHash);
    }

    @Override
    public void markEmailVerified(Long userId) {
        userMapper.markEmailVerified(userId);
    }

    @Override
    public void markPhoneVerified(Long userId) {
        userMapper.markPhoneVerified(userId);
    }

    @Override
    public void updateLastLoginAt(Long userId) {
        userMapper.updateLastLoginAt(userId);
    }
}
