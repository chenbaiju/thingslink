package com.things.link.bootstrap.rule;

import com.things.link.rule.domain.DeviceActionDeliverySummary;
import com.things.link.rule.domain.NotificationDeliverySummary;
import com.things.link.rule.domain.RuleExecutionAttempt;
import com.things.link.rule.domain.RuleExecutionLogQuery;
import com.things.link.rule.domain.RuleExecutionLogReadRepository;
import com.things.link.rule.domain.RuleExecutionSummary;
import com.things.link.rule.domain.RuleOption;
import com.things.link.rule.domain.RuleSceneExecutionQuery;
import com.things.link.rule.domain.RuleSceneExecutionReadRepository;
import com.things.link.rule.domain.RuleSceneExecutionSummary;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S9-5 执行记录只读查询的真实 PostgreSQL、RLS 与游标分页验收。
 *
 * <p>两个只读仓储（上行规则执行聚合 + 手动场景执行）都在真实 TimescaleDB 上验证：attempt 聚合压成执行级摘要、
 * 键集游标稳定翻页、状态/规则/场景/时间筛选、读侧关联当前名称、详情追加投递状态摘要，以及「另一项目读不到」的
 * 隔离断言。种子数据由迁移超级用户（表 owner，不受 RLS 与 REVOKE 影响）直写，查询走受 RLS 约束的应用角色仓储。</p>
 */
class RuleExecutionReadQueryIntegrationTests extends AbstractIntegrationTest {

    /** 上行规则执行日志只读仓储（受 RLS 约束的应用角色 Bean）。 */
    @Autowired private RuleExecutionLogReadRepository executionLogRepository;
    /** 手动场景执行只读仓储。 */
    @Autowired private RuleSceneExecutionReadRepository sceneExecutionRepository;

    private final java.util.ArrayList<UUID> fixtureProjects = new java.util.ArrayList<>();

