package com.things.link.issuer.infrastructure;

import com.things.link.issuer.application.EnrollmentReviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** 发行方申请和只追加审核事实；同申请写入前持申请行锁。 */
@Repository
public class JdbcEnrollmentReviewRepository implements EnrollmentReviewRepository {
    private final JdbcTemplate jdbc;

    public JdbcEnrollmentReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RequestFact requestForUpdate(UUID requestId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT public.shc_enrollment_request_lock(?)", Boolean.class, requestId))) return null;
        return findRequest(requestId);
    }

    @Override
    public RequestFact request(UUID requestId) {
        return findRequest(requestId);
    }

    private RequestFact findRequest(UUID requestId) {
        String sql = """
                SELECT request_id, deployment_id, tenant_id, public_key_sha256, request_sha256, status
                  FROM public.sys_shc_enrollment_request WHERE request_id = ?
                """;
        List<RequestFact> facts = jdbc.query(sql, JdbcEnrollmentReviewRepository::mapRequest, requestId);
        return facts.isEmpty() ? null : facts.getFirst();
    }

    @Override
    public List<Attestation> attestations(UUID requestId) {
        return jdbc.query("""
                SELECT request_id, reviewer_account_id, deployment_id, tenant_id,
                       public_key_sha256, request_sha256, organization_ref, evidence_sha256,
                       tier, revision_id, revision_sha256, attested_at
                  FROM public.sys_shc_review_attestation WHERE request_id = ?
                 ORDER BY attested_at, reviewer_account_id
                """, JdbcEnrollmentReviewRepository::mapAttestation, requestId);
    }

    @Override
    public void insert(Attestation fact) {
        jdbc.update("""
                INSERT INTO public.sys_shc_review_attestation
                    (request_id, reviewer_account_id, deployment_id, tenant_id,
                     public_key_sha256, request_sha256, organization_ref, evidence_sha256,
                     tier, revision_id, revision_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, fact.requestId(), fact.reviewerAccountId(), fact.deploymentId(), fact.tenantId(),
                fact.publicKeySha256(), fact.requestSha256(), fact.organizationReference(),
                fact.evidenceSha256(), fact.tier(), fact.revisionId(), fact.revisionSha256());
    }

    private static RequestFact mapRequest(ResultSet rs, int ignored) throws SQLException {
        return new RequestFact(rs.getObject("request_id", UUID.class),
                rs.getObject("deployment_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getBytes("public_key_sha256"), rs.getBytes("request_sha256"), rs.getString("status"));
    }

    private static Attestation mapAttestation(ResultSet rs, int ignored) throws SQLException {
        return new Attestation(rs.getObject("request_id", UUID.class),
                rs.getObject("reviewer_account_id", UUID.class),
                rs.getObject("deployment_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getBytes("public_key_sha256"), rs.getBytes("request_sha256"),
                rs.getString("organization_ref"), rs.getBytes("evidence_sha256"),
                rs.getString("tier"), rs.getString("revision_id"),
                rs.getBytes("revision_sha256"), rs.getTimestamp("attested_at").toInstant());
    }
}
