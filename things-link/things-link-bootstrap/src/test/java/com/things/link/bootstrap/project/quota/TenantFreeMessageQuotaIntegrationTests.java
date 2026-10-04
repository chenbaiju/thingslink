package com.things.link.bootstrap.project.quota;

import com.things.link.project.application.DailyUsageReconciliationService;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.domain.DailyUsageReconciliationRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-2b：FREE 冻结消息额度（每日 1,000 条 = 上行 700 + 下行 300）的真实 PostgreSQL 验收。
 *
 * <p><b>本片不给消息新增任何限流器。</b>S14-2a 之后新租户的有效策略是 {@code PLAN_R1_FREE}，
 * 其 {@code uplink_message_daily_limit / downlink_message_daily_limit} 就是 700/300；
 * {@code trusted_project_daily_quota_decision} 已经按这两个列返回日额度与分级阈值，
 * 各写入口也已经在用 {@link ProjectDailyQuotaDecisionService}。此前缺的不是代码，而是
 * <b>用真实事实把这条链跑通的验收</b>：本类从幂等 telemetry 事实出发，经真实归并服务写入
 * {@code sys_usage_counter_daily}，再读回决策服务，逐点钉住软/硬/降级阈值、UTC 日切换与重放不重复计数。
 *
 * <p>共享测试 profile 只关闭 REST 事实的同步落库（{@code things-link.quota.daily-usage-recording-enabled}），
 * 该开关仅作用于 iam 的 REST 过滤器；上行/下行用量来自幂等 inbox/command 事实的绝对重算，
 * 与本开关无关，因此消息额度在测试与生产都是可判定的真实表面。
 */
@DisplayName("S14-2b FREE 消息日额度")
class TenantFreeMessageQuotaIntegrationTests extends AbstractIntegrationTest {

    /** 事实写入与投影核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 从幂等事实重算今天/昨天绝对用量的生产服务。 */
    @Autowired
    private DailyUsageReconciliationService reconciliationService;
    /** 绝对值单调归并端口，用于把某日用量定到精确阈值点。 */
    @Autowired
    private DailyUsageReconciliationRepository reconciliationRepository;
    /** 各入口共用的 PostgreSQL 权威日额度决策服务。 */
    @Autowired
    private ProjectDailyQuotaDecisionService decisionService;
    /** 走真实入口创建绑定 PLAN_R1_FREE 的租户。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 租户创建的 MANDATORY 外层事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例唯一租户（默认 FREE）。 */
    private UUID tenantId;
    /** 本用例唯一项目。 */
    private UUID projectId;
    /** 命令事实外键账号。 */
    private UUID accountId;
    /** 设备与命令定义夹具。 */
    private UUID deviceId;
    /** 设备类型 ID。 */
    private UUID deviceTypeId;
    /** 命令定义 ID。 */
    private UUID commandDefinitionId;

