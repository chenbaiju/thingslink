package com.things.link.bootstrap.rule;

import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;
import com.things.link.bootstrap.fixture.KafkaRecordConsumptionBarrier;
import com.things.link.ingestion.infrastructure.NormalizedUplinkKafkaConsumer;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.CreateMessageRuleCommand;
import com.things.link.rule.application.MessageRuleService;
import com.things.link.rule.application.MessageRuleVersionView;
import com.things.link.rule.application.MessageRuleView;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.notification.delivery.ExternalNotificationSender;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.things.link.ingestion.application.RealtimeKafkaPublisher.REALTIME_TOPIC;
import static com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
import static com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher.RETRY_FIVE_MINUTES_TOPIC;
import static com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * S9-1 组合验收：动作副作用意图 → 同事务 Outbox → Kafka → 投递事实的端到端闭环。
 *
 * <p>本类在真实 Kafka + PostgreSQL + Worker 上验证：脚本派生 payload 后，主机侧动作节点产出通知意图，
 * 桥接层把它与回执终态同事务写入 Outbox，发布器投到 {@code tc.rule.notification}，轻量消费者落
 * {@code rule_notification_delivery} 投递事实。无动作规则不产生任何 Outbox/投递事实，动作严格按项目隔离，
 * 非法动作配置在写入版本事实前 fail-closed。</p>
 */
@Import({RuleActionOutboxIntegrationTests.KafkaTopicTestConfiguration.class, DeviceProtocolKafkaTopicsTestConfiguration.class})
@TestPropertySource(properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true",
        // 默认测试 profile 关闭 Outbox 发布器避免后台线程连开发机 broker；本类要验收闭环，显式恢复真实调度。
        "things-link.outbox.publisher.enabled=true",
        // 本类保留正常通知闭环的原调度节奏；重试函数逐次提交合同已迁至专库测试，不依靠此值隔离派发。
        "things-link.rule.notification.retry.scan-millis=3600000"
})
class RuleActionOutboxIntegrationTests extends AbstractKafkaIntegrationTest {

