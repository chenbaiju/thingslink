package com.things.link.bootstrap.telemetry.history;

import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.device.infrastructure.cache.RedisDeviceCurrentValueCache;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.api.dto.response.MessageLogResponse;
import com.things.link.telemetry.api.dto.response.PropertyHistoryPointResponse;
import com.things.link.telemetry.domain.DeviceMessageLog;
import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.MessageLogQuery;
import com.things.link.telemetry.domain.PropertyPoint;
import com.things.link.telemetry.infrastructure.persistence.JdbcMessageLogRepository;
import com.things.link.telemetry.infrastructure.persistence.JdbcPropertyPointRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q6-R1：实际缓存与两仓储的跨精度反例，禁止以缓存毫秒冒充原始事件或持久化微秒。
 * <p>仅使用独占 Redis/PostgreSQL 与普通最小表，不启动 Spring、Broker 或浏览器；不声称完整摄入链路资格。</p>
 */
class HistoryTimestampPrecisionTests {
    /** 与 Q5 固定版本一致，随机端口及数据卷完全隔离。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"));
    /** 仅本测试客户端可见，不使用共享 Redis DB15。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2-alpine")
            .withExposedPorts(6379);
    /** API DTO 与生产缓存共用应用实际 Jackson 3 模块。 */
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    /** 独占连接必须显式关闭，避免 Lettuce 线程残留。 */
    private static LettuceConnectionFactory redisConnection;
    /** 实际缓存，不复制实现算法。 */
    private static RedisDeviceCurrentValueCache cache;
    /** 独占 PostgreSQL 连接入口。 */
    private static JdbcTemplate jdbc;
    /** 两条写入使用同一真实事务。 */
    private static TransactionTemplate transaction;
    /** 实际消息仓储保留 Timestamp 转换。 */
    private static JdbcMessageLogRepository logs;
    /** 实际历史仓储保留时间窗口及值投影。 */
    private static JdbcPropertyPointRepository points;

    /** 最小普通表只隔离持久化/精度边界，不模拟 RLS、Timescale chunk 或完整业务约束。 */
    @BeforeAll
    static void startIsolatedDependencies() {
        DATABASE.start();
        REDIS.start();
        DriverManagerDataSource source = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        logs = new JdbcMessageLogRepository(jdbc);
        points = new JdbcPropertyPointRepository(jdbc);
        jdbc.execute("""
                CREATE TABLE ts_property_point (
                  project_id uuid, device_id uuid, property_key varchar, ts timestamptz, message_id uuid,
                  data_type varchar, thing_model_version_id uuid, model_version varchar, value_double double precision,
                  value_text text, value_bool boolean, value_json jsonb, quality smallint)
                """);
        jdbc.execute("""
                CREATE TABLE ts_device_message_log (
                  id uuid, project_id uuid, device_id uuid, message_id uuid, tenant_id uuid, protocol varchar,
                  direction varchar, topic varchar, payload_summary varchar, raw_bytes integer, error_code varchar,
                  ts timestamptz, received_at timestamptz, trace_id varchar, created_at timestamptz DEFAULT now(),
                  -- AX-5b 起生产写入语句包含调试时间线列；夹具表必须同步，否则本类只能证明「SQL 语法错误」。
                  message_type varchar, accepted_at timestamptz, parsed_at timestamptz, processed_at timestamptz,
                  delivered_at timestamptz, replied_at timestamptz, truncated boolean, sampled boolean)
                """);
        redisConnection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnection.afterPropertiesSet();
        redisConnection.start();
        cache = new RedisDeviceCurrentValueCache(new StringRedisTemplate(redisConnection), JSON);
    }