    /** 建立 FREE 租户、项目与最小设备/命令事实夹具，并把线程范围切到该租户项目。 */
    @BeforeEach
    void seed() {
        tenantId = transactionTemplate.execute(status -> tenantProvisioning.createTenant("S14-2b 消息额度租户"));
        accountId = Uuid7.generate();
        projectId = Uuid7.generate();
        deviceTypeId = Uuid7.generate();
        deviceId = Uuid7.generate();
        commandDefinitionId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S14-2b 消息账号')
                """, accountId, "s14-2b-msg-" + accountId + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, 'S14-2b 消息项目', 'sh-1', ?)
                """, projectId, tenantId, projectKey(projectId));
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        jdbcTemplate.update("""
                INSERT INTO dev_type
                    (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status)
                VALUES (?, ?, ?, 's14_2b_msg_type', 'S14-2b 消息设备类型', 'STANDARD', 'DIRECT', 'PUBLISHED')
                """, deviceTypeId, tenantId, projectId);
        jdbcTemplate.update("""
                INSERT INTO dev_device
                    (id, tenant_id, project_id, device_type_id, device_key, name, status)
                VALUES (?, ?, ?, ?, 's14_2b_msg_device', 'S14-2b 消息设备', 'ONLINE')
                """, deviceId, tenantId, projectId, deviceTypeId);
        jdbcTemplate.update("""
                INSERT INTO dev_command_definition
                    (id, tenant_id, project_id, device_type_id, command_key, name,
                     input_schema, output_schema, timeout_seconds)
                VALUES (?, ?, ?, ?, 'restart', '重启', '{}'::jsonb, '{}'::jsonb, 30)
                """, commandDefinitionId, tenantId, projectId, deviceTypeId);
    }

    /** 按依赖顺序回收夹具并清线程范围。 */
    @AfterEach
    void cleanUp() throws SQLException {
        try {
            if (tenantId != null) {
                // 日用量事实先删：它引用项目行，一旦清理中途失败，留在共享库里的这一行会让后续每个
                // 「先清点再删项目」的用例都撞上 sys_usage_counter_daily 外键（verify-8 的 67 条连带失败）。
                jdbcTemplate.update("DELETE FROM sys_usage_counter_daily WHERE tenant_id = ?", tenantId);
                TenantContext.set(new TenantScope(tenantId, projectId, accountId));
                deleteRawPropertyPoints();
                jdbcTemplate.update("DELETE FROM ts_device_command WHERE project_id = ?", projectId);
                deleteMessageLogFacts();
                jdbcTemplate.update("DELETE FROM sys_inbox_message WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM dev_device WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM dev_command_definition WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM dev_type WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM sys_project_member WHERE project_id = ?", projectId);
            }
        } finally {
            TenantContext.clear();
        }
        if (projectId != null) {
            jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
        }
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
        }
        if (accountId != null) {
            jdbcTemplate.update("DELETE FROM sys_account WHERE id = ?", accountId);
        }
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        }
    }

    /**
     * 删除本项目的下行/上行消息日志事实（{@code ts_device_message_log} 是 hypertable）。
     *
     * <p>同样不能用「单条 {@code DELETE ... WHERE project_id = ?}」：该语句被服务端预编译后
     * （JDBC 第 6 次执行起）会让 TimescaleDB 在分块裁剪时抛 {@code variable not found in subplan target list}。
     * 按仓库既有的生产惯用法改成「完整主键 {@code (id, ts)} 候选 CTE + LIMIT 500」分批删除
     * （见 {@code telemetry_project_cleanup_batch}），既避开该计划缺陷也不会长时间持锁。
     */
    private void deleteMessageLogFacts() {
        int deleted;
        do {
            deleted = jdbcTemplate.update("""
                    WITH candidates AS MATERIALIZED (
                        SELECT id, ts
                          FROM ts_device_message_log
                         WHERE project_id = ?
                         ORDER BY id, ts
                         LIMIT 500
                    )
                    DELETE FROM ts_device_message_log log USING candidates candidate
                     WHERE log.id = candidate.id AND log.ts = candidate.ts
                    """, projectId);
        } while (deleted > 0);
    }

    /**
     * 删除本项目在**原始热点表**上的时序点。
     *
     * <p>不能对 {@code ts_property_point} 执行 DELETE：{@code V20260808_1000} 之后它是
     * {@code security_barrier} 视图，TimescaleDB 会把该语句重写成带子查询的计划并报
     * {@code variable not found in subplan target list}（verify-8 实测：只要同一 JVM 里先跑过写点的用例
     * 就会触发）。一次清理失败会中断整个 {@link #cleanUp()}，把租户/项目/日用量行留在共享库里，
     * 后续用例的 {@code DELETE FROM sys_project} 便全部撞上 {@code sys_usage_counter_daily} 外键。
     *
     * <p>按仓库既有做法：走 **fixture owner 连接**（应用角色对原始表没有 DML 权限），
     * 用「完整主键 + LIMIT 500」的候选 CTE 分批删除（与
     * {@code telemetry_project_history_cleanup_batch} 和
     * {@code AbstractIntegrationTest#clearRawPropertyPointsBeforeAllProjectFixtureReset} 同一惯用法），
     * 兼容压缩块且不长时间持锁。
     */
    private void deleteRawPropertyPoints() throws SQLException {
        try (Connection connection = fixtureOwnerConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     WITH candidates AS MATERIALIZED (
                         SELECT project_id, device_id, property_key, ts, message_id
                           FROM public.ts_property_point_internal
                          WHERE project_id = ?
                          ORDER BY device_id, property_key, ts, message_id
                          LIMIT 500
                     )
                     DELETE FROM public.ts_property_point_internal point USING candidates candidate
                      WHERE (point.project_id, point.device_id, point.property_key, point.ts, point.message_id)
                          = (candidate.project_id, candidate.device_id, candidate.property_key,
                             candidate.ts, candidate.message_id)
                     """)) {
            int deleted;
            do {
                statement.setObject(1, projectId);
                deleted = statement.executeUpdate();
            } while (deleted > 0);
        }
    }

    /** FREE 有效策略在真实决策投影里就是冻结的 700 上行 / 300 下行，两池之和为公开总量 1,000，且零用量为 NORMAL。 */
    @Test
    void freeEffectivePolicyPublishesFrozenUplinkAndDownlinkLimits() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        Long uplinkLimit = decisionLimit(today, QuotaMetric.UPLINK_MESSAGE);
        Long downlinkLimit = decisionLimit(today, QuotaMetric.DOWNLINK_MESSAGE);
        assertThat(uplinkLimit).isEqualTo(700L);
        assertThat(downlinkLimit).isEqualTo(300L);
        assertThat(uplinkLimit + downlinkLimit)
                .as("S14-0 P2：公开总量必须恒等于两池之和，取整余数归上行")
                .isEqualTo(1_000L);
        assertThat(decisionService.decideTrustedProject(tenantId, projectId, QuotaMetric.UPLINK_MESSAGE))
                .isEqualTo(QuotaStatus.NORMAL);
        assertThat(decisionService.decideTrustedProject(tenantId, projectId, QuotaMetric.DOWNLINK_MESSAGE))
                .isEqualTo(QuotaStatus.NORMAL);
    }

    /**
     * 上行在 560/700/840 处依次进入软限、硬限、降级；下行在 240/300/360 处同理。
     *
     * <p>阈值来自策略行的 8000/12000 基点；700 的 80% = 560、120% = 840，300 的 80% = 240、120% = 360。
     */
    @Test
    void frozenThresholdsProduceSoftHardAndDegradedAtExactBoundaries() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertUsageStatus(today, QuotaMetric.UPLINK_MESSAGE, 559, QuotaStatus.NORMAL);
        assertUsageStatus(today, QuotaMetric.UPLINK_MESSAGE, 560, QuotaStatus.SOFT_LIMIT);
        assertUsageStatus(today, QuotaMetric.UPLINK_MESSAGE, 700, QuotaStatus.HARD_LIMIT);
        assertUsageStatus(today, QuotaMetric.UPLINK_MESSAGE, 840, QuotaStatus.DEGRADED);

        assertUsageStatus(today, QuotaMetric.DOWNLINK_MESSAGE, 239, QuotaStatus.NORMAL);
        assertUsageStatus(today, QuotaMetric.DOWNLINK_MESSAGE, 240, QuotaStatus.SOFT_LIMIT);
        assertUsageStatus(today, QuotaMetric.DOWNLINK_MESSAGE, 300, QuotaStatus.HARD_LIMIT);
        assertUsageStatus(today, QuotaMetric.DOWNLINK_MESSAGE, 360, QuotaStatus.DEGRADED);
    }

    /** 幂等 inbox 与命令事实只计一次：重复归并、消息重放、命令重试都不抬高用量。 */
    @Test
    void reconciliationCountsIdempotentFactsOnceAndReplayDoesNotDoubleCount() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = Instant.now();
        UUID firstMessageId = Uuid7.generate();
        UUID secondMessageId = Uuid7.generate();
        seedInboxMessage(firstMessageId, now);
        seedInboxMessage(secondMessageId, now.plusSeconds(1));
        UUID commandId = seedCommand(now);

        reconcile();
        assertThat(dailyUsed(today, QuotaMetric.UPLINK_MESSAGE)).isEqualTo(2L);
        assertThat(dailyUsed(today, QuotaMetric.DOWNLINK_MESSAGE)).isEqualTo(1L);

        // Kafka 重试/扫描重叠：绝对重算 + GREATEST 只能把用量单调推进一次。
        reconcile();
        assertThat(dailyUsed(today, QuotaMetric.UPLINK_MESSAGE)).isEqualTo(2L);

        // 重放同一条上行消息在幂等键上被数据库拒绝，不可能变成第三条用量。
        assertThatThrownBy(() -> seedInboxMessage(firstMessageId, now.plusSeconds(5)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 命令重试只递增同一条命令事实的尝试次数，不产生新下行事实。
        jdbcTemplate.update("UPDATE ts_device_command SET attempt_count = attempt_count + 1 WHERE id = ?", commandId);
        reconcile();

        assertThat(dailyUsed(today, QuotaMetric.UPLINK_MESSAGE)).isEqualTo(2L);
        assertThat(dailyUsed(today, QuotaMetric.DOWNLINK_MESSAGE)).isEqualTo(1L);
    }

    /**
     * UTC 日边界：昨日 23:59:59 记昨日、今日 00:00:00 记今日，昨日的超额不让今日提前降级。
     */
    @Test
    void utcDayBoundarySeparatesFactsAndResetsTodaysDecision() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate yesterday = today.minusDays(1);
        seedInboxMessage(Uuid7.generate(), yesterday.atTime(23, 59, 59).toInstant(ZoneOffset.UTC));
        seedInboxMessage(Uuid7.generate(), today.atStartOfDay(ZoneOffset.UTC).toInstant());

        reconcile();

        assertThat(dailyUsed(yesterday, QuotaMetric.UPLINK_MESSAGE))
                .as("23:59:59 属于上一个 UTC 日")
                .isEqualTo(1L);
        assertThat(dailyUsed(today, QuotaMetric.UPLINK_MESSAGE))
                .as("00:00:00 属于新的 UTC 日")
                .isEqualTo(1L);

        // 把昨日推到降级水位之上：今日决策必须只看今日窗口，回到 1 条的正常状态。
        mergeAbsolute(yesterday, QuotaMetric.UPLINK_MESSAGE, 900L);
        assertThat(dailyUsed(yesterday, QuotaMetric.UPLINK_MESSAGE)).isEqualTo(900L);
        assertThat(decisionLimit(yesterday, QuotaMetric.UPLINK_MESSAGE)).isEqualTo(700L);
        assertThat(decisionService.decideTrustedProject(tenantId, projectId, QuotaMetric.UPLINK_MESSAGE))
                .as("00:00:00 之后昨日用量不再计入，决策必须重置")
                .isEqualTo(QuotaStatus.NORMAL);
    }

    /** 把一个日指标定到精确用量，并用生产决策服务读取状态。 */
    private void assertUsageStatus(LocalDate date, QuotaMetric metric, long used, QuotaStatus expected) {
        mergeAbsolute(date, metric, used);
        assertThat(dailyUsed(date, metric)).isEqualTo(used);
        assertThat(decisionService.decideTrustedProject(tenantId, projectId, metric))
                .as("%s 用量 %d 的状态", metric, used)
                .isEqualTo(expected);
    }

    /** 执行一次真实归并；租户/项目范围由线程上下文在借出连接时写入。 */
    private void reconcile() {
        reconciliationService.reconcile(new DailyUsageScope(tenantId, projectId));
    }

    /** 直接调用权威决策函数取某日某指标的上限。 */
    private Long decisionLimit(LocalDate date, QuotaMetric metric) {
        return jdbcTemplate.queryForObject("""
                SELECT limit_value FROM trusted_project_daily_quota_decision(?, ?, ?, ?)
                """, Long.class, tenantId, projectId, date, metric.name());
    }

    /** 取权威日用量的租户合计。 */
    private long dailyUsed(LocalDate date, QuotaMetric metric) {
        Long used = jdbcTemplate.queryForObject("""
                SELECT tenant_used_value FROM trusted_project_daily_quota_decision(?, ?, ?, ?)
                """, Long.class, tenantId, projectId, date, metric.name());
        return used == null ? 0L : used;
    }

    /** 用绝对值单调归并把一个指标定到精确值；GREATEST 语义要求调用方只单调抬升。 */
    private void mergeAbsolute(LocalDate date, QuotaMetric metric, long used) {
        reconciliationRepository.mergeAbsolute(
                new com.things.link.project.domain.DailyUsageScope(tenantId, projectId), date,
                new com.things.link.project.domain.DailyUsageValue(
                        com.things.link.project.domain.QuotaMetric.valueOf(metric.name()), used));
    }

    /** 写一条幂等上行事实；同一 message_id 重放时由主键拒绝。 */
    private void seedInboxMessage(UUID messageId, Instant receivedAt) {
        jdbcTemplate.update("INSERT INTO sys_inbox_message (message_id, project_id, received_at) VALUES (?, ?, ?)",
                messageId, projectId, Timestamp.from(receivedAt));
    }

    /** 写一条可计数的下行命令事实，返回命令 ID 供重试夹具复用。 */
    private UUID seedCommand(Instant acceptedAt) {
        UUID commandId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO ts_device_command
                    (id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                     command_key, request_payload, status, idempotency_key, requested_by, timeout_seconds,
                     attempt_count, max_attempts, next_attempt_at, trace_id, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, 'restart', '{}'::jsonb, 'ACCEPTED', ?, ?, 30, 0, 3, ?, ?, ?)
                """, commandId, tenantId, projectId, deviceId, deviceId, commandDefinitionId,
                "s14-2b-command-" + commandId, accountId,
                Timestamp.from(acceptedAt), "s14-2b-trace", Timestamp.from(acceptedAt));
        return commandId;
    }

    /** @param projectId 项目 UUID @return 满足 MQTT project_key 字符集与唯一性的测试短标识 */
    private static String projectKey(UUID projectId) {
        return "s142b" + projectId.toString().replace("-", "").substring(0, 15);
    }
}
