package com.things.link.support.outbox;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import com.things.link.support.observability.OutboxMetrics;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL Outbox 与真实 Kafka 验证受控消息路由。
 *
 * <p>该类故意不启动生产下行消费者：它要观察的是 Outbox 发布器是否将已经提交的事实准确投到 Kafka，
 * 而不是调用 EMQX 或推进命令状态。测试 profile 默认关闭所有 listener；这里手工创建 KafkaConsumer
 * 读取字节，避免把测试行为建立在 listener 自动启动时机上。</p>
 */
@Import({KafkaTransactionalOutboxPublisherIntegrationTests.KafkaTopics.class,
        KafkaTransactionalOutboxPublisherIntegrationTests.IsolatedDatabaseConfiguration.class})
class KafkaTransactionalOutboxPublisherIntegrationTests extends AbstractKafkaIntegrationTest {

    /** 独占数据库名防止全局Outbox领取到其他测试类尚未确认或待重试的事件。 */
    private static final String DATABASE_NAME = "kafka_outbox_" + UUID.randomUUID().toString().replace("-", "");

    /** Outbox 公开写入与领取端口。 */
    @Autowired private TransactionalOutboxRepository outboxRepository;
    /** 与生产相同的 Kafka 模板，含幂等配置和 trace producer interceptor。 */
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    /** 发布结果指标门面；手工构造发布器仍要走生产的低基数观测路径。 */
    @Autowired private OutboxMetrics outboxMetrics;
    /** 只用于观察由真实数据库持久化的发布状态。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 让 append 与 RLS 配置在同一个物理连接的真实事务中执行。 */
    @Autowired private TransactionTemplate transactionTemplate;
    /** 共享 JSON 映射器，用于把 Kafka 字节还原为冻结的命令信封。 */
    @Autowired private ObjectMapper objectMapper;

