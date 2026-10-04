package com.things.link.bootstrap.device.access;

import com.things.link.device.application.DeviceAccessAcceptancePort;
import com.things.link.device.application.DeviceAccessRequestCleanupScheduler;
import com.things.link.device.domain.DeviceAccessRequestRepository;
import com.things.link.ingestion.application.access.DeviceAccessUplinkIngressService;
import com.things.link.ingestion.application.access.DeviceAccessUplinkMessage;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PostgreSQL／Kafka 下的接入幂等受理：判定分类、RLS 隔离、跨协议共用同一事实、保留期清理与
 * 「重放不重复上车」。
 *
 * <p>用真库而不是内存替身，是因为本片的关键性质全部落在数据库语义上：主键 {@code (device_id, message_id)}、
 * {@code ON CONFLICT DO NOTHING} 的并发收敛、项目 RLS 的可见性，以及 {@code SECURITY DEFINER} 清理函数。
 * 这些用替身跑绿不能说明任何事。</p>
 */
@Import(DeviceAccessAcceptanceIntegrationTests.AccessTopicTestConfiguration.class)
@TestPropertySource(properties = "spring.kafka.admin.auto-create=true")
class DeviceAccessAcceptanceIntegrationTests extends AbstractKafkaIntegrationTest {

    /** 与 device 域共用的幂等受理端口，真实实现。 */
    @Autowired
    private DeviceAccessAcceptancePort acceptance;

    /** 真实受理用例：解析 → 幂等判定 → 总线交接 → 落事实。 */
    @Autowired
    private DeviceAccessUplinkIngressService ingressService;

    /** 受理事实仓储；保留期语义由用例显式驱动，避免后台线程抢先删除夹具。 */
    @Autowired
    private DeviceAccessRequestRepository repository;

    /** 应用角色连接，用于验证 RLS 可见性。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 标准主题的独立观察者由用例内联创建，不依赖 Spring listener 自动启动。 */

    /** 本类独占夹具，测试结束后按依赖顺序回收。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 不残留夹具事实；清理失败不应掩盖断言结果，因此只做尽力回收。 */
    @AfterEach
    void clearFixtures() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                execute(owner, "DELETE FROM dev_access_request WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 首次受理落事实，重放返回首次结果，同键异载荷判冲突且不改动既有事实。 */
    @Test
    void recordsOnceThenReportsReplayAndConflict() throws SQLException {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        DeviceAccessAcceptancePort.Attempt first = attempt(fixture, messageId, TransportProtocol.HTTP, digest("a"));

        assertThat(acceptance.decide(first))
                .isInstanceOf(DeviceAccessAcceptancePort.Decision.Fresh.class);
        DeviceAccessAcceptancePort.Recording recording = acceptance.record(first);
        assertThat(recording.firstRecording()).isTrue();
        assertThat(recording.acceptance().messageId()).isEqualTo(messageId);
        assertThat(recording.acceptance().receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(recording.acceptance().acceptedAt()).isNotNull();

        assertThat(acceptance.decide(first))
                .isInstanceOf(DeviceAccessAcceptancePort.Decision.Duplicate.class);
        DeviceAccessAcceptancePort.Decision.Conflict conflict = (DeviceAccessAcceptancePort.Decision.Conflict)
                acceptance.decide(attempt(fixture, messageId, TransportProtocol.HTTP, digest("b")));
        assertThat(conflict.acceptance().payloadDigest()).isEqualTo(digest("a"));

        // 冲突不得改动首次事实：仍是同摘要的重放，且库里只有一行。
        assertThat(acceptance.decide(first))
                .isInstanceOf(DeviceAccessAcceptancePort.Decision.Duplicate.class);
        assertThat(ownerFactCount(fixture)).isEqualTo(1);
    }

    /** 幂等键里没有协议维度：HTTP 受理过的 messageId 经 CoAP 重发仍是重放，只留一行事实。 */
    @Test
    void sameMessageIdAcrossProtocolsSharesSingleFact() throws SQLException {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        acceptance.record(attempt(fixture, messageId, TransportProtocol.HTTP, digest("a")));

        DeviceAccessAcceptancePort.Decision decision =
                acceptance.decide(attempt(fixture, messageId, TransportProtocol.COAP, digest("a")));

        assertThat(decision).isInstanceOf(DeviceAccessAcceptancePort.Decision.Duplicate.class);
        assertThat(ownerFactCount(fixture)).isEqualTo(1);
    }

    /** 受理事实按项目隔离：应用角色在无范围时看不到其他项目的行，端口也不会跨项目误判重放。 */
    @Test
    void factsAreIsolatedByProjectScope() throws SQLException {
        Fixture first = seed();
        Fixture second = seed();
        UUID messageId = Uuid7.generate();
        acceptance.record(attempt(first, messageId, TransportProtocol.TCP, digest("a")));

        assertThat(acceptance.decide(attempt(second, messageId, TransportProtocol.TCP, digest("a"))))
                .as("另一项目持有同一 messageId 不构成重放")
                .isInstanceOf(DeviceAccessAcceptancePort.Decision.Fresh.class);
        assertThat(ownerFactCount(first)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dev_access_request WHERE device_id = ?",
                Integer.class, first.deviceId()))
                .as("未设置项目范围时 RLS 必须挡住应用角色")
                .isZero();
    }

    /** 保留期清理只删过期事实，且不会连带删掉窗口内的事实。 */
    @Test
    void retentionSweepDeletesOnlyExpiredFacts() throws SQLException {
        Fixture fixture = seed();
        UUID expired = Uuid7.generate();
        UUID recent = Uuid7.generate();
        acceptance.record(attempt(fixture, expired, TransportProtocol.HTTP, digest("a")));
        acceptance.record(attempt(fixture, recent, TransportProtocol.HTTP, digest("b")));
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE dev_access_request SET accepted_at = now() - interval '8 days'"
                    + " WHERE device_id = ? AND message_id = ?", fixture.deviceId(), expired);
        }

