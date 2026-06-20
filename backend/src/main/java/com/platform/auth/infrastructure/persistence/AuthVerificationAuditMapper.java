package com.platform.auth.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthVerificationAuditMapper {

    @Insert("""
            INSERT INTO auth_verification_audit (channel, target, purpose, status, request_ip, failure_reason)
            VALUES (#{channel}, #{target}, #{purpose}, #{status}, #{requestIp}, #{failureReason})
            """)
    int insert(@Param("channel") String channel,
               @Param("target") String target,
               @Param("purpose") String purpose,
               @Param("status") String status,
               @Param("requestIp") String requestIp,
               @Param("failureReason") String failureReason);
}
