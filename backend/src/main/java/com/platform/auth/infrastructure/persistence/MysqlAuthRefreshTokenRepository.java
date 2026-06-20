package com.platform.auth.infrastructure.persistence;

import com.platform.auth.domain.AuthRefreshToken;
import com.platform.auth.repository.AuthRefreshTokenRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class MysqlAuthRefreshTokenRepository implements AuthRefreshTokenRepository {

    private final AuthRefreshTokenMapper mapper;

    public MysqlAuthRefreshTokenRepository(AuthRefreshTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AuthRefreshToken save(AuthRefreshToken token) {
        AuthRefreshTokenRow row = AuthRefreshTokenRow.fromDomain(token);
        mapper.insert(row);
        return mapper.findById(row.getId()).map(AuthRefreshTokenRow::toDomain).orElseThrow();
    }

    @Override
    public Optional<AuthRefreshToken> findByTokenHash(String tokenHash) {
        return mapper.findByTokenHash(tokenHash).map(AuthRefreshTokenRow::toDomain);
    }

    @Override
    public void revoke(Long tokenId, LocalDateTime revokedAt, Long replacedByTokenId) {
        mapper.revoke(tokenId, revokedAt, replacedByTokenId);
    }

    @Override
    public void revokeAllByUserId(Long userId, LocalDateTime revokedAt) {
        mapper.revokeAllByUserId(userId, revokedAt);
    }

    @Override
    public int claimForRotation(Long tokenId, LocalDateTime revokedAt) {
        return mapper.claimForRotation(tokenId, revokedAt);
    }

    @Override
    public void linkReplacement(Long oldTokenId, Long newTokenId) {
        mapper.linkReplacement(oldTokenId, newTokenId);
    }
}
