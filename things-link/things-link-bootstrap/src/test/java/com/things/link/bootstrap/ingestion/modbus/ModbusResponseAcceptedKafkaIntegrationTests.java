package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.device.application.ModbusPollService;
import com.things.link.device.application.ThingModelVersionBindingService;
import com.things.link.ingestion.infrastructure.ModbusResponseKafkaConsumer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.observability.OutboxMetrics;
import com.things.link.support.outbox.KafkaTransactionalOutboxPublisher;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR0063：无外层事务的真实consumer接纳，与随后真实Kafka交付分开核验。
 * 独占真实PG和Kafka，复用Redis及手动轮询夹具；全局Outbox领取不得混入其他测试的待发事件。
 */
@Import({ModbusResponseAcceptedKafkaIntegrationTests.KafkaTopics.class,
        ModbusResponseAcceptedKafkaIntegrationTests.IsolatedDatabaseConfiguration.class})
@OwnedTestContainers({"ACCEPTANCE_POSTGRES", "KAFKA"})
class ModbusResponseAcceptedKafkaIntegrationTests extends AbstractModbusPollIntegrationTest {

    /** 全局领取不能只靠随机项目隔离，专库名称用于核验连接实际落点。 */
    private static final String DATABASE_NAME = "modbus_accepted_" + UUID.randomUUID().toString().replace("-", "");

