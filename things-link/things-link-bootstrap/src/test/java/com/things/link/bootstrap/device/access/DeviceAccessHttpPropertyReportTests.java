package com.things.link.bootstrap.device.access;

import com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration;
import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设备面属性上报的真实 HTTP 闭环：真实 socket 客户端 → 受理 → 标准管道 → 当前值与历史 → 计量输入事实。
 *
 * <p>用 {@code RANDOM_PORT} 起真实容器并用 JDK {@link HttpClient} 发请求，而不是 MockMvc：本片要证明的正是
 * 「真实 HTTP 客户端」这条契约——过滤器、媒体类型协商、体积上限与状态码都在真实 servlet 栈上执行。数据面用
 * 真实 PostgreSQL／Kafka，因此「拿到 202」与「属性真的落到当前值和历史」是两件事，本类分别断言。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 刻意不覆盖连接池规格：测试档默认每池 2 条、控制池等待 500ms 正好是「控制池可被占满」的场景，
        // 而任何额外的属性覆盖都会让 Spring 多建一个上下文（并再跑一次 Flyway），与并发夹具清理争 DDL 锁。
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"
})
@Import({DeviceDataPlaneTopicsTestConfiguration.class, DeviceProtocolKafkaTopicsTestConfiguration.class})
class DeviceAccessHttpPropertyReportTests extends AbstractKafkaIntegrationTest {

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 属性上报路径。 */
    private static final String REPORT_PATH = "/device-access/v1/property/report";

    /** 测试设备密钥明文。 */
    private static final String SECRET = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    /** 与数据面夹具一致的物模型快照：仅一个可上报的 NUMBER 属性。 */
    private static final String TEMPERATURE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\","
                    + "\"minimum\":-40,\"maximum\":125}},\"events\":{},\"commands\":{}}";

    /** 真实 HTTP 客户端：只用 JDK 标准库，避免测试与某个框架客户端的行为差异。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** 真实监听端口。 */
    @LocalServerPort
    private int port;

    /** 接入配置仓储；测试以显式项目范围开通平面。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** 应用角色连接。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 显式事务，保证 RLS 范围在连接首次取出前已设置。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 本类独占夹具。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 不残留夹具事实。 */
    @AfterEach
    void clearFixtures() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                // 原始时序点必须先清：物模型版本删除有 MODEL_RAW_REFERENCE_REMAINS 守卫。
                execute(owner, "DELETE FROM public.ts_property_point_internal WHERE project_id = ?",
                        fixture.projectId());
                execute(owner, "DELETE FROM sys_inbox_message WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_usage_counter_daily WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_shadow WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                // 物模型版本与绑定历史引用设备类型，必须先于类型回收。
                execute(owner, "DELETE FROM dev_device_model_binding_history WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 完整闭环：202 受理 → 标准管道 → 当前值（影子）与历史（时序点）落地，计量输入事实同源。 */
    @Test
    void reportReachesCurrentValueHistoryAndMeteringFact() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        String body = reportBody(messageId, "{\"temperature\":26.5}");

        HttpResult response = post(fixture, body, MediaType.APPLICATION_JSON);

        assertThat(response.status()).isEqualTo(202);
        JsonNode payload = JSON.readTree(response.body());
        assertThat(payload.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(payload.get("messageId").asString()).isEqualTo(messageId.toString());
        assertThat(payload.get("receivedAt").asString()).isNotBlank();

        await().atMost(30, SECONDS).untilAsserted(() -> {
            assertThat(currentValue(fixture)).contains("26.5");
            assertThat(historyCount(fixture)).isEqualTo(1);
            assertThat(inboxCount(fixture, messageId)).isEqualTo(1);
        });
    }

    /** 同键同载荷重放：返回 202 与**首次**结果，且不产生第二个历史点。 */
    @Test
    void identicalReplayReturnsFirstAcceptanceWithoutDuplicatePoint() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        String body = reportBody(messageId, "{\"temperature\":21.25}");

        JsonNode first = JSON.readTree(post(fixture, body, MediaType.APPLICATION_JSON).body());
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(historyCount(fixture)).isEqualTo(1));

        HttpResult replay = post(fixture, body, MediaType.APPLICATION_JSON);

        assertThat(replay.status()).isEqualTo(202);
        JsonNode replayPayload = JSON.readTree(replay.body());
        assertThat(replayPayload.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(replayPayload.get("receivedAt").asString())
                .as("重放返回首次受理时间，让设备能对齐同一次尝试")
                .isEqualTo(first.get("receivedAt").asString());
        assertThat(replayPayload.get("messageId").asString()).isEqualTo(messageId.toString());
        assertThat(historyCount(fixture)).as("重放不得产生第二个时序点").isEqualTo(1);
    }

    /** 同键异载荷：409 且首次历史不变。 */
    @Test
    void sameMessageWithDifferentPayloadIsConflict() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        post(fixture, reportBody(messageId, "{\"temperature\":20}"), MediaType.APPLICATION_JSON);
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(historyCount(fixture)).isEqualTo(1));

