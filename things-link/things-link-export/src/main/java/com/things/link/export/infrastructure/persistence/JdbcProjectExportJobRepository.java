package com.things.link.export.infrastructure.persistence;

import com.things.link.export.domain.ProjectExportClaim;
import com.things.link.export.domain.ProjectExportCleanupClaim;
import com.things.link.export.domain.ProjectExportDownloadClaim;
import com.things.link.export.domain.ProjectExportExpiryClaim;
import com.things.link.export.domain.ProjectExportJob;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.export.domain.ProjectExportStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 通过显式SECURITY DEFINER函数访问RLS导出队列的JDBC仓储。 */
@Repository
public class JdbcProjectExportJobRepository implements ProjectExportJobRepository {

    /** 固定两分钟任务和清理租约，SQL函数会再次拒绝其他值。 */
    private static final int LEASE_SECONDS = 120;
    /** JDBC访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate JDBC访问器 */
    public JdbcProjectExportJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean create(UUID id, UUID tenantId, UUID projectId, long generation, UUID requesterAccountId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT request_project_export_job(?, ?, ?, ?, ?)", Boolean.class,
                id, tenantId, projectId, generation, requesterAccountId));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ProjectExportJob> findActive(UUID tenantId, UUID projectId, long generation) {
        return jdbcTemplate.query("SELECT * FROM find_active_project_export_job(?, ?, ?)",
                this::mapJob, tenantId, projectId, generation).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ProjectExportJob> findLatest(UUID tenantId, UUID projectId, long generation, UUID requesterAccountId) {
        return jdbcTemplate.query("SELECT * FROM find_latest_project_export_job(?, ?, ?, ?)",
                this::mapJob, tenantId, projectId, generation, requesterAccountId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ProjectExportJob> findByIdentity(UUID tenantId, UUID projectId, UUID exportId) {
        return jdbcTemplate.query("SELECT * FROM find_project_export_job(?, ?, ?)",
                this::mapJob, tenantId, projectId, exportId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProjectExportDownloadClaim> lockDownloadCandidate(
            UUID tenantId, UUID projectId, UUID exportId, long generation, UUID requesterAccountId) {
        return jdbcTemplate.query("SELECT * FROM lock_project_export_download(?, ?, ?, ?, ?)",
                (rs, row) -> new ProjectExportDownloadClaim(
                        mapJob(rs, row), instant(rs, "download_expires_at")),
                tenantId, projectId, exportId, generation, requesterAccountId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProjectExportClaim> claimReady(String workerName) {
        return jdbcTemplate.query("SELECT * FROM claim_project_export_job(?, ?)", (rs, row) ->
                new ProjectExportClaim(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("project_id", UUID.class), rs.getLong("project_generation"),
                        rs.getObject("requester_account_id", UUID.class), rs.getInt("attempt_count"),
                        rs.getObject("lease_token", UUID.class)), workerName, LEASE_SECONDS)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renew(UUID exportId, UUID leaseToken) {
        return result("SELECT renew_project_export_job(?, ?, ?)", exportId, leaseToken, LEASE_SECONDS);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerUpload(UUID exportId, UUID leaseToken, UUID cleanupId, UUID uploadId,
                                  String objectKey, Instant snapshotAt) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT register_project_export_upload(?, ?, ?, ?, ?, ?)", Boolean.class,
                exportId, leaseToken, cleanupId, uploadId, objectKey, Timestamp.from(snapshotAt)));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(UUID exportId, UUID leaseToken, UUID uploadId, String objectKey,
                            long objectSize, String objectSha256) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT complete_project_export_job(?, ?, ?, ?, ?, ?)", Boolean.class,
                exportId, leaseToken, uploadId, objectKey, objectSize, objectSha256));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(UUID exportId, UUID leaseToken, String failureCode, boolean permanent) {
        return result("SELECT fail_project_export_job(?, ?, ?, ?)",
                exportId, leaseToken, failureCode, permanent);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProjectExportCleanupClaim> claimCleanup() {
        return jdbcTemplate.query("SELECT * FROM claim_project_export_cleanup(?)", (rs, row) ->
                new ProjectExportCleanupClaim(
                        rs.getObject("id", UUID.class), rs.getString("object_key"),
                        rs.getObject("lease_token", UUID.class)), LEASE_SECONDS).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeCleanup(UUID cleanupId, UUID leaseToken) {
        return result("SELECT complete_project_export_cleanup(?, ?)", cleanupId, leaseToken);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failCleanup(UUID cleanupId, UUID leaseToken, String failureCode) {
        return result("SELECT fail_project_export_cleanup(?, ?, ?)", cleanupId, leaseToken, failureCode);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProjectExportExpiryClaim> claimExpired() {
        return jdbcTemplate.query("SELECT * FROM claim_expired_project_export_cleanup(?)", (rs, row) ->
                new ProjectExportExpiryClaim(
                        rs.getObject("export_id", UUID.class), rs.getObject("cleanup_id", UUID.class),
                        rs.getString("object_key"), rs.getObject("lease_token", UUID.class)),
                LEASE_SECONDS).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeExpired(UUID exportId, UUID cleanupId, UUID leaseToken) {
        return result("SELECT complete_expired_project_export_cleanup(?, ?, ?)",
                exportId, cleanupId, leaseToken);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failExpired(UUID cleanupId, UUID leaseToken, String failureCode) {
        return result("SELECT fail_expired_project_export_cleanup(?, ?, ?)",
                cleanupId, leaseToken, failureCode);
    }

    /** UPDATE RETURNING型函数未命中时返回零行，不能用queryForObject把正常CAS失败变成异常。 */
    private boolean result(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (rs, row) -> rs.getBoolean(1), arguments)
                .stream().findFirst().orElse(false);
    }

    /** 映射公开状态事实，不暴露租约和当前upload内部字段。 */
    private ProjectExportJob mapJob(ResultSet rs, int row) throws SQLException {
        return new ProjectExportJob(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getLong("project_generation"),
                rs.getObject("requester_account_id", UUID.class),
                ProjectExportStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count"),
                instant(rs, "next_attempt_at"), instant(rs, "snapshot_at"), rs.getString("object_key"),
                rs.getObject("object_size", Long.class), rs.getString("object_sha256"),
                rs.getString("failure_code"), instant(rs, "requested_at"), instant(rs, "started_at"),
                instant(rs, "succeeded_at"), instant(rs, "expires_at"));
    }

    /** nullable JDBC时间到UTC。 */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
