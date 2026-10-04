package com.things.link.support.outbox;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.observability.OutboxMetrics;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR0063 §5–7：真实 PostgreSQL 持久事件经手动生产发布器进入真实 Kafka，重试复用冻结信封。
 * 首次明确失败可以模拟，但恢复成功必须由原生消费者读取 broker 字节证明。
 * 全局Outbox领取使用专用PostgreSQL集群，不能仅凭随机项目身份排除其他测试遗留的待发事件。
 */
@Import({ModbusNormalizedOutboxPublisherIntegrationTests.KafkaTopics.class,
        ModbusNormalizedOutboxPublisherIntegrationTests.IsolatedDatabaseConfiguration.class})
class ModbusNormalizedOutboxPublisherIntegrationTests extends AbstractKafkaIntegrationTest {

    /** 名称独立于共享库，用真实SQL验证控制面、数据面和owner均使用专库。 */
    private static final String DATABASE_NAME = "modbus_normalized_" + UUID.randomUUID().toString().replace("-", "");
    /** 角色属于整个集群；同镜像独占容器隔离全局领取与Flyway角色迁移，不改生产权限。 */
    private static final PostgreSQLContainer<?> NORMALIZED_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    /** Testcontainers随机端口统一供Flyway、应用数据源与owner观察使用。 */
    private static final String DATABASE_URL = startIsolatedDatabase();
    /** 查询实际数据源身份，防止仅配置了专库但运行时属性仍被父类覆盖。 */
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 冻结路由字符串独立于新增生产常量，确保旧实现以能力缺失而非编译错误失败。 */
    private static final String TOPIC = "tc.device.uplink.normalized";
    /** ADR0063 §5 的受控事件类型。 */
    private static final String EVENT_TYPE = "DEVICE_MODBUS_NORMALIZED";
    /** MANDATORY append 与租约管理均使用真实数据库实现。 */
    @Autowired private TransactionalOutboxRepository repository;
    /** 恢复阶段必须使用真实 Kafka 模板及 serializer。 */
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    /** 手动发布器保持生产低基数指标接线。 */
    @Autowired private OutboxMetrics metrics;
    /** 生产发布器沿用应用mapper；测试观察器另行显式启用精确数字。 */
    @Autowired private ObjectMapper mapper;
    /** append 所需的真实控制面事务。 */
    @Autowired private TransactionTemplate transactionTemplate;
    /** 每个实例只清理自己持久化的事件，失败用例不会污染下一次领取。 */
    private final List<UUID> eventIds = new ArrayList<>();

    /** 复用已有真实发布器测试的完整容器、JSON serializer 与禁用后台配置。 */
    @DynamicPropertySource
    static void publisherProperties(DynamicPropertyRegistry registry) {
        KafkaTransactionalOutboxPublisherIntegrationTests.outboxProperties(registry);
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.flyway.url", () -> DATABASE_URL);
    }

    /** 先启动专库再构建Spring上下文；测试退出时由Testcontainers回收容器。 */
    private static String startIsolatedDatabase() {
        NORMALIZED_POSTGRES.start();
        return NORMALIZED_POSTGRES.getJdbcUrl();
    }