    /** PostgreSQL角色为集群级；沿用相同镜像但独占容器，避免专库迁移污染共享角色。 */
    private static final PostgreSQLContainer<?> ACCEPTANCE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());

    /** 固定实际随机端口，Spring运行时、Flyway和所有owner夹具必须共用这一地址。 */
    private static final String DATABASE_URL = startIsolatedDatabase();

    /** 本类不经REST准备夹具；抑制父类直连共享数据库的配额放宽runner，避免跨库写入。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;

    /** 与已接受ADR的固定目标一致，不能把normalized意外投往配置主题。 */
    private static final String TOPIC = "tc.device.uplink.normalized";
    /** 独立查询业务接纳新增事件，排除已有config/request事件干扰。 */
    private static final String EVENT_TYPE = "DEVICE_MODBUS_NORMALIZED";
    /** 与实际点位类型一致的合法物模型快照。 */
    private static final String MODEL_SNAPSHOT = """
            {"properties":{"temperature_0":{"dataType":"NUMBER","accessType":"REPORT"}},"events":{},"commands":{}}
            """;
    /** 独占broker沿用已验证的JVM镜像；由OwnedTestContainers在本类上下文物理关闭后回收，不触碰开发服务。 */
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        KAFKA.start();
    }

    /** 消费入口负责校验真实网关key并委托事务接纳。 */
    @Autowired private ModbusResponseKafkaConsumer responseConsumer;
    /** 由生产扫描创建在途requestId，不直接插入poll状态。 */
    @Autowired private ModbusPollService pollService;
    /** 版本变更后再次使用真实解析证明当前事实已改变。 */
    @Autowired private ThingModelVersionBindingService versionBindingService;
    /** 真实数据库租约、确认与重试实现。 */
    @Autowired private TransactionalOutboxRepository repository;
    /** 恢复交付必须经过真实serializer和broker ACK。 */
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    /** 手动发布器保留生产低基数指标。 */
    @Autowired private OutboxMetrics metrics;
    /** 读取持久JSON与broker原始字节，比较完整信封。 */
    @Autowired private ObjectMapper mapper;

    /** 完整重申随机容器地址并打开建topic；所有业务listener及自动publisher仍关闭。 */
    @DynamicPropertySource
    static void kafkaAcceptanceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.flyway.url", () -> DATABASE_URL);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> "true");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("things-link.outbox.publisher.enabled", () -> "false");
    }

    /** 专库先于Spring/Flyway启动，退出后由Testcontainers回收，不连接开发数据库。 */
    private static String startIsolatedDatabase() {
        ACCEPTANCE_POSTGRES.start();
        return ACCEPTANCE_POSTGRES.getJdbcUrl();
    }

    /** 在业务夹具准备前核验控制面、数据面和owner实际数据库及权限，禁止依赖动态属性声明猜测隔离。 */
    @BeforeEach
    void verifyDatabaseIsolationBeforeFixtureWrites() throws SQLException {
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                Map<String, Object> identity = jdbcTemplate.queryForMap("SELECT current_database(), current_user");
                assertThat(identity.get("current_database")).isEqualTo(DATABASE_NAME);
                assertThat(identity.get("current_user")).isEqualTo(APP_ROLE);
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = current_user
                        """, Boolean.class)).isTrue();
            }
        }
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("SELECT current_database(), current_user")) {
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
                assertThat(rows.getString(2)).isEqualTo(ACCEPTANCE_POSTGRES.getUsername());
            }
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event"))
                    .as("专库每例开始时全局Outbox必须为空，不能让上例或其他测试参与单次领取").isZero();
        }
    }

    /** Modbus基类播种和AfterEach清理动态调用本入口，均限定本类专库。 */
    @Override
    protected Connection ownerConnection() throws SQLException {
        return fixtureOwnerConnection();
    }

    /** 物模型初始版本和升级历史必须与业务服务写入同一专库，不能沿用父类静态共享地址。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, ACCEPTANCE_POSTGRES.getUsername(), ACCEPTANCE_POSTGRES.getPassword());
    }

    /** 全局领取测试必须连接专库；项目随机身份不能隔离其他测试遗留的合法待发事件。 */
    @Test
    void usesDedicatedDatabaseForGlobalClaims() throws SQLException {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(DATABASE_NAME).isNotEqualTo(POSTGRES.getDatabaseName());
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("SELECT current_database()")) {
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
            }
        }
    }

    /** 先证明未来事件合法不可领取，再等待数据库真实资格并以单次发布取得两种路由的broker字节。 */
    @Test
    void waitsForDatabaseAvailabilityBeforePublishingBothFixtureEvents() throws Exception {
        Fixture fixture = prepareInFlight();
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, """
                    UPDATE sys_outbox_event SET available_at = clock_timestamp() + interval '60 seconds'
                     WHERE project_id = ?
                    """, fixture.projectId())).isEqualTo(2);
            assertThat(number(owner, """
                    SELECT count(*) FROM sys_outbox_event
                     WHERE project_id = ? AND available_at > clock_timestamp()
                    """, fixture.projectId())).isEqualTo(2);
        }
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> untouchedTemplate = mock(KafkaTemplate.class);
        publishOnce(untouchedTemplate);
        verifyNoInteractions(untouchedTemplate);
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, """
                    SELECT count(*) FROM sys_outbox_event WHERE project_id = ?
                       AND published_at IS NULL AND attempt_count = 0 AND lease_token IS NULL
                       AND leased_until IS NULL AND available_at > clock_timestamp()
                    """, fixture.projectId())).isEqualTo(2);
        }

        try (KafkaConsumer<String, byte[]> observer = consumerAtEnd(List.of("tc.device.config", "tc.device.modbus.request"))) {
            // 只推进本例退避到仍在未来的短窗口，helper必须只读等待，不得改时间或反复发布。
            try (Connection owner = ownerConnection()) {
                assertThat(execute(owner, """
                        UPDATE sys_outbox_event SET available_at = clock_timestamp() + interval '200 milliseconds'
                         WHERE project_id = ?
                        """, fixture.projectId())).isEqualTo(2);
                assertThat(number(owner, """
                        SELECT count(*) FROM sys_outbox_event
                         WHERE project_id = ? AND available_at > clock_timestamp()
                        """, fixture.projectId())).isEqualTo(2);
            }
            publish(kafkaTemplate, fixture);
            assertConfigAndRequestBytes(observer, fixture);
            try (Connection owner = ownerConnection()) {
                assertThat(number(owner, """
                        SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND published_at IS NOT NULL
                        """, fixture.projectId())).isEqualTo(2);
            }
        }
    }

    /** 重复响应仅留下一个完整持久事件，手动发布后实际字节保留子设备key、requestId及原时间。 */
    @Test
    void acceptsDuplicateOnceAndPublishesCommittedEnvelopeToRealKafka() throws Exception {
        Fixture fixture = inFlight();
        ModbusResponse response = response(fixture);
        try (KafkaConsumer<String, byte[]> observer = consumerAtEnd()) {
            consumeWithoutTransaction(response);
            consumeWithoutTransaction(response);
            EventState accepted = accepted(fixture, response);
            assertThat(accepted.published()).isFalse();
            publish(kafkaTemplate, fixture);
            assertEnvelope(ownRecord(observer, fixture), fixture, response);
            assertThat(event(fixture).published()).isTrue();
            consumeWithoutTransaction(response);
            publish(kafkaTemplate, fixture);
            assertThat(event(fixture).id()).isEqualTo(accepted.id());
            assertThat(observer.poll(Duration.ofMillis(500))).noneMatch(record -> fixture.subDeviceId().toString().equals(record.key()));
        }
    }

    /** 接纳后明确发送失败只登记持久重试，恢复必须从真实Kafka读到原始信封才算交付成功。 */
    @Test
    void retainsAcceptedResponseAcrossSendFailureAndRecoversThroughRealKafka() throws Exception {
        Fixture fixture = inFlight();
        ModbusResponse response = response(fixture);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> failOnce = mock(KafkaTemplate.class);
        AtomicInteger attempts = new AtomicInteger();
        when(failOnce.send(eq(TOPIC), eq(fixture.subDeviceId().toString()), any())).thenAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                return CompletableFuture.failedFuture(new IllegalStateException("D-122已接纳后明确发送失败"));
            }
            return kafkaTemplate.send(TOPIC, fixture.subDeviceId().toString(), invocation.getArgument(2));
        });
        try (KafkaConsumer<String, byte[]> observer = consumerAtEnd()) {
            consumeWithoutTransaction(response);
            EventState accepted = accepted(fixture, response);
            publish(failOnce, fixture);
            EventState failed = event(fixture);
            assertThat(failed.id()).isEqualTo(accepted.id());
            assertThat(failed.published()).isFalse();
            assertThat(failed.attempts()).isEqualTo(1);
            assertThat(failed.lastError()).isEqualTo("ExecutionException");
            assertThat(failed.leased()).isFalse();
            assertThat(failed.payload()).isEqualTo(accepted.payload());
            assertThat(observer.poll(Duration.ofMillis(500))).noneMatch(record -> fixture.subDeviceId().toString().equals(record.key()));
            consumeWithoutTransaction(response);
            assertThat(accepted(fixture, response)).isEqualTo(failed);
            makeReady(accepted.id());
            publish(failOnce, fixture);
            assertEnvelope(ownRecord(observer, fixture), fixture, response);
            assertThat(event(fixture).published()).isTrue();
            assertThat(event(fixture).payload()).isEqualTo(accepted.payload());
            publish(failOnce, fixture);
            assertThat(attempts.get()).isEqualTo(2);
        }
    }

    /** 合法追加同类型UPGRADE历史并切换版本后，发布重试不能重新解释已经接纳的1.0.0。 */
    @Test
    void publishesAcceptedVersionAfterCurrentBindingActuallyChanges() throws Exception {
        Fixture fixture = inFlight();
        ModbusResponse response = response(fixture);
        try (KafkaConsumer<String, byte[]> observer = consumerAtEnd()) {
            consumeWithoutTransaction(response);
            EventState accepted = accepted(fixture, response);
            seedNonInitialTransition(fixture.tenantId(), fixture.projectId(), typeId(fixture), fixture.subDeviceId(), MODEL_SNAPSHOT);
            String current = scopedApplicationCall(fixture, () -> versionBindingService
                    .resolveCurrentVersionForPlatformGenerated(
                            fixture.tenantId(), fixture.projectId(), fixture.subDeviceId()));
            assertThat(current).isEqualTo("2.0.0");
            publish(kafkaTemplate, fixture);
            assertEnvelope(ownRecord(observer, fixture), fixture, response);
            assertThat(event(fixture).payload()).isEqualTo(accepted.payload());
            assertThat(event(fixture).published()).isTrue();
        }
    }

    /** 在线网关与初始版本是真实事实；单独准备事件，使资格测试能在首次领取前控制本例时间。 */
    private Fixture prepareInFlight() throws Exception {
        Fixture fixture = fixture(points(1));
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_device SET status = 'ONLINE' WHERE id = ?", fixture.gatewayId())).isEqualTo(1);
        }
        seedThingModelVersion(fixture.tenantId(), fixture.projectId(), typeId(fixture), fixture.subDeviceId(), MODEL_SNAPSHOT);
        pushConfig(fixture);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            assertThat(pollService.scanDue(100)).isPositive();
        }
        return fixture;
    }

    /** 先确认旧config/request事件后才测试normalized故障，避免前置路由干扰批次。 */
    private Fixture inFlight() throws Exception {
        Fixture fixture = prepareInFlight();
        // 两种旧路由有不同lane，先真实发布且逐行确认，避免它们占用normalized测试的批次或stripe。
        publish(kafkaTemplate, fixture);
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ?", fixture.projectId())).isEqualTo(2);
            assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND published_at IS NOT NULL",
                    fixture.projectId())).withFailMessage("单次发布后两个前置事件必须都已确认；本例状态=%s", fixtureOutboxStates(fixture))
                    .isEqualTo(2);
        }
        return fixture;
    }

    /** 失败仅输出调度身份和状态；足以区分未就绪、stripe拒绝与发送故障，不暴露设备payload。 */
    private List<Map<String, Object>> fixtureOutboxStates(Fixture fixture) throws SQLException {
        List<Map<String, Object>> states = new ArrayList<>();
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("""
                SELECT id, destination_topic, published_at, attempt_count, last_error, available_at,
                       lease_token, leased_until FROM sys_outbox_event WHERE project_id = ? ORDER BY id
                """)) {
            parameters(query, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> state = new LinkedHashMap<>();
                    for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                        state.put(rows.getMetaData().getColumnLabel(column), rows.getObject(column));
                    }
                    states.add(state);
                }
            }
        }
        return states;
    }

    /** 使用真实关联requestId；寄存器按夹具FLOAT32/LITTLE_ENDIAN解码缩放为-2.25。 */
    private ModbusResponse response(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT request_id FROM dev_modbus_poll WHERE project_id = ? AND status = 'IN_FLIGHT'")) {
            parameters(query, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new ModbusResponse(rows.getObject(1, UUID.class), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(),
                        ModbusResponse.Status.SUCCESS, List.of(0, 0x3f80), null,
                        Instant.parse("2026-08-20T01:02:03.123456Z"), "0123456789abcdef0123456789abcdef");
            }
        }
    }

    /** 与生产监听线程一致，只有DATA路由没有外层事务；不借测试事务掩盖提交边界。 */
    private void consumeWithoutTransaction(ModbusResponse response) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            responseConsumer.consume(new ConsumerRecord<>(ModbusResponseKafkaConsumer.MODBUS_RESPONSE_TOPIC,
                    0, 0L, response.gatewayId().toString(), response));
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    /** 独立owner可见IDLE和唯一事件才证明已提交，版本和信封从持久payload读取。 */
    private EventState accepted(Fixture fixture, ModbusResponse response) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND status = 'IDLE' AND request_id IS NULL",
                    fixture.projectId())).isEqualTo(1);
        }
        EventState state = event(fixture);
        assertThat(mapper.readValue(state.payload(), StandardUplinkMessage.class)).isEqualTo(expected(fixture, response));
        return state;
    }

    /** 一次读取必须恰有一行，覆盖重复接纳不能产生第二个持久事件。 */
    private EventState event(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("""
                SELECT id, published_at IS NOT NULL, attempt_count, last_error, lease_token IS NOT NULL, payload
                  FROM sys_outbox_event WHERE project_id = ? AND event_type = ?
                """)) {
            parameters(query, fixture.projectId(), EVENT_TYPE);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                EventState state = new EventState(rows.getObject(1, UUID.class), rows.getBoolean(2), rows.getInt(3),
                        rows.getString(4), rows.getBoolean(5), rows.getString(6));
                assertThat(rows.next()).isFalse();
                return state;
            }
        }
    }

    /** 不更改任何事件内容，只提前本例可重试时刻，避免用墙钟sleep等待退避。 */
    private void makeReady(UUID eventId) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE sys_outbox_event SET available_at = now() - interval '1 second' WHERE id = ?", eventId)).isEqualTo(1);
        }
    }

    /** 查询真实类型保证初始与升级快照均满足同项目、同类型复合外键。 */
    private UUID typeId(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT device_type_id FROM dev_device WHERE id = ?")) {
            parameters(query, fixture.subDeviceId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    /** 仅等待本例待发时间达到数据库领取条件，然后恰好扫描一次；发布错误由调用方原断言立即观察。 */
    private void publish(KafkaTemplate<String, Object> template, Fixture fixture) throws Exception {
        awaitDatabaseReadiness(fixture);
        publishOnce(template);
    }

    /**
     * available_at来自应用时钟、领取按PG时钟；测试不得假设两者在立即扫描时已经满足大小关系。
     * 五秒是测试前置等待上界，活跃租约立即失败；不改事件、不重试发布、不吞发送或仓储错误。
     */
    private void awaitDatabaseReadiness(Fixture fixture) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("""
                SELECT count(*),
                       count(*) FILTER (WHERE published_at IS NULL AND leased_until > clock_timestamp()),
                       count(*) FILTER (WHERE published_at IS NULL AND available_at > clock_timestamp())
                  FROM sys_outbox_event WHERE project_id = ?
                """)) {
            parameters(query, fixture.projectId());
            while (true) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).as("自己的config/request事实必须仍在，不能靠空集误过就绪检查").isGreaterThanOrEqualTo(2);
                    assertThat(rows.getLong(2)).as("手动发布前不得出现意外活跃租约").isZero();
                    if (rows.getLong(3) == 0) return;
                    assertThat(System.nanoTime()).as("五秒内本例available_at必须达到数据库墙钟").isLessThan(deadline);
                }
                Thread.sleep(10);
            }
        }
    }

    /** 每次显式销毁等待stripe完成，确认及重试断言不会抢在后台提交之前。 */
    private void publishOnce(KafkaTemplate<String, Object> template) throws Exception {
        KafkaTransactionalOutboxPublisher publisher = new KafkaTransactionalOutboxPublisher(repository, template, metrics, mapper, 8, 30);
        try {
            publisher.publishReadyEvents();
        } finally {
            publisher.destroy();
        }
    }

    /** 显式分配全部分区并从末尾开始，只观察本例独占key。 */
    private KafkaConsumer<String, byte[]> consumerAtEnd() {
        return consumerAtEnd(List.of(TOPIC));
    }

    /** 前置资格回归也读取config/request实际字节，仍只使用本例独占broker。 */
    private KafkaConsumer<String, byte[]> consumerAtEnd(List<String> topics) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "modbus-accepted-" + Uuid7.generate());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        KafkaConsumer<String, byte[]> observer = new KafkaConsumer<>(properties);
        List<TopicPartition> partitions = topics.stream().flatMap(topic -> observer.partitionsFor(topic).stream()
                .map(partition -> new TopicPartition(topic, partition.partition()))).toList();
        observer.assign(partitions);
        observer.seekToEnd(partitions);
        partitions.forEach(observer::position);
        return observer;
    }

    /** 两个固定前置topic的完整JSON必须与已持久化载荷相同，不能只把数据库标记当网络交付。 */
    private void assertConfigAndRequestBytes(KafkaConsumer<String, byte[]> observer, Fixture fixture) throws SQLException {
        Map<String, String> expectedPayloads = new HashMap<>();
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("""
                SELECT destination_topic, payload FROM sys_outbox_event WHERE project_id = ?
                """)) {
            parameters(query, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) expectedPayloads.put(rows.getString(1), rows.getString(2));
            }
        }
        assertThat(expectedPayloads.keySet()).containsExactlyInAnyOrder("tc.device.config", "tc.device.modbus.request");
        Set<String> receivedTopics = new HashSet<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (receivedTopics.size() < 2 && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : observer.poll(Duration.ofMillis(100))) {
                if (!fixture.gatewayId().toString().equals(record.key())) continue;
                assertThat(expectedPayloads).containsKey(record.topic());
                assertThat(mapper.readTree(record.value())).isEqualTo(mapper.readTree(expectedPayloads.get(record.topic())));
                receivedTopics.add(record.topic());
            }
        }
        assertThat(receivedTopics).containsExactlyInAnyOrderElementsOf(expectedPayloads.keySet());
    }

    /** 有限等待broker真实字节，不把mock Future成功或模板调用意图记为交付。 */
    private ConsumerRecord<String, byte[]> ownRecord(KafkaConsumer<String, byte[]> observer, Fixture fixture) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, byte[]> record : observer.poll(Duration.ofMillis(250))) {
                if (fixture.subDeviceId().toString().equals(record.key())) return record;
            }
        }
        throw new AssertionError("真实Kafka没有返回当前接纳响应的normalized字节");
    }

    /** 全信封比较同时钉住稳定版本、消息号、两个原始时间、trace及解码数值。 */
    private void assertEnvelope(ConsumerRecord<String, byte[]> record, Fixture fixture, ModbusResponse response) {
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(fixture.subDeviceId().toString());
        assertThat(mapper.readValue(record.value(), StandardUplinkMessage.class)).isEqualTo(expected(fixture, response));
        assertThat(record.headers().lastHeader("traceId").value()).isEqualTo(response.traceId().getBytes(StandardCharsets.UTF_8));
    }

    /** 期望信封独立按ADR0063字段合同构造，不复用待测接管实现。 */
    private StandardUplinkMessage expected(Fixture fixture, ModbusResponse response) {
        return new StandardUplinkMessage(response.requestId(), fixture.tenantId(), fixture.projectId(), fixture.subDeviceId(),
                fixture.gatewayId(), TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", response.receivedAt(), response.receivedAt(),
                response.traceId(), 0, Map.of("temperature_0", -2.25d));
    }

    /** 专库URL在父类静态动态属性之后注册，避免Spring属性合并顺序把连接退回共享库。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** Registrar在普通单例创建前覆盖两个入口；应用角色和迁移owner凭据仍沿用标准配置。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
            };
        }
    }

    /** 只在本测试独占broker建立三个固定路由，业务消费者保持关闭。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTopics {
        /** 三分区足以观察子设备key，无需沿用生产容量。 */
        @Bean
        NewTopic acceptedNormalizedTopic() {
            return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
        }
        /** 真正确认前置配置事件，避免伪造旧Outbox已发布状态。 */
        @Bean
        NewTopic acceptedConfigTopic() {
            return TopicBuilder.name("tc.device.config").partitions(3).replicas(1).build();
        }
        /** 真正确认前置读请求，normalized故障测试才不会混入其他路由。 */
        @Bean
        NewTopic acceptedModbusRequestTopic() {
            return TopicBuilder.name("tc.device.modbus.request").partitions(3).replicas(1).build();
        }
    }

    /** @param id 持久事件 @param published 已确认 @param attempts 失败次数 @param lastError 脱敏错误 @param leased 租约状态 @param payload 冻结JSON */
    private record EventState(UUID id, boolean published, int attempts, String lastError, boolean leased, String payload) { }
}
