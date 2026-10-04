package com.things.link.bootstrap.ingestion.property;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.KafkaRecordConsumptionBarrier;

import com.things.link.device.application.DeviceIngestionContext;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.device.domain.ThingModelVersionRepository.BindingTransition.TransitionType;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.telemetry.application.ProjectIngestionRejectedException;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.infrastructure.UplinkPayloadPrecisionDeserializer;
import io.micrometer.core.instrument.MeterRegistry;
import com.things.link.testing.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0065 / S12-P0-5e1d：真实processed消费保留首次拒绝、幂等重放及DLQ确认后的精确offset边界。
 * 独占PG/Kafka保留原服务和错误处理器；唯一故障注入是目标DLQ首次发送结果失败，不冒充网络或ACL故障。
 */
@Import(PropertyIngestionProjectLifecycleKafkaTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"PROBE_POSTGRES", "PROBE_KAFKA"})
class PropertyIngestionProjectLifecycleKafkaTests extends AbstractIntegrationTest {
    /** 摘要只输出资格、异常类型、SQLSTATE和表计数，不打印设备载荷或账号数据。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PropertyIngestionProjectLifecycleKafkaTests.class);
    /** D-109要求物理隔离；唯一project不足以隔离缓存上下文的全局领取者。 */
    private static final String DATABASE_NAME = "telemetry_lifecycle_kafka_" + UUID.randomUUID().toString().replace("-", "");
    /** 与共享验收同镜像同owner；独立容器保护全局角色及所有后台数据库对象。 */
    private static final PostgreSQLContainer<?> PROBE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Flyway、CONTROL、DATA及owner观察必须共同使用专库地址。 */
    private static final String DATABASE_URL = startDatabase();
    /** 独占真实Broker避免固定生产group被其他缓存上下文接管；不连接开发Kafka。 */
    private static final KafkaContainer PROBE_KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))
            .withStartupTimeout(Duration.ofMinutes(2));
    /** Spring模板、原listener与独立Admin均使用此临时Broker。 */
    private static final String KAFKA_SERVERS = startKafka();
    /** 直接验证最终摄入续接，不启动raw/normalized或规则worker消费链。 */
    private static final String PROCESSED_TOPIC = "tc.device.uplink.processed";
    /** 公共错误处理器的原恢复目标，不使用规则专属tc.rule.dlq。 */
    private static final String DLQ_TOPIC = "tc.dlq";
    /** 必须观察生产group的提交位点，随机探针group不能证明源消费完成。 */
    private static final String PROCESSED_GROUP = "things-link-ingestion-processed";
    /** 包含冷consumer分配和原200ms两次重试，所有等待以事实而非固定sleep成功。 */
    private static final Duration KAFKA_TIMEOUT = Duration.ofSeconds(30);
    /** 本类Broker在六例间复用，记录首次创建的topicId，禁止重建主题或重置生产group位点。 */
    private static final Map<String, Uuid> TOPIC_IDENTITIES = new ConcurrentHashMap<>();
    /** 同一合法属性快照用于两版，差异仅为真实绑定资格，避免属性校验错误掩盖生命周期结果。 */
    private static final String SNAPSHOT = "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\","
            + "\"accessType\":\"REPORT\",\"minimum\":-40,\"maximum\":125}},\"events\":{},\"commands\":{}}";
    /**
     * 完整行比较包含时间戳、revision和载荷，不能只凭数量相等声称未修改。
     * V20260808_1000把属性点同名对象变为显式app_current_project过滤视图，owner也不能绕过WHERE；
     * 取证读取真实internal物理表，其余五项均为原物理表（消息日志没有同名视图门面）。
     */
    private static final List<String> FACT_TABLES = List.of("sys_inbox_message", "ts_property_point_internal",
            "ts_device_message_log", "sys_message_log_inbox", "ts_property_aggregate_backfill", "dev_shadow");
    /** 父runner直接连共享库，专库探针必须抑制它。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;
    /** 不允许自动领取通知；生产摄入和告警服务仍全部真实。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationWorkCoordinator;
    /** 回补事实是观察对象，自动领取/刷新会污染完整行比较。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedAggregateBackfillScanner;
    /** 本片不验证任务后台；禁止无关调度自动访问专库。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskSchedulingScanner;
    /** 被测端口必须通过原Spring代理自行开启事务，测试外部没有TransactionTemplate。 */
    @MockitoSpyBean private PropertyIngestionService ingestion;
    /** 资格验证调用真实版本化设备端口，排除无效版本或错误身份造成假通过。 */
    @Autowired private DeviceIngestionService devices;
    /** UPGRADE必须通过原绑定事务追加不可变历史并CAS指针。 */
    @Autowired private ThingModelVersionBindingService bindings;
    /** 删除必须经过真实OWNER管理与原事务提交。 */
    @Autowired private ProjectService projects;
    /** spy只记录真实日额度异常，绝不替换返回值或绕过事务代理。 */
    @MockitoSpyBean private ProjectDailyQuotaDecisionService dailyQuota;
    /** 核对被测事务实际使用APP_ROLE与正确物理库。 */
    @Autowired private JdbcTemplate jdbc;
    /** 明确检查Flyway连接而非仅检查继承配置的字符串。 */
    @Autowired private Environment environment;
    /** Boot 4.1原@Bean声明KafkaTemplate<?, ?>；按原名及声明类型包装，避免spy严格泛型匹配误判Bean缺失。 */
    @MockitoSpyBean(name = "kafkaTemplate") private KafkaTemplate<?, ?> kafkaTemplate;
    /** 成功DLQ计数必须等待Broker ACK，发送失败不能提前增长。 */
    @Autowired private MeterRegistry meterRegistry;
    /** CURRENT基线沿真实提交后行为写Redis热键，清理只能删除本例精确属性键。 */
    @Autowired private StringRedisTemplate redis;
    /** 每例只持有并停止processed原容器，禁止影响其他缓存上下文的监听器。 */
    private MessageListenerContainer activeListener;
    /** 从注册中心只启动processed原容器，保留其真实factory/error handler。 */
    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
    /** 从DLQ原始JSON恢复完整冻结信封，避免只按key误认其他消息。 */
    @Autowired private ObjectMapper mapper;
    /** 每个展开用例均有独占身份，历史事实保留至容器回收。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 原额度结果仅用于ACTIVE前置；冻结首次拒绝必须在额度调用前停止。 */
    private final List<String> quotaObservations = new CopyOnWriteArrayList<>();
    /** 真实转换时间截断到PG精度，receivedAt使用该时间之后一秒且不超过十分钟。 */
    private Instant transitionAt;

    /** 先验证真实落点再建种子；不调用既有PropertyIngestionTests的全库删除夹具。 */
    @BeforeEach
    void prepare() throws Exception {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(listenerRegistry.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
        try (AdminClient admin = kafkaAdmin()) { createTopicsAndAwaitMetadata(admin); }
        for (Object disabled : List.of(unusedRestQuotaRelaxation, unusedNotificationWorkCoordinator,
                unusedAggregateBackfillScanner, unusedTaskSchedulingScanner)) {
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        }
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection migration = DriverManager.getConnection(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
             Connection owner = fixtureOwnerConnection()) {
            verifyOwnerIdentity(migration);
            verifyOwnerIdentity(owner);
            // 数据库目录实证观察对象为物理表，防止后续迁移再引入视图而静默把不可见行当零。
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement("SELECT relkind::text FROM pg_class WHERE oid=?::regclass")) {
                    query.setString(1, table);
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).as("事实观察对象必须是物理表: %s", table).isIn("r", "p");
                    }
                }
            }
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '遥测冻结探针租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '探针OWNER')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '遥测冻结探针', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "telemetry_probe_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'probe_type', '探针类型', 'STANDARD', 'DIRECT', 'PUBLISHED')",
                    fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, 'probe_device', '探针设备', 'ONLINE')",
                    fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) VALUES (?, ?, ?, ?, 'temperature', '温度', 'REPORT', 'NUMBER')",
                    Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.commit();
        }
        // 基类版本种子通过下方override owner连接落专库；从不修改不可变版本或历史行。
        seedThingModelVersion(fixture.tenantId(), fixture.projectId(), fixture.typeId(), fixture.deviceId(), SNAPSHOT);
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, """
                    INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,
                        version_number,version_major,version_minor,version_patch,change_level,schema_profile,
                        model_snapshot,schema_digest,digest_algorithm)
                    VALUES (?, ?, ?, ?, '2.0.0',2,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',?::jsonb,
                        encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')
                    """, fixture.versionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), SNAPSHOT, SNAPSHOT);
        }
        transitionAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        asOwner(() -> bindings.bind(fixture.projectId(), fixture.deviceId(), fixture.versionId(),
                Uuid7.generate(), TransitionType.UPGRADE, transitionAt));
        // 只给代理后面的真实spy打探针；业务依旧经过外层@Transactional，原异常才能标记rollback-only。
        assertThat(AopUtils.isAopProxy(dailyQuota)).isTrue();
        ProjectDailyQuotaDecisionService quotaTarget = AopTestUtils.getUltimateTargetObject(dailyQuota);
        assertThat(mockingDetails(quotaTarget).isSpy()).isTrue();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            try {
                ProjectDailyQuotaDecisionService.Decision result =
                        (ProjectDailyQuotaDecisionService.Decision) invocation.callRealMethod();
                quotaObservations.add(invocation.getArgument(2) + "=" + result.status());
                return result;
            } catch (RuntimeException failure) {
                quotaObservations.add(invocation.getArgument(2) + "=" + failureSummary(failure));
                throw failure;
            }
        }).when(quotaTarget).decisionTrustedProject(eq(fixture.tenantId()), eq(fixture.projectId()), any());
    }

    /** 两种合法版本在两种冻结状态下均由原处理器尝试三次后确认DLQ，首次拒绝不进入额度。 */
    @ParameterizedTest
    @CsvSource({"CURRENT,DELETE", "HISTORY_ONLY,DELETE", "CURRENT,ARCHIVED", "HISTORY_ONLY,ARCHIVED"})
    void frozenFirstMessageUsesOriginalRetriesAndConfirmedDlq(VersionCase version, Freeze freeze) throws Exception {
        assertActiveBaseline(version);
        freeze(freeze);
        Map<String, List<String>> before = facts();
        quotaObservations.clear();
        StandardUplinkMessage message = message(version, false);
        IngestionObservation observation = observeIngestion(message.messageId());
        double dlqBefore = successfulDlqCount();
        try (AdminClient admin = kafkaAdmin(); KafkaConsumer<String, String> dlq = dlqConsumer()) {
            prepareDlqReader(dlq);
            startProcessedListener();
            RecordMetadata source = sendProcessed(message);
            ConsumerRecord<String, String> recovered = awaitExactDeadLetter(dlq, message.messageId());
            assertDeadLetter(recovered, message, source, ProjectIngestionRejectedException.class);
            awaitSourceCommitted(admin, source);
            assertThat(observation.calls()).hasValue(3);
            assertThat(observation.repeats()).hasValue(0);
            assertThat(observation.failures()).hasSize(3)
                    .allSatisfy(value -> assertThat(value).isEqualTo("ProjectIngestionRejectedException"));
            assertThat(quotaObservations).isEmpty();
            assertThat(successfulDlqCount()).isEqualTo(dlqBefore + 1);
            assertThat(facts()).isEqualTo(before);
            logRecovery("FIRST_REJECTED", source, recovered, observation.calls().get(), admin);
        }
    }

    /** 冻结后已成功的原消息仍false/正常ACK；同项目换payload保留原冲突并一次直接DLQ，不混同首次许可拒绝。 */
    @Test
    void frozenReplayKeepsOriginalNoOpAndPayloadConflict() throws Exception {
        StandardUplinkMessage accepted = assertActiveBaseline(VersionCase.HISTORY_ONLY);
        freeze(Freeze.DELETE);
        Map<String, List<String>> before = facts();
        quotaObservations.clear();
        IngestionObservation observation = observeIngestion(accepted.messageId());
        double dlqBefore = successfulDlqCount();
        try (AdminClient admin = kafkaAdmin(); KafkaConsumer<String, String> dlq = dlqConsumer()) {
            prepareDlqReader(dlq);
            long dlqEndBefore = dlq.endOffsets(List.of(new TopicPartition(DLQ_TOPIC, 0))).get(new TopicPartition(DLQ_TOPIC, 0));
            startProcessedListener();
            RecordMetadata replay = sendProcessed(accepted);
            awaitSourceCommitted(admin, replay);
            assertThat(observation.calls()).hasValue(1);
            assertThat(observation.repeats()).hasValue(1);
            assertThat(observation.failures()).isEmpty();
            assertThat(successfulDlqCount()).isEqualTo(dlqBefore);
            assertThat(dlq.endOffsets(List.of(new TopicPartition(DLQ_TOPIC, 0))).get(new TopicPartition(DLQ_TOPIC, 0))).isEqualTo(dlqEndBefore);
            assertThat(facts()).isEqualTo(before);

            StandardUplinkMessage changed = new StandardUplinkMessage(accepted.messageId(), accepted.tenantId(), accepted.projectId(),
                    accepted.deviceId(), accepted.gatewayId(), accepted.protocol(), accepted.direction(), accepted.type(), accepted.modelVersion(),
                    accepted.occurredAt(), accepted.receivedAt(), accepted.traceId(), accepted.rawBytes(), Map.of("temperature", new BigDecimal("27.5")));
            RecordMetadata conflict = sendProcessed(changed);
            ConsumerRecord<String, String> recovered = awaitExactDeadLetter(dlq, changed.messageId());
            assertDeadLetter(recovered, changed, conflict, InvalidUplinkMessageException.class);
            awaitSourceCommitted(admin, conflict);
            assertThat(observation.calls()).hasValue(2);
            assertThat(observation.repeats()).hasValue(1);
            assertThat(observation.failures()).containsExactly("BusinessException[30059]");
            assertThat(quotaObservations).isEmpty();
            assertThat(successfulDlqCount()).isEqualTo(dlqBefore + 1);
            assertThat(facts()).isEqualTo(before);
            logRecovery("REPLAY_CONFLICT", conflict, recovered, observation.calls().get(), admin);
        }
    }

    /**
     * 只注入目标DLQ首个发送结果Future失败，第二次恢复发送前暂停；不是Broker断网或ACL故障。
     * 第二次入口证明原handler确实经历失败恢复；放行后调用原KafkaTemplate并等真实ACK，禁止伪造成功Future。
     */
    @Test
    void failedDlqSendResultKeepsSourceUncommittedUntilRealRecovery() throws Exception {
        assertActiveBaseline(VersionCase.HISTORY_ONLY);
        freeze(Freeze.DELETE);
        Map<String, List<String>> before = facts();
        quotaObservations.clear();
        StandardUplinkMessage message = message(VersionCase.HISTORY_ONLY, false);
        IngestionObservation observation = observeIngestion(message.messageId());
        assertProcessedRoundTripMatchesFixture(message);
        AtomicInteger dlqSends = new AtomicInteger();
        AtomicReference<RecordMetadata> exactSource = new AtomicReference<>();
        CountDownLatch secondRecovery = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        KafkaTemplate<Object, Object> target = AopTestUtils.getUltimateTargetObject(messageTemplate());
        assertThat(mockingDetails(target).isSpy()).isTrue();
        doAnswer(invocation -> {
            int attempt = dlqSends.incrementAndGet();
            if (attempt == 1) {
                return CompletableFuture.<SendResult<Object, Object>>failedFuture(
                        new IllegalStateException("仅本例目标DLQ发送结果失败"));
            }
            if (attempt == 2) {
                secondRecovery.countDown();
                if (!releaseRecovery.await(KAFKA_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待本例DLQ恢复放行超时");
                }
            }
            return invocation.callRealMethod();
        }).when(target).send(argThat((ProducerRecord<Object, Object> record) -> matchesExactDlq(record, message, exactSource.get())));
        double dlqBefore = successfulDlqCount();
        try (AdminClient admin = kafkaAdmin(); KafkaConsumer<String, String> dlq = dlqConsumer()) {
            prepareDlqReader(dlq);
            long dlqEndBefore = dlq.endOffsets(List.of(new TopicPartition(DLQ_TOPIC, 0))).get(new TopicPartition(DLQ_TOPIC, 0));
            RecordMetadata source = sendProcessed(message);
            exactSource.set(source);
            // 先冻结真实源坐标，再启动原consumer，注入范围不依赖异步赋值恰好跑赢DLQ。
            startProcessedListener();
            try {
                assertThat(secondRecovery.await(KAFKA_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("原handler必须再次进入DLQ恢复").isTrue();
                assertThat(dlqSends).hasValue(2);
                OffsetAndMetadata committed = committedOffsets(admin).get(new TopicPartition(source.topic(), source.partition()));
                assertThat(committed == null || committed.offset() <= source.offset()).as("失败恢复期间不得越过本条源记录").isTrue();
                assertThat(successfulDlqCount()).isEqualTo(dlqBefore);
                assertThat(dlq.endOffsets(List.of(new TopicPartition(DLQ_TOPIC, 0))).get(new TopicPartition(DLQ_TOPIC, 0))).isEqualTo(dlqEndBefore);
                assertThat(facts()).isEqualTo(before);
                releaseRecovery.countDown();
                ConsumerRecord<String, String> recovered = awaitExactDeadLetter(dlq, message.messageId());
                assertDeadLetter(recovered, message, source, ProjectIngestionRejectedException.class);
                awaitSourceCommitted(admin, source);
                assertThat(dlqSends).hasValue(2);
                // 恢复器失败可重置原退避状态；只要求全部尝试均原异常，不硬编码总业务尝试次数。
                assertThat(observation.calls().get()).isGreaterThanOrEqualTo(3);
                assertThat(observation.failures()).hasSize(observation.calls().get())
                        .allSatisfy(value -> assertThat(value).isEqualTo("ProjectIngestionRejectedException"));
                assertThat(quotaObservations).isEmpty();
                assertThat(successfulDlqCount()).isEqualTo(dlqBefore + 1);
                assertThat(facts()).isEqualTo(before);
                logRecovery("DLQ_SEND_RESULT_RECOVERED", source, recovered, observation.calls().get(), admin);
            } finally { releaseRecovery.countDown(); }
        } finally { releaseRecovery.countDown(); }
    }

    /**
     * 故障注入前先验证真实Kafka编解码后的完整信封相等；数值类型漂移必须立即失败，不能等恢复超时。
     * S12-4f精确小数解码是生产合同，不将匹配器放宽为只比较messageId或忽略payload。
     */
    private void assertProcessedRoundTripMatchesFixture(StandardUplinkMessage message) {
        assertThat(environment.getRequiredProperty("spring.kafka.consumer.value-deserializer"))
                .isEqualTo(UplinkPayloadPrecisionDeserializer.class.getName());
        try (var serializer = new JsonSerializer<Object>(); var decoder = new UplinkPayloadPrecisionDeserializer()) {
            decoder.configure(Map.of(JsonDeserializer.TRUSTED_PACKAGES, "com.things.link.shared.message"), false);
            var headers = new RecordHeaders();
            StandardUplinkMessage decoded = (StandardUplinkMessage) decoder.deserialize(PROCESSED_TOPIC, headers,
                    serializer.serialize(PROCESSED_TOPIC, headers, message));
            assertThat(decoded).as("故障注入夹具必须与真实Kafka解码后的完整信封一致；temperature类型 %s -> %s",
                    message.payload().get("temperature").getClass().getSimpleName(),
                    decoded.payload().get("temperature").getClass().getSimpleName()).isEqualTo(message);
        }
    }

    /** 只匹配本例原消息及其精确来源；其他ProducerRecord沿spy默认真实发送，不得到失败替身。 */
    private boolean matchesExactDlq(ProducerRecord<Object, Object> record, StandardUplinkMessage message, RecordMetadata source) {
        if (record == null || source == null || !DLQ_TOPIC.equals(record.topic()) || !message.equals(record.value())
                || !message.deviceId().toString().equals(record.key())) return false;
        var topic = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        var partition = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION);
        var offset = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET);
        var group = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
        return topic != null && partition != null && offset != null && group != null
                && source.topic().equals(new String(topic.value(), StandardCharsets.UTF_8))
                && partition.value().length == Integer.BYTES && ByteBuffer.wrap(partition.value()).getInt() == source.partition()
                && offset.value().length == Long.BYTES && ByteBuffer.wrap(offset.value()).getLong() == source.offset()
                && PROCESSED_GROUP.equals(new String(group.value(), StandardCharsets.UTF_8));
    }

    /** 在原服务target观察而不绕过其外层事务；按唯一messageId隔离每个Kafka重试及重放。 */
    private IngestionObservation observeIngestion(UUID messageId) {
        IngestionObservation observation = new IngestionObservation(new AtomicInteger(), new AtomicInteger(), new CopyOnWriteArrayList<>());
        assertThat(AopUtils.isAopProxy(ingestion)).isTrue();
        PropertyIngestionService target = AopTestUtils.getUltimateTargetObject(ingestion);
        assertThat(mockingDetails(target).isSpy()).isTrue();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            observation.calls().incrementAndGet();
            try {
                Object result = invocation.callRealMethod();
                if (Boolean.FALSE.equals(result)) observation.repeats().incrementAndGet();
                return result;
            } catch (RuntimeException failure) {
                observation.failures().add(failure instanceof com.things.link.shared.error.BusinessException business
                        ? "BusinessException[" + business.errorCode().code() + "]" : failureSummary(failure));
                throw failure;
            }
        }).when(target).ingest(argThat((StandardUplinkMessage value) -> value != null && value.messageId().equals(messageId)));
        return observation;
    }

    /** 保留原processed工厂、并发、ack和错误处理器，只显式启动该group；所有其他listener必须停止。 */
    private void startProcessedListener() {
        assertThat(listenerRegistry.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
        List<MessageListenerContainer> selected = listenerRegistry.getListenerContainers().stream()
                .filter(container -> PROCESSED_GROUP.equals(container.getGroupId())).toList();
        assertThat(selected).hasSize(1);
        activeListener = selected.getFirst();
        assertThat(activeListener.getContainerProperties().getTopics()).containsExactly(PROCESSED_TOPIC);
        assertThat(activeListener.getContainerProperties().getAckMode())
                .isEqualTo(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        activeListener.start();
        assertThat(listenerRegistry.getListenerContainers()).filteredOn(MessageListenerContainer::isRunning).containsExactly(activeListener);
    }

    /** 真实模板等待Broker ACK后才记录源坐标，不将fixture messageId代替Kafka offset。 */
    private RecordMetadata sendProcessed(StandardUplinkMessage message) throws Exception {
        return messageTemplate().send(PROCESSED_TOPIC, message.deviceId().toString(), message)
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    /** Boot原实例实际由ProducerFactory<Object,Object>创建；仅恢复该泛型视图，绝不新建或替换生产模板。 */
    @SuppressWarnings("unchecked")
    private KafkaTemplate<Object, Object> messageTemplate() {
        return (KafkaTemplate<Object, Object>) kafkaTemplate;
    }

    /** 用精确源坐标等待原group提交，旧业务回执或DLQ可见不能单独代替此屏障。 */
    private void awaitSourceCommitted(AdminClient admin, RecordMetadata source) {
        new KafkaRecordConsumptionBarrier(KAFKA_TIMEOUT, () -> committedOffsets(admin)).awaitConsumed(List.of(source));
    }

    /** 原生探针只读专属DLQ分区，关闭自动提交，不与生产group共用位点。 */
    private void prepareDlqReader(KafkaConsumer<String, String> consumer) {
        TopicPartition partition = new TopicPartition(DLQ_TOPIC, 0);
        consumer.assign(List.of(partition));
        consumer.seekToBeginning(List.of(partition));
    }

    /** 完整载荷及原来源头共同匹配，统一DLQ中的异构或旧记录不能冒充本次恢复。 */
    private void assertDeadLetter(ConsumerRecord<String, String> record, StandardUplinkMessage message,
                                  RecordMetadata source, Class<? extends RuntimeException> cause) {
        assertThat(record.key()).isEqualTo(message.deviceId().toString());
        // 只对DLQ信封启用精确小数，与processed解码一致；不能用默认Double读取掩盖类型漂移。
        StandardUplinkMessage recovered = mapper.readerFor(StandardUplinkMessage.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(record.value());
        assertThat(recovered).isEqualTo(message);
        assertThat(headerText(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(source.topic());
        assertThat(ByteBuffer.wrap(headerBytes(record, KafkaHeaders.DLT_ORIGINAL_PARTITION)).getInt()).isEqualTo(source.partition());
        assertThat(ByteBuffer.wrap(headerBytes(record, KafkaHeaders.DLT_ORIGINAL_OFFSET)).getLong()).isEqualTo(source.offset());
        assertThat(headerText(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo(PROCESSED_GROUP);
        assertThat(headerText(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isEqualTo(cause.getName());
    }

    /** 成功计数只观察processed来源；恢复器尚未首次成功时没有meter等于零而非失败。 */
    private double successfulDlqCount() {
        var counter = meterRegistry.find("thingslink.ingestion.dlq.messages").tag("source_topic", PROCESSED_TOPIC).counter();
        return counter == null ? 0D : counter.count();
    }

    /** 只记录精确坐标与次数，不打印载荷、账号或敏感异常头。 */
    private void logRecovery(String outcome, RecordMetadata source, ConsumerRecord<?, ?> recovered, int attempts, AdminClient admin) {
        LOGGER.info("TELEMETRY_LIFECYCLE_KAFKA outcome={} source={}-{}@{} dlq={}-{}@{} attempts={} committed={} unchanged=true",
                outcome, source.topic(), source.partition(), source.offset(), recovered.topic(), recovered.partition(), recovered.offset(),
                attempts, committedOffsets(admin).get(new TopicPartition(source.topic(), source.partition())).offset());
    }

    /** 独占Broker只建两个必要主题；创建响应不足以证明metadata已传播，须核对本轮topicId、分区与leader。 */
    private void createTopicsAndAwaitMetadata(AdminClient admin) throws Exception {
        Map<String, Integer> partitions = Map.of(PROCESSED_TOPIC, 4, DLQ_TOPIC, 1);
        if (TOPIC_IDENTITIES.isEmpty()) {
            var created = admin.createTopics(partitions.entrySet().stream()
                    .map(entry -> new NewTopic(entry.getKey(), entry.getValue(), (short) 1)).toList());
            created.all().get(10, TimeUnit.SECONDS);
            for (String topic : partitions.keySet()) TOPIC_IDENTITIES.put(topic, created.topicId(topic).get(10, TimeUnit.SECONDS));
        }
        Map<String, Uuid> identities = Map.copyOf(TOPIC_IDENTITIES);
        long deadline = System.nanoTime() + KAFKA_TIMEOUT.toNanos();
        // 同support包私有KafkaTopicMetadataBarrier的判据；本类不复制其测试到生产或跨模块暴露测试API。
        try (AdminClient observer = kafkaAdmin()) {
            while (System.nanoTime() < deadline) {
                try {
                    var descriptions = observer.describeTopics(partitions.keySet()).allTopicNames().get(2, TimeUnit.SECONDS);
                    boolean ready = partitions.entrySet().stream().allMatch(entry -> {
                        var description = descriptions.get(entry.getKey());
                        return description != null && identities.get(entry.getKey()).equals(description.topicId())
                                && description.partitions().size() == entry.getValue()
                                && description.partitions().stream().allMatch(partition -> partition.leader() != null && !partition.leader().isEmpty());
                    });
                    if (ready) return;
                } catch (java.util.concurrent.ExecutionException failure) {
                    // 仅容忍新建metadata传播的明确状态，网络/授权失败不能吞成继续等待。
                    if (!(failure.getCause() instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException)
                            && !(failure.getCause() instanceof org.apache.kafka.common.errors.LeaderNotAvailableException)) throw failure;
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("专库探针Kafka主题metadata未在期限内就绪");
    }

    /** 单次查询限时，Admin不加入生产consumer group，也不修改其offset。 */
    private AdminClient kafkaAdmin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_SERVERS,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000));
    }

    /** 与既有KafkaRecordConsumptionBarrier配合；缺失位点或查询失败不得当作消费成功。 */
    private Map<TopicPartition, OffsetAndMetadata> committedOffsets(AdminClient admin) {
        try {
            return admin.listConsumerGroupOffsets(PROCESSED_GROUP).partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取processed消费位点被中断", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("读取processed消费位点失败", failure);
        }
    }

    /** 字符串探针保留原JSON和异常headers，关闭自动提交，不干涉源group。 */
    private KafkaConsumer<String, String> dlqConsumer() {
        return new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_SERVERS,
                ConsumerConfig.GROUP_ID_CONFIG, "telemetry-freeze-dlq-probe-" + Uuid7.generate(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000, ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000));
    }

    /** 只接受本例messageId，不以DLQ队首或设备key替代精确消息身份。 */
    private ConsumerRecord<String, String> awaitExactDeadLetter(KafkaConsumer<String, String> consumer, UUID messageId) {
        long deadline = System.nanoTime() + KAFKA_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                if (messageId.toString().equals(mapper.readTree(record.value()).path("messageId").asString())) return record;
            }
        }
        throw new AssertionError("本条processed记录未在期限内进入tc.dlq: " + messageId);
    }

    /** Kafka标准原offset/partition头为网络字节序，缺失头必须失败而非默认为零。 */
    private byte[] headerBytes(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        assertThat(header).as("DLQ必须保留%s", name).isNotNull();
        return header.value();
    }

    /** 标准字符串头按UTF8核对原topic/group与异常分类。 */
    private String headerText(ConsumerRecord<?, ?> record, String name) {
        return new String(headerBytes(record, name), StandardCharsets.UTF_8);
    }

    /** 八日前合法点真实产生回补事实，冻结探针随后逐字段保护该非空基线。 */
    private StandardUplinkMessage assertActiveBaseline(VersionCase version) throws Exception {
        StandardUplinkMessage message = message(version, true);
        DeviceIngestionContext context;
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            context = devices.validateReportedProperties(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    message.modelVersion(), message.receivedAt(), message.payload());
        }
        assertThat(context.eligibility().name()).isEqualTo(version.name());
        assertThat(context.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(ingest(message)).isTrue();
        Map<String, List<String>> committed = facts();
        for (String table : FACT_TABLES.subList(0, FACT_TABLES.size() - 1)) assertThat(committed.get(table)).as(table).hasSize(1);
        assertThat(committed.get("dev_shadow")).hasSize(version == VersionCase.CURRENT ? 1 : 0);
        assertThat(quotaObservations).hasSize(3).allSatisfy(value -> assertThat(value).endsWith("=NORMAL"));
        LOGGER.info("TELEMETRY_FREEZE_CONTROL version={} eligibility={} counts={} quota={}",
                version, context.eligibility(), counts(committed), quotaObservations);
        return message;
    }

    /** 原数据面服务自行开启事务；不得用测试外层事务改变REQUIRED参与者的rollback-only结果。 */
    private boolean ingest(StandardUplinkMessage message) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return ingestion.ingest(message);
        }
    }

    /** receivedAt位于真实UPGRADE之后一秒；冻结探针使用新鲜点，避免七日前回补专属拒绝掩盖额度事务结果。 */
    private StandardUplinkMessage message(VersionCase version, boolean baseline) {
        // S12-4f标准信封使用精确小数；Double夹具会让DLQ完整equals匹配失效，故障根本没有注入。
        Instant receivedAt = transitionAt.plusSeconds(1);
        return new StandardUplinkMessage(Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(), null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT,
                version == VersionCase.CURRENT ? "2.0.0" : "1.0.0",
                baseline ? receivedAt.minus(8, ChronoUnit.DAYS) : receivedAt.minusSeconds(1),
                receivedAt, "telemetry-freeze-probe", 32, Map.of("temperature", new BigDecimal(baseline ? "18.5" : "26.5")));
    }

    /** DELETE经过真实OWNER提交，ARCHIVED仅使用已有状态字段的明确SQL夹具，不编造归档API。 */
    private void freeze(Freeze freeze) throws SQLException {
        if (freeze == Freeze.DELETE) asOwner(() -> projects.delete(fixture.projectId()));
        else try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT status,deleted_at IS NOT NULL FROM sys_project WHERE id=?")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(freeze == Freeze.DELETE ? "DELETING" : "ARCHIVED");
                assertThat(rows.getBoolean(2)).isEqualTo(freeze == Freeze.DELETE);
            }
        }
    }

    /** 只有建模和删除以真实OWNER身份进入生产代理；摄入始终无控制台身份。 */
    private void asOwner(Runnable action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { action.run(); } finally { TenantContext.clear(); }
    }

    /** 独立owner读取所有原始字段，排序只服务稳定比较，不更改数据。 */
    private Map<String, List<String>> facts() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=? ORDER BY row_to_json(f)::text")) {
                    query.setObject(1, fixture.projectId());
                    List<String> values = new ArrayList<>();
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) values.add(rows.getString(1)); }
                    result.put(table, List.copyOf(values));
                }
            }
        }
        return result;
    }

    /** 日志只保留每张表的计数，失败断言仍比较完整行。 */
    private static Map<String, Integer> counts(Map<String, List<String>> facts) {
        Map<String, Integer> result = new LinkedHashMap<>();
        facts.forEach((table, rows) -> result.put(table, rows.size()));
        return result;
    }

    /** 异常链仅保留类名和SQLSTATE；UnexpectedRollback不携带原额度异常时由quotaObservations补齐。 */
    private static String failureSummary(Throwable failure) {
        if (failure == null) return "NONE";
        List<String> chain = new ArrayList<>();
        for (Throwable item = failure; item != null; item = item.getCause()) {
            chain.add(item.getClass().getSimpleName() + (item instanceof SQLException sql ? "[SQLSTATE=" + sql.getSQLState() + "]" : ""));
        }
        return String.join("->", chain);
    }

    /** Flyway及owner连接实际数据库、角色都必须匹配专库；仅URL字符串相同不足以证明。 */
    private void verifyOwnerIdentity(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT current_database(),current_user"); ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
            assertThat(rows.getString(2)).isEqualTo(PROBE_POSTGRES.getUsername());
        }
    }

    /** 先有界停止原listener；失败也清本例Redis热键及上下文，不可变专库事实留到容器回收。 */
    @AfterEach
    void cleanupContextOnly() throws InterruptedException {
        try {
            if (activeListener != null) {
                CountDownLatch stopped = new CountDownLatch(1);
                activeListener.stop(stopped::countDown);
                assertThat(stopped.await(KAFKA_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("原processed容器必须完全停止后结束夹具").isTrue();
                assertThat(activeListener.isRunning()).isFalse();
            }
            assertThat(listenerRegistry.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
        } finally {
            try { redis.delete("things-link:shadow:{" + fixture.projectId() + "}:" + fixture.deviceId() + ":temperature"); }
            finally { TenantContext.clear(); }
        }
    }

    /** 覆盖基类版本夹具连接，杜绝在共享库插入设备版本。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, PROBE_POSTGRES.getUsername(), PROBE_POSTGRES.getPassword());
    }

    /** 参数化SQL仅作用于本例身份，固定表名不接收外部输入。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 容器先启动再提供Flyway属性，生命周期由本类OwnedTestContainers结束。 */
    private static String startDatabase() { PROBE_POSTGRES.start(); return PROBE_POSTGRES.getJdbcUrl(); }

    /** 只启动本类独占真实Broker；所有生产listener仍由auto-startup=false保持停止。 */
    private static String startKafka() { PROBE_KAFKA.start(); return PROBE_KAFKA.getBootstrapServers(); }

    /** 使用Registrar覆盖父类DynamicPropertySource，保持现有镜像、用户、密码及池预算。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 禁止无关外发、重试和Kafka listener；直接摄入链保持真实生产装配。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("spring.kafka.bootstrap-servers", () -> KAFKA_SERVERS);
            };
        }
    }

    /** @param calls 原事务方法尝试数 @param repeats 原方法返回false次数 @param failures 原异常及业务码 */
    private record IngestionObservation(AtomicInteger calls, AtomicInteger repeats, List<String> failures) { }

    /** 两条真实版本资格不能被模型省略兼容逻辑合并。 */
    private enum VersionCase {
        /** 当前2.0.0可推进影子。 */ CURRENT,
        /** 直接旧版1.0.0只写历史。 */ HISTORY_ONLY
    }

    /** 两种冻结状态都保留设备、版本及历史事实。 */
    private enum Freeze {
        /** 真实OWNER删除提交。 */ DELETE,
        /** 明确归档状态夹具。 */ ARCHIVED
    }

    /** 独占项目及不可变版本身份；不从tenant推定任何历史成员。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId, UUID deviceId, UUID versionId) { }
}
