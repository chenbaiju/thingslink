package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaDownloadRequestRepository;
import com.things.link.shared.id.Uuid7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 已认证设备下载申请只接纳排队，所有写入参加调用方真实资格事务。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaDownloadRequestRepository implements OtaDownloadRequestRepository {
    /** 固定显式投影列。 */
    private static final String COLUMNS = "id,tenant_id,project_id,device_id,credential_version,request_id,job_id,"
            + "campaign_id,firmware_id,attempt_no,manifest_sha256,canonical,canonical_sha256,original_deadline,"
            + "broker_received_at,accepted_at,report_revision,report_hash";
    /** 当前作用域真实数据库连接。 */
    private final JdbcTemplate jdbc;
    /** 注入当前事务连接。 */
    public JdbcOtaDownloadRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override public Optional<JobContext> locate(UUID jobId) {
        return jdbc.query("""
                SELECT j.tenant_id,j.project_id,j.device_id,j.campaign_id,c.firmware_id,j.id,j.status,j.state_version,
                    j.attempt_no,o.credential_version,o.manifest_sha256,j.deadline_at
                FROM ota_device_job j JOIN ota_campaign c ON c.id=j.campaign_id
                    JOIN ota_job_dispatch_outbox o ON o.job_id=j.id AND o.attempt_no=j.attempt_no
                WHERE j.id=?
                """, (rs, row) -> new JobContext(rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("device_id", UUID.class), rs.getObject("campaign_id", UUID.class),
                rs.getObject("firmware_id", UUID.class), rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getLong("state_version"), rs.getInt("attempt_no"), rs.getLong("credential_version"),
                rs.getString("manifest_sha256"), rs.getTimestamp("deadline_at").toInstant()), jobId).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Request> find(UUID deviceId, UUID requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ota_download_request WHERE device_id=? AND request_id=?",
                JdbcOtaDownloadRequestRepository::map, deviceId, requestId).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Request> findByJobAttempt(UUID jobId, int attemptNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ota_download_request WHERE job_id=? AND attempt_no=?",
                JdbcOtaDownloadRequestRepository::map, jobId, attemptNo).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean create(Request r, long expectedJobRevision) {
        int changed = jdbc.update("INSERT INTO ota_download_request(" + COLUMNS + ",job_revision)"
                + " SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?"
                + " WHERE ota_download_request_current(?,?,?,?,?,?,?,?,?,?,?)", r.id(), r.tenantId(), r.projectId(),
                r.deviceId(), r.credentialVersion(), r.requestId(), r.jobId(), r.campaignId(), r.firmwareId(),
                r.attemptNo(), r.manifestSha256(), r.canonical(), r.canonicalSha256(), Timestamp.from(r.originalDeadline()),
                Timestamp.from(r.brokerReceivedAt()), Timestamp.from(r.acceptedAt()), r.reportRevision(), r.reportHash(),
                expectedJobRevision, r.tenantId(), r.projectId(), r.deviceId(), r.jobId(), r.campaignId(), r.firmwareId(),
                r.attemptNo(), r.credentialVersion(), r.manifestSha256(), expectedJobRevision, Timestamp.from(r.originalDeadline()));
        if (changed == 0) return false;
        jdbc.update("INSERT INTO ota_download_request_outbox(id,tenant_id,project_id,receipt_id,event_type,created_at)"
                + " VALUES(?,?,?,?,'OTA_DOWNLOAD_REQUEST_ACCEPTED',?)", Uuid7.generate(), r.tenantId(), r.projectId(), r.id(),
                Timestamp.from(r.acceptedAt()));
        return true;
    }
    /** {@inheritDoc} */
    @Override public boolean safetyPause(JobContext c, long expectedCampaignRevision, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_download_request_safety_pause(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Boolean.class, c.tenantId(), c.projectId(), c.deviceId(), c.jobId(), c.campaignId(), c.firmwareId(),
                c.attemptNo(), c.credentialVersion(), c.manifestSha256(), c.jobRevision(), Timestamp.from(c.originalDeadline()),
                expectedCampaignRevision, reason));
    }
    /** 原始接纳投影，读取绝不刷新资格或时钟。 */
    private static Request map(ResultSet rs, int row) throws SQLException {
        return new Request(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("device_id", UUID.class), rs.getLong("credential_version"),
                rs.getObject("request_id", UUID.class), rs.getObject("job_id", UUID.class), rs.getObject("campaign_id", UUID.class),
                rs.getObject("firmware_id", UUID.class), rs.getInt("attempt_no"), rs.getString("manifest_sha256"),
                rs.getBytes("canonical"), rs.getString("canonical_sha256"), rs.getTimestamp("original_deadline").toInstant(),
                rs.getTimestamp("broker_received_at").toInstant(), rs.getTimestamp("accepted_at").toInstant(),
                rs.getLong("report_revision"), rs.getString("report_hash"));
    }
}
