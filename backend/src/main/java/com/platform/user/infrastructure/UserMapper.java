package com.platform.user.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    @Insert("""
            INSERT INTO user_account (username, email, phone, password_hash, status, role,
                email_verified, phone_verified, last_login_at)
            VALUES (#{username}, #{email}, #{phone}, #{passwordHash}, #{status}, #{role},
                #{emailVerified}, #{phoneVerified}, #{lastLoginAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertAccount(UserAccountRow row);

    @Insert("""
            INSERT INTO user_profile (user_id, display_name, avatar_url, bio, location, website, birthday)
            VALUES (#{userId}, #{displayName}, #{avatarUrl}, #{bio}, #{location}, #{website}, #{birthday})
            """)
    void insertProfile(UserProfileRow row);

    @Select("SELECT COUNT(1) FROM user_account WHERE username = #{username}")
    int countByUsername(@Param("username") String username);

    @Select("SELECT COUNT(1) FROM user_account WHERE email = #{email}")
    int countByEmail(@Param("email") String email);

    @Select("""
            SELECT id, username, email, phone, password_hash, status, role,
                email_verified, phone_verified, last_login_at, created_at, updated_at
            FROM user_account
            WHERE id = #{userId}
            """)
    @Results(id = "userAccountResult", value = {
            @Result(column = "password_hash", property = "passwordHash"),
            @Result(column = "email_verified", property = "emailVerified"),
            @Result(column = "phone_verified", property = "phoneVerified"),
            @Result(column = "last_login_at", property = "lastLoginAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<UserAccountRow> findAccountById(@Param("userId") Long userId);

    @Select("""
            SELECT id, username, email, phone, password_hash, status, role,
                email_verified, phone_verified, last_login_at, created_at, updated_at
            FROM user_account
            WHERE username = #{username}
            """)
    @Results(id = "userAccountByUsernameResult", value = {
            @Result(column = "password_hash", property = "passwordHash"),
            @Result(column = "email_verified", property = "emailVerified"),
            @Result(column = "phone_verified", property = "phoneVerified"),
            @Result(column = "last_login_at", property = "lastLoginAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<UserAccountRow> findAccountByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, email, phone, password_hash, status, role,
                email_verified, phone_verified, last_login_at, created_at, updated_at
            FROM user_account
            WHERE username = #{usernameOrEmail} OR email = #{usernameOrEmail}
            LIMIT 1
            """)
    @Results(id = "userAccountByLoginResult", value = {
            @Result(column = "password_hash", property = "passwordHash"),
            @Result(column = "email_verified", property = "emailVerified"),
            @Result(column = "phone_verified", property = "phoneVerified"),
            @Result(column = "last_login_at", property = "lastLoginAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<UserAccountRow> findAccountByUsernameOrEmail(@Param("usernameOrEmail") String usernameOrEmail);

    @Select("""
            SELECT id, username, email, phone, password_hash, status, role,
                email_verified, phone_verified, last_login_at, created_at, updated_at
            FROM user_account
            WHERE email = #{email}
            """)
    @ResultMap("userAccountResult")
    Optional<UserAccountRow> findAccountByEmail(@Param("email") String email);

    @Select("""
            SELECT id, username, email, phone, password_hash, status, role,
                email_verified, phone_verified, last_login_at, created_at, updated_at
            FROM user_account
            WHERE phone = #{phone}
            """)
    @ResultMap("userAccountResult")
    Optional<UserAccountRow> findAccountByPhone(@Param("phone") String phone);

    @Update("UPDATE user_account SET password_hash = #{passwordHash} WHERE id = #{userId}")
    int updatePasswordHash(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Update("UPDATE user_account SET email_verified = TRUE WHERE id = #{userId}")
    int markEmailVerified(@Param("userId") Long userId);

    @Update("UPDATE user_account SET phone_verified = TRUE WHERE id = #{userId}")
    int markPhoneVerified(@Param("userId") Long userId);

    @Update("UPDATE user_account SET last_login_at = CURRENT_TIMESTAMP WHERE id = #{userId}")
    int updateLastLoginAt(@Param("userId") Long userId);

    @Select("""
            SELECT user_id, display_name, avatar_url, bio, location, website,
                birthday, created_at, updated_at
            FROM user_profile
            WHERE user_id = #{userId}
            """)
    @Results(id = "userProfileResult", value = {
            @Result(column = "user_id", property = "userId"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "avatar_url", property = "avatarUrl"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<UserProfileRow> findProfileByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE user_profile
            SET display_name = #{displayName},
                avatar_url = #{avatarUrl},
                bio = #{bio},
                location = #{location},
                website = #{website},
                birthday = #{birthday}
            WHERE user_id = #{userId}
            """)
    int updateProfile(UserProfileRow row);
}