    /** 冷 Worker（独立 JVM）启动、Outbox 调度与两跳 Kafka 的预算，不依赖固定 sleep。
     *  全量集成运行中可与其他容器及 Kafka 消费组重平衡并发；Windows 低 I/O 下实测链路曾超过 180s，
     *  因此留出 5 分钟让真实消息链完成，同时保留明确期限以暴露未处理消息。 */
    private static final Duration CHAIN_TIMEOUT = Duration.ofMinutes(5);
    /** 版本化数据面夹具用 1.0.0 快照：仅一个 NUMBER 上报温度属性。 */
    private static final String TEMPERATURE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},"
                    + "\"events\":{},\"commands\":{}}";

    /** 无 UI 规则应用服务，创建并发布带动作的 ACTIVE 不可变版本。 */
    @Autowired private MessageRuleService ruleService;
    /** 真实 PostgreSQL 事实断言入口（RLS 视图、Outbox 与投递事实）。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 与生产同源的共享幂等 Kafka 模板。 */
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    /** 仅在遥测超时时读取历史降级的三项权威额度状态。 */
    @Autowired private ProjectDailyQuotaDecisionService dailyQuotaDecisionService;
    /** 集成测试不连接公网 SMTP，但必须模拟真实共用发送器成功，不能让 LoggingMailSender 冒充送达。 */
    @MockitoBean(name = ExternalNotificationSender.EMAIL_BEAN)
    private ExternalNotificationSender emailSender;
    /** 本轮由 Broker 确认的 normalized 记录；清理前必须越过提交位点。 */
    private final List<RecordMetadata> sentRecords = new ArrayList<>();

    /** 每个用例为真实投递状态机装配确定性供应商成功回执。 */
    @BeforeEach
    void configureNotificationSender() {
        when(emailSender.channel()).thenReturn("EMAIL");
        when(emailSender.send(any())).thenAnswer(invocation -> {
            com.things.link.support.notification.delivery.ExternalNotificationRequest request =
                    invocation.getArgument(0);
            return "test-email:" + request.deliveryId();
        });
    }

    /** ThreadLocal 项目范围不得泄漏到其他集成测试。 */
    @AfterEach
    void clearScopeAndRuleFacts() throws SQLException {
        TenantContext.clear();
        awaitNormalizedConsumed(sentRecords);
        // 规则五表 + 投递事实都以无 ON DELETE CASCADE 的外键引用 sys_project / rule 表，且应用角色被
        // REVOKE DELETE；只能借迁移超级用户（表 owner，不受 RLS 与撤销影响）按依赖逆序清空，否则残留行会
        // 阻断其他测试类的 DELETE FROM sys_project。投递事实必须先于 rule_version / rule_message 删除。
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rule_notification_delivery");
            statement.executeUpdate("DELETE FROM sys_outbox_event");
            statement.executeUpdate("DELETE FROM rule_debug_event");
            statement.executeUpdate("DELETE FROM rule_execution_log");
            statement.executeUpdate("DELETE FROM rule_execution_receipt");
            // 解活动指针前必须先置回 DRAFT：ACTIVE/PAUSED 要求 active_version_id 非空，直接清空违反互斥约束。
            statement.executeUpdate("UPDATE rule_message SET status = 'DRAFT', active_version_id = NULL");
            statement.executeUpdate("DELETE FROM rule_version");
            statement.executeUpdate("DELETE FROM rule_message");
        }
    }

    /**
     * 通知动作把派生 payload 渲染进投递意图，经 Outbox 与 Kafka 最终落 DELIVERED 投递事实。
     */
    @Test
    void notificationActionFlowsThroughOutboxToDeliveryFact() throws Exception {
        Fixture fixture = seedFixture("closed-loop");
        activateRule(fixture, "input => ({temperature: input.temperature + 0.5})", List.of(new ActionSpec(
                "notification-action", notificationConfig("ops@example.com",
                        "温度 ${payload.temperature}", "设备 ${deviceId} 温度异常"))));
        UUID messageId = Uuid7.generate();

        sendUplink(fixture, messageId, Map.of("temperature", 26.5));

        // 脚本派生 payload 已续接遥测；投递事实只会在 Outbox 发布 + 消费者落库后才出现，是闭环的唯一可靠信号。
        awaitPropertyValue(fixture, messageId, 27.0);
        awaitDeliveryFact(fixture, messageId);
        withScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_notification_delivery WHERE message_id = ?", messageId))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT channel FROM rule_notification_delivery WHERE message_id = ?", String.class, messageId))
                    .isEqualTo("EMAIL");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT recipient FROM rule_notification_delivery WHERE message_id = ?", String.class, messageId))
                    .isEqualTo("ops@example.com");
            // 脚本 `26.5 + 0.5` 在 JS 中得 27.0，JSON 序列化丢尾部 .0 成整数 27；
            // 渲染忠实于 payload JSON 而非 double 的十进制展示，故主题为「温度 27」。
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT subject FROM rule_notification_delivery WHERE message_id = ?", String.class, messageId))
                    .isEqualTo("温度 27");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT body FROM rule_notification_delivery WHERE message_id = ?", String.class, messageId))
                    .isEqualTo("设备 " + fixture.deviceId() + " 温度异常");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM rule_notification_delivery WHERE message_id = ?", String.class, messageId))
                    .isEqualTo("DELIVERED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT delivered_at IS NOT NULL FROM rule_notification_delivery WHERE message_id = ?",
                    Boolean.class, messageId)).isTrue();
        });
        // Outbox 事件已得到 Kafka broker 确认，证明投递事实确实经发布器而非同步直写。
        awaitOutboxPublished(fixture, messageId);
    }

    /** 同一生产规则版本的通知事实在暂停及 Kafka 重放后仍唯一；暂停只影响后来的消息。 */
    @Test
    void pauseThenReplayKeepsOriginalExecutionAndDeliveryUnique() throws Exception {
        Fixture fixture = seedFixture("pause-replay");
        MessageRuleView active = activateRule(fixture,
                "input => ({temperature: input.temperature + 0.5})",
                List.of(new ActionSpec("notification-action", notificationConfig(
                        "ops@example.com", "温度 ${payload.temperature}", "同源规则通知"))));
        UUID first = Uuid7.generate();
        RecordMetadata initial = sendUplink(fixture, first, Map.of("temperature", 26.5));
        awaitNormalizedConsumed(initial);
        assertThat(inScopeCount(fixture, "SELECT count(*) FROM rule_execution_receipt WHERE message_id = ?", first))
                .as("首条 normalized 消费完成后应有规则回执").isEqualTo(1);
        awaitPropertyValue(fixture, first, 27.0);
        awaitExecutionLog(fixture, first, "SUCCESS");
        awaitDeliveryFact(fixture, first);
        awaitOutboxPublished(fixture, first);

        TenantContext.set(scope(fixture));
        try {
            MessageRuleView paused = ruleService.pause(fixture.projectId(), active.id(), active.version());
            assertThat(paused.status()).isEqualTo("PAUSED");
        } finally {
            TenantContext.clear();
        }
        UUID afterPause = Uuid7.generate();
        sendUplink(fixture, afterPause, Map.of("temperature", 26.5));
        awaitPropertyValue(fixture, afterPause, 26.5);
        withScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_execution_log WHERE message_id = ?", afterPause)).isZero();
            assertThat(count("SELECT count(*) FROM rule_notification_delivery WHERE message_id = ?", afterPause))
                    .isZero();
            assertThat(count("SELECT count(*) FROM sys_outbox_event"
                    + " WHERE event_type = 'RULE_NOTIFICATION_DELIVERY_REQUEST'")).isEqualTo(1);
        });

        RecordMetadata replay = sendUplink(fixture, first, Map.of("temperature", 26.5));
        awaitNormalizedConsumed(replay);
        withScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_execution_log WHERE message_id = ?", first)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_execution_receipt WHERE message_id = ?", first)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_notification_delivery WHERE message_id = ?", first))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM ts_property_point WHERE message_id = ?", first)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM sys_outbox_event"
                    + " WHERE event_type = 'RULE_NOTIFICATION_DELIVERY_REQUEST'")).isEqualTo(1);
        });
    }

    /** 无动作规则仍走同一 processed 续接并落遥测，但不产生任何 Outbox 行或投递事实。 */
    @Test
    void ruleWithoutActionProducesNoOutboxOrDelivery() throws Exception {
        Fixture fixture = seedFixture("no-action");
        activateRule(fixture, "input => ({temperature: input.temperature + 0.5})", List.of());
        UUID messageId = Uuid7.generate();

        sendUplink(fixture, messageId, Map.of("temperature", 26.5));

        awaitPropertyValue(fixture, messageId, 27.0);
        awaitExecutionLog(fixture, messageId, "SUCCESS");
        withScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_notification_delivery WHERE message_id = ?", messageId))
                    .isZero();
            assertThat(count("SELECT count(*) FROM sys_outbox_event WHERE event_type = ?",
                    "RULE_NOTIFICATION_DELIVERY_REQUEST")).isZero();
        });
    }

    /** 动作只作用于自己的项目：邻项目无规则报文直通，不产生跨项目投递事实。 */
    @Test
    void notificationActionIsScopedToItsOwnProjectOnly() throws Exception {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        insertTenant(tenantId, "isolation");
        insertAccount(accountId, "isolation");
        ProjectFixture withRule = seedProjectDevice(tenantId, accountId, "with-rule");
        ProjectFixture withoutRule = seedProjectDevice(tenantId, accountId, "without-rule");
        Fixture ruleProject = new Fixture(tenantId, accountId, withRule.projectId(),
                withRule.typeId(), withRule.deviceId());
        Fixture plainProject = new Fixture(tenantId, accountId, withoutRule.projectId(),
                withoutRule.typeId(), withoutRule.deviceId());

        activateRule(ruleProject, "input => ({temperature: input.temperature + 0.5})", List.of(new ActionSpec(
                "notification-action", notificationConfig(
                        "ops@example.com", "温度 ${payload.temperature}", "项目隔离验证"))));
        UUID transformedId = Uuid7.generate();
        UUID passthroughId = Uuid7.generate();

        sendUplink(ruleProject, transformedId, Map.of("temperature", 26.5));
        sendUplink(plainProject, passthroughId, Map.of("temperature", 26.5));

        awaitPropertyValue(ruleProject, transformedId, 27.0);
        awaitPropertyValue(plainProject, passthroughId, 26.5);
        awaitDeliveryFact(ruleProject, transformedId);
        withScope(plainProject, () -> assertThat(
                count("SELECT count(*) FROM rule_notification_delivery")).isZero());
    }

    /** 非法动作配置（缺 recipient）在写入版本事实前被确定性引擎拒绝，不产生任何规则或投递事实。 */
    @Test
    void invalidActionConfigRejectedAtControlPlane() {
        Fixture fixture = seedFixture("invalid-action");
        ObjectNode config = new ObjectMapper().createObjectNode().put("channel", "email");
        TenantContext.set(scope(fixture));
        try {
            assertThatThrownBy(() -> ruleService.create(fixture.projectId(),
                    new CreateMessageRuleCommand("rule-" + shortId(), null,
                            "input => input", List.of(new ActionSpec("notification-action", config)))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", RuleErrorCode.RULE_ACTION_INVALID);
        } finally {
            TenantContext.clear();
        }
        withScope(fixture, () -> assertThat(count("SELECT count(*) FROM rule_message")).isZero());
    }

    /** @return 记录全部设备上下文，供遥测断言与规则发布复用。 */
    private record Fixture(UUID tenantId, UUID accountId, UUID projectId, UUID typeId, UUID deviceId) {
    }

    /** @return 某租户下的一台温度上报设备及其类型。 */
    private record ProjectFixture(UUID projectId, UUID typeId, UUID deviceId) {
    }

    /** @return 通过节点校验的通知动作配置快照 */
    private static ObjectNode notificationConfig(String recipient, String subject, String body) {
        return new ObjectMapper().createObjectNode()
                .put("channel", "email")
                .put("recipient", recipient)
                .put("subject", subject)
                .put("body", body);
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

    /** @param key 唯一后缀 @return 满足项目键长度约束的短标识 */
    private void insertTenant(UUID tenantId, String key) {
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)",
                tenantId, "S9-1 tenant " + key + "-" + tenantId.toString().substring(0, 8));
    }

    /** @param key 唯一后缀 @return 不会与历史测试冲突的账号 */
    private void insertAccount(UUID accountId, String key) {
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S9-1 account')
                """, accountId, "s9-1-" + key + "-" + accountId + "@example.com");
    }

    /** 创建项目、OWNER 成员、设备类型、设备与温度属性，满足数据面完整外键约束。 */
    private ProjectFixture seedProjectDevice(UUID tenantId, UUID accountId, String key) {
        UUID projectId = Uuid7.generate();
        UUID typeId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, projectId, tenantId, "S9-1 project " + key, projectKey(projectId));
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
                    """, typeId, tenantId, projectId, typeKey(key, typeId), "S9-1 type " + key);
            jdbcTemplate.update("""
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'ONLINE')
                    """, deviceId, tenantId, projectId, typeId, deviceKey(key, deviceId), "S9-1 device " + key);
            jdbcTemplate.update("""
                    INSERT INTO dev_property_definition
                        (id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type)
                    VALUES (?, ?, ?, ?, 'temperature', '温度', 'REPORT', 'NUMBER')
                    """, Uuid7.generate(), tenantId, projectId, typeId);
        } finally {
            TenantContext.clear();
        }
        // 版本发布属控制面（gap 2.3），数据面夹具直接建立 1.0.0 与 INITIAL 绑定，接通 versioned 摄入链。
        seedThingModelVersion(tenantId, projectId, typeId, deviceId, TEMPERATURE_SNAPSHOT);
        return new ProjectFixture(projectId, typeId, deviceId);
    }

    /** 在项目范围内创建并激活一条带动作的规则，随后清除 ThreadLocal。 */
    private MessageRuleView activateRule(Fixture fixture, String source, List<ActionSpec> actions) {
        TenantContext.set(scope(fixture));
        try {
            MessageRuleView rule = ruleService.create(fixture.projectId(),
                    new CreateMessageRuleCommand("rule-" + shortId(), null, source, actions));
            MessageRuleVersionView version = ruleService.versions(fixture.projectId(), rule.id()).getFirst();
            return ruleService.activate(fixture.projectId(), rule.id(), version.id(), rule.version());
        } finally {
            TenantContext.clear();
        }
    }

    /** 以设备 ID 为 key 发布确权后的标准上行，模拟 RawUplinkKafkaConsumer 的续接输出。 */
    private RecordMetadata sendUplink(Fixture fixture, UUID messageId, Map<String, Object> payload) throws Exception {
        Instant now = Instant.now();
        StandardUplinkMessage message = new StandardUplinkMessage(
                messageId, fixture.tenantId(), fixture.projectId(), fixture.deviceId(), null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", now.minusSeconds(1), now,
                "s9-1-" + messageId, 32, payload);
        RecordMetadata metadata = kafkaTemplate.send(NormalizedUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC,
                fixture.deviceId().toString(), message).get(10, TimeUnit.SECONDS).getRecordMetadata();
        sentRecords.add(metadata);
        return metadata;
    }

    /** 以本次重放记录的 normalized 消费组提交位点作屏障，不能沿用首次回执判断重放完成。 */
    private void awaitNormalizedConsumed(RecordMetadata record) {
        awaitNormalizedConsumed(List.of(record));
    }

    /** 等待本轮全部发送记录越过各自分区的提交位点，清理时也使用同一屏障。 */
    private void awaitNormalizedConsumed(List<RecordMetadata> records) {
        if (records.isEmpty()) {
            return;
        }
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000))) {
            new KafkaRecordConsumptionBarrier(CHAIN_TIMEOUT, () -> {
                try {
                    return admin.listConsumerGroupOffsets("things-link-ingestion-normalized")
                            .partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("查询重放消费位点被中断", interrupted);
                } catch (Exception failure) {
                    throw new IllegalStateException("查询重放消费位点失败", failure);
                }
            }).awaitConsumed(records);
        }
    }

    /** 等待 messageId 的遥测点出现且 value_double 与期望值一致（容差内）。 */
    private void awaitPropertyValue(Fixture fixture, UUID messageId, double expected) {
        try {
            awaitCondition("遥测点未出现 messageId=" + messageId + " value=" + expected, () -> inScopeCount(fixture,
                    "SELECT count(*) FROM ts_property_point WHERE message_id = ? AND value_double IS NOT NULL"
                            + " AND abs(value_double - ?) < 0.0001", messageId, expected) > 0);
        } catch (AssertionError failure) {
            if (!failure.getMessage().startsWith("未在期限内满足条件:")) throw failure;
            throw new AssertionError(failure.getMessage() + "; " + propertyTimeoutFacts(fixture, messageId), failure);
        }
    }

    /** 超时时只读取有限状态与数值，不输出设备载荷、脚本源码或异常正文。 */
    private String propertyTimeoutFacts(Fixture fixture, UUID messageId) {
        TenantContext.set(scope(fixture));
        try {
            UUID projectId = fixture.projectId();
            return "receipt=" + diagnostic(() -> jdbcTemplate.queryForList(
                    "SELECT status, attempt FROM rule_execution_receipt"
                            + " WHERE project_id = ? AND message_id = ? ORDER BY attempt LIMIT 5",
                    projectId, messageId))
                    + ", execution=" + diagnostic(() -> jdbcTemplate.queryForList(
                    "SELECT status, result_code, attempt FROM rule_execution_log"
                            + " WHERE project_id = ? AND message_id = ? ORDER BY attempt LIMIT 5",
                    projectId, messageId))
                    + ", points=" + diagnostic(() -> jdbcTemplate.queryForList(
                    "SELECT property_key, value_double FROM ts_property_point"
                            + " WHERE project_id = ? AND message_id = ? ORDER BY ts DESC LIMIT 5",
                    projectId, messageId))
                    + ", inbox=" + diagnostic(() -> count(
                    "SELECT count(*) FROM sys_inbox_message WHERE project_id = ? AND message_id = ?",
                    projectId, messageId))
                    + ", messageLog=" + diagnostic(() -> count(
                    "SELECT count(*) FROM ts_device_message_log WHERE project_id = ? AND message_id = ?",
                    projectId, messageId))
                    + ", historicalQuota=" + diagnostic(() -> List.of(
                    dailyQuotaDecisionService.decideTrustedProject(
                            fixture.tenantId(), projectId, QuotaMetric.UPLINK_MESSAGE),
                    dailyQuotaDecisionService.decideTrustedProject(
                            fixture.tenantId(), projectId, QuotaMetric.UPLINK_BYTES),
                    dailyQuotaDecisionService.decideTrustedProject(
                            fixture.tenantId(), projectId, QuotaMetric.TIME_SERIES_POINT)));
        } finally {
            TenantContext.clear();
        }
    }

    /** 某项诊断查询失败仍保留其他事实及原始超时。 */
    private static String diagnostic(Supplier<?> query) {
        try {
            return String.valueOf(query.get());
        } catch (RuntimeException failure) {
            return "unavailable(" + failure.getClass().getSimpleName() + ")";
        }
    }

    /** 等待规则执行日志进入目标状态。 */
    private void awaitExecutionLog(Fixture fixture, UUID messageId, String status) {
        awaitCondition("执行日志未进入 " + status + " messageId=" + messageId, () -> inScopeCount(fixture,
                "SELECT count(*) FROM rule_execution_log WHERE message_id = ? AND status = ?",
                messageId, status) > 0);
    }

    /** 等待投递事实落库：只有 Outbox 发布 + Kafka 消费完成才会出现该行。 */
    private void awaitDeliveryFact(Fixture fixture, UUID messageId) {
        awaitCondition("投递事实未落库 messageId=" + messageId, () -> inScopeCount(fixture,
                "SELECT count(*) FROM rule_notification_delivery WHERE message_id = ? AND status = 'DELIVERED'",
                messageId) > 0);
    }

    /** 等待 Outbox 事件被发布器确认为 PUBLISHED，证明投递事实确实经 Kafka 而非同步直写。 */
    private void awaitOutboxPublished(Fixture fixture, UUID messageId) {
        awaitCondition("Outbox 事件未发布 messageId=" + messageId, () -> inScopeCount(fixture,
                "SELECT count(*) FROM sys_outbox_event WHERE event_type = 'RULE_NOTIFICATION_DELIVERY_REQUEST'"
                        + " AND status = 'PUBLISHED'") > 0);
    }

    /** 在项目 RLS 范围内执行断言或查询。 */
    private void withScope(Fixture fixture, Runnable action) {
        TenantContext.set(scope(fixture));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    /** 在项目 RLS 范围内执行一次计数查询并返回结果。 */
    private long inScopeCount(Fixture fixture, String sql, Object... args) {
        TenantContext.set(scope(fixture));
        try {
            return count(sql, args);
        } finally {
            TenantContext.clear();
        }
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
        return "s91" + projectId.toString().replace("-", "").substring(0, 16);
    }

    /** @return 项目内唯一类型键 */
    private static String typeKey(String key, UUID typeId) {
        return "s91t" + key.replace("-", "") + typeId.toString().replace("-", "").substring(0, 8);
    }

    /** @return 项目内唯一设备键 */
    private static String deviceKey(String key, UUID deviceId) {
        return "s91d" + key.replace("-", "") + deviceId.toString().replace("-", "").substring(0, 8);
    }

    /** @return 规则名使用的稳定短标识 */
    private static String shortId() {
        return Uuid7.generate().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 本测试上下文启动前必须显式创建全部消息链路 Topic；生产 {@code missing-topics-fatal=true}，每个
     * {@link org.springframework.kafka.annotation.KafkaListener} Topic 都必须在启动前用 {@link NewTopic}
     * 建好，禁止依赖 broker 自动建主题。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTopicTestConfiguration {

        @Bean
        NewTopic rawUplinkTopic() {
            return TopicBuilder.name(RAW_UPLINK_TOPIC).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic normalizedUplinkTopic() {
            return TopicBuilder.name(NormalizedUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC)
                    .partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic processedUplinkTopic() {
            return TopicBuilder.name(PROCESSED_UPLINK_TOPIC).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic commandDownlinkTopic() {
            return TopicBuilder.name(DOWNLINK_TOPIC).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic realtimeTopic() {
            return TopicBuilder.name(REALTIME_TOPIC).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic notificationTopic() {
            return TopicBuilder.name("tc.notification").partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic ruleRetryOneMinuteTopic() {
            return TopicBuilder.name(RETRY_ONE_MINUTE_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic ruleRetryFiveMinutesTopic() {
            return TopicBuilder.name(RETRY_FIVE_MINUTES_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic ruleDeadLetterTopic() {
            return TopicBuilder.name(KafkaRuleRecoveryPublisher.DEAD_LETTER_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic ruleNotificationTopic() {
            return TopicBuilder.name("tc.rule.notification").partitions(3).replicas(1).build();
        }

        /** S9-2 终态 listener 随完整上下文启动，测试必须显式准备主题。 */
        @Bean
        NewTopic deviceCommandTerminalTopic() {
            return TopicBuilder.name("tc.device.command.terminal").partitions(3).replicas(1).build();
        }
    }
}