    /** ThreadLocal 项目范围不得泄漏到其他集成测试。 */
    @AfterEach
    void clearScopeAndFacts() throws SQLException {
        TenantContext.clear();
        // 只清本例创建的项目事实，不能删除其他缓存上下文的活动场景版本。
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            for (UUID project : fixtureProjects) {
                for (String sql : List.of(
                        "DELETE FROM rule_notification_delivery WHERE project_id=?",
                        "DELETE FROM rule_device_action_delivery WHERE project_id=?",
                        "DELETE FROM rule_scene_execution WHERE project_id=?",
                        "UPDATE rule_scene SET status='DRAFT',active_version_id=NULL WHERE project_id=?",
                        "DELETE FROM rule_scene_version WHERE project_id=?",
                        "DELETE FROM rule_scene WHERE project_id=?",
                        "DELETE FROM rule_execution_log WHERE project_id=?",
                        "UPDATE rule_message SET status='DRAFT',active_version_id=NULL WHERE project_id=?",
                        "DELETE FROM rule_version WHERE project_id=?",
                        "DELETE FROM rule_message WHERE project_id=?")) {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setObject(1, project);
                        statement.executeUpdate();
                    }
                }
            }
            connection.commit();
        }
    }

    /**
     * 上行规则执行：同一逻辑执行的多次 attempt 聚合为执行级摘要，最新 attempt 决定状态与结果码，累计耗时相加，
     * 时间边界取首末 attempt；再验证状态/规则筛选、键集游标翻页、attempt 时间线与规则筛选选项。
     */
    @Test
    void messageRuleExecutionAggregatesAttemptsAndPaginates() throws SQLException {
        Fixture a = seedBase("msg-a");
        Fixture b = seedBase("msg-b");
        // timestamptz 只到微秒精度，先把基准截断到微秒，避免读回值与纳秒期望的逐位比较失配。
        Instant base = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);

        UUID ruleAlpha = Uuid7.generate();
        UUID ruleBeta = Uuid7.generate();
        UUID versionAlpha = Uuid7.generate();
        UUID versionBeta = Uuid7.generate();
        UUID messageM1 = Uuid7.generate();
        UUID messageM2 = Uuid7.generate();
        UUID messageM3 = Uuid7.generate();

        try (Connection connection = POSTGRES.createConnection("")) {
            insertRule(connection, a, ruleAlpha, "rule-alpha", versionAlpha);
            insertRule(connection, a, ruleBeta, "rule-beta", versionBeta);
            // 逻辑执行 M1：三次 attempt，最新 DEAD_LETTER，累计 60ms，结果码取最新 attempt。
            insertLog(connection, a, messageM1, ruleAlpha, versionAlpha, 1,
                    "SUCCESS", "SUCCESS", 10, 100, 110, base.plusSeconds(10));
            insertLog(connection, a, messageM1, ruleAlpha, versionAlpha, 2,
                    "RETRY_SCHEDULED", "RETRY_SCHEDULED", 20, 100, 0, base.plusSeconds(20));
            insertLog(connection, a, messageM1, ruleAlpha, versionAlpha, 3,
                    "DEAD_LETTER", "SCRIPT_FAILURE", 30, 100, 0, base.plusSeconds(30));
            // 逻辑执行 M2：单次成功，最后尝试最晚。
            insertLog(connection, a, messageM2, ruleAlpha, versionAlpha, 1,
                    "SUCCESS", "SUCCESS", 40, 90, 95, base.plusSeconds(50));
            // 逻辑执行 M3：另一条规则的等待重试。
            insertLog(connection, a, messageM3, ruleBeta, versionBeta, 1,
                    "RETRY_SCHEDULED", "RETRY_SCHEDULED", 50, 80, 0, base.plusSeconds(40));

            // 邻项目规则与执行事实，只做隔离对照。
            UUID otherRule = Uuid7.generate();
            UUID otherVersion = Uuid7.generate();
            insertRule(connection, b, otherRule, "neighbor-rule", otherVersion);
            insertLog(connection, b, Uuid7.generate(), otherRule, otherVersion, 1,
                    "SUCCESS", "SUCCESS", 1, 1, 1, base.plusSeconds(60));
        }

        List<RuleExecutionSummary> all = inScope(a, () -> executionLogRepository
                .findSummaries(new RuleExecutionLogQuery(a.projectId(), null, null, null, null, null, 50)).items());
        assertThat(all).hasSize(3).as("邻项目执行事实必须被 RLS 隐藏");
        // 按最后尝试时刻倒序：M2(t50)、M3(t40)、M1(t30)。
        assertThat(all).extracting(RuleExecutionSummary::messageId)
                .containsExactly(messageM2, messageM3, messageM1);

        RuleExecutionSummary m1 = all.get(2);
        assertThat(m1.attemptCount()).isEqualTo(3);
        assertThat(m1.status()).isEqualTo("DEAD_LETTER");
        assertThat(m1.resultCode()).isEqualTo("SCRIPT_FAILURE");
        assertThat(m1.cumulativeDurationMillis()).isEqualTo(60L);
        assertThat(m1.ruleName()).isEqualTo("rule-alpha");
        assertThat(m1.firstAttemptAt()).isEqualTo(base.plusSeconds(10));
        assertThat(m1.lastAttemptAt()).isEqualTo(base.plusSeconds(30));

        List<RuleExecutionSummary> success = inScope(a, () -> executionLogRepository
                .findSummaries(new RuleExecutionLogQuery(a.projectId(), null, "SUCCESS", null, null, null, 50)).items());
        assertThat(success).extracting(RuleExecutionSummary::messageId).containsExactly(messageM2);

        List<RuleExecutionSummary> byRule = inScope(a, () -> executionLogRepository
                .findSummaries(new RuleExecutionLogQuery(a.projectId(), ruleAlpha, null, null, null, null, 50)).items());
        assertThat(byRule).extracting(RuleExecutionSummary::messageId)
                .containsExactlyInAnyOrder(messageM1, messageM2);

        CursorPage<RuleExecutionSummary> page1 = inScope(a, () -> executionLogRepository
                .findSummaries(new RuleExecutionLogQuery(a.projectId(), null, null, null, null, null, 2)));
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isNotBlank();
        CursorPage<RuleExecutionSummary> page2 = inScope(a, () -> executionLogRepository
                .findSummaries(new RuleExecutionLogQuery(a.projectId(), null, null, null, null, page1.nextCursor(), 2)));
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.items().get(0).messageId()).isEqualTo(messageM1);

        List<RuleExecutionAttempt> attempts = inScope(a, () -> executionLogRepository
                .findAttempts(a.projectId(), messageM1, ruleAlpha, versionAlpha));
        assertThat(attempts).extracting(RuleExecutionAttempt::attempt).containsExactly(1, 2, 3);
        assertThat(attempts.get(2).resultCode()).isEqualTo("SCRIPT_FAILURE");

        List<RuleOption> options = inScope(a, () -> executionLogRepository.findRuleOptions(a.projectId()));
        assertThat(options).extracting(RuleOption::name).containsExactly("rule-alpha", "rule-beta");
    }

    /**
     * 手动场景执行：一行即一次逻辑执行，读侧关联场景当前名称；详情追加通知与设备动作投递状态摘要，
     * 再验证状态/场景筛选、筛选选项与跨项目隔离。
     */
    @Test
    void sceneExecutionListsAndAssemblesDeliveryDetail() throws SQLException {
        Fixture a = seedBase("scene-a");
        Fixture b = seedBase("scene-b");
        Instant base = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);

        UUID sceneAlpha = Uuid7.generate();
        UUID sceneBeta = Uuid7.generate();
        UUID versionAlpha = Uuid7.generate();
        UUID versionBeta = Uuid7.generate();
        UUID executionX1 = Uuid7.generate();
        UUID executionX2 = Uuid7.generate();
        UUID executionX3 = Uuid7.generate();

        try (Connection connection = POSTGRES.createConnection("")) {
            insertScene(connection, a, sceneAlpha, "scene-alpha", versionAlpha);
            insertScene(connection, a, sceneBeta, "scene-beta", versionBeta);
            insertExecution(connection, a, executionX1, sceneAlpha, versionAlpha,
                    "DISPATCHED", base.plusSeconds(10));
            insertExecution(connection, a, executionX2, sceneAlpha, versionAlpha,
                    "SKIPPED", base.plusSeconds(20));
            insertExecution(connection, a, executionX3, sceneBeta, versionBeta,
                    "FAILED", base.plusSeconds(30));
            insertNotification(connection, a, executionX1, sceneAlpha, versionAlpha, base.plusSeconds(10));
            insertDeviceAction(connection, a, executionX1, sceneAlpha, versionAlpha, base.plusSeconds(10));

            UUID otherScene = Uuid7.generate();
            UUID otherVersion = Uuid7.generate();
            insertScene(connection, b, otherScene, "neighbor-scene", otherVersion);
            insertExecution(connection, b, Uuid7.generate(), otherScene, otherVersion,
                    "DISPATCHED", base.plusSeconds(60));
        }

        List<RuleSceneExecutionSummary> all = inScope(a, () -> sceneExecutionRepository
                .find(new RuleSceneExecutionQuery(a.projectId(), null, null, null, null, null, 50)).items());
        assertThat(all).hasSize(3).as("邻项目场景执行事实必须被 RLS 隐藏");
        // 按落库时刻倒序：X3(t30)、X2(t20)、X1(t10)。
        assertThat(all).extracting(RuleSceneExecutionSummary::id)
                .containsExactly(executionX3, executionX2, executionX1);
        assertThat(all.get(2).sceneName()).isEqualTo("scene-alpha");
        assertThat(all.get(2).status()).isEqualTo("DISPATCHED");

        List<RuleSceneExecutionSummary> skipped = inScope(a, () -> sceneExecutionRepository
                .find(new RuleSceneExecutionQuery(a.projectId(), null, "SKIPPED", null, null, null, 50)).items());
        assertThat(skipped).extracting(RuleSceneExecutionSummary::id).containsExactly(executionX2);

        List<RuleSceneExecutionSummary> byScene = inScope(a, () -> sceneExecutionRepository
                .find(new RuleSceneExecutionQuery(a.projectId(), sceneAlpha, null, null, null, null, 50)).items());
        assertThat(byScene).extracting(RuleSceneExecutionSummary::id)
                .containsExactlyInAnyOrder(executionX1, executionX2);

        RuleSceneExecutionSummary summary = inScope(a, () -> sceneExecutionRepository
                .findSummary(a.projectId(), executionX1)).orElseThrow();
        assertThat(summary.deviceId()).isEqualTo(a.deviceId());
        assertThat(summary.operatorAccountId()).isEqualTo(a.accountId());

        List<NotificationDeliverySummary> notifications = inScope(a, () -> sceneExecutionRepository
                .findNotifications(a.projectId(), executionX1));
        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.channel()).isEqualTo("EMAIL");
            assertThat(notification.recipient()).isEqualTo("ops@example.com");
            assertThat(notification.status()).isEqualTo("DELIVERED");
            assertThat(notification.maxAttempts()).isEqualTo(3);
        });

        List<DeviceActionDeliverySummary> deviceActions = inScope(a, () -> sceneExecutionRepository
                .findDeviceActions(a.projectId(), executionX1));
        assertThat(deviceActions).singleElement().satisfies(action -> {
            assertThat(action.operationType()).isEqualTo("COMMAND");
            assertThat(action.status()).isEqualTo("SUCCEEDED");
            assertThat(action.deviceId()).isEqualTo(a.deviceId());
        });

        List<RuleOption> options = inScope(a, () -> sceneExecutionRepository.findSceneOptions(a.projectId()));
        assertThat(options).extracting(RuleOption::name).containsExactly("scene-alpha", "scene-beta");
    }

    /**
     * 读侧关联当前名称：执行事实落库后改名，列表与详情必须返回查询时刻的新名称而非落库时的旧名称。
     */
    @Test
    void readSideReflectsCurrentRuleNameNotSnapshot() throws SQLException {
        Fixture a = seedBase("rename");
        UUID ruleId = Uuid7.generate();
        UUID versionId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        try (Connection connection = POSTGRES.createConnection("")) {
            insertRule(connection, a, ruleId, "before-rename", versionId);
            insertLog(connection, a, messageId, ruleId, versionId, 1,
                    "SUCCESS", "SUCCESS", 1, 10, 11, Instant.now());
        }

        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE rule_message SET name = 'after-rename' WHERE id = '" + ruleId + "'");
        }

        List<RuleExecutionSummary> summaries = inScope(a, () -> executionLogRepository
                .findSummaries(new RuleExecutionLogQuery(a.projectId(), ruleId, null, null, null, null, 50)).items());
        assertThat(summaries).singleElement()
                .extracting(RuleExecutionSummary::ruleName).isEqualTo("after-rename");
    }

    /** @return 记录一套项目与设备上下文，供执行事实夹具复用。 */
    private record Fixture(UUID tenantId, UUID accountId, UUID projectId, UUID typeId, UUID deviceId) {
    }

    /** 在项目 RLS 范围内执行一次查询/断言并返回结果。 */
    private <T> T inScope(Fixture fixture, Supplier<T> action) {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 用迁移超级用户直写一套独立租户、账号、项目与设备，绕开 RLS 与撤销以构造可信夹具。 */
    private Fixture seedBase(String key) throws SQLException {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        fixtureProjects.add(projectId);
        UUID typeId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        try (Connection connection = POSTGRES.createConnection("")) {
            insert(connection, "INSERT INTO sys_tenant (id, name) VALUES (?, ?)",
                    tenantId, "S9-5 tenant " + key);
            insert(connection, "INSERT INTO sys_account (id, email, password_hash, display_name) "
                            + "VALUES (?, ?, '{noop}unused', 'S9-5 account')",
                    accountId, "s9-5-" + key + "-" + accountId + "@example.com");
            insert(connection, "INSERT INTO sys_project (id, tenant_id, name, region, project_key) "
                            + "VALUES (?, ?, ?, 'sh-1', ?)",
                    projectId, tenantId, "S9-5 project " + key, projectKey(projectId));
            insert(connection, "INSERT INTO sys_project_member (id, project_id, account_id, role) "
                            + "VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), projectId, accountId);
            insert(connection, "INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, "
                            + "access_protocol, device_kind, status) VALUES (?, ?, ?, ?, ?, 'STANDARD', 'DIRECT', 'PUBLISHED')",
                    typeId, tenantId, projectId, "s95t" + typeId.toString().replace("-", "").substring(0, 8),
                    "S9-5 type " + key);
            insert(connection, "INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'ONLINE')",
                    deviceId, tenantId, projectId, typeId,
                    "s95d" + deviceId.toString().replace("-", "").substring(0, 8), "S9-5 device " + key);
        }
        return new Fixture(tenantId, accountId, projectId, typeId, deviceId);
    }

    /** 直写一条 DRAFT 消息规则与一条不可变版本，满足执行日志外键。 */
    private void insertRule(Connection connection, Fixture fixture, UUID ruleId, String name, UUID versionId)
            throws SQLException {
        insert(connection, "INSERT INTO rule_message (id, tenant_id, project_id, name, status, active_version_id, "
                        + "version, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, 'DRAFT', NULL, 1, ?, ?, ?)",
                ruleId, fixture.tenantId(), fixture.projectId(), name, fixture.accountId(),
                Instant.now(), Instant.now());
        insert(connection, "INSERT INTO rule_version (id, tenant_id, project_id, rule_id, version_number, source, "
                        + "source_sha256, created_by, created_at) VALUES (?, ?, ?, ?, 1, 'input => input', ?, ?, ?)",
                versionId, fixture.tenantId(), fixture.projectId(), ruleId, "0".repeat(64),
                fixture.accountId(), Instant.now());
    }

    /** 直写一条生产执行 attempt 日志。 */
    private void insertLog(Connection connection, Fixture fixture, UUID messageId, UUID ruleId, UUID versionId,
                           int attempt, String status, String resultCode, long durationMillis,
                           int inputBytes, int outputBytes, Instant createdAt) throws SQLException {
        insert(connection, "INSERT INTO rule_execution_log (id, tenant_id, project_id, message_id, rule_id, "
                        + "rule_version_id, attempt, status, result_code, duration_millis, input_bytes, output_bytes, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Uuid7.generate(), fixture.tenantId(), fixture.projectId(), messageId, ruleId, versionId,
                attempt, status, resultCode, durationMillis, inputBytes, outputBytes, createdAt);
    }

    /** 直写一条 DRAFT 手动场景与一条不可变版本。 */
    private void insertScene(Connection connection, Fixture fixture, UUID sceneId, String name, UUID versionId)
            throws SQLException {
        insert(connection, "INSERT INTO rule_scene (id, tenant_id, project_id, name, status, active_version_id, "
                        + "version, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, 'DRAFT', NULL, 1, ?, ?, ?)",
                sceneId, fixture.tenantId(), fixture.projectId(), name, fixture.accountId(),
                Instant.now(), Instant.now());
        insert(connection, "INSERT INTO rule_scene_version (id, tenant_id, project_id, scene_id, version_number, "
                        + "conditions, actions, created_by, created_at) VALUES (?, ?, ?, ?, 1, '[]'::jsonb, '[]'::jsonb, ?, ?)",
                versionId, fixture.tenantId(), fixture.projectId(), sceneId,
                fixture.accountId(), Instant.now());
    }

    /** 直写一条手动场景执行事实，终态落库时刻即 created_at。 */
    private void insertExecution(Connection connection, Fixture fixture, UUID executionId, UUID sceneId,
                                 UUID sceneVersionId, String status, Instant createdAt) throws SQLException {
        insert(connection, "INSERT INTO rule_scene_execution (id, tenant_id, project_id, scene_id, scene_version_id, "
                        + "idempotency_key, request_digest, device_id, status, trigger, operator_account_id, trace_id, "
                        + "occurred_at, created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?, ?)",
                executionId, fixture.tenantId(), fixture.projectId(), sceneId, sceneVersionId,
                "s95-" + executionId, "0".repeat(64), fixture.deviceId(), status,
                fixture.accountId(), "trace-s9-5-" + executionId, createdAt, createdAt, createdAt);
    }

    /** 直写一条场景来源的已送达通知投递事实。 */
    private void insertNotification(Connection connection, Fixture fixture, UUID executionId, UUID sceneId,
                                    UUID sceneVersionId, Instant createdAt) throws SQLException {
        insert(connection, "INSERT INTO rule_notification_delivery (id, tenant_id, project_id, scene_id, "
                        + "scene_version_id, scene_execution_id, device_id, channel, recipient, subject, body, status, "
                        + "trace_id, attempt_count, max_attempts, delivered_at, created_at, updated_at, terminal_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'EMAIL', ?, 'subject', 'body', 'DELIVERED', ?, 1, 3, ?, ?, ?, ?)",
                Uuid7.generate(), fixture.tenantId(), fixture.projectId(), sceneId, sceneVersionId, executionId,
                fixture.deviceId(), "ops@example.com", "trace-s9-5-notif", createdAt,
                createdAt, createdAt, createdAt);
    }

    /** 直写一条场景来源的已成功设备命令投递事实。 */
    private void insertDeviceAction(Connection connection, Fixture fixture, UUID executionId, UUID sceneId,
                                    UUID sceneVersionId, Instant createdAt) throws SQLException {
        insert(connection, "INSERT INTO rule_device_action_delivery (id, tenant_id, project_id, scene_id, "
                        + "scene_version_id, scene_execution_id, device_id, operation_type, command_id, status, "
                        + "failure_code, trace_id, created_at, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'COMMAND', ?, 'SUCCEEDED', NULL, ?, ?, ?)",
                Uuid7.generate(), fixture.tenantId(), fixture.projectId(), sceneId, sceneVersionId, executionId,
                fixture.deviceId(), Uuid7.generate(), "trace-s9-5-action", createdAt, createdAt);
    }

    /** 在超级用户连接上执行一次参数化写入。 */
    private void insert(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Instant instant) {
                    statement.setTimestamp(i + 1, Timestamp.from(instant));
                } else {
                    statement.setObject(i + 1, arg);
                }
            }
            statement.executeUpdate();
        }
    }

    /** @return 满足项目键约束的唯一短标识 */
    private static String projectKey(UUID projectId) {
        return "s95" + projectId.toString().replace("-", "").substring(0, 16);
    }
}