    /** 夹具写入前检查真实连接身份及全局Outbox空集，隔离错误或上一例清理失败必须立即暴露。 */
    @BeforeEach
    void verifyIsolatedDatabaseBeforeWritingFixture() throws Exception {
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                Map<String, Object> identity = jdbcTemplate.queryForMap("SELECT current_database(), current_user");
                assertThat(identity.get("current_database")).isEqualTo(DATABASE_NAME)
                        .isNotEqualTo(POSTGRES.getDatabaseName());
                assertThat(identity.get("current_user")).isEqualTo(APP_ROLE);
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = current_user
                        """, Boolean.class)).isTrue();
            }
        }
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT current_database(), current_user, (SELECT count(*) FROM sys_outbox_event)
                """)) {
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
                assertThat(rows.getString(2)).isEqualTo(NORMALIZED_POSTGRES.getUsername());
                assertThat(rows.getLong(3)).as("每例开始时专库全局Outbox必须为空，不能混入其他测试事件").isZero();
            }
        }
    }

    /** 正常发布必须保留完整信封、子设备key及原响应时间，ACK后才确认数据库行。 */
    @Test
    void publishesFrozenNormalizedBytesWithIdentityVersionAndOriginalTimes() throws Exception {
        Fixture fixture = fixture();
        OutboxEvent event = event(fixture, null);
        try (KafkaConsumer<String, byte[]> consumer = consumerAtEnd()) {
            append(fixture, event);
            publish(kafkaTemplate);
            ConsumerRecord<String, byte[]> record = ownRecord(consumer, event.partitionKey());
            assertEnvelope(record, fixture.message());
            assertThat(record.headers().lastHeader("traceId").value())
                    .isEqualTo(fixture.message().traceId().getBytes(StandardCharsets.UTF_8));
            assertThat(state(event.id()).published()).isTrue();
            assertThat(state(event.id()).lastError()).isNull();
        }
    }

    /** 每例只破坏一个授权或必填轴；拒绝必须持久登记重试，且不得到达 broker。 */
    @ParameterizedTest(name = "非法 {0} 不发布且保留重试")
    @EnumSource(InvalidField.class)
    void rejectsInvalidIdentityAndRoutingBeforePublishing(InvalidField invalid) throws Exception {
        Fixture fixture = fixture();
        OutboxEvent event = event(fixture, invalid);
        try (KafkaConsumer<String, byte[]> consumer = consumerAtEnd()) {
            append(fixture, event);
            publish(kafkaTemplate);
            EventState state = state(event.id());
            assertThat(state.published()).isFalse();
            assertThat(state.attempts()).isEqualTo(1);
            assertThat(state.lastError()).isNotBlank();
            assertThat(state.leased()).isFalse();
            assertThat(state.payload()).isEqualTo(event.payload());
            assertThat(consumer.poll(Duration.ofMillis(500))).noneMatch(record ->
                    record.key().equals(event.partitionKey()) || record.key().equals(fixture.message().deviceId().toString()));
        }
    }

    /** 明确失败释放租约；恢复后读取真实broker内容，并证明发布重试不重建版本、消息号或时间。 */
    @Test
    void retriesAnExplicitSendFailureAndThenPublishesTheSamePayloadToRealKafka() throws Exception {
        Fixture fixture = fixture();
        OutboxEvent event = event(fixture, null);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> failOnce = mock(KafkaTemplate.class);
        AtomicInteger sends = new AtomicInteger();
        when(failOnce.send(eq(TOPIC), eq(event.partitionKey()), any())).thenAnswer(invocation -> {
            if (sends.getAndIncrement() == 0) {
                return CompletableFuture.failedFuture(new IllegalStateException("D-122明确发送故障"));
            }
            return kafkaTemplate.send(TOPIC, event.partitionKey(), invocation.getArgument(2));
        });
        try (KafkaConsumer<String, byte[]> consumer = consumerAtEnd()) {
            append(fixture, event);
            publish(failOnce);
            EventState failed = state(event.id());
            assertThat(failed.published()).isFalse();
            assertThat(failed.attempts()).isEqualTo(1);
            assertThat(failed.lastError()).isEqualTo("ExecutionException");
            assertThat(failed.leased()).isFalse();
            assertThat(failed.payload()).isEqualTo(event.payload());
            makeReady(event.id());

            // publish每次创建新实例，从真实持久事件恢复，不能复用内存解码结果。
            publish(failOnce);

            assertEnvelope(ownRecord(consumer, event.partitionKey()), fixture.message());
            EventState recovered = state(event.id());
            assertThat(recovered.published()).isTrue();
            assertThat(recovered.payload()).isEqualTo(event.payload());
            assertThat(sends.get()).isEqualTo(2);
            publish(failOnce);
            assertThat(sends.get()).as("已确认的数据库行不再被正常扫描重发").isEqualTo(2);
        }
    }

    /** 每轮销毁手动实例会等待已有stripe退出，数据库断言不会抢在确认事务之前执行。 */
    private void publish(KafkaTemplate<String, Object> template) throws Exception {
        KafkaTransactionalOutboxPublisher publisher = new KafkaTransactionalOutboxPublisher(repository, template, metrics, mapper, 8, 30);
        try {
            publisher.publishReadyEvents();
        } finally {
            publisher.destroy();
        }
    }

    /** payload 本身允许普通直连信封的空gateway/version，由新路由执行平台轮询专属约束。 */
    private OutboxEvent event(Fixture fixture, InvalidField invalid) {
        StandardUplinkMessage original = fixture.message();
        StandardUplinkMessage message = new StandardUplinkMessage(original.messageId(),
                invalid == InvalidField.TENANT ? Uuid7.generate() : original.tenantId(),
                invalid == InvalidField.PROJECT ? Uuid7.generate() : original.projectId(), original.deviceId(),
                invalid == InvalidField.GATEWAY ? null : original.gatewayId(), original.protocol(), original.direction(),
                original.type(), invalid == InvalidField.MODEL_VERSION ? null : original.modelVersion(),
                original.occurredAt(), original.receivedAt(), original.traceId(), original.rawBytes(), original.payload());
        // 本片验证路由与发送，不验证首次退避；固定过去时刻使PG立即可领取，避免JVM/容器时钟差干扰单次发布。
        // 消息内occurredAt/receivedAt仍保留原响应时间，不能以修改业务信封来消除调度时钟边界。
        return new OutboxEvent(Uuid7.generate(), original.tenantId(), original.projectId(),
                invalid == InvalidField.AGGREGATE_TYPE ? "DEVICE_COMMAND" : "DEVICE_MODBUS_RESULT",
                invalid == InvalidField.AGGREGATE_ID ? Uuid7.generate() : original.deviceId(), EVENT_TYPE,
                invalid == InvalidField.KEY ? Uuid7.generate().toString() : original.deviceId().toString(),
                mapper.writeValueAsString(message), original.traceId(), Instant.EPOCH);
    }

    /** 归属随机独占，时间与版本固定为明显非当前值以捕捉发布时重建。 */
    private Fixture fixture() {
        UUID tenant = Uuid7.generate();
        UUID project = Uuid7.generate();
        Instant originalTime = Instant.parse("2026-08-20T01:02:03.123456Z");
        StandardUplinkMessage message = new StandardUplinkMessage(Uuid7.generate(), tenant, project,
                Uuid7.generate(), Uuid7.generate(), TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "2.3.4", originalTime, originalTime,
                "0123456789abcdef0123456789abcdef", 0, Map.of("temperature_0", new BigDecimal("0.12345678901234567890123456789"),
                        "large", new BigInteger("9007199254740993"),
                        "nested", Map.of("values", List.of(new BigDecimal("123456789.1234567890123456789"),
                                new BigInteger("9223372036854775808123456789")))));
        return new Fixture(new TenantScope(tenant, project, Uuid7.generate()), message);
    }

    /** 范围先于借连接设置，真正执行 RLS 与 MANDATORY 事务检查。 */
    private void append(Fixture fixture, OutboxEvent event) {
        eventIds.add(event.id());
        TenantContext.set(fixture.scope());
        try {
            transactionTemplate.executeWithoutResult(ignored -> repository.append(event));
        } finally {
            TenantContext.clear();
        }
    }

    /** 直接分配所有分区并定位末尾，不依赖消费组加入耗时，也不读取历史记录。 */
    private KafkaConsumer<String, byte[]> consumerAtEnd() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "modbus-normalized-" + Uuid7.generate());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties);
        List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                .map(partition -> new TopicPartition(TOPIC, partition.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    /** 十秒内等待本例随机key，读取的是broker返回的字节而非模板调用参数。 */
    private ConsumerRecord<String, byte[]> ownRecord(KafkaConsumer<String, byte[]> consumer, String key) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                if (key.equals(record.key())) return record;
            }
        }
        throw new AssertionError("未从真实Kafka读到本例normalized消息");
    }

    /** 完整record比较覆盖三身份、版本、稳定messageId、两个原始时间和解码数值。 */
    private void assertEnvelope(ConsumerRecord<String, byte[]> record, StandardUplinkMessage expected) {
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(expected.deviceId().toString());
        StandardUplinkMessage actual = new ObjectMapper().readerFor(StandardUplinkMessage.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .readValue(record.value());
        assertThat(actual).isEqualTo(expected);
        // 单独检查关键数字，避免生产与观察端同时舍入后得到假相等。
        assertThat(actual.payload().get("temperature_0"))
                .isEqualTo(new BigDecimal("0.12345678901234567890123456789"));
        assertThat(actual.payload().get("large")).isEqualTo(new BigInteger("9007199254740993"));
    }

    /** owner只读本例事件状态，避免空APP范围把未发布误判为不存在。 */
    private EventState state(UUID eventId) throws Exception {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT published_at IS NOT NULL, attempt_count, last_error, lease_token IS NOT NULL, payload
                  FROM sys_outbox_event WHERE id = ?
                """)) {
            query.setObject(1, eventId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new EventState(rows.getBoolean(1), rows.getInt(2), rows.getString(3), rows.getBoolean(4), rows.getString(5));
            }
        }
    }

    /** 只提前本例一秒退避，不修改payload、版本或确认字段，不用sleep等墙钟。 */
    private void makeReady(UUID eventId) throws Exception {
        try (Connection owner = owner(); PreparedStatement statement = owner.prepareStatement(
                "UPDATE sys_outbox_event SET available_at = now() - interval '1 second' WHERE id = ?")) {
            statement.setObject(1, eventId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /** 独立owner连接仅服务本例只读观察、退避推进及清理。 */
    private Connection owner() throws Exception {
        return DriverManager.getConnection(DATABASE_URL, NORMALIZED_POSTGRES.getUsername(), NORMALIZED_POSTGRES.getPassword());
    }

    /** 失败反例也删除自己的待重试行，避免下一参数用例重复领取污染次数。 */
    @AfterEach
    void clearOwnEvents() throws Exception {
        TenantContext.clear();
        try (Connection owner = owner(); PreparedStatement delete = owner.prepareStatement("DELETE FROM sys_outbox_event WHERE id = ?")) {
            for (UUID eventId : eventIds) {
                delete.setObject(1, eventId);
                delete.executeUpdate();
            }
        }
    }

    /** 专库URL在继承的静态动态属性之后注册，保证两个Spring入口都不会退回共享数据库。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 保留标准应用角色、owner凭据与全部迁移，只在普通单例构建前替换数据库地址。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
            };
        }
    }

    /** 测试显式建受控主题，不依赖broker自动创建。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTopics {
        /** 三分区足够核验子设备稳定key，生产分区规模不在本片改变。 */
        @Bean
        org.apache.kafka.clients.admin.NewTopic modbusNormalizedTopic() {
            return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
        }
    }

    /** 每个非法维度独立变化，避免多个不符掩盖漏检。 */
    private enum InvalidField {
        /** 租户必须一致。 */ TENANT,
        /** 项目必须一致。 */ PROJECT,
        /** 聚合必须是子设备。 */ AGGREGATE_ID,
        /** key必须是子设备。 */ KEY,
        /** 平台轮询不能缺网关。 */ GATEWAY,
        /** 必须持久冻结版本。 */ MODEL_VERSION,
        /** 聚合类型必须属于平台轮询结果。 */ AGGREGATE_TYPE
    }

    /** @param scope 写入范围 @param message 完整冻结信封 */
    private record Fixture(TenantScope scope, StandardUplinkMessage message) { }

    /** @param published 已确认 @param attempts 失败次数 @param lastError 脱敏错误 @param leased 是否仍持租约 @param payload 原始载荷 */
    private record EventState(boolean published, int attempts, String lastError, boolean leased, String payload) { }
}