        HttpResult conflict = post(fixture, reportBody(messageId, "{\"temperature\":99}"),
                MediaType.APPLICATION_JSON);

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(JSON.readTree(conflict.body()).get("errorCode").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(historyCount(fixture)).isEqualTo(1);
    }

    /** 报文自报归属必须被忽略（§3.2「未知字段忽略」）：属性点仍落在认证设备下。 */
    @Test
    void payloadCannotRedirectIdentity() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        UUID claimedDevice = Uuid7.generate();
        String body = "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + Instant.now()
                + "\",\"deviceId\":\"" + claimedDevice + "\",\"projectId\":\"" + Uuid7.generate()
                + "\",\"tenantId\":\"" + Uuid7.generate()
                + "\",\"payload\":{\"temperature\":22}}";

        assertThat(post(fixture, body, MediaType.APPLICATION_JSON).status()).isEqualTo(202);

        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(historyCount(fixture)).isEqualTo(1));
        assertThat(historyCountForOtherDevice(fixture)).as("自报归属不得把点写到别处").isZero();
        assertThat(historyCountForDevice(fixture, claimedDevice)).isZero();
    }

    /** 媒体类型、体积与载荷形态的错误码逐行对照 §3.5。 */
    @Test
    void payloadShapeErrorsUseFrozenCodes() throws Exception {
        Fixture fixture = seed();

        HttpResult wrongType = post(fixture, reportBody(Uuid7.generate(), "{}"), MediaType.TEXT_PLAIN);
        assertThat(wrongType.status()).isEqualTo(415);
        assertThat(JSON.readTree(wrongType.body()).get("errorCode").asString())
                .isEqualTo("UNSUPPORTED_CONTENT_TYPE");

        String oversized = "{\"messageId\":\"" + Uuid7.generate() + "\",\"occurredAt\":\"2026-09-18T08:00:00Z\","
                + "\"payload\":{\"temperature\":1},\"padding\":\"" + "x".repeat(70 * 1024) + "\"}";
        HttpResult tooLarge = post(fixture, oversized, MediaType.APPLICATION_JSON);
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(JSON.readTree(tooLarge.body()).get("errorCode").asString()).isEqualTo("PAYLOAD_TOO_LARGE");

        HttpResult invalid = post(fixture, "not-json", MediaType.APPLICATION_JSON);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(JSON.readTree(invalid.body()).get("errorCode").asString()).isEqualTo("PAYLOAD_INVALID");
        assertThat(historyCount(fixture)).isZero();
    }

    /** 项目归档只读后必须拒绝设备写入，且不记录活动。 */
    @Test
    void frozenProjectRejectsDeviceWrite() throws Exception {
        Fixture fixture = seed();
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
        }

        HttpResult response = post(fixture, reportBody(Uuid7.generate(), "{\"temperature\":20}"),
                MediaType.APPLICATION_JSON);

        assertThat(response.status()).isEqualTo(403);
        assertThat(JSON.readTree(response.body()).get("errorCode").asString()).isEqualTo("PROJECT_UNAVAILABLE");
        assertThat(historyCount(fixture)).isZero();
        assertThat(activityCount(fixture)).isZero();
    }

    /**
     * 设备面不依赖控制池：控制池被完全占满时，属性上报仍必须被受理。
     *
     * <p>D-196 的证据：AX-6d 全量验证实测到控制池被占满时设备面上报先以 401 失败、并发回复被 500ms 超时打成 500，
     * 根因是新增三协议没有按 ADR 0045 的数据面口径走数据池。这条用例把结论钉成可回归的事实：占满控制池后设备面
     * 上报仍 202，说明请求在过滤器里就已进入数据面范围；一旦有人摘掉入口的数据面路由，请求会先等控制池再超时，
     * 用例必红。</p>
     *
     * @throws Exception 夹具或线程失败
     */
    @Test
    void uplinkSucceedsWhileControlPoolIsFullyOccupied() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        int controlPoolSize = 2;
        ExecutorService pool = Executors.newFixedThreadPool(controlPoolSize);
        CountDownLatch occupied = new CountDownLatch(controlPoolSize);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int index = 0; index < controlPoolSize; index++) {
                pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    // 事务内取一次连接即可，连接会一直被占到这个事务结束。
                    jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                    occupied.countDown();
                    try {
                        release.await(30, SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            }
            assertThat(occupied.await(10, SECONDS)).as("控制池必须已被占满").isTrue();

            HttpResult response = post(fixture, reportBody(messageId, "{\"temperature\":26.5}"),
                    MediaType.APPLICATION_JSON);

            assertThat(response.status()).as("设备面上报不得依赖控制池连接").isEqualTo(202);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(historyCount(fixture)).isEqualTo(1));
    }

    /**
     * 发送一次设备面属性上报。
     *
     * @param fixture 独占夹具
     * @param body 请求体
     * @param contentType 媒体类型
     * @return HTTP 响应
     */
    private HttpResult post(Fixture fixture, String body, MediaType contentType) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + REPORT_PATH))
                .header("Content-Type", contentType.toString())
                .header("X-TC-Device-Key", fixture.projectKey() + "/ax2b_device")
                .header("X-TC-Device-Secret", SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("设备面请求失败", exception);
        }
    }

    /**
     * 构造属性上报请求体。
     *
     * @param messageId 消息标识
     * @param payload 属性对象 JSON
     * @return 请求体
     */
    private static String reportBody(UUID messageId, String payload) {
        return "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + Instant.now()
                + "\",\"payload\":" + payload + "}";
    }

    /**
     * 读取影子里的当前值。
     *
     * @param fixture 独占夹具
     * @return reported JSON 文本；不存在时为空串
     * @throws SQLException 查询失败
     */
    private String currentValue(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT reported::text FROM dev_shadow WHERE project_id = ? AND device_id = ?")) {
            statement.setObject(1, fixture.projectId());
            statement.setObject(2, fixture.deviceId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        }
    }

    /**
     * 统计该设备的时序点数（历史）。
     *
     * @param fixture 独占夹具
     * @return 点数
     * @throws SQLException 查询失败
     */
    private int historyCount(Fixture fixture) throws SQLException {
        // ts_property_point 是 security_barrier 视图（按 app_current_project 过滤），owner 旁观必须读内部表。
        return count("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id = ? AND device_id = ?",
                fixture.projectId(), fixture.deviceId());
    }

    /**
     * 统计项目中其他设备是否被写入时序点，用于证明载荷不能改写归属。
     *
     * @param fixture 独占夹具
     * @return 非本设备的点数
     * @throws SQLException 查询失败
     */
    private int historyCountForOtherDevice(Fixture fixture) throws SQLException {
        return count("SELECT count(*) FROM public.ts_property_point_internal"
                        + " WHERE project_id = ? AND device_id <> ?",
                fixture.projectId(), fixture.deviceId());
    }

    /**
     * 统计指定设备（可能是报文自报的归属）是否真的被写入时序点。
     *
     * @param fixture 独占夹具
     * @param deviceId 报文自报的设备
     * @return 该设备的点数
     * @throws SQLException 查询失败
     */
    private int historyCountForDevice(Fixture fixture, UUID deviceId) throws SQLException {
        return count("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id = ? AND device_id = ?",
                fixture.projectId(), deviceId);
    }

    /**
     * 统计计量输入事实（业务侧幂等记录）是否包含该消息。
     *
     * @param fixture 独占夹具
     * @param messageId 消息标识
     * @return 命中行数
     * @throws SQLException 查询失败
     */
    private int inboxCount(Fixture fixture, UUID messageId) throws SQLException {
        return count("SELECT count(*) FROM sys_inbox_message WHERE project_id = ? AND message_id = ?",
                fixture.projectId(), messageId);
    }

    /**
     * 统计接入配置上是否记录了活动。
     *
     * @param fixture 独占夹具
     * @return 有活动时间的配置行数
     * @throws SQLException 查询失败
     */
    private int activityCount(Fixture fixture) throws SQLException {
        return count("SELECT count(*) FROM dev_access_binding WHERE project_id = ? AND last_activity_at IS NOT NULL",
                fixture.projectId());
    }

    /**
     * 参数化计数查询。
     *
     * @param sql 语句
     * @param arguments 参数
     * @return 计数
     * @throws SQLException 查询失败
     */
    private int count(String sql, Object... arguments) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /**
     * 在夹具项目范围内开通 HTTP 平面。
     *
     * @param fixture 独占夹具
     */
    private void bind(Fixture fixture) {
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                com.things.link.shared.message.TransportProtocol.HTTP, 30));
    }

    /**
     * 在夹具项目范围内执行一次应用角色调用。
     *
     * @param fixture 独占夹具
     * @param work 范围内动作
     * @param <T> 返回值类型
     * @return 动作结果
     */
    private <T> T inProject(Fixture fixture, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    fixture.tenantId().toString());
            jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                    fixture.projectId().toString());
            return work.get();
        });
    }

    /**
     * 播种独占租户、项目、直连设备、凭据、HTTP 平面与数据面物模型绑定。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "ax2b_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'HTTP闭环独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, 'HTTP闭环项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax2b_type', 'HTTP闭环类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax2b_device', 'HTTP闭环设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_credential
                        (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                    VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    sha256(SECRET));
        }
        // 数据面夹具直接建立 1.0.0 与 INITIAL 绑定，接通版本化摄入链（版本发布属控制面）。
        seedThingModelVersion(fixture.tenantId(), fixture.projectId(), fixture.typeId(), fixture.deviceId(),
                TEMPERATURE_SNAPSHOT);
        bind(fixture);
        return fixture;
    }

    /**
     * 计算与凭据校验一致的 SHA-256 十六进制摘要。
     *
     * @param input 明文
     * @return 摘要
     */
    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
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
     * 一次真实 HTTP 结果。
     *
     * @param status HTTP 状态码
     * @param body 响应体文本
     */
    private record HttpResult(int status, String body) {
    }

    /**
     * 独占夹具身份。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceId 设备
     * @param typeId 设备类型
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, String projectKey) {
    }
}