        repository.deleteAcceptedBefore(Instant.now().minus(DeviceAccessRequestCleanupScheduler.RETENTION),
                DELETION_BATCH);

        assertThat(ownerFactCount(fixture)).isEqualTo(1);
        assertThat(acceptance.decide(attempt(fixture, expired, TransportProtocol.HTTP, digest("a"))))
                .as("过期事实清理后同一 messageId 重新成为新尝试")
                .isInstanceOf(DeviceAccessAcceptancePort.Decision.Fresh.class);
        assertThat(acceptance.decide(attempt(fixture, recent, TransportProtocol.HTTP, digest("b"))))
                .isInstanceOf(DeviceAccessAcceptancePort.Decision.Duplicate.class);
    }

    /** 端到端：重放不得再次上车，库里也只有一次受理事实。 */
    @Test
    void duplicateReplayDoesNotPublishSecondEnvelope() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        byte[] payload = report(messageId, "{\"temperature\":26.5}");
        DeviceAccessUplinkMessage uplink = new DeviceAccessUplinkMessage(
                fixture.tenantId(), fixture.projectId(), fixture.deviceId(), TransportProtocol.HTTP, payload,
                RECEIVED_AT, TRACE, new AuthenticatedDeviceIdentity(
                        fixture.tenantId(), fixture.projectId(), fixture.deviceId(), 3));

        // 同一个 consumer 贯穿两次受理：换 group 重订阅会按 earliest 重读历史，把首条误当成新记录。
        try (KafkaConsumer<String, String> consumer = normalizedConsumer()) {
            consumer.subscribe(List.of(NORMALIZED_UPLINK_TOPIC));

            assertThat(ingressService.accept(uplink).status())
                    .isEqualTo(DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance.Status.ACCEPTED);
            List<String> first = drain(consumer, fixture.deviceId(), Duration.ofSeconds(10));
            assertThat(first).as("首次受理必须落到标准主题").hasSize(1);
            assertThat(first.getFirst()).contains(messageId.toString()).contains("\"protocol\":\"HTTP\"");

            assertThat(ingressService.accept(uplink).status())
                    .isEqualTo(DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance.Status.DUPLICATE);
            assertThat(drain(consumer, fixture.deviceId(), Duration.ofSeconds(2)))
                    .as("重放不得产生第二条标准上行").isEmpty();
        }
        assertThat(ownerFactCount(fixture)).isEqualTo(1);
    }

    /**
     * 创建标准主题观察者。
     *
     * <p>刻意不用 {@code @KafkaListener}：本测试只关心「有没有第二条」，让全部生产 listener 自动启动会为
     * 无关主题引入缺主题启动失败。值为字符串即可满足断言，避免为读一条记录再搭一套反序列化信任配置。</p>
     *
     * @return 调用方负责关闭的 consumer
     */
    private static KafkaConsumer<String, String> normalizedConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "device-access-acceptance-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }

    /**
     * 在给定窗口内读取该设备在标准主题上新增的信封原始 JSON。
     *
     * @param consumer 复用中的观察者，位置随每次调用前进
     * @param deviceId 设备标识，同时是 Kafka key
     * @param window 最长等待窗口
     * @return 窗口内该设备的新增信封 JSON
     */
    private static List<String> drain(KafkaConsumer<String, String> consumer, UUID deviceId,
                                      Duration window) {
        List<String> payloads = new ArrayList<>();
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                if (deviceId.toString().equals(record.key())) {
                    payloads.add(record.value());
                }
            }
        }
        return payloads;
    }

    /**
     * 播种独占租户、项目、直连设备类型与设备。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败直接终止用例
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "ax1c_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '接入幂等独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '接入幂等独占项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax1c_direct', '接入幂等直连类型', 'DIRECT', 'STANDARD', 'WIFI', 'DRAFT')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax1c_device', '接入幂等设备', 'OFFLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
        }
        return fixture;
    }

    /**
     * 构造幂等尝试。
     *
     * @param fixture 独占夹具
     * @param messageId 消息标识
     * @param protocol 传输协议
     * @param digest 载荷摘要
     * @return 幂等尝试
     */
    private static DeviceAccessAcceptancePort.Attempt attempt(Fixture fixture, UUID messageId,
                                                             TransportProtocol protocol, String digest) {
        return new DeviceAccessAcceptancePort.Attempt(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                messageId, protocol, digest, RECEIVED_AT);
    }

    /**
     * 用 owner 连接统计受理事实行数，用于与 RLS 可见性对照。
     *
     * @param fixture 独占夹具
     * @return 真实行数
     * @throws SQLException 查询失败
     */
    private long ownerFactCount(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT count(*) FROM dev_access_request WHERE device_id = ?")) {
            statement.setObject(1, fixture.deviceId());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /**
     * owner 连接用于夹具播种与独立旁观。
     *
     * @return 调用方负责关闭的连接
     * @throws SQLException 连接失败
     */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * 参数化执行夹具语句。
     *
     * @param connection owner 连接
     * @param sql 语句
     * @param arguments 参数
     * @throws SQLException 执行失败
     */
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }

    /**
     * 返回 64 位小写十六进制测试摘要。
     *
     * @param seed 单字符种子
     * @return SHA-256 形状的摘要
     */
    private static String digest(String seed) {
        return seed.repeat(64);
    }

    /**
     * 构造与 MQTT 同源的属性上报载荷。
     *
     * @param messageId 设备消息标识
     * @param payload 属性对象 JSON
     * @return 业务载荷字节
     */
    private static byte[] report(UUID messageId, String payload) {
        return ("{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"2026-09-18T08:00:00Z\",\"payload\":"
                + payload + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** 保留期用例的单轮删除上限。 */
    private static final int DELETION_BATCH = 1_000;

    /** 平台接收时刻。 */
    private static final Instant RECEIVED_AT = Instant.parse("2026-09-18T08:00:01Z");

    /** 测试链路标识。 */
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    /**
     * 独占夹具身份。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceId 直连设备
     * @param typeId 设备类型
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, String projectKey) {
    }

    /** 标准主题与生产消费者启动所需的最小主题声明。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class AccessTopicTestConfiguration {

        /** @return 与生产 raw 主题对应的三分区测试主题 */
        @Bean
        NewTopic rawUplinkTopic() {
            return TopicBuilder.name(RAW_UPLINK_TOPIC).partitions(3).replicas(1).build();
        }

        /** @return 与生产 normalized 主题对应的三分区测试主题 */
        @Bean
        NewTopic normalizedUplinkTopic() {
            return TopicBuilder.name(NORMALIZED_UPLINK_TOPIC).partitions(3).replicas(1).build();
        }

    }

}
