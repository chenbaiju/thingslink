package com.things.link.bootstrap.rule;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.KafkaRecordConsumptionBarrier;

import com.things.link.ingestion.infrastructure.NormalizedUplinkKafkaConsumer;
import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.CreateMessageRuleCommand;
import com.things.link.rule.application.MessageRuleService;
import com.things.link.rule.application.MessageRuleVersionView;
import com.things.link.rule.application.MessageRuleView;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionFailure;
import com.things.link.rule.application.queue.RuleExecutionReceiptStore;
import com.things.link.rule.application.queue.RuleExecutionReceiptClaim;
import com.things.link.rule.infrastructure.messaging.KafkaRuleRecoveryPublisher;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.fault.FaultInjectionCheckpoint;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR0068真实Broker验收：processed先确认，冻结动作的规则DLQ确认后才能完成receipt和来源offset。
 * 专库/专Broker只启动normalized原listener，processed记录仅作为可靠续接事实；不声称本类验证遥测消费。
 * 受控恢复发布失败沿原入口tc.dlq恢复，区分规则DLQ与来源DLQ，不修改共享错误分类或租约。
 */
@Import(RuleActionLifecycleKafkaTests.IsolatedConfiguration.class)
@OwnedTestContainers({"PROBE_POSTGRES", "PROBE_KAFKA"})
class RuleActionLifecycleKafkaTests extends AbstractIntegrationTest {
    /** 固定生产group只能由本类独占Broker上的原listener消费。 */
    private static final String NORMALIZED_GROUP = "things-link-ingestion-normalized";
    /** 实际源码、原Worker冷启动及Broker交接的有界等待。 */
    private static final Duration CHAIN_TIMEOUT = Duration.ofSeconds(30);
    /** normalized和processed保留原三分区及设备key。 */
    private static final String PROCESSED_TOPIC = "tc.device.uplink.processed";
    /** normalized原错误处理器在未知恢复异常时使用共用入口DLQ。 */
    private static final String SOURCE_DLQ_TOPIC = "tc.dlq";
    /** 独占物理库；无全局清表或删除不可变规则历史。 */
    private static final String DATABASE_NAME = "rule_action_kafka_" + UUID.randomUUID().toString().replace("-", "");
    /** 测试镜像和角色与基类相同，Flyway/APP/owner共用专库。 */
    private static final PostgreSQLContainer<?> PROBE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** 在Registrar之前启动专库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 独占真实Kafka，不与缓存上下文共享消费组。 */
    private static final KafkaContainer PROBE_KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))
            .withStartupTimeout(Duration.ofMinutes(2));
    /** 统一生产模板/原listener/独立观察端使用同Broker。 */
    private static final String KAFKA_SERVERS = startKafka();
    /** 满足真实版本化设备admission的最小温度模型。 */
    private static final String TEMPERATURE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},"
                    + "\"events\":{},\"commands\":{}}";
    /** 父runner直连共享库，在专库夹具禁用。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedQuotaRunner;
    /** 禁止不相关全局领取者处理专库事实。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotifications;
    /** 无关任务不参与规则消息验收。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTasks;
    /** 本类不启动遥测消费或回补。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfill;
    /** 创建真实不可变规则及活动指针。 */
    @Autowired private MessageRuleService ruleService;
    /** 原APP/RLS读写端口。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 原消息序列化和Broker ACK。 */
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    /** 独立查询Flyway连接地址，避免只验证APP落点。 */
    @Autowired private org.springframework.core.env.Environment environment;
    /** 仅匹配目标message的禁用故障钩子；不替代处理器/桥接/回执。 */
    @MockitoSpyBean private FaultInjectionCheckpoint faultCheckpoint;
    /** 只在目标消息注入屏障或一次发送边界异常，成功时调用真实Kafka发布。 */
    @MockitoSpyBean private KafkaRuleRecoveryPublisher recovery;
    /** 观察真实COMPLETED领取结果，不能以来源位点完成猜测重放被吸收。 */
    @MockitoSpyBean private RuleExecutionReceiptStore receipts;
    /** 原工厂和错误处理器保持生产配置，仅手动启动normalized。 */
    @Autowired private KafkaListenerEndpointRegistry listeners;
    /** 本例实际启动的唯一原消费容器。 */
    private MessageListenerContainer activeListener;

    /** 专库、APP角色、Flyway与原topic均检查后，才启动原normalized listener。 */
    @BeforeEach
    void prepare() throws Exception {
        assertThat(environment.getProperty("spring.flyway.url")).isEqualTo(DATABASE_URL);
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            }
        }
        try (Connection owner = fixtureOwnerConnection(); var statement = owner.createStatement();
             var rows = statement.executeQuery("SELECT current_database(),current_user")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
            assertThat(rows.getString(2)).isEqualTo(PROBE_POSTGRES.getUsername());
        }
        assertThat(listeners.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
        try (AdminClient admin = offsetAdmin()) {
            if (!admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(PROCESSED_TOPIC)) {
                admin.createTopics(List.of(new NewTopic(NormalizedUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC, 3, (short) 1),
                        new NewTopic(PROCESSED_TOPIC, 3, (short) 1),
                        new NewTopic(KafkaRuleRecoveryPublisher.DEAD_LETTER_TOPIC, 1, (short) 1),
                        new NewTopic(SOURCE_DLQ_TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            }
            assertThat(admin.describeTopics(List.of(PROCESSED_TOPIC)).allTopicNames().get(5, TimeUnit.SECONDS)
                    .get(PROCESSED_TOPIC).partitions()).hasSize(3);
        }
        var selected = listeners.getListenerContainers().stream()
                .filter(container -> NORMALIZED_GROUP.equals(container.getContainerProperties().getGroupId())).toList();
        assertThat(selected).hasSize(1);
        activeListener = selected.getFirst();
        activeListener.start();
        assertThat(listeners.getListenerContainers()).filteredOn(MessageListenerContainer::isRunning).containsExactly(activeListener);
    }

    /** 动作拒绝不能伪造SUCCESS，真实规则DLQ确认之前保持来源位点和未完成receipt。 */
    @Test
    void frozenActionWaitsForRuleDlqAckThenCompletesAndAbsorbsReplay() throws Exception {
        Fixture fixture = seedFixture("freeze");
        activateRule(fixture, "input => input");
        UUID messageId = Uuid7.generate();
        AtomicInteger continuations = freezeAfterProcessedAck(fixture, messageId);
        AtomicInteger ruleDlqs = new AtomicInteger();
        AtomicInteger completedClaims = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            RuleExecutionEnvelope attempted = invocation.getArgument(0);
            if (attempted.key().messageId().equals(messageId) && result == RuleExecutionReceiptClaim.COMPLETED)
                completedClaims.incrementAndGet();
            return result;
        }).when(receipts).tryClaim(any());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            RuleExecutionEnvelope envelope = invocation.getArgument(0);
            if (envelope.key().messageId().equals(messageId)) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                ruleDlqs.incrementAndGet();
                reached.countDown();
                if (!release.await(CHAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    throw new IllegalStateException("规则DLQ屏障未释放");
            }
            return invocation.callRealMethod();
        }).when(recovery).publishDeadLetter(any(), eq(RuleExecutionFailure.SECURITY_REJECTED));
        try {
            RecordMetadata source = sendUplink(fixture, messageId, Map.of("temperature", 26.5));
            assertThat(reached.await(CHAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(awaitTopicRecord(PROCESSED_TOPIC, messageId).key()).isEqualTo(fixture.deviceId().toString());
            assertThat(committedOffset(source)).isLessThan(source.offset() + 1);
            assertReceipt(fixture, messageId, "IN_PROGRESS");
            assertNoActionsOrSuccess(fixture, messageId);
            release.countDown();
            assertThat(awaitTopicRecord(KafkaRuleRecoveryPublisher.DEAD_LETTER_TOPIC, messageId).value())
                    .contains("SECURITY_REJECTED");
            awaitRecordsConsumed(List.of(source));
            assertReceipt(fixture, messageId, "COMPLETED");
            awaitExecutionLog(fixture, messageId, "DEAD_LETTER");
            assertNoActionsOrSuccess(fixture, messageId);
            updateProject(fixture, "ACTIVE");
            RecordMetadata replay = sendUplink(fixture, messageId, Map.of("temperature", 26.5));
            awaitReplayProcessed(fixture, messageId, replay);
            assertThat(completedClaims).hasValue(1);
            assertThat(continuations).hasValue(1);
            assertThat(ruleDlqs).hasValue(1);
            assertThat(inScopeCount(fixture, "SELECT count(*) FROM rule_execution_log WHERE message_id=?", messageId)).isEqualTo(1);
            assertNoActionsOrSuccess(fixture, messageId);
        } finally { release.countDown(); }
    }

    /** 规则DLQ发送边界失败不挂Worker：原normalized工厂持久交给入口DLQ，不能冒称规则receipt已完成。 */
    @Test
    void failedRuleDlqReturnsToOriginalSourceRecoveryWithoutFalseCompletion() throws Exception {
        Fixture fixture = seedFixture("dlqfail");
        activateRule(fixture, "input => input");
        UUID messageId = Uuid7.generate();
        freezeAfterProcessedAck(fixture, messageId);
        AtomicInteger failedSends = new AtomicInteger();
        doAnswer(invocation -> {
            RuleExecutionEnvelope envelope = invocation.getArgument(0);
            if (envelope.key().messageId().equals(messageId)) {
                failedSends.incrementAndGet();
                throw new KafkaException("受控规则恢复发布失败");
            }
            return invocation.callRealMethod();
        }).when(recovery).publishDeadLetter(any(), eq(RuleExecutionFailure.SECURITY_REJECTED));
        RecordMetadata source = sendUplink(fixture, messageId, Map.of("temperature", 26.5));
        assertThat(awaitTopicRecord(PROCESSED_TOPIC, messageId).key()).isEqualTo(fixture.deviceId().toString());
        assertThat(awaitTopicRecord(SOURCE_DLQ_TOPIC, messageId).key()).isEqualTo(fixture.deviceId().toString());
        awaitRecordsConsumed(List.of(source));
        assertThat(failedSends).hasValue(1);
        assertReceipt(fixture, messageId, "IN_PROGRESS");
        assertNoActionsOrSuccess(fixture, messageId);
        assertThat(inScopeCount(fixture, "SELECT count(*) FROM rule_execution_log WHERE message_id=?", messageId)).isZero();
    }

    /** 在真实processed ACK后的原检查点提交归档，不替换项目许可或动作结果。 */
    private AtomicInteger freezeAfterProcessedAck(Fixture fixture, UUID messageId) {
        AtomicInteger acks = new AtomicInteger();
        String identity = fixture.tenantId() + ":" + fixture.projectId() + ":" + messageId + ":1";
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            acks.incrementAndGet();
            updateProject(fixture, "ARCHIVED");
            return invocation.callRealMethod();
        }).when(faultCheckpoint).reach(eq(FaultInjectionCheckpoint.Checkpoint.RULE_AFTER_CONTINUATION_ACK), eq(identity));
        return acks;
    }

    /** 归档/恢复仅修改本例项目；不伪造OWNER删除或任何新恢复API。 */
    private void updateProject(Fixture fixture, String status) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement statement = owner.prepareStatement(
                "UPDATE sys_project SET status=? WHERE id=?")) {
            statement.setString(1, status); statement.setObject(2, fixture.projectId());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /** 不完整状态和无SUCCESS是独立断言，不以来源消费完成替代数据库状态。 */
    private void assertReceipt(Fixture fixture, UUID messageId, String status) {
        assertThat(inScopeCount(fixture, "SELECT count(*) FROM rule_execution_receipt WHERE message_id=? AND status=?", messageId, status)).isEqualTo(1);
    }

    /** 本通知动作及关联投递不得新增；执行追踪不能先写SUCCESS再写拒绝。 */
    private void assertNoActionsOrSuccess(Fixture fixture, UUID messageId) {
        withScope(fixture, () -> {
            for (String table : List.of("sys_outbox_event", "rule_notification_delivery", "rule_device_action_delivery"))
                assertThat(count("SELECT count(*) FROM " + table + " WHERE project_id=?", fixture.projectId())).as(table).isZero();
            assertThat(count("SELECT count(*) FROM rule_execution_log WHERE message_id=? AND status='SUCCESS'", messageId)).isZero();
        });
    }

    /** 有界停原listener；成功断言已验证消费位点，失败也释放专库上下文。 */
    @AfterEach
    void cleanup() throws InterruptedException {
        try {
            if (activeListener != null) {
                CountDownLatch stopped = new CountDownLatch(1);
                activeListener.stop(stopped::countDown);
                assertThat(stopped.await(CHAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            }
        } finally { TenantContext.clear(); }
    }

    /** 版本夹具所有owner写必须落专库，不能沿基类共享PG地址。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, PROBE_POSTGRES.getUsername(), PROBE_POSTGRES.getPassword());
    }

    /** 容器交给本类OwnedTestContainers回收。 */
    private static String startDatabase() { PROBE_POSTGRES.start(); return PROBE_POSTGRES.getJdbcUrl(); }
    /** 专Broker仅供本类生产listener与探针。 */
    private static String startKafka() { PROBE_KAFKA.start(); return PROBE_KAFKA.getBootstrapServers(); }
    /** 使用Registrar覆盖继承属性，保持原生产池和错误处理预算。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedConfiguration {
        /** 不启动其他listener或外发，全局扫描器已显式隔离。 */
        @Bean
        DynamicPropertyRegistrar isolatedProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("spring.kafka.bootstrap-servers", () -> KAFKA_SERVERS);
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("spring.kafka.admin.auto-create", () -> "false");
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
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
                    new CreateMessageRuleCommand("rule-" + shortId(), null, source, List.of(new ActionSpec(
                            "notification-action", new ObjectMapper().createObjectNode()
                            .put("channel", "email").put("recipient", "ops@example.com")
                            .put("subject", "冻结动作").put("body", "动作必须可靠拒绝")))));
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
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_SERVERS,
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

    /** 等待规则执行日志进入目标状态；status 取 Status 枚举名（SUCCESS / DEAD_LETTER / RETRY_SCHEDULED）。 */
    private void awaitExecutionLog(Fixture fixture, UUID messageId, String status) {
        awaitCondition("执行日志未进入 " + status + " messageId=" + messageId, () -> inScopeCount(fixture,
                "SELECT count(*) FROM rule_execution_log WHERE message_id = ? AND status = ?",
                messageId, status) > 0);
    }

    /** 根据真实Broker中的完整消息身份定位续接或恢复记录；不使用只看旧数据库行的完成谓词。 */
    private ConsumerRecord<String, String> awaitTopicRecord(String topic, UUID messageId) {
        try (KafkaConsumer<String, String> consumer = dlqConsumer()) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(CHAIN_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    if (record.value().contains(messageId.toString())) return record;
                }
            }
            throw new AssertionError("目标消息未进入 " + topic);
        }
    }

    /** @return 从 earliest 读取的字符串消费者，避免与生产消费组共享 offset。 */
    private KafkaConsumer<String, String> dlqConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_SERVERS);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "s8-2d-dlq-probe-" + Uuid7.generate());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
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
