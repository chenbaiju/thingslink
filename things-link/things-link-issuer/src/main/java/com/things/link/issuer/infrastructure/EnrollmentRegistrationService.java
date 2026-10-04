package com.things.link.issuer.infrastructure;

import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.issuer.application.EnrollmentRegistry;
import com.things.link.issuer.application.EnrollmentConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * ThingsLink 发行方的首期申请登记。联网与离线只区分送达通道，
 * 共用申请验签、部署身份、唯一约束和幂等事实。此服务不批准租户或签发授权。
 */
@Service
public class EnrollmentRegistrationService implements EnrollmentRegistry {
    private final JdbcTemplate jdbc;

    public EnrollmentRegistrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    @Override
    public Registration register(byte[] envelope, Channel channel) {
        if (channel == null) throw new IllegalArgumentException("申请通道不能为空");
        EnrollmentRequestV1.Verified verified = EnrollmentRequestV1.verify(envelope);
        jdbc.update("""
                INSERT INTO public.sys_shc_enrollment_request
                    (request_id, deployment_id, tenant_id, public_key_spki, public_key_sha256,
                     request_sha256, channel)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, verified.requestId(), verified.deploymentId(), verified.tenantId(),
                verified.publicKeySpki(), verified.publicKeySha256(), verified.requestSha256(),
                channel.name());
        List<Registration> matches = jdbc.query("""
                SELECT request_id, deployment_id, tenant_id, public_key_sha256,
                       request_sha256, channel, status, received_at
                  FROM public.sys_shc_enrollment_request
                 WHERE request_id = ? OR deployment_id = ?
                """, EnrollmentRegistrationService::map, verified.requestId(), verified.deploymentId());
        if (matches.size() != 1) throw new IllegalStateException("申请登记唯一性异常");
        Registration existing = matches.getFirst();
        if (!existing.requestId().equals(verified.requestId())
                || !existing.deploymentId().equals(verified.deploymentId())
                || !existing.tenantId().equals(verified.tenantId())
                || !MessageDigest.isEqual(existing.publicKeySha256(), verified.publicKeySha256())
                || !MessageDigest.isEqual(existing.requestSha256(), verified.requestSha256())) {
            throw new EnrollmentConflictException();
        }
        return existing;
    }

    private static Registration map(ResultSet rs, int rowNumber) throws SQLException {
        return new Registration(rs.getObject("request_id", UUID.class),
                rs.getObject("deployment_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getBytes("public_key_sha256"), rs.getBytes("request_sha256"),
                Channel.valueOf(rs.getString("channel")), rs.getString("status"),
                rs.getTimestamp("received_at").toInstant());
    }

}
