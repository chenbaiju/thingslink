package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaJobExecutionOrigin;
import com.things.link.ota.domain.OtaJobProgressReceipt;
import com.things.link.ota.domain.OtaJobProgressRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原执行报告、认证观察与平台期限的真实事务仓储。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaJobProgressRepository implements OtaJobProgressRepository {
    /** 实际受RLS约束的事务连接。 */
    private final JdbcTemplate jdbc;
    /** 注入项目数据平面。 */
    public JdbcOtaJobProgressRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override public Optional<Context> locate(UUID jobId) {
        return jdbc.query("""
                SELECT j.tenant_id,j.project_id,j.campaign_id,c.firmware_id,j.id AS job_id,j.device_id,
                    j.attempt_no,j.status,j.state_version,j.qualified_credential_version,c.manifest_sha256,j.deadline_at,
                    (SELECT id FROM ota_download_authorization WHERE job_id=j.id AND sealed_at IS NOT NULL) AS authorization_id
                FROM ota_device_job j JOIN ota_campaign c ON c.id=j.campaign_id WHERE j.id=?
                """, (rs, row) -> context(rs), jobId).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaJobExecutionOrigin> origin(UUID jobId, int attemptNo) {
        return jdbc.query("SELECT * FROM ota_job_execution_origin WHERE job_id=? AND attempt_no=?", (rs, row) ->
                new OtaJobExecutionOrigin(uuid(rs,"tenant_id"), uuid(rs,"project_id"), uuid(rs,"campaign_id"),
                        uuid(rs,"job_id"), uuid(rs,"device_id"), rs.getInt("attempt_no"), rs.getLong("credential_version"),
                        rs.getLong("report_revision"), rs.getLong("report_sequence"), rs.getString("report_hash"),
                        rs.getBytes("canonical"), instant(rs,"broker_received_at"), instant(rs,"accepted_at"),
                        instant(rs,"captured_at"), rs.getLong("dispatched_revision")), jobId, attemptNo).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaJobProgressReceipt> find(UUID jobId, int attemptNo, long seq) {
        return jdbc.query("SELECT * FROM ota_job_progress WHERE job_id=? AND attempt_no=? AND progress_seq=?",
                JdbcOtaJobProgressRepository::receipt, jobId, attemptNo, seq).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<OtaJobProgressReceipt> latest(UUID jobId, int attemptNo) {
        return jdbc.query("SELECT * FROM ota_job_progress WHERE job_id=? AND attempt_no=? ORDER BY progress_seq DESC LIMIT 1",
                JdbcOtaJobProgressRepository::receipt, jobId, attemptNo).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean accept(Context expected, OtaJobProgressReceipt r, String nextStatus, String reason) {
        if (!expected.jobId().equals(r.jobId())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_job_accept_progress(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Boolean.class, r.id(), r.tenantId(), r.projectId(), r.campaignId(), r.jobId(), r.deviceId(), r.attemptNo(),
                r.credentialVersion(), r.progressSeq(), r.authorizationId(), r.manifestSha256(), r.stage(), r.bootId(),
                r.canonical(), r.payloadHash(), Timestamp.from(r.brokerReceivedAt()), Timestamp.from(r.acceptedAt()),
                expected.revision(), nextStatus, reason));
    }
    /** {@inheritDoc} */
    @Override public Optional<ExpiryClaim> claimExpired() {
        return jdbc.query("SELECT * FROM ota_job_claim_expired()", JdbcOtaJobProgressRepository::expiry).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<ExpiryClaim> authoritativeExpiry(UUID jobId, UUID token) {
        if (jobId == null || token == null) return Optional.empty();
        return jdbc.query("SELECT * FROM ota_job_authoritative_expiry(?,?)", JdbcOtaJobProgressRepository::expiry,
                jobId, token).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean expire(ExpiryClaim claim, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_job_expire(?,?,?,?)", Boolean.class,
                claim.context().jobId(), claim.token(), claim.context().revision(), reason));
    }
    /** 数据库完整原scope投影，不能从调用方补字段。 */
    private static Context context(ResultSet rs) throws SQLException {
        return new Context(uuid(rs,"tenant_id"), uuid(rs,"project_id"), uuid(rs,"campaign_id"), uuid(rs,"firmware_id"),
                uuid(rs,"job_id"), uuid(rs,"device_id"), rs.getInt("attempt_no"), rs.getString("status"),
                rs.getLong("state_version"), rs.getLong("qualified_credential_version"), rs.getString("manifest_sha256"),
                uuid(rs,"authorization_id"), instant(rs,"deadline_at"));
    }
    /** 原认证观察防御复制。 */
    private static OtaJobProgressReceipt receipt(ResultSet rs, int row) throws SQLException {
        return new OtaJobProgressReceipt(uuid(rs,"id"), uuid(rs,"tenant_id"), uuid(rs,"project_id"), uuid(rs,"campaign_id"),
                uuid(rs,"job_id"), uuid(rs,"device_id"), rs.getInt("attempt_no"), rs.getLong("credential_version"),
                rs.getLong("progress_seq"), uuid(rs,"authorization_id"), rs.getString("manifest_sha256"), rs.getString("stage"),
                uuid(rs,"boot_id"), rs.getBytes("canonical"), rs.getString("payload_hash"),
                instant(rs,"broker_received_at"), instant(rs,"accepted_at"));
    }
    /** 独立到期租约。 */
    private static ExpiryClaim expiry(ResultSet rs, int row) throws SQLException {
        return new ExpiryClaim(context(rs), uuid(rs,"lease_token"), instant(rs,"lease_until"));
    }
    /** 可空标识。 */
    private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
    /** 可空数据库时间。 */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
