package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaJobCompletionCapture;
import org.springframework.beans.factory.annotation.Autowired;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignBatchProgress;
import com.things.link.ota.domain.OtaCampaignRuntimeCancellation;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository.JobState;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository.RetryDue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 数据库运行准入仓储；不建立虚假账号、不调用网络。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaCampaignRuntimeRepository implements OtaCampaignRuntimeRepository {
    /** 真实事务连接。 */
    private final JdbcTemplate jdbc;
    /** 既有活动完整快照读取。 */
    private final JdbcOtaCampaignRepository campaigns;
    /** 生产必需的原事务捕获端口。 */
    private final OtaJobCompletionCapture completion;
    /** 低层仓储夹具兼容构造；生产容器必须使用显式捕获依赖构造。 */
    public JdbcOtaCampaignRuntimeRepository(JdbcTemplate jdbc) {
        this(jdbc, (tenant, project) -> { });
    }
    /** 生产接线不允许以缺失组件静默关闭来源。 */
    @Autowired
    public JdbcOtaCampaignRuntimeRepository(JdbcTemplate jdbc, OtaJobCompletionCapture completion) {
        this.completion = java.util.Objects.requireNonNull(completion);
        this.jdbc = jdbc;
        this.campaigns = new JdbcOtaCampaignRepository(jdbc);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void controlLock(UUID tenant, UUID project) {
        completion.capture(tenant, project);
        readControlLock(tenant, project);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public void readControlLock(UUID tenant, UUID project) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended("
                + "concat_ws(':','ota-project-control-v1',?::text,?::text),13026::bigint))",
                Integer.class, tenant, project);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Instant currentTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<UUID> firmwaresUsingKey(UUID project, String domain, String version) {
        return List.copyOf(jdbc.query("SELECT DISTINCT c.firmware_id FROM ota_campaign c"
                + " JOIN ota_firmware_release r ON r.id=c.release_id AND r.project_id=c.project_id"
                + " WHERE c.project_id=? AND c.status IN ('RUNNING','PAUSED')"
                + " AND convert_from(r.trust_snapshot,'UTF8')::jsonb->>'trustDomain'=?"
                + " AND convert_from(r.trust_snapshot,'UTF8')::jsonb->>'keyVersion'=?"
                + " ORDER BY c.firmware_id", (rs, row) -> rs.getObject(1, UUID.class), project, domain, version));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Claim> claimOne() {
        return jdbc.query("SELECT * FROM ota_job_claim_one()", JdbcOtaCampaignRuntimeRepository::claim).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<Claim> authoritativeClaim(UUID job, UUID token) {
        if (job == null || token == null) return Optional.empty();
        return jdbc.query("SELECT * FROM ota_job_authoritative_claim(?,?)",
                JdbcOtaCampaignRuntimeRepository::claim, job, token).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaCampaignRuntime> lockRuntime(UUID project, UUID campaign) {
        if (campaigns.find(project, campaign, true).isEmpty()) return Optional.empty();
        jdbc.queryForList("SELECT batch_number FROM ota_campaign_batch WHERE project_id=? AND campaign_id=?"
                + " ORDER BY batch_number FOR UPDATE", project, campaign);
        jdbc.queryForList("SELECT id FROM ota_device_job WHERE project_id=? AND campaign_id=?"
                + " ORDER BY device_id::text FOR UPDATE", project, campaign);
        return read(project, campaign);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<OtaCampaignRuntime> read(UUID project, UUID campaign) {
        var found = campaigns.find(project, campaign, false);
        if (found.isEmpty()) return Optional.empty();
        return jdbc.query("""
                SELECT started_at,current_batch,pause_kind,pause_reason,paused_at,pause_actor_id,pause_job_id,
                    (SELECT count(*) FROM ota_device_job WHERE campaign_id=c.id AND status='PENDING') AS pending,
                    (SELECT count(*) FROM ota_device_job WHERE campaign_id=c.id AND status='DISPATCHED') AS dispatched,
                    (SELECT count(*) FROM ota_device_job WHERE campaign_id=c.id AND status='SKIPPED_INELIGIBLE') AS skipped
                FROM ota_campaign c WHERE project_id=? AND id=?
                """, (rs, row) -> new OtaCampaignRuntime(found.orElseThrow(), instant(rs, "started_at"),
                rs.getObject("current_batch", Integer.class), rs.getString("pause_kind"), rs.getString("pause_reason"),
                instant(rs, "paused_at"), rs.getObject("pause_actor_id", UUID.class), rs.getObject("pause_job_id", UUID.class),
                rs.getLong("pending"), rs.getLong("dispatched"), rs.getLong("skipped"), cancellation(project, campaign), batchProgress(project, campaign)), project, campaign).stream().findFirst();
    }
    /** 先读取固定完成事实；尚未完成时投影原冻结目标的当前计数。 */
    private OtaCampaignBatchProgress batchProgress(UUID project, UUID campaign) {
        return jdbc.query("""
                SELECT b.status AS batch_status,
                    (convert_from(c.canonical_plan,'UTF8')::jsonb#>>'{executionPolicy,requireManualBatchApproval}')::boolean AS manual,
                    c.status='RUNNING' AND b.status='SUCCEEDED' AND c.current_batch<c.batch_count
                        AND (convert_from(c.canonical_plan,'UTF8')::jsonb#>>'{executionPolicy,requireManualBatchApproval}')::boolean AS awaiting,
                    CASE WHEN c.current_batch<c.batch_count THEN c.current_batch+1 END AS next_batch,
                    f.completed_at,f.outcome,coalesce(f.target_count,c.target_count) AS target_count,
                    coalesce(f.succeeded_count,(SELECT count(*) FROM ota_device_job WHERE campaign_id=c.id AND status='SUCCEEDED')) AS succeeded_count,
                    coalesce(f.rolled_back_count,(SELECT count(*) FROM ota_device_job WHERE campaign_id=c.id AND status='ROLLED_BACK')) AS rolled_back_count,
                    coalesce(f.skipped_count,(SELECT count(*) FROM ota_device_job WHERE campaign_id=c.id AND status='SKIPPED_INELIGIBLE')) AS skipped_count,
                    -- ADR0207约束保证完成快照五类和等于总数；0360旧四类和等于总数，差值严格为0。
                    coalesce(f.target_count-f.succeeded_count-f.rolled_back_count-f.skipped_count-f.cancelled_count,
                        (SELECT count(*) FROM ota_device_job WHERE campaign_id=c.id AND status='TIMED_OUT')) AS timed_out_count,
                    coalesce(f.cancelled_count,(SELECT count(*) FROM ota_device_job WHERE campaign_id=c.id AND status='CANCELLED')) AS cancelled_count
                FROM ota_campaign c LEFT JOIN ota_campaign_batch b ON b.campaign_id=c.id AND b.batch_number=c.current_batch
                LEFT JOIN ota_campaign_completion f ON f.campaign_id=c.id AND f.project_id=c.project_id AND f.tenant_id=c.tenant_id
                WHERE c.project_id=? AND c.id=?
                """, (rs, row) -> new OtaCampaignBatchProgress(rs.getString("batch_status"),rs.getBoolean("manual"),
                rs.getBoolean("awaiting"),rs.getObject("next_batch",Integer.class),instant(rs,"completed_at"),
                rs.getString("outcome"),rs.getLong("target_count"),rs.getLong("succeeded_count"),
                rs.getLong("rolled_back_count"),rs.getLong("skipped_count"),rs.getLong("timed_out_count"),rs.getLong("cancelled_count")),
                project,campaign).stream().findFirst().orElseThrow();
    }
    /** 原不可变请求加当前责任计数，不刷新请求时间或创建伪历史取消。 */
    private OtaCampaignRuntimeCancellation cancellation(UUID project, UUID campaign) {
        return jdbc.query("""
                SELECT x.requested_revision,x.requested_from_status,x.requested_at,x.requested_by,x.reason,
                    x.cancelled_pending_count,c.cancelled_at,
                    (SELECT count(*) FROM ota_device_job j WHERE j.campaign_id=c.id
                        AND j.status NOT IN ('CANCELLED','SKIPPED_INELIGIBLE','SUCCEEDED','ROLLED_BACK','TIMED_OUT')) AS unresolved_count
                FROM ota_campaign_runtime_cancellation x JOIN ota_campaign c ON c.id=x.campaign_id
                WHERE x.project_id=? AND x.campaign_id=?
                """, (rs, row) -> new OtaCampaignRuntimeCancellation(rs.getLong("requested_revision"),
                rs.getString("requested_from_status"), instant(rs,"requested_at"), rs.getObject("requested_by",UUID.class),
                rs.getString("reason"),rs.getLong("cancelled_pending_count"),rs.getLong("unresolved_count"),instant(rs,"cancelled_at")),
                project,campaign).stream().findFirst().orElse(null);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean start(UUID project, UUID campaign, long revision, UUID actor) {
        return change(project, campaign, revision, "START", actor, null, null);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean pause(UUID project, UUID campaign, long revision, UUID actor, String reason) {
        return change(project, campaign, revision, "PAUSE", actor, reason, null);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public List<UUID> resumeFailureCandidates(UUID project, UUID campaign) {
        return jdbc.queryForList("""
                SELECT j.id FROM ota_device_job j WHERE j.project_id=? AND j.campaign_id=? AND j.status='DISPATCHED'
                AND (EXISTS(SELECT 1 FROM ota_notification_delivery d WHERE d.job_id=j.id AND d.job_attempt_no=j.attempt_no AND d.status='EXHAUSTED')
                OR EXISTS(SELECT 1 FROM ota_download_authorization a JOIN ota_download_request r ON r.id=a.id
                    WHERE a.job_id=j.id AND r.attempt_no=j.attempt_no AND a.status='EXHAUSTED')) ORDER BY j.id
                """, UUID.class, project, campaign);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean resume(UUID project, UUID campaign, long revision, UUID actor, String reason) {
        return change(project, campaign, revision, "RESUME", actor, reason, null);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean cancelRuntime(UUID project, UUID campaign, long revision, UUID actor, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_campaign_cancel_runtime(?,?,?,?,?)",
                Boolean.class, project, campaign, revision, actor, reason));
    }
    /** 单事务状态CAS，数据库承担不可变事实图守卫。 */
    private boolean change(UUID project, UUID campaign, long revision, String action, UUID actor, String reason, UUID job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_campaign_runtime_change(?,?,?,?,?,?,?)",
                Boolean.class, project, campaign, revision, action, actor, reason, job));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean admit(Claim claim, long credential, long reportRevision, String reportHash, Instant checkedAt) {
        return admission(claim, true, null, credential, reportRevision, reportHash, checkedAt);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean skip(Claim claim, String reason) {
        return admission(claim, false, reason, null, null, null, null);
    }
    /** 最终动作仅使用精确数据库租约，客户端claim范围不能覆盖RLS。 */
    private boolean admission(Claim c, boolean eligible, String reason, Long credential, Long revision,
            String hash, Instant checked) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_job_admission(?,?,?,?,?,?,?,?,?,?,?,?)", Boolean.class,
                c.tenantId(), c.projectId(), c.campaignId(), c.jobId(), c.jobRevision(), c.token(), eligible, reason,
                credential, revision, hash, checked == null ? null : Timestamp.from(checked)));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public int securityPause(UUID project, UUID firmware, UUID actor, String reason) {
        var ids = jdbc.query("SELECT id FROM ota_campaign WHERE project_id=? AND firmware_id=?"
                + " AND status IN ('RUNNING','PAUSED') ORDER BY id::text FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), project, firmware);
        int changed = 0;
        for (UUID id : ids) {
            var current = campaigns.find(project, id, false).orElseThrow();
            if (change(project, id, current.stateVersion(), "SECURITY", actor, reason, null)) changed++;
        }
        return changed;
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean securityPauseJob(Claim supplied, String reason) {
        var actual = authoritativeClaim(supplied.jobId(), supplied.token());
        if (actual.isEmpty() || !actual.get().equals(supplied)) return false;
        var current = campaigns.find(supplied.projectId(), supplied.campaignId(), true);
        if (current.isEmpty()) return false;
        // 等待活动锁后再核验数据库真实时间，不能用进入事务前token结果。
        if (authoritativeClaim(supplied.jobId(), supplied.token()).isEmpty()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_campaign_runtime_change(?,?,?,?,?,?,?,?,?)",
                Boolean.class, supplied.projectId(), supplied.campaignId(), current.get().stateVersion(),
                "SECURITY", null, reason, supplied.jobId(), supplied.token(), supplied.jobRevision()));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<RetryDue> claimRetryDue() {
        return jdbc.query("SELECT * FROM ota_job_claim_retry_due()", JdbcOtaCampaignRuntimeRepository::retryDue)
                .stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean consumeRetryClaim(RetryDue due) {
        if (due == null || due.token() == null || due.leaseUntil() == null || due.nextAttemptAt() == null) return false;
        return jdbc.update("""
                UPDATE ota_device_job SET lease_token=NULL,lease_until=NULL
                WHERE tenant_id=? AND project_id=? AND campaign_id=? AND id=? AND device_id=?
                  AND status='RETRY_WAIT' AND attempt_no=? AND state_version=?
                  AND next_attempt_at=? AND failure_code IS NOT DISTINCT FROM ?::varchar
                  AND lease_token=? AND lease_until=?
                  AND next_attempt_at<=clock_timestamp() AND lease_until>clock_timestamp()
                """, due.tenantId(), due.projectId(), due.campaignId(), due.jobId(), due.deviceId(),
                due.attemptNo(), due.jobRevision(), java.sql.Timestamp.from(due.nextAttemptAt()), due.failureCode(),
                due.token(), java.sql.Timestamp.from(due.leaseUntil())) == 1;
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean beginRetry(UUID tenantId, UUID projectId, UUID jobId, String failureCode, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_job_begin_retry(?,?,?,?,?)", Boolean.class,
                tenantId, projectId, jobId, failureCode, reason));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean dispatchRetry(UUID jobId, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_job_dispatch_retry(?,?)", Boolean.class,
                jobId, reason));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean exhaustRetry(UUID jobId, String failureCode, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_job_exhaust_retry(?,?,?)", Boolean.class,
                jobId, failureCode, reason));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean cancelRetryWait(UUID jobId, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_job_cancel_retry_wait(?,?)", Boolean.class,
                jobId, reason));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean hasInstallStopFence(UUID jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ota_install_stop_control WHERE job_id=?)", Boolean.class, jobId));
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public Optional<JobState> jobState(UUID jobId) {
        return jdbc.query("SELECT status,attempt_no,state_version FROM ota_device_job WHERE id=?",
                (rs, row) -> new JobState(rs.getString("status"), rs.getInt("attempt_no"),
                        rs.getLong("state_version")), jobId).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean retryBudgetRemains(UUID jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT j.attempt_no <= (convert_from(c.canonical_plan,'UTF8')::jsonb"
                        + " #>>'{executionPolicy,downloadRetryLimit}')::integer"
                        + " FROM ota_device_job j JOIN ota_campaign c ON c.id=j.campaign_id WHERE j.id=?",
                Boolean.class, jobId));
    }
    /** 重试到期投影字段，不含凭据或签名正文。 */
    private static RetryDue retryDue(ResultSet rs, int row) throws SQLException {
        return new RetryDue(rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("campaign_id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getObject("device_id", UUID.class), rs.getInt("attempt_no"), rs.getLong("state_version"),
                instant(rs, "next_attempt_at"), rs.getString("failure_code"), rs.getObject("lease_token", UUID.class), instant(rs, "lease_until"));
    }
    /** 权威领取字段，数据库token不由调用方构造。 */
    private static Claim claim(ResultSet rs, int row) throws SQLException {        return new Claim(rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("campaign_id", UUID.class), rs.getObject("id", UUID.class), rs.getInt("batch_number"),
                rs.getObject("device_id", UUID.class), rs.getLong("state_version"), rs.getObject("lease_token", UUID.class),
                instant(rs, "lease_until"));
    }
    /** 可空数据库时钟投影。 */
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
