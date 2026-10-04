package com.things.link.bootstrap.rule;

import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;

import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.ConditionSpec;
import com.things.link.rule.application.CreateSceneCommand;
import com.things.link.rule.application.ExecuteSceneCommand;
import com.things.link.rule.application.RuleSceneExecutionView;
import com.things.link.rule.application.RuleSceneService;
import com.things.link.rule.application.RuleSceneVersionView;
import com.things.link.rule.application.RuleSceneView;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.notification.delivery.ExternalNotificationRequest;
import com.things.link.support.notification.delivery.ExternalNotificationSender;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * S9-6 整合验收：手动场景一键执行在真实 PostgreSQL + Kafka 上的端到端写路径闭环。
 *
 * <p>S9-4 落的是应用层单元测试，S9-5 落的是执行日志查询与读路径。本类补齐唯一遗留的真实环境断言：
 * 执行事实与动作副作用 Outbox 同事务原子提交、通知投递经 {@code tc.rule.notification} 异步落 DELIVERED
 * 并携带场景来源（而非规则来源）、场景三表严格按项目 RLS 隔离、幂等键重放收敛到同一执行事实、条件不满足
 * 时短路 SKIPPED 且不产生任何副作用。</p>
 *
 * <p>复用 {@link RuleActionOutboxIntegrationTests.KafkaTopicTestConfiguration} 与共享种子/断言约定，
 * 不重复声明消息链路 Topic；只额外覆盖场景表（{@code rule_scene} / {@code rule_scene_version} /
 * {@code rule_scene_execution}）的端到端闭环。</p>
 */
@Import({RuleActionOutboxIntegrationTests.KafkaTopicTestConfiguration.class, DeviceProtocolKafkaTopicsTestConfiguration.class})
@TestPropertySource(properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true",
        // 默认测试 profile 关闭 Outbox 发布器避免后台线程连开发机 broker；本类要验收闭环，显式恢复真实调度。
        "things-link.outbox.publisher.enabled=true",
        // 场景投递不带重试租约（见 ADR 0030），拉长扫描避免与断言竞争，初始空扫不会领取尚未创建的事实。
        "things-link.rule.notification.retry.scan-millis=3600000"
})
class SceneExecutionIntegrationTests extends AbstractKafkaIntegrationTest {

    /** 场景执行只跳一跳 Kafka（Outbox → tc.rule.notification → 消费者落库），但首用例仍含
     *  Outbox 调度暖机；放宽上限以避免低 I/O 环境误报（失败时仍按具体断言而非超时暴露）。 */
    private static final Duration CHAIN_TIMEOUT = Duration.ofSeconds(180);

    /** 手动场景应用服务：定义、激活与一键执行的控制面入口。 */
    @Autowired private RuleSceneService sceneService;
    /** 真实 PostgreSQL 事实断言入口（RLS 视图、Outbox 与投递事实）。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 集成测试不连接公网 SMTP，但必须模拟真实共用发送器成功，不能让 LoggingMailSender 冒充送达。 */
    @MockitoBean(name = ExternalNotificationSender.EMAIL_BEAN)
    private ExternalNotificationSender emailSender;

    /** 每个用例为真实投递状态机装配确定性供应商成功回执。 */
    @BeforeEach
    void configureNotificationSender() {
        when(emailSender.channel()).thenReturn("EMAIL");
        when(emailSender.send(any())).thenAnswer(invocation -> {
            ExternalNotificationRequest request = invocation.getArgument(0);
            return "test-email:" + request.deliveryId();
        });
    }