    /**
     * 关闭自动发布器与全部 listener。
     *
     * <p>Outbox 调度配置直接使用 {@code @EnableScheduling}，不受 Boot 的 scheduling 属性开关控制。
     * 因此测试保持 publisher Bean 未创建，再手工构造并同步调用发布器，避免后台线程抢走本用例的
     * 真实数据库事件；listener 也必须关闭，以免下行消费者尝试调用 EMQX。</p>
     *
     * @param registry Spring 动态配置注册表
     */
    @DynamicPropertySource
    static void outboxProperties(DynamicPropertyRegistry registry) {
        // 子类额外声明动态属性时，Spring Test 不会合并祖先的同类回调；完整重申容器地址，
        // 否则 Flyway 会退回 support 测试环境中并不存在的 localhost 数据源。
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // SupportTestApplication 不加载 bootstrap 的 Kafka producer 基线；若省略 JSON serializer，
        // Boot 会回退 StringSerializer，真实消息对象会在 broker ACK 前因序列化失败而被重试。
        registry.add("spring.kafka.producer.key-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        registry.add("spring.kafka.producer.value-serializer",
                () -> "org.springframework.kafka.support.serializer.JsonSerializer");
        registry.add("spring.kafka.producer.acks", () -> "all");
        registry.add("spring.kafka.producer.properties.enable.idempotence", () -> "true");
        registry.add("things-link.outbox.publisher.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    /**
     * 每例写夹具前同时检查控制/数据连接池与owner确实落在专库，
     * 且全局Outbox没有遗留候选。
     * @throws Exception owner JDBC连接失败时交由测试框架报告
     */
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
                assertThat(rows.getString(2)).isEqualTo(IsolatedDatabase.POSTGRES.getUsername());
                assertThat(rows.getLong(3)).as("每例开始时专库全局Outbox必须为空").isZero();
            }
        }
    }

    /** 清理测试线程的项目范围，避免借出的下一条连接继承上一个项目。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /**
     * 命令事件只能进入固定 downlink topic，且必须保留路由 key、冻结 payload 和 traceId。
     *
     * @throws Exception Kafka consumer 或 JSON 解析失败时交由测试报告
     */
    @Test
    void publishesCommandOnlyToDownlinkWithStableKeyPayloadAndTrace() throws Exception {
        Fixture fixture = fixture();
        DeviceCommandDispatch dispatch = dispatch(fixture);
        OutboxEvent event = commandEvent(fixture, dispatch);

        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.subscribe(List.of(KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC));
            // 先完成 group join 并取得 latest offset，再追加事件，避免复用容器中的旧记录污染本断言。
            consumer.poll(Duration.ofSeconds(2));
            append(fixture.scope(), event);

            publishReadyEvents();

            List<ConsumerRecord<String, byte[]>> records = pollUntil(consumer, dispatch.connectionDeviceId().toString());
            ConsumerRecord<String, byte[]> record = records.stream()
                    .filter(value -> dispatch.connectionDeviceId().toString().equals(value.key()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未收到本测试 Outbox 事件"));
            DeviceCommandDispatch published = objectMapper.readValue(record.value(), DeviceCommandDispatch.class);

            assertThat(record.topic()).isEqualTo(KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC);
            assertThat(record.key()).isEqualTo(dispatch.connectionDeviceId().toString());
            assertThat(published).isEqualTo(dispatch);
            assertThat(record.headers().lastHeader("traceId").value())
                    .as("Kafka trace interceptor 必须把 Outbox 继承的链路 ID 带到消费者")
                    .isEqualTo(fixture.traceId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertThat(published(event.id(), fixture.scope())).isTrue();
    }

    /** 合格属性经真实Outbox和broker保留精确数字、原事件身份和设备key。 */
    @Test
    void publishesAcceptedAutomationPropertyWithExactInput() throws Exception {
        Fixture fixture = fixture();
        UUID deviceId = Uuid7.generate();
        var message = new AutomationPropertyAccepted(1, Uuid7.generate(), fixture.tenantId(), fixture.projectId(),
                deviceId, "1.0.0", Instant.now(), Instant.now(),
                Map.of("value", new java.math.BigDecimal("0.12345678901234567890123456789")), fixture.traceId());
        var event = new OutboxEvent(Uuid7.generate(), fixture.tenantId(), fixture.projectId(),
                AutomationPropertyAccepted.AGGREGATE_TYPE, deviceId, AutomationPropertyAccepted.EVENT_TYPE,
                deviceId.toString(), objectMapper.writeValueAsString(message), fixture.traceId(), Instant.now());
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.subscribe(List.of(AutomationPropertyAccepted.TOPIC));
            consumer.poll(Duration.ofSeconds(2));
            append(fixture.scope(), event);
            publishReadyEvents();
            var record = pollUntil(consumer, deviceId.toString()).stream()
                    .filter(value -> deviceId.toString().equals(value.key())).findFirst().orElseThrow();
            AutomationPropertyAccepted restored = objectMapper.readerFor(AutomationPropertyAccepted.class)
                    .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(record.value());
            assertThat(restored).isEqualTo(message);
            assertThat(record.topic()).isEqualTo(AutomationPropertyAccepted.TOPIC);
        }
        assertThat(published(event.id(), fixture.scope())).isTrue();
    }

    /**
     * 告警通知请求只能进入固定 notification topic，且使用投递意图 ID 保持同一意图的消费顺序。
     *
     * <p>共享消息契约刻意不携带收件地址和正文；本断言以字节反序列化后的完整信封为准，防止发布器
     * 将通知误投 downlink 或在路由时丢失幂等键、项目隔离轴及 trace。</p>
     *
     * @throws Exception Kafka consumer 或 JSON 解析失败时交由测试报告
     */
    @Test
    void publishesNotificationOnlyToNotificationTopicWithStableKeyPayloadAndTrace() throws Exception {
        Fixture fixture = fixture();
        NotificationDeliveryRequest request = notificationRequest(fixture);
        OutboxEvent event = notificationEvent(fixture, request);

        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.subscribe(List.of(KafkaTransactionalOutboxPublisher.NOTIFICATION_TOPIC));
            consumer.poll(Duration.ofSeconds(2));
            append(fixture.scope(), event);

            publishReadyEvents();

            List<ConsumerRecord<String, byte[]>> records = pollUntil(consumer, request.deliveryId().toString());
            ConsumerRecord<String, byte[]> record = records.stream()
                    .filter(value -> request.deliveryId().toString().equals(value.key()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未收到本测试通知 Outbox 事件"));
            NotificationDeliveryRequest published = objectMapper.readValue(record.value(), NotificationDeliveryRequest.class);

            assertThat(record.topic()).isEqualTo(KafkaTransactionalOutboxPublisher.NOTIFICATION_TOPIC);
            assertThat(record.key()).isEqualTo(request.deliveryId().toString());
            assertThat(published).isEqualTo(request);
            assertThat(record.headers().lastHeader("traceId").value())
                    .as("通知 Kafka 记录必须继承 Outbox 的链路 ID")
                    .isEqualTo(fixture.traceId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertThat(published(event.id(), fixture.scope())).isTrue();
    }

    /**
     * 任何未进入白名单的事件都不得被误投到任一受控 topic。
     *
     * <p>当前发布器会把它登记为可重试错误；该断言阻止新增 eventType 在未显式冻结路由前被当作
     * 命令或通知反序列化，或发送到默认 topic。</p>
     *
     * @throws Exception Kafka consumer 读取失败时交由测试报告
     */
    @Test
    void rejectsUnknownEventBeforeItCanEnterDurableOutbox() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> new OutboxEvent(
                Uuid7.generate(), fixture.tenantId(), fixture.projectId(),
                "TEST", Uuid7.generate(), "UNKNOWN_OUTBOX_EVENT", Uuid7.generate().toString(),
                "{\"eventId\":\"unknown\"}", fixture.traceId(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 Outbox 事件类型");
    }

    /**
     * 创建带项目隔离轴的最小完整夹具。
     *
     * @return 当前用例独占的租户、项目和 trace 信息
     */
    private static Fixture fixture() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        return new Fixture(tenantId, projectId, "0123456789abcdef0123456789abcdef", new TenantScope(
                tenantId, projectId, Uuid7.generate()));
    }

    /**
     * 创建实际连接设备就是 partition key 的下行命令；这是 S4-3 同连接设备顺序契约。
     *
     * @param fixture 当前项目夹具
     * @return 可被生产发布器反序列化的冻结消息
     */
    private static DeviceCommandDispatch dispatch(Fixture fixture) {
        UUID connectionDeviceId = Uuid7.generate();
        return new DeviceCommandDispatch(Uuid7.generate(), fixture.tenantId(), fixture.projectId(),
                Uuid7.generate(), Uuid7.generate(), 1, Uuid7.generate(), "target-device",
                connectionDeviceId, "connection-device", "project", "restart", "{}",
                Instant.parse("2026-08-09T00:01:00Z"), fixture.traceId());
    }

    /**
     * 将命令消息封装为待投递 Outbox 行。
     *
     * @param fixture 当前项目夹具
     * @param dispatch 冻结命令消息
     * @return 与命令连接设备同 key 的受控事件
     */
    private OutboxEvent commandEvent(Fixture fixture, DeviceCommandDispatch dispatch) {
        return new OutboxEvent(dispatch.eventId(), fixture.tenantId(), fixture.projectId(), "DEVICE_COMMAND",
                dispatch.commandId(), KafkaTransactionalOutboxPublisher.DEVICE_COMMAND_DISPATCH_EVENT,
                dispatch.connectionDeviceId().toString(), objectMapper.writeValueAsString(dispatch), fixture.traceId(),
                Instant.now());
    }

    /**
     * 创建只含受控索引字段的通知请求；敏感收件信息必须由消费者按 deliveryId 在项目范围内读取。
     *
     * @param fixture 当前项目夹具
     * @return 可被生产发布器反序列化的冻结消息
     */
    private static NotificationDeliveryRequest notificationRequest(Fixture fixture) {
        return new NotificationDeliveryRequest(Uuid7.generate(), fixture.tenantId(), fixture.projectId(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 1,
                Instant.parse("2026-08-09T00:02:00Z"), fixture.traceId());
    }

    /**
     * 将通知请求封装为与投递意图同 key 的 Outbox 行。
     *
     * @param fixture 当前项目夹具
     * @param request 冻结通知请求
     * @return 受控通知路由事件
     */
    private OutboxEvent notificationEvent(Fixture fixture, NotificationDeliveryRequest request) {
        return new OutboxEvent(request.eventId(), fixture.tenantId(), fixture.projectId(),
                "ALARM_NOTIFICATION_DELIVERY", request.deliveryId(),
                KafkaTransactionalOutboxPublisher.NOTIFICATION_DELIVERY_REQUEST_EVENT,
                request.deliveryId().toString(), objectMapper.writeValueAsString(request), fixture.traceId(), Instant.now());
    }

    /**
     * 构造一次性、同步驱动的生产发布器。
     *
     * <p>这里不能从 Spring 注入 publisher：其 {@code @Scheduled} 方法会在用例尚未开始观察 Kafka
     * 时并发领取同一行。构造参数与生产默认值完全一致，故仍覆盖真实 Outbox 路由与 Kafka ACK 边界。</p>
     *
     * @throws Exception 等待stripe退出失败时交由测试框架报告
     */
    private void publishReadyEvents() throws Exception {
        KafkaTransactionalOutboxPublisher publisher = new KafkaTransactionalOutboxPublisher(
                outboxRepository, kafkaTemplate, outboxMetrics, objectMapper, 8, 30);
        try {
            publisher.publishReadyEvents();
        } finally {
            // destroy会等待各stripe任务退出，避免下一例全局空集检查与上例确认事务竞争。
            publisher.destroy();
        }
    }

    /**
     * 在当前项目范围内写入 Outbox；append 的 MANDATORY 事务和 RLS 两项约束都在此处实际生效。
     *
     * @param scope 租户与项目范围
     * @param event 待发布事件
     */
    private void append(TenantScope scope, OutboxEvent event) {
        TenantContext.set(scope);
        try {
            transactionTemplate.executeWithoutResult(ignored -> outboxRepository.append(event));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 使用独立、latest-offset 的原生 consumer 观察本次发布后的记录。
     *
     * @return 不影响生产 consumer group 的 Kafka 测试消费者
     */
    private KafkaConsumer<String, byte[]> consumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-routing-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    /**
     * 轮询到指定 key 或超时。
     *
     * @param consumer 原生消费者
     * @param expectedKey 当前事件 key
     * @return 期间收到的所有记录
     */
    private static List<ConsumerRecord<String, byte[]>> pollUntil(KafkaConsumer<String, byte[]> consumer,
                                                                    String expectedKey) {
        Instant deadline = Instant.now().plusSeconds(10);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        while (Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            if (records.stream().anyMatch(record -> expectedKey.equals(record.key()))) {
                return records;
            }
        }
        return records;
    }

    /**
     * 在固定短窗口内收集记录；未知类型场景只需证明不存在指定 key。
     *
     * @param consumer 原生消费者
     * @param duration 观察窗口
     * @return 收到的所有记录
     */
    private static List<ConsumerRecord<String, byte[]>> pollFor(KafkaConsumer<String, byte[]> consumer,
                                                                  Duration duration) {
        Instant deadline = Instant.now().plus(duration);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        while (Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
        }
        return records;
    }

    /**
     * 在 RLS 项目范围内读取本事件的最终发布标记。
     *
     * @param eventId Outbox 事件 ID
     * @param scope 对应项目范围
     * @return 是否已得到 Kafka broker 确认
     */
    private boolean published(UUID eventId, TenantScope scope) {
        TenantContext.set(scope);
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT published_at IS NOT NULL FROM sys_outbox_event WHERE id = ?", Boolean.class, eventId));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 在 RLS 项目范围内读取发布器持久化的脱敏错误类型。
     *
     * @param eventId Outbox 事件 ID
     * @param scope 对应项目范围
     * @return 最近一次失败诊断
     */
    private String lastError(UUID eventId, TenantScope scope) {
        TenantContext.set(scope);
        try {
            return jdbcTemplate.queryForObject("SELECT last_error FROM sys_outbox_event WHERE id = ?", String.class,
                    eventId);
        } finally {
            TenantContext.clear();
        }
    }

    /** 独立owner连接只用于验证数据库身份与清空本类专库。 */
    private Connection owner() throws Exception {
        return DriverManager.getConnection(
                IsolatedDatabase.URL,
                IsolatedDatabase.POSTGRES.getUsername(),
                IsolatedDatabase.POSTGRES.getPassword());
    }

    /**
     * 每例清空专库Outbox；成功发布行同样保留审计事实，
     * 必须显式删除后才能证明下一例没有全局候选。
     * @throws Exception owner清理失败时立即报告，禁止带污染继续执行下一例
     */
    @AfterEach
    void clearOutbox() throws Exception {
        TenantContext.clear();
        try (Connection owner = owner();
             PreparedStatement delete = owner.prepareStatement("DELETE FROM sys_outbox_event")) {
            delete.executeUpdate();
        }
    }

    /** 专库URL在静态属性回调之后再次登记，避免属性合并顺序回退到父类共享数据库。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 仅替换应用与Flyway地址，角色、密码和迁移合同仍使用真实生产配置。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> IsolatedDatabase.URL);
                registry.add("spring.flyway.url", () -> IsolatedDatabase.URL);
            };
        }
    }

    /**
     * 延迟启动目标类专库；同包Modbus测试复用静态Kafka属性方法时，
     * 不会额外拉起无关PostgreSQL容器。
     */
    private static final class IsolatedDatabase {
        /** 使用与父类相同镜像和owner凭据，但由独立容器隔离角色、迁移和全局领取。 */
        private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                DockerImageName.parse(KafkaTransactionalOutboxPublisherIntegrationTests.POSTGRES.getDockerImageName())
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(DATABASE_NAME)
                .withUsername(KafkaTransactionalOutboxPublisherIntegrationTests.POSTGRES.getUsername())
                .withPassword(KafkaTransactionalOutboxPublisherIntegrationTests.POSTGRES.getPassword());
        /** Flyway、应用连接池与owner观察共用的随机端口专库地址。 */
        private static final String URL = start();

        /** 先启动专库再让Registrar发布地址；容器在JVM退出时由Testcontainers统一回收。 */
        private static String start() {
            POSTGRES.start();
            return POSTGRES.getJdbcUrl();
        }

        /** 纯静态容器持有者禁止实例化。 */
        private IsolatedDatabase() {
        }
    }

    /** 真实 Kafka 测试专用 topic 装配。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTopics {
        /** 新内部主题沿生产分区及七天保留合同显式创建。 */
        @Bean org.apache.kafka.clients.admin.NewTopic automationPropertyTopic() {
            return TopicBuilder.name(AutomationPropertyAccepted.TOPIC).partitions(6).replicas(1)
                    .config("retention.ms", "604800000").build();
        }


        /**
         * 显式创建命令下行 topic；测试不依赖 broker 自动建主题，从而保持 deploy 的生产基线。
         *
         * @return 三分区命令 topic
         */
        @Bean
        org.apache.kafka.clients.admin.NewTopic deviceDownlinkTopic() {
            return TopicBuilder.name(KafkaTransactionalOutboxPublisher.DEVICE_DOWNLINK_TOPIC)
                    .partitions(3).replicas(1).build();
        }

        /**
         * 显式创建通知 topic；生产配置已冻结其主题名，测试只缩小分区数以降低容器开销。
         *
         * @return 三分区通知 topic
         */
        @Bean
        org.apache.kafka.clients.admin.NewTopic notificationTopic() {
            return TopicBuilder.name(KafkaTransactionalOutboxPublisher.NOTIFICATION_TOPIC)
                    .partitions(3).replicas(1).build();
        }
    }

    /** 当前用例的租户、项目、trace 与 RLS 范围。 */
    private record Fixture(UUID tenantId, UUID projectId, String traceId, TenantScope scope) {
    }
}