    /**
     * 同一纳秒输入分别走真实 Redis 和两个 JDBC 仓储；覆盖半微秒及秒进位，不对实际结果施加容差。
     * @param original 事件时间 @param persisted 精确预期的 PostgreSQL 微秒结果
     */
    @ParameterizedTest
    @CsvSource({
            "2026-09-02T18:12:49.718386Z,2026-09-02T18:12:49.718386Z",
            "2026-09-02T18:12:49.718386499Z,2026-09-02T18:12:49.718386Z",
            "2026-09-02T18:12:49.718386500Z,2026-09-02T18:12:49.718387Z",
            "2026-09-02T18:12:49.999999499Z,2026-09-02T18:12:49.999999Z",
            "2026-09-02T18:12:49.999999500Z,2026-09-02T18:12:50Z",
            "2026-09-02T18:12:49.000000499Z,2026-09-02T18:12:49Z"
    })
    void cacheAndPersistedApiTimestampsHaveDifferentPrecision(String original, String persisted) {
        UUID project = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        Instant eventAt = Instant.parse(original);
        Instant expected = Instant.parse(persisted);
        cache.merge(project, device, Map.of("temperature", new DeviceCurrentValueCache.ReportedValue("22", eventAt, 1, "1", null)));
        var key = new DeviceCurrentValueCache.ValueKey(device, "temperature");
        var cached = cache.findAll(project, List.of(key)).get(key);
        assertThat(cached.occurredAt()).isEqualTo(eventAt);
        assertThat(cached.value().asDouble()).isEqualTo(22.0);
        transaction.executeWithoutResult(status -> {
            points.save(new PropertyPoint(project, device, "temperature", eventAt, message, "NUMBER",
                    UUID.randomUUID(), "1.0.0", 22.0, null, null, null, (short) 0));
            logs.save(new DeviceMessageLog(UUID.randomUUID(), project, device, message, UUID.randomUUID(),
                    TransportProtocol.MQTT, DeviceMessageLog.Direction.UP, null, "{\"temperature\":22}",
                    18, null, eventAt, eventAt.plusSeconds(1), "q6-r1", eventAt));
        });
        var log = logs.find(new MessageLogQuery(project, device, DeviceMessageLog.Direction.UP,
                TransportProtocol.MQTT, expected.minusSeconds(1), expected.plusSeconds(1), null, null, 50))
                .items().getFirst();
        var point = points.findByDevice(project, device, "temperature", expected.minusSeconds(1),
                expected.plusSeconds(1), null, 50).items().getFirst();
        var history = points.findHistory(project, device, "temperature", expected.minusSeconds(1),
                expected.plusSeconds(1), HistoryGranularity.RAW, HistoryAggregation.AVG, 50).getFirst();
        assertThat(log.messageId()).isEqualTo(message).isEqualTo(point.messageId());
        assertThat(log.ts()).isEqualTo(expected).isEqualTo(point.ts()).isEqualTo(history.ts());
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM ts_device_message_log WHERE message_id=?",
                String.class, message)).isEqualTo(jdbc.queryForObject(
                "SELECT xmin::text FROM ts_property_point WHERE message_id=?", String.class, message));
        var logApi = JSON.readTree(JSON.writeValueAsString(MessageLogResponse.from(log)));
        var historyApi = JSON.readTree(JSON.writeValueAsString(PropertyHistoryPointResponse.from(history)));
        assertThat(logApi.get("messageId").asString()).isEqualTo(message.toString());
        assertThat(logApi.get("ts").asString()).isEqualTo(expected.toString());
        assertThat(historyApi.get("ts").asString()).isEqualTo(logApi.get("ts").asString());
        assertThat(historyApi.get("value").asDouble()).isEqualTo(22.0);
        assertThat(points.findHistory(project, device, "temperature", expected.minusSeconds(1), expected,
                HistoryGranularity.RAW, HistoryAggregation.AVG, 50)).isEmpty();
        assertThat(logs.find(new MessageLogQuery(UUID.randomUUID(), device, DeviceMessageLog.Direction.UP,
                TransportProtocol.MQTT, null, null, null, null, 50)).items()).isEmpty();
        System.out.println("Q6_R1_PRECISION " + JSON.writeValueAsString(Map.of(
                "eventAt", eventAt, "cacheAt", cached.occurredAt(), "persistedAt", expected,
                "messageApi", logApi, "historyApi", historyApi, "sameTransaction", true,
                "exclusiveToCount", 0, "otherProjectCount", 0)));
    }

    /** 清理仅涉及本类独占依赖，失败路径也关闭客户端与容器。 */
    @AfterAll
    static void stopIsolatedDependencies() {
        if (redisConnection != null) redisConnection.destroy();
        REDIS.stop();
        DATABASE.stop();
    }
}