    /** ThreadLocal 项目范围不得泄漏到其他集成测试。 */
    @AfterEach
    void clearScopeAndSceneFacts() throws SQLException {
        TenantContext.clear();
        // 场景定义/版本/执行事实 + 投递事实都以无 ON DELETE CASCADE 的外键引用 sys_project / dev_device，
        // 且应用角色被 REVOKE DELETE（场景版本与执行事实不可改写）；只能借迁移超级用户（表 owner，不受 RLS
        // 与撤销影响）按依赖逆序清空，否则残留行会阻断其他测试类的 DELETE FROM sys_project / dev_device。
        // dev_* / sys_* 均级联删除，交给后续设备/项目测试类的 @BeforeEach 统一处理。
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rule_notification_delivery");
            statement.executeUpdate("DELETE FROM rule_device_action_delivery");
            statement.executeUpdate("DELETE FROM sys_outbox_event");
            statement.executeUpdate("DELETE FROM rule_scene_execution");
            // 释放活动版本指针前必须先置回 DRAFT：ACTIVE 要求 active_version_id 非空，直接清空违反互斥约束；
            // 且 rule_scene.active_version_id → rule_scene_version.id 无级联，不先置空就无法删除版本事实。
            statement.executeUpdate("UPDATE rule_scene SET status = 'DRAFT', active_version_id = NULL");
            statement.executeUpdate("DELETE FROM rule_scene_version");
            statement.executeUpdate("DELETE FROM rule_scene");
        }
    }

    /**
     * 一键执行活动场景：DISPATCHED 执行事实与通知 Outbox 同事务提交，随后经 Kafka 落 DELIVERED 投递事实，
     * 且携带场景来源（scene_id / scene_version_id / scene_execution_id），message_id 为空（非规则来源）。
     */
    @Test
    void sceneExecutionFlowsThroughOutboxToSceneSourcedDeliveryFact() {
        Fixture fixture = seedFixture("deliver");
        RuleSceneView scene = createAndActivateScene(fixture, List.of(), List.of(notificationAction()));

        RuleSceneExecutionView execution = inScope(fixture, () -> sceneService.execute(
                fixture.projectId(), scene.id(), "key-deliver",
                new ExecuteSceneCommand(fixture.deviceId(), new ObjectMapper().createObjectNode())));

        assertThat(execution.status()).isEqualTo("DISPATCHED");
        assertThat(execution.sceneId()).isEqualTo(scene.id());
        assertThat(execution.sceneVersionId()).isEqualTo(scene.activeVersionId());
        assertThat(execution.deviceId()).isEqualTo(fixture.deviceId());
        assertThat(execution.operatorAccountId()).isEqualTo(fixture.accountId());
        assertThat(execution.traceId()).isNotBlank();

        // execute 返回即事务提交：执行事实与通知 Outbox 必须同时存在（原子），不依赖异步链路。
        withScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_scene_execution WHERE id = ? AND status = 'DISPATCHED'",
                    execution.id())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM sys_outbox_event WHERE event_type = ?",
                    "RULE_NOTIFICATION_DELIVERY_REQUEST")).isEqualTo(1);
        });

        // 投递事实只会在 Outbox 发布 + Kafka 消费完成后出现，是闭环的唯一可靠信号。
        awaitSceneDeliveryFact(fixture, execution.id());
        withScope(fixture, () -> {
            Map<String, Object> delivery = jdbcTemplate.queryForMap("""
                    SELECT scene_id, scene_version_id, scene_execution_id, message_id, channel, recipient, status
                      FROM rule_notification_delivery WHERE scene_execution_id = ?
                    """, execution.id());
            assertThat(delivery.get("scene_id")).isEqualTo(scene.id());
            assertThat(delivery.get("scene_version_id")).isEqualTo(scene.activeVersionId());
            assertThat(delivery.get("scene_execution_id")).isEqualTo(execution.id());
            // 场景来源不写 message_id，证明走的场景桥接而非规则桥接。
            assertThat(delivery.get("message_id")).isNull();
            assertThat(delivery.get("channel")).isEqualTo("EMAIL");
            assertThat(delivery.get("recipient")).isEqualTo("ops@example.com");
            assertThat(delivery.get("status")).isEqualTo("DELIVERED");
        });
        // Outbox 事件已得到 Kafka broker 确认，证明投递事实确实经发布器而非同步直写。
        awaitOutboxPublished(fixture);
    }

    /** 场景定义/版本/执行/投递事实严格按项目隔离：邻项目同为 OWNER 也读不到任何行，跨项目读取按不存在隐藏。 */
    @Test
    void sceneDefinitionAndExecutionAreProjectIsolated() {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        insertTenant(tenantId, "scene-isolation");
        insertAccount(accountId, "scene-isolation");
        ProjectFixture withScene = seedProjectDevice(tenantId, accountId, "with-scene");
        ProjectFixture neighbor = seedProjectDevice(tenantId, accountId, "neighbor");
        Fixture sceneProject = new Fixture(tenantId, accountId, withScene.projectId(),
                withScene.typeId(), withScene.deviceId());
        Fixture neighborProject = new Fixture(tenantId, accountId, neighbor.projectId(),
                neighbor.typeId(), neighbor.deviceId());

        RuleSceneView scene = createAndActivateScene(sceneProject, List.of(), List.of(notificationAction()));
        RuleSceneExecutionView execution = inScope(sceneProject, () -> sceneService.execute(
                sceneProject.projectId(), scene.id(), "key-isolation",
                new ExecuteSceneCommand(sceneProject.deviceId(), new ObjectMapper().createObjectNode())));

        withScope(neighborProject, () -> {
            assertThat(count("SELECT count(*) FROM rule_scene")).isZero();
            assertThat(count("SELECT count(*) FROM rule_scene_version")).isZero();
            assertThat(count("SELECT count(*) FROM rule_scene_execution")).isZero();
            assertThat(count("SELECT count(*) FROM rule_notification_delivery")).isZero();
        });
        // 跨项目读取按「不存在」统一隐藏，不泄露场景归属（避免可枚举 ID 探测）。
        withScope(neighborProject, () -> assertThatThrownBy(() ->
                sceneService.get(neighborProject.projectId(), scene.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(RuleErrorCode.SCENE_NOT_FOUND)));
    }

    /** 幂等键重放收敛：同键同内容返回同一执行事实；同键不同内容（payload 改变）拒绝，避免静默归并到旧执行。 */
    @Test
    void sceneExecutionIsIdempotentPerIdempotencyKey() {
        Fixture fixture = seedFixture("idempotent");
        RuleSceneView scene = createAndActivateScene(fixture, List.of(), List.of(notificationAction()));

        RuleSceneExecutionView first = inScope(fixture, () -> sceneService.execute(
                fixture.projectId(), scene.id(), "key-idem",
                new ExecuteSceneCommand(fixture.deviceId(), new ObjectMapper().createObjectNode())));
        // 同键同内容重放命中 existing 分支，返回既有事实，不产生第二条执行。
        RuleSceneExecutionView replay = inScope(fixture, () -> sceneService.execute(
                fixture.projectId(), scene.id(), "key-idem",
                new ExecuteSceneCommand(fixture.deviceId(), new ObjectMapper().createObjectNode())));
        assertThat(replay.id()).isEqualTo(first.id());
        withScope(fixture, () -> assertThat(
                count("SELECT count(*) FROM rule_scene_execution WHERE id = ?", first.id())).isEqualTo(1));

        // 同键不同内容：摘要变化识别为新输入，拒绝而非覆盖或归并。
        ObjectNode changed = new ObjectMapper().createObjectNode().put("temperature", 30);
        withScope(fixture, () -> assertThatThrownBy(() -> sceneService.execute(
                fixture.projectId(), scene.id(), "key-idem",
                new ExecuteSceneCommand(fixture.deviceId(), changed)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(RuleErrorCode.SCENE_STATE_CONFLICT)));
    }

    /** 条件不满足时短路 SKIPPED：仍落执行事实，但不产生任何 Outbox 或投递副作用。 */
    @Test
    void sceneConditionShortCircuitSkipsAllSideEffects() {
        Fixture fixture = seedFixture("skip");
        RuleSceneView scene = createAndActivateScene(fixture,
                List.of(equalsCondition("/on", true)), List.of(notificationAction()));

        RuleSceneExecutionView execution = inScope(fixture, () -> sceneService.execute(
                fixture.projectId(), scene.id(), "key-skip",
                new ExecuteSceneCommand(fixture.deviceId(), new ObjectMapper().createObjectNode().put("on", false))));

        assertThat(execution.status()).isEqualTo("SKIPPED");
        withScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_scene_execution WHERE id = ? AND status = 'SKIPPED'",
                    execution.id())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM sys_outbox_event WHERE event_type = ?",
                    "RULE_NOTIFICATION_DELIVERY_REQUEST")).isZero();
            assertThat(count("SELECT count(*) FROM rule_notification_delivery")).isZero();
        });
    }

    /** @return 记录全部设备上下文，供场景执行与断言复用。 */
    private record Fixture(UUID tenantId, UUID accountId, UUID projectId, UUID typeId, UUID deviceId) {
    }

    /** @return 某租户下的一台设备及其类型。 */
    private record ProjectFixture(UUID projectId, UUID typeId, UUID deviceId) {
    }

    /** @return 通过节点校验的通知动作配置快照 */
    private ActionSpec notificationAction() {
        return new ActionSpec("notification-action", new ObjectMapper().createObjectNode()
                .put("channel", "email")
                .put("recipient", "ops@example.com")
                .put("subject", "场景通知")
                .put("body", "设备温度异常"));
    }

    /** @return 布尔等值条件：读取 payload 属性与 value 严格相等。 */
    private ConditionSpec equalsCondition(String pointer, boolean value) {
        return new ConditionSpec("payload-property-compare", new ObjectMapper().createObjectNode()
                .put("pointer", pointer)
                .put("operator", "EQ")
                .put("value", value));
    }

    /** 创建一套独立租户、账号与项目设备，避免与同容器内其他测试冲突。 */
    private Fixture seedFixture(String key) {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        insertTenant(tenantId, key);
        insertAccount(accountId, key);
        ProjectFixture project = seedProjectDevice(tenantId, accountId, key);
        return new Fixture(tenantId, accountId, project.projectId(), project.typeId(), project.deviceId());
    }

    /** 创建并激活一个仅带通知动作的活动场景，返回含 activeVersionId 的定义投影。 */
    private RuleSceneView createAndActivateScene(
            Fixture fixture, List<ConditionSpec> conditions, List<ActionSpec> actions) {
        return inScope(fixture, () -> {
            RuleSceneView scene = sceneService.create(fixture.projectId(),
                    new CreateSceneCommand("场景-" + shortId(), null, conditions, actions));
            RuleSceneVersionView version = sceneService.versions(fixture.projectId(), scene.id()).getFirst();
            return sceneService.activate(fixture.projectId(), scene.id(), version.id(), scene.version());
        });
    }

    /** @param key 唯一后缀 @return 满足项目键长度约束的短标识 */
    private void insertTenant(UUID tenantId, String key) {
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)",
                tenantId, "S9-6 tenant " + key + "-" + tenantId.toString().substring(0, 8));
    }

    /** @param key 唯一后缀 @return 不会与历史测试冲突的账号 */
    private void insertAccount(UUID accountId, String key) {
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S9-6 account')
                """, accountId, "s9-6-" + key + "-" + accountId + "@example.com");
    }

    /** 创建项目、OWNER 成员、设备类型、设备与温度属性，满足数据面完整外键约束。 */
    private ProjectFixture seedProjectDevice(UUID tenantId, UUID accountId, String key) {
        UUID projectId = Uuid7.generate();
        UUID typeId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, projectId, tenantId, "S9-6 project " + key, projectKey(projectId));
        jdbcTemplate.update("""
                INSERT INTO sys_project_member (id, project_id, account_id, role)
                VALUES (?, ?, ?, 'OWNER')
                """, Uuid7.generate(), projectId, accountId);
        // 设备三表受项目 RLS 保护：借出连接时按 TenantContext 写入 app.project_id，
        // 因此先套上项目范围再写入，否则策略 fail-closed 拦下全部行。
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        try {
            jdbcTemplate.update("""
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status)
                    VALUES (?, ?, ?, ?, ?, 'STANDARD', 'DIRECT', 'PUBLISHED')
                    """, typeId, tenantId, projectId, typeKey(key, typeId), "S9-6 type " + key);
            jdbcTemplate.update("""
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'ONLINE')
                    """, deviceId, tenantId, projectId, typeId, deviceKey(key, deviceId), "S9-6 device " + key);
            jdbcTemplate.update("""
                    INSERT INTO dev_property_definition
                        (id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type)
                    VALUES (?, ?, ?, ?, 'temperature', '温度', 'REPORT', 'NUMBER')
                    """, Uuid7.generate(), tenantId, projectId, typeId);
        } finally {
            TenantContext.clear();
        }
        return new ProjectFixture(projectId, typeId, deviceId);
    }

    /** 等待场景投递事实落 DELIVERED：只有 Outbox 发布 + Kafka 消费完成才会出现该行。 */
    private void awaitSceneDeliveryFact(Fixture fixture, UUID executionId) {
        awaitCondition("场景投递事实未落库 sceneExecutionId=" + executionId, () -> inScopeCount(fixture,
                "SELECT count(*) FROM rule_notification_delivery WHERE scene_execution_id = ? AND status = 'DELIVERED'",
                executionId) > 0);
    }

    /** 等待 Outbox 事件被发布器确认为 PUBLISHED，证明投递事实确实经 Kafka 而非同步直写。 */
    private void awaitOutboxPublished(Fixture fixture) {
        awaitCondition("Outbox 事件未发布", () -> inScopeCount(fixture,
                "SELECT count(*) FROM sys_outbox_event WHERE event_type = 'RULE_NOTIFICATION_DELIVERY_REQUEST'"
                        + " AND status = 'PUBLISHED'") > 0);
    }

    /** 在项目 RLS 范围内执行断言或查询。 */
    private void withScope(Fixture fixture, Runnable action) {
        inScope(fixture, () -> {
            action.run();
            return null;
        });
    }

    /** 在项目 RLS 范围内执行一次返回值的操作。 */
    private <T> T inScope(Fixture fixture, java.util.function.Supplier<T> action) {
        TenantContext.set(scope(fixture));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 在项目 RLS 范围内执行一次计数查询并返回结果。 */
    private long inScopeCount(Fixture fixture, String sql, Object... args) {
        return inScope(fixture, () -> count(sql, args));
    }

    /** @return 当前项目计数。 */
    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    /** @param fixture 项目归属 @return 模拟已认证协作者的 ThreadLocal 范围 */
    private static TenantScope scope(Fixture fixture) {
        return new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId());
    }

    /** 轮询直到条件满足，超时抛错而不是依赖固定 sleep。 */
    private static void awaitCondition(String description, java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(CHAIN_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待中断: " + description, exception);
            }
        }
        throw new AssertionError("未在期限内满足条件: " + description);
    }

    /** @return 满足项目键约束的唯一短标识 */
    private static String projectKey(UUID projectId) {
        return "s96" + projectId.toString().replace("-", "").substring(0, 16);
    }

    /** @return 项目内唯一类型键 */
    private static String typeKey(String key, UUID typeId) {
        return "s96t" + key.replace("-", "") + typeId.toString().replace("-", "").substring(0, 8);
    }

    /** @return 项目内唯一设备键 */
    private static String deviceKey(String key, UUID deviceId) {
        return "s96d" + key.replace("-", "") + deviceId.toString().replace("-", "").substring(0, 8);
    }

    /** @return 场景名使用的稳定短标识 */
    private static String shortId() {
        return Uuid7.generate().toString().replace("-", "").substring(0, 12);
    }
}
