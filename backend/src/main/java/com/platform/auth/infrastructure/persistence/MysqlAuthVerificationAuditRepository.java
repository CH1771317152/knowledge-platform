package com.platform.auth.infrastructure.persistence;

import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.repository.AuthVerificationAuditRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class MysqlAuthVerificationAuditRepository implements AuthVerificationAuditRepository {

    private final AuthVerificationAuditMapper mapper;

    public MysqlAuthVerificationAuditRepository(AuthVerificationAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(VerificationChannel channel,
                       String target,
                       VerificationPurpose purpose,
                       String status,
                       String requestIp,
                       String failureReason) {
        mapper.insert(
                channel.name(),
                target,
                purpose.name(),
                status,
                requestIp,
                failureReason
        );
    }
}
