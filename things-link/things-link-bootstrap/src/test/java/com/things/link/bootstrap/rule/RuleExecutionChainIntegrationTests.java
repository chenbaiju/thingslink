package com.things.link.bootstrap.rule;

import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;
import com.things.link.bootstrap.fixture.KafkaRecordConsumptionBarrier;

import com.things.link.ingestion.infrastructure.NormalizedUplinkKafkaConsumer;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.rule.application.CreateMessageRuleCommand;
import com.things.link.rule.application.MessageRuleService;
import com.things.link.rule.application.MessageRuleVersionView;
import com.things.link.rule.application.MessageRuleView;
import com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import com.things.link.support.fault.FaultInjectionCheckpoint;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static com.things.link.ingestion.application.RealtimeKafkaPublisher.REALTIME_TOPIC;
import static com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
import static com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher.RETRY_FIVE_MINUTES_TOPIC;
import static com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher.RETRY_ONE_MINUTE_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * S8-2D 组合验收：真实 Kafka + PostgreSQL + Worker 打通规则执行全链路。
 *
 * <p>单测已分别覆盖慢租户公平、沙箱资源拒绝、队列饱和与有限退避时序；本类只补它们缺少的组合事实——
 * 一条确权后的标准上行从 normalized 经过两级公平队列、独立 Worker 与 processed 续接，最终落到遥测；
 * 无规则直通、脚本失败 DLQ 与原身份重放都经过真实业务 Bean；CI竞态回归仅在禁用的故障钩子上
 * 设置可释放屏障，不替换规则、数据库或Kafka结果。</p>
 */
@Import({RuleExecutionChainIntegrationTests.KafkaTopicTestConfiguration.class, DeviceProtocolKafkaTopicsTestConfiguration.class})
@TestPropertySource(properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"
})
class RuleExecutionChainIntegrationTests extends AbstractKafkaIntegrationTest {

