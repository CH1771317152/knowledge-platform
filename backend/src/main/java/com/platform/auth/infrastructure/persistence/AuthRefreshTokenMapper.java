package com.platform.auth.infrastructure.persistence;

import java.time.LocalDateTime;
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
public interface AuthRefreshTokenMapper {

    @Insert("""
            INSERT INTO auth_refresh_token (user_id, token_hash, token_jti, device_id, user_agent,
                ip_address, expires_at, revoked_at, replaced_by_token_id)
            VALUES (#{userId}, #{tokenHash}, #{tokenJti}, #{deviceId}, #{userAgent},
                #{ipAddress}, #{expiresAt}, #{revokedAt}, #{replacedByTokenId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AuthRefreshTokenRow row);

    @Select("""
            SELECT id, user_id, token_hash, token_jti, device_id, user_agent, ip_address,
                expires_at, revoked_at, replaced_by_token_id, created_at, updated_at
            FROM auth_refresh_token
            WHERE token_hash = #{tokenHash}
            """)
    @Results(id = "authRefreshTokenResult", value = {
            @Result(column = "user_id", property = "userId"),
            @Result(column = "token_hash", property = "tokenHash"),
            @Result(column = "token_jti", property = "tokenJti"),
            @Result(column = "device_id", property = "deviceId"),
            @Result(column = "user_agent", property = "userAgent"),
            @Result(column = "ip_address", property = "ipAddress"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "revoked_at", property = "revokedAt"),
            @Result(column = "replaced_by_token_id", property = "replacedByTokenId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<AuthRefreshTokenRow> findByTokenHash(@Param("tokenHash") String tokenHash);

    @Select("""
            SELECT id, user_id, token_hash, token_jti, device_id, user_agent, ip_address,
                expires_at, revoked_at, replaced_by_token_id, created_at, updated_at
            FROM auth_refresh_token
            WHERE id = #{id}
            """)
    @ResultMap("authRefreshTokenResult")
    Optional<AuthRefreshTokenRow> findById(@Param("id") Long id);

    @Update("""
            UPDATE auth_refresh_token
            SET revoked_at = #{revokedAt},
                replaced_by_token_id = #{replacedByTokenId}
            WHERE id = #{id} AND revoked_at IS NULL
            """)
    int revoke(@Param("id") Long id,
               @Param("revokedAt") LocalDateTime revokedAt,
               @Param("replacedByTokenId") Long replacedByTokenId);

    @Update("""
            UPDATE auth_refresh_token
            SET revoked_at = #{revokedAt}
            WHERE user_id = #{userId} AND revoked_at IS NULL
            """)
    int revokeAllByUserId(@Param("userId") Long userId,
                          @Param("revokedAt") LocalDateTime revokedAt);

    @Update("""
            UPDATE auth_refresh_token
            SET revoked_at = #{revokedAt}
            WHERE id = #{tokenId} AND revoked_at IS NULL
            """)
    int claimForRotation(@Param("tokenId") Long tokenId,
                         @Param("revokedAt") LocalDateTime revokedAt);

    @Update("""
            UPDATE auth_refresh_token
            SET replaced_by_token_id = #{newTokenId}
            WHERE id = #{oldTokenId}
            """)
    int linkReplacement(@Param("oldTokenId") Long oldTokenId,
                        @Param("newTokenId") Long newTokenId);
}
