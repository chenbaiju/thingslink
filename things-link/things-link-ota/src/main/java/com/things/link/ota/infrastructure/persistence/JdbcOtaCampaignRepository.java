package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 活动完整事实同事务落库，无网络或隐式派发副作用。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaCampaignRepository implements OtaCampaignRepository {
    /** 固定列，不接受客户端SQL片段。 */
    private static final String COLUMNS = "id,tenant_id,project_id,firmware_id,release_id,created_by,canonical_plan,"
            + "plan_sha256,canonical_manifest,manifest_sha256,status,state_version,target_count,batch_count,"
            + "created_at,updated_at,scheduled_at,cancelled_at,cancellation_reason";
    /** 列表白名单列；刻意不选规范计划、清单与发布身份，避免按行拉取大对象。 */
    private static final String SUMMARY_COLUMNS = "id,firmware_id,status,state_version,target_count,batch_count,"
            + "created_at,updated_at,scheduled_at,cancelled_at";
    /** 作业白名单列；刻意不选租户、租约令牌、规范字节与资格报告摘要。 */
    private static final String JOB_SUMMARY_COLUMNS = "id,batch_number,device_id,device_type_id,"
            + "thing_model_version_id,credential_version,status,attempt_no,state_version,failure_code,"
            + "first_dispatched_at,dispatched_at,deadline_at,next_attempt_at";
    /** 当前事务及RLS连接。 */
    private final JdbcTemplate jdbc;
    /** 注入数据平面连接。 */
    public JdbcOtaCampaignRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void lockCreation(UUID tenant, UUID project, UUID account, String keyDigest) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended("
                + "concat_ws(':','ota-campaign-create',?::text,?::text,?::text,?::text),13025::bigint))",
                Integer.class, tenant, project, account, keyDigest);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<Creation> findCreation(UUID project, UUID account, String keyDigest) {
        return jdbc.query("SELECT campaign_id,request_digest FROM ota_campaign_creation_request"
                + " WHERE project_id=? AND account_id=? AND key_digest=?",
                (rs, row) -> new Creation(rs.getObject("campaign_id", UUID.class), rs.getString("request_digest")),
                project, account, keyDigest).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<OtaCampaign> find(UUID project, UUID campaign, boolean exclusive) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ota_campaign WHERE project_id=? AND id=?"
                + (exclusive ? " FOR UPDATE" : ""), JdbcOtaCampaignRepository::map, project, campaign)
                .stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public CursorPage<Summary> search(UUID project, String cursor, int limit) {
        if (limit < 1 || limit > 100) { throw invalidCursor(); }
        List<Summary> rows;
        if (cursor == null || cursor.isEmpty()) {
            rows = jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM ota_campaign WHERE project_id=?"
                    + " ORDER BY created_at DESC,id DESC LIMIT ?", JdbcOtaCampaignRepository::mapSummary,
                    project, limit + 1);
        } else {
            Instant time;
            UUID id;
            try {
                if (cursor.length() > 256) { throw invalidCursor(); }
                String[] parts = Cursor.decode(cursor).split("\\|", -1);
                // 游标载荷携带项目身份，跨项目复用同一游标直接判为非法，不泄露其他项目位置。
                if (parts.length != 3 || !project.toString().equals(parts[0])) { throw invalidCursor(); }
                time = Instant.parse(parts[1]);
                id = UUID.fromString(parts[2]);
            } catch (RuntimeException failure) { throw invalidCursor(); }
            rows = jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM ota_campaign WHERE project_id=?"
                    + " AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
                    JdbcOtaCampaignRepository::mapSummary, project, Timestamp.from(time), id, limit + 1);
        }
        if (rows.size() <= limit) { return CursorPage.last(rows); }
        List<Summary> items = List.copyOf(rows.subList(0, limit));
        Summary last = items.getLast();
        return CursorPage.of(items, Cursor.encode(project + "|" + last.createdAt() + "|" + last.id()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void create(OtaCampaign c, String keyDigest, String requestDigest) {
        jdbc.update("INSERT INTO ota_campaign (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.id(), c.tenantId(), c.projectId(), c.firmwareId(), c.releaseId(), c.createdBy(), c.canonicalPlan(),
                c.planSha256(), c.canonicalManifest(), c.manifestSha256(), c.status(), c.stateVersion(),
                c.targetCount(), c.batchCount(), time(c.createdAt()), time(c.updatedAt()), time(c.scheduledAt()),
                time(c.cancelledAt()), c.cancellationReason());
        jdbc.update("INSERT INTO ota_campaign_creation_request"
                + "(tenant_id,project_id,account_id,key_digest,request_digest,campaign_id) VALUES(?,?,?,?,?,?)",
                c.tenantId(), c.projectId(), c.createdBy(), keyDigest, requestDigest, c.id());
        transition(c, null, "DRAFT", 0, c.createdBy(), c.createdAt(), null);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean schedule(long expectedRevision, OtaCampaign frozen, List<Target> targets, int batchSize,
            UUID actor, Instant occurredAt) {
        if (targets.isEmpty() || targets.size() > 1000 || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("活动目标与批次超出平台上限");
        }
        List<Target> sorted = targets.stream().sorted(Comparator.comparing(t -> t.deviceId().toString())).toList();
        if (sorted.stream().map(Target::deviceId).distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("活动目标重复");
        }
        // 所有活动按规范UUID文本顺序领取设备预占锁，冲突检查发生在任何写入之前。
        String deviceArray = "{" + sorted.stream().map(t -> t.deviceId().toString())
                .collect(java.util.stream.Collectors.joining(",")) + "}";
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended("
                + "concat_ws(':','ota-campaign-device',device::text),13025::bigint))"
                + " FROM unnest(?::uuid[]) AS device ORDER BY device::text", deviceArray);
        // 与数据库独占索引采用相同的已实现真实终态集合，恢复未决继续占位。
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ota_device_job"
                + " WHERE device_id=ANY(?::uuid[]) AND status NOT IN ('CANCELLED','SKIPPED_INELIGIBLE','SUCCEEDED','ROLLED_BACK'))", Boolean.class, deviceArray))) {
            throw new TargetOccupiedException();
        }
        int changed = jdbc.update("""
                UPDATE ota_campaign SET status='SCHEDULED',state_version=state_version+1,
                    target_count=?,batch_count=?,scheduled_at=?,updated_at=?
                WHERE tenant_id=? AND project_id=? AND id=? AND status='DRAFT' AND state_version=?
                """, sorted.size(), (sorted.size() + batchSize - 1) / batchSize, time(occurredAt), time(occurredAt),
                frozen.tenantId(), frozen.projectId(), frozen.id(), expectedRevision);
        if (changed == 0) return false;
        List<Object[]> batchRows = new ArrayList<>();
        List<Object[]> jobRows = new ArrayList<>();
        for (int start = 0; start < sorted.size(); start += batchSize) {
            int batch = start / batchSize + 1;
            int end = Math.min(start + batchSize, sorted.size());
            batchRows.add(new Object[]{frozen.tenantId(), frozen.projectId(), frozen.id(), batch, end - start});
            for (int index = start; index < end; index++) {
                Target t = sorted.get(index);
                jobRows.add(new Object[]{Uuid7.generate(), frozen.tenantId(), frozen.projectId(), frozen.id(), batch,
                        t.deviceId(), t.deviceTypeId(), t.thingModelVersionId(), t.credentialVersion()});
            }
        }
        jdbc.batchUpdate("INSERT INTO ota_campaign_batch(tenant_id,project_id,campaign_id,batch_number,target_count,status)"
                + " VALUES(?,?,?,?,?,'PENDING')", batchRows);
        jdbc.batchUpdate("INSERT INTO ota_device_job(id,tenant_id,project_id,campaign_id,batch_number,device_id,"
                + "device_type_id,thing_model_version_id,credential_version,status) VALUES(?,?,?,?,?,?,?,?,?,'PENDING')", jobRows);
        transition(frozen, "DRAFT", "SCHEDULED", expectedRevision + 1, actor, occurredAt, null);
        return true;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean cancelUndispatched(long expectedRevision, OtaCampaign supplied, UUID actor,
            Instant occurredAt, String reason) {
        Optional<OtaCampaign> found = find(supplied.projectId(), supplied.id(), true);
        if (found.isEmpty()) return false;
        OtaCampaign c = found.get();
        if (c.stateVersion() != expectedRevision || !(c.status().equals("DRAFT") || c.status().equals("SCHEDULED"))) {
            return false;
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ota_device_job"
                + " WHERE campaign_id=? AND status<>'PENDING')", Boolean.class, c.id()))) return false;
        if (c.status().equals("DRAFT")) {
            updateCancellation(c, "DRAFT", "CANCELLED", expectedRevision, occurredAt, reason, true);
            transition(c, "DRAFT", "CANCELLED", expectedRevision + 1, actor, occurredAt, reason);
            return true;
        }
        updateCancellation(c, "SCHEDULED", "CANCELLING", expectedRevision, occurredAt, reason, false);
        transition(c, "SCHEDULED", "CANCELLING", expectedRevision + 1, actor, occurredAt, reason);
        jdbc.queryForList("SELECT ota_campaign_cancel_batches(?,?,?)", c.tenantId(), c.projectId(), c.id());
        updateCancellation(c, "CANCELLING", "CANCELLED", expectedRevision + 1, occurredAt, reason, true);
        transition(c, "CANCELLING", "CANCELLED", expectedRevision + 2, actor, occurredAt, reason);
        return true;
    }

    /** 单步更新头，调用方已持有头排他锁。 */
    private void updateCancellation(OtaCampaign c, String from, String to, long revision, Instant at,
            String reason, boolean complete) {
        int changed = jdbc.update("UPDATE ota_campaign SET status=?,state_version=state_version+1,"
                + "updated_at=?,cancellation_reason=?,cancelled_at=? WHERE tenant_id=? AND project_id=?"
                + " AND id=? AND status=? AND state_version=?", to, time(at), reason, complete ? time(at) : null,
                c.tenantId(), c.projectId(), c.id(), from, revision);
        if (changed != 1) throw new IllegalStateException("活动取消持锁修订发生变化");
    }

    /** 转移与待交付事件一对一；事件不包含计划、地址或秘密。 */
    private void transition(OtaCampaign c, String from, String to, long revision, UUID actor, Instant at,
            String reason) {
        UUID transition = Uuid7.generate();
        jdbc.update("INSERT INTO ota_campaign_transition"
                + "(id,tenant_id,project_id,campaign_id,from_status,to_status,state_version,actor_id,occurred_at,reason)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)", transition, c.tenantId(), c.projectId(), c.id(), from, to,
                revision, actor, time(at), reason);
        jdbc.update("INSERT INTO ota_campaign_outbox"
                + "(id,tenant_id,project_id,campaign_id,transition_id,event_type,state_version,status,created_at)"
                + " VALUES(?,?,?,?,?,'OTA_CAMPAIGN_STATE_CHANGED',?,?,?)", Uuid7.generate(), c.tenantId(),
                c.projectId(), c.id(), transition, revision, to, time(at));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<Target> targets(UUID project, UUID campaign) {
        return jobs(project, campaign).stream().map(j -> new Target(j.deviceId(), j.deviceTypeId(),
                j.thingModelVersionId(), j.credentialVersion())).toList();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<Job> jobs(UUID project, UUID campaign) {
        List<Job> result = jdbc.query("SELECT id,batch_number,device_id,device_type_id,thing_model_version_id,"
                + "credential_version,status FROM ota_device_job WHERE project_id=? AND campaign_id=?"
                + " ORDER BY device_id::text LIMIT 1001", (rs, row) -> new Job(rs.getObject("id", UUID.class),
                rs.getInt("batch_number"), rs.getObject("device_id", UUID.class),
                rs.getObject("device_type_id", UUID.class), rs.getObject("thing_model_version_id", UUID.class),
                rs.getLong("credential_version"), rs.getString("status")), project, campaign);
        if (result.size() > 1000) throw new IllegalStateException("活动持久目标超出平台上限");
        return List.copyOf(result);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public CursorPage<JobSummary> jobs(UUID project, UUID campaign, String cursor, int limit) {
        if (limit < 1 || limit > 200) { throw invalidJobCursor(); }
        List<JobSummary> rows;
        if (cursor == null || cursor.isEmpty()) {
            rows = jdbc.query("SELECT " + JOB_SUMMARY_COLUMNS + " FROM ota_device_job WHERE project_id=?"
                    + " AND campaign_id=? ORDER BY id DESC LIMIT ?", JdbcOtaCampaignRepository::mapJobSummary,
                    project, campaign, limit + 1);
        } else {
            UUID id;
            try {
                if (cursor.length() > 256) { throw invalidJobCursor(); }
                String[] parts = Cursor.decode(cursor).split("\\|", -1);
                // 游标载荷携带项目身份，跨项目复用同一游标直接判为非法，不泄露其他项目位置。
                if (parts.length != 2 || !project.toString().equals(parts[0])) { throw invalidJobCursor(); }
                id = UUID.fromString(parts[1]);
            } catch (RuntimeException failure) { throw invalidJobCursor(); }
            rows = jdbc.query("SELECT " + JOB_SUMMARY_COLUMNS + " FROM ota_device_job WHERE project_id=?"
                    + " AND campaign_id=? AND id<? ORDER BY id DESC LIMIT ?",
                    JdbcOtaCampaignRepository::mapJobSummary, project, campaign, id, limit + 1);
        }
        if (rows.size() <= limit) { return CursorPage.last(rows); }
        List<JobSummary> items = List.copyOf(rows.subList(0, limit));
        return CursorPage.of(items, Cursor.encode(project + "|" + items.getLast().id()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code ota_device_job} 没有 created_at：作业主键是 UUIDv7，高 48 位即生成毫秒且进程内严格
     * 单调，因此 {@code ORDER BY id DESC} 就是真实的创建倒序（与活动维度作业列表同一口径）。
     * 游标载荷仍绑定 {@code project|device|id} 三元组，跨项目或跨设备复用一律判为非法。
     */
    @Override
    public CursorPage<JobSummary> jobsByDevice(UUID project, UUID device, String cursor, int limit) {
        if (limit < 1 || limit > 100) { throw invalidJobCursor(); }
        List<JobSummary> rows;
        if (cursor == null || cursor.isEmpty()) {
            rows = jdbc.query("SELECT " + JOB_SUMMARY_COLUMNS + " FROM ota_device_job WHERE project_id=?"
                    + " AND device_id=? ORDER BY id DESC LIMIT ?", JdbcOtaCampaignRepository::mapJobSummary,
                    project, device, limit + 1);
        } else {
            UUID id;
            try {
                if (cursor.length() > 320) { throw invalidJobCursor(); }
                String[] parts = Cursor.decode(cursor).split("\\|", -1);
                // 游标同时绑定项目与设备，跨父资源续页直接判为非法，不泄露其他设备的作业位置。
                if (parts.length != 3 || !project.toString().equals(parts[0])
                        || !device.toString().equals(parts[1])) { throw invalidJobCursor(); }
                id = UUID.fromString(parts[2]);
            } catch (RuntimeException failure) { throw invalidJobCursor(); }
            rows = jdbc.query("SELECT " + JOB_SUMMARY_COLUMNS + " FROM ota_device_job WHERE project_id=?"
                    + " AND device_id=? AND id<? ORDER BY id DESC LIMIT ?",
                    JdbcOtaCampaignRepository::mapJobSummary, project, device, id, limit + 1);
        }
        if (rows.size() <= limit) { return CursorPage.last(rows); }
        List<JobSummary> items = List.copyOf(rows.subList(0, limit));
        return CursorPage.of(items, Cursor.encode(project + "|" + device + "|" + items.getLast().id()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<JobDetail> job(UUID project, UUID campaign, UUID job) {
        Optional<JobSummary> found = jdbc.query("SELECT " + JOB_SUMMARY_COLUMNS + " FROM ota_device_job"
                + " WHERE project_id=? AND campaign_id=? AND id=?", JdbcOtaCampaignRepository::mapJobSummary,
                project, campaign, job).stream().findFirst();
        if (found.isEmpty()) { return Optional.empty(); }
        // to_revision自1起连续递增，既是历史顺序也是链式证据；作业状态机有界（每阶段至多一次转移
        // 加有限重试），最早的200条必然覆盖最新事实，上限只用于防御损坏数据而非截断正常运行历史。
        List<JobTransition> transitions = List.copyOf(jdbc.query("SELECT from_status,to_status,from_revision,"
                + "to_revision,actor_kind,actor_id,occurred_at,reason FROM ota_job_transition"
                + " WHERE project_id=? AND campaign_id=? AND job_id=? ORDER BY to_revision ASC LIMIT 200",
                JdbcOtaCampaignRepository::mapJobTransition, project, campaign, job));
        return Optional.of(new JobDetail(found.get(), transitions));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<Batch> batches(UUID project, UUID campaign) {
        // 计数口径与运行投影完全一致：只认真实作业终态，不从计划或策略推算。
        return List.copyOf(jdbc.query("""
                SELECT b.batch_number,b.status,b.target_count,t.occurred_at AS completed_at,t.to_status AS outcome,
                    count(j.id) FILTER (WHERE j.status='SUCCEEDED') AS succeeded_count,
                    count(j.id) FILTER (WHERE j.status='ROLLED_BACK') AS rolled_back_count,
                    count(j.id) FILTER (WHERE j.status='SKIPPED_INELIGIBLE') AS skipped_count,
                    count(j.id) FILTER (WHERE j.status='TIMED_OUT') AS timed_out_count,
                    count(j.id) FILTER (WHERE j.status='CANCELLED') AS cancelled_count
                FROM ota_campaign_batch b
                LEFT JOIN ota_device_job j ON j.project_id=b.project_id AND j.campaign_id=b.campaign_id
                    AND j.batch_number=b.batch_number
                LEFT JOIN LATERAL (
                    SELECT to_status,occurred_at FROM ota_batch_transition
                    WHERE project_id=b.project_id AND campaign_id=b.campaign_id AND batch_number=b.batch_number
                        AND to_status IN ('SUCCEEDED','FAILED','CANCELLED')
                    ORDER BY to_revision DESC LIMIT 1) t ON true
                WHERE b.project_id=? AND b.campaign_id=?
                GROUP BY b.batch_number,b.status,b.target_count,t.occurred_at,t.to_status
                ORDER BY b.batch_number
                """, JdbcOtaCampaignRepository::mapBatch, project, campaign));
    }

    /** 数据库精确投影，不自动注入当前配置。 */
    private static OtaCampaign map(ResultSet rs, int row) throws SQLException {
        return new OtaCampaign(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("firmware_id", UUID.class),
                rs.getObject("release_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getBytes("canonical_plan"), rs.getString("plan_sha256"), rs.getBytes("canonical_manifest"),
                rs.getString("manifest_sha256"), rs.getString("status"), rs.getLong("state_version"),
                rs.getInt("target_count"), rs.getInt("batch_count"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "scheduled_at"), instant(rs, "cancelled_at"),
                rs.getString("cancellation_reason"));
    }

    /** 列表白名单投影，不构造含规范字节的完整聚合。 */
    private static Summary mapSummary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getObject("id", UUID.class), rs.getObject("firmware_id", UUID.class),
                rs.getString("status"), rs.getLong("state_version"), rs.getInt("target_count"),
                rs.getInt("batch_count"), instant(rs, "created_at"), instant(rs, "updated_at"),
                instant(rs, "scheduled_at"), instant(rs, "cancelled_at"));
    }

    /** 作业白名单投影，不含租户、租约令牌与资格报告。 */
    private static JobSummary mapJobSummary(ResultSet rs, int row) throws SQLException {
        return new JobSummary(rs.getObject("id", UUID.class), rs.getInt("batch_number"),
                rs.getObject("device_id", UUID.class), rs.getObject("device_type_id", UUID.class),
                rs.getObject("thing_model_version_id", UUID.class), rs.getLong("credential_version"),
                rs.getString("status"), rs.getInt("attempt_no"), rs.getLong("state_version"),
                rs.getString("failure_code"), instant(rs, "first_dispatched_at"), instant(rs, "dispatched_at"),
                instant(rs, "deadline_at"), instant(rs, "next_attempt_at"));
    }

    /** 不可变转移投影；actor_id为空的SYSTEM转移是真实事实而非缺失。 */
    private static JobTransition mapJobTransition(ResultSet rs, int row) throws SQLException {
        return new JobTransition(rs.getString("from_status"), rs.getString("to_status"),
                rs.getLong("from_revision"), rs.getLong("to_revision"), rs.getString("actor_kind"),
                rs.getObject("actor_id", UUID.class), instant(rs, "occurred_at"), rs.getString("reason"));
    }

    /** 批次投影：可空终结时间由左连接的真实转移决定。 */
    private static Batch mapBatch(ResultSet rs, int row) throws SQLException {
        return new Batch(rs.getInt("batch_number"), rs.getString("status"), rs.getInt("target_count"),
                rs.getLong("succeeded_count"), rs.getLong("rolled_back_count"), rs.getLong("skipped_count"),
                rs.getLong("timed_out_count"), rs.getLong("cancelled_count"), instant(rs, "completed_at"), rs.getString("outcome"));
    }

    /** 游标和范围无效不回显解码内容。 */
    private static BusinessException invalidCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "活动分页参数不合法");
    }

    /** 作业游标与范围无效同样不回显解码内容。 */
    private static BusinessException invalidJobCursor() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "作业分页参数不合法");
    }

    /** 可空数据库时间映射。 */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    /** 可空时间参数。 */
    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
}