    /** 冷 Worker 启动与两跳 Kafka 的宽松上限，测试不依赖固定 sleep。 */
    private static final Duration CHAIN_TIMEOUT = Duration.ofMinutes(5);
    /** 与生产normalized listener一致，提交位点表示规则持久终态后的消费返回。 */
    private static final String NORMALIZED_GROUP = "things-link-ingestion-normalized";
    /** 版本化数据面夹具用 1.0.0 快照：仅一个 NUMBER 上报温度属性。 */
    private static final String TEMPERATURE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},"
                    + "\"events\":{},\"commands\":{}}";

    /** 无 UI 规则应用服务，创建并发布 ACTIVE 不可变版本。 */
    @Autowired private MessageRuleService ruleService;
    /** 真实 PostgreSQL 事实断言入口（RLS 视图与规则回执）。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 与生产同源的共享幂等 Kafka 模板。 */
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    /** 仅在遥测超时时读取历史降级的三项权威额度状态。 */
    @Autowired private ProjectDailyQuotaDecisionService dailyQuotaDecisionService;

    /** 只拦截本轮匹配消息的禁用故障钩子，业务持久化仍由生产Bean执行。 */
    @MockitoSpyBean private FaultInjectionCheckpoint faultCheckpoint;
    /** 本用例实际获Broker确认的记录；清理必须逐分区越过最高offset才安全。 */
    private final List<RecordMetadata> sentRecords = new ArrayList<>();

    /** ThreadLocal 项目范围不得泄漏到其他集成测试。 */
    @AfterEach
    void clearScopeAndRuleFacts() throws SQLException {
        TenantContext.clear();
        // S12-CI-1：遥测/日志或旧回执可见不代表listener已返回；先等本轮所有normalized记录提交，
        // 再取排他锁，避免清理与仍在写规则事实的Worker互相等待。超时/查询错误必须直接失败。
        // 本类没有延迟重试场景；将来若加入RETRY_SCHEDULED，须另等retry终态，不能复用本屏障清理。
        awaitRecordsConsumed(sentRecords);
        // 规则五表都以无 ON DELETE CASCADE 的外键引用 sys_project，且应用角色被 REVOKE DELETE 了
        // rule_version / rule_execution_log / rule_debug_event；只能借迁移超级用户（表 owner，不受 RLS
        // 与撤销影响）连接按依赖逆序清空，否则残留行会阻断其他测试类的 DELETE FROM sys_project。
        // dev_* / ts_* 均级联删除，交给后续测试类的 @BeforeEach 统一处理。
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            // 清理必须保持为一个事务，并先锁住会被异步 Worker 回写的规则事实表。若沿用自动提交，
            // Worker 可在 DELETE receipt 与 DELETE version 之间补写回执，令版本外键再次变为被引用状态。
            connection.setAutoCommit(false);
            statement.execute("""
                    LOCK TABLE rule_debug_event, rule_execution_log, rule_execution_receipt,
                               rule_version, rule_message IN ACCESS EXCLUSIVE MODE
                    """);
            statement.executeUpdate("DELETE FROM rule_debug_event");
            statement.executeUpdate("DELETE FROM rule_execution_log");
            statement.executeUpdate("DELETE FROM rule_execution_receipt");
            // 解活动指针前必须先置回 DRAFT：ACTIVE/PAUSED 要求 active_version_id 非空，直接清空违反互斥约束。
            statement.executeUpdate("UPDATE rule_message SET status = 'DRAFT', active_version_id = NULL");
            statement.executeUpdate("DELETE FROM rule_version");
            statement.executeUpdate("DELETE FROM rule_message");
            connection.commit();
        }
    }

    /**
     * S8-2D 组合验收在本测试上下文启动前必须显式创建全部消息链路 Topic。
     *
     * <p>bootstrap 测试基线默认 {@code auto-startup=false} 且 {@code auto-create=false}，非消息测试因此
     * 不连 Kafka。本类恢复真实 broker 后，所有 {@link org.springframework.kafka.annotation.KafkaListener}
     * （raw / normalized / processed / downlink / realtime / notification / 两个 retry）都会随上下文启动，
     * 而生产配置 {@code missing-topics-fatal=true}；故每个监听 Topic 都必须在启动前用 {@link NewTopic}
     * 显式建好，禁止依赖 broker 自动建主题。processed 与 normalized 保持相同分区数，维持设备内顺序。</p>
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

    /** 完整链路：规则把 payload 派生为新值，processed 续接后遥测落库的是派生值而非原始值。 */
    @Test
    void processedChainTransformsPayloadAndIngestsTelemetry() throws Exception {
        Fixture fixture = seedFixture("transform");
        activateRule(fixture, "input => ({temperature: input.temperature + 0.5})");
        UUID messageId = Uuid7.generate();

        sendUplink(fixture, messageId, Map.of("temperature", 26.5));

        awaitPropertyValue(fixture, messageId, 27.0);
        awaitExecutionLog(fixture, messageId, "SUCCESS");
        withScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_execution_log WHERE message_id = ?", messageId))
                    .as("成功执行只留一条日志").isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM rule_execution_log WHERE message_id = ?", String.class, messageId))
                    .isEqualTo("SUCCESS");
            assertThat(count("SELECT count(*) FROM rule_execution_receipt WHERE message_id = ?", messageId))
                    .as("回执完成且唯一").isEqualTo(1);
        });
    }

    /** 无活动规则也走同一 processed 续接 Topic，遥测收到未变化的原值。 */
    @Test
    void messageWithoutActiveRulePassesThroughUnchanged() throws Exception {
        Fixture fixture = seedFixture("passthrough");
        UUID messageId = Uuid7.generate();

        sendUplink(fixture, messageId, Map.of("temperature", 26.5));

        awaitPropertyValue(fixture, messageId, 26.5);
        withScope(fixture, () -> assertThat(
                count("SELECT count(*) FROM rule_execution_log WHERE message_id = ?", messageId)).isZero());
    }

    /** 脚本抛出异常属于永久失败：进入 tc.rule.dlq、留失败事实且不产生任何遥测点。 */
    @Test
    void scriptFailureRoutesToDlqAndWritesNoTelemetry() throws Exception {
        Fixture fixture = seedFixture("script-failure");
        activateRule(fixture, "input => { throw new Error('boom') }");
        UUID messageId = Uuid7.generate();

        sendUplink(fixture, messageId, Map.of("temperature", 26.5));

        ConsumerRecord<String, String> deadLetter = awaitDlqRecord(messageId);
        assertThat(deadLetter.value()).contains("SCRIPT_FAILURE").contains(messageId.toString());
        withScope(fixture, () -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM rule_execution_log WHERE message_id = ?", String.class, messageId))
                    .isEqualTo("DEAD_LETTER");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT result_code FROM rule_execution_log WHERE message_id = ?", String.class, messageId))
                    .isEqualTo("SCRIPT_FAILURE");
            assertThat(count("SELECT count(*) FROM ts_property_point WHERE message_id = ?", messageId)).isZero();
        });
    }

    /** 同一 messageId 重投只执行一次：持久回执吸收重放，日志、回执与遥测点都保持唯一。 */
    @Test
    void replayOfSameMessageExecutesAndIngestsExactlyOnce() throws Exception {
        Fixture fixture = seedFixture("replay");
        activateRule(fixture, "input => ({temperature: input.temperature + 0.5})");
        UUID messageId = Uuid7.generate();

        sendUplink(fixture, messageId, Map.of("temperature", 26.5));
        awaitPropertyValue(fixture, messageId, 27.0);
        awaitExecutionLog(fixture, messageId, "SUCCESS");
        RecordMetadata replay = sendUplink(fixture, messageId, Map.of("temperature", 26.5));

        awaitReplayProcessed(fixture, messageId, replay);
        withScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_execution_log WHERE message_id = ?", messageId))
                    .as("重放不得重复执行").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_execution_receipt WHERE message_id = ?", messageId))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM ts_property_point WHERE message_id = ?", messageId))
                    .as("重放不得重复落遥测").isEqualTo(1);
        });
    }

    /** 持久回执已存在而重放Worker仍被阻塞时，验收必须继续等待本条消费记录。 */
    @Test
    void replayCompletionWaitsBeyondExistingReceipt() throws Exception {
        Fixture fixture = seedFixture("replay-barrier");
        activateRule(fixture, "input => ({temperature: input.temperature + 0.5})");
        UUID messageId = Uuid7.generate();
        RecordMetadata first = sendUplink(fixture, messageId, Map.of("temperature", 26.5));
        awaitRecordsConsumed(List.of(first));
        awaitPropertyValue(fixture, messageId, 27.0);

        CountDownLatch replayReached = new CountDownLatch(1);
        CountDownLatch releaseReplay = new CountDownLatch(1);
        String identity = fixture.tenantId() + ":" + fixture.projectId() + ":" + messageId + ":1";
        doAnswer(invocation -> {
            replayReached.countDown();
            if (!releaseReplay.await(CHAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("受控重放未在期限内释放");
            }
            return null;
        }).when(faultCheckpoint).reach(eq(FaultInjectionCheckpoint.Checkpoint.RULE_BEFORE_RECEIPT_CLAIM),
                eq(identity));

        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                RecordMetadata replay = sendUplink(fixture, messageId, Map.of("temperature", 26.5));
                assertThat(replayReached.await(CHAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
                assertThat(inScopeCount(fixture,
                        "SELECT count(*) FROM rule_execution_receipt WHERE message_id = ?", messageId))
                        .as("旧完成谓词已成立，但真实重放仍停在claim前").isEqualTo(1);
                assertThat(committedOffset(replay)).isLessThan(replay.offset() + 1);
                var completion = executor.submit(() -> awaitReplayProcessed(fixture, messageId, replay));
                assertThatThrownBy(() -> completion.get(250, TimeUnit.MILLISECONDS))
                        .as("释放之前不能仅凭旧回执误判本次重放完成")
                        .isInstanceOf(TimeoutException.class);
                releaseReplay.countDown();
                completion.get(CHAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                withScope(fixture, () -> {
                    assertThat(count("SELECT count(*) FROM rule_execution_receipt WHERE message_id = ?", messageId))
                            .isEqualTo(1);
                    assertThat(count("SELECT count(*) FROM rule_execution_log WHERE message_id = ?", messageId))
                            .isEqualTo(1);
                    assertThat(count("SELECT count(*) FROM ts_property_point WHERE message_id = ?", messageId))
                            .isEqualTo(1);
                });
            } finally {
                // 断言失败也释放真实Worker；不能留下人为阻塞污染后续清理/测试。
                releaseReplay.countDown();
                awaitRecordsConsumed(sentRecords);
            }
        }
    }

    /** 规则只作用于自己的项目：同租户另一个项目无规则，其报文直通而不被邻项目规则改写。 */
    @Test
    void activeRuleIsScopedToItsOwnProjectOnly() throws Exception {
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

        activateRule(ruleProject, "input => ({temperature: input.temperature + 0.5})");
        UUID transformedId = Uuid7.generate();
        UUID passthroughId = Uuid7.generate();

        sendUplink(ruleProject, transformedId, Map.of("temperature", 26.5));
        sendUplink(plainProject, passthroughId, Map.of("temperature", 26.5));

        awaitPropertyValue(ruleProject, transformedId, 27.0);
        awaitPropertyValue(plainProject, passthroughId, 26.5);
    }

    /** @return 记录全部设备上下文，供遥测断言与规则发布复用。 */
    private record Fixture(UUID tenantId, UUID accountId, UUID projectId, UUID typeId, UUID deviceId) {
    }

    /** @return 某租户下的一台温度上报设备及其类型。 */
    private record ProjectFixture(UUID projectId, UUID typeId, UUID deviceId) {
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
                tenantId, "S8-2D tenant " + key + "-" + tenantId.toString().substring(0, 8));
    }

    /** @param key 唯一后缀 @return 不会与历史测试冲突的账号 */
    private void insertAccount(UUID accountId, String key) {
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S8-2D account')
                """, accountId, "s8-2d-" + key + "-" + accountId + "@example.com");
    }

    /** 创建项目、OWNER 成员、设备类型、设备与温度属性，满足数据面完整外键约束。 */
    private ProjectFixture seedProjectDevice(UUID tenantId, UUID accountId, String key) {
        UUID projectId = Uuid7.generate();
        UUID typeId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, projectId, tenantId, "S8-2D project " + key, projectKey(projectId));
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
                    """, typeId, tenantId, projectId, typeKey(key, typeId), "S8-2D type " + key);
            jdbcTemplate.update("""
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'ONLINE')
                    """, deviceId, tenantId, projectId, typeId, deviceKey(key, deviceId), "S8-2D device " + key);
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

    /** 在项目范围内创建并激活一条规则，随后清除 ThreadLocal。 */
    private void activateRule(Fixture fixture, String source) {
        TenantContext.set(scope(fixture));
        try {
            MessageRuleView rule = ruleService.create(fixture.projectId(),
                    new CreateMessageRuleCommand("rule-" + shortId(), null, source));
            MessageRuleVersionView version = ruleService.versions(fixture.projectId(), rule.id()).getFirst();
            ruleService.activate(fixture.projectId(), rule.id(), version.id(), rule.version());
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
                "s8-2d-" + messageId, 32, payload);
        RecordMetadata metadata = kafkaTemplate.send(NormalizedUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC,
                fixture.deviceId().toString(), message).get(10, TimeUnit.SECONDS).getRecordMetadata();
        sentRecords.add(metadata);
        return metadata;
    }

    /** 重放只在本次消费已返回后再断言回执唯一，不能用首次执行的既有行充当消费屏障。 */
    private void awaitReplayProcessed(Fixture fixture, UUID messageId, RecordMetadata replay) {
        awaitRecordsConsumed(List.of(replay));
        assertThat(inScopeCount(fixture,
                "SELECT count(*) FROM rule_execution_receipt WHERE message_id = ?", messageId))
                .as("本条重放消费完成后回执仍唯一").isEqualTo(1);
    }

    /** 本轮各分区必须提交到最高发送记录之后；查询失败或超时都禁止进入清理。 */
    private void awaitRecordsConsumed(Collection<RecordMetadata> records) {
        if (records.isEmpty()) {
            return;
        }
        try (AdminClient admin = offsetAdmin()) {
            new KafkaRecordConsumptionBarrier(CHAIN_TIMEOUT, () -> readCommittedOffsets(admin))
                    .awaitConsumed(records);
        }
    }

    /** 读取本条记录的真实消费进度；缺失提交不能解释成offset 0已经处理。 */
    private long committedOffset(RecordMetadata record) {
        try (AdminClient admin = offsetAdmin()) {
            OffsetAndMetadata offset = readCommittedOffsets(admin)
                    .get(new TopicPartition(record.topic(), record.partition()));
            return offset == null ? -1 : offset.offset();
        }
    }

    /** 使用独立Admin查询真实消费组，单次请求有界，不影响生产consumer。 */
    private AdminClient offsetAdmin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000));
    }

    /** Admin异常保持失败；中断恢复标记，不能吞错后继续删除规则事实。 */
    private Map<TopicPartition, OffsetAndMetadata> readCommittedOffsets(AdminClient admin) {
        try {
            return admin.listConsumerGroupOffsets(NORMALIZED_GROUP)
                    .partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取normalized消费位点被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("读取normalized消费位点失败", exception);
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

    /** 等待规则执行日志进入目标状态；status 取 Status 枚举名（SUCCESS / DEAD_LETTER / RETRY_SCHEDULED）。 */
    private void awaitExecutionLog(Fixture fixture, UUID messageId, String status) {
        awaitCondition("执行日志未进入 " + status + " messageId=" + messageId, () -> inScopeCount(fixture,
                "SELECT count(*) FROM rule_execution_log WHERE message_id = ? AND status = ?",
                messageId, status) > 0);
    }

    /** 用独立消费组等待 tc.rule.dlq 中 key 为 messageId 的原始死信记录。 */
    private ConsumerRecord<String, String> awaitDlqRecord(UUID messageId) {
        try (KafkaConsumer<String, String> consumer = dlqConsumer()) {
            consumer.subscribe(List.of(KafkaRuleRecoveryPublisher.DEAD_LETTER_TOPIC));
            Instant deadline = Instant.now().plus(CHAIN_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    if (messageId.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
            throw new AssertionError("规则 DLQ 未在期限内收到 messageId=" + messageId);
        }
    }

    /** @return 从 earliest 读取的字符串消费者，避免与生产消费组共享 offset。 */
    private KafkaConsumer<String, String> dlqConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "s8-2d-dlq-probe-" + Uuid7.generate());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        return new KafkaConsumer<>(properties);
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
        return "s8d" + projectId.toString().replace("-", "").substring(0, 16);
    }

    /** @return 项目内唯一类型键 */
    private static String typeKey(String key, UUID typeId) {
        return "s8dt" + key.replace("-", "") + typeId.toString().replace("-", "").substring(0, 8);
    }

    /** @return 项目内唯一设备键 */
    private static String deviceKey(String key, UUID deviceId) {
        return "s8dd" + key.replace("-", "") + deviceId.toString().replace("-", "").substring(0, 8);
    }

    /** @return 规则名使用的稳定短标识 */
    private static String shortId() {
        return Uuid7.generate().toString().replace("-", "").substring(0, 12);
    }
}
