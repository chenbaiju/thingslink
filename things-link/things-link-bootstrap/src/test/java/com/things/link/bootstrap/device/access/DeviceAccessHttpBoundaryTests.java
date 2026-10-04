package com.things.link.bootstrap.device.access;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.context.TestPropertySource;

/**
 * 设备面 HTTP 的边界与恢复面：并发领取、并发回复、认证失效、轮询共享预算与异常报文。
 *
 * <p>这些都是「只有真实依赖才能给结论」的性质：并发由数据库条件更新仲裁，预算状态在 Redis，凭据失效走
 * 缓存失效链路。替身在这里只能证明替身自己的行为。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 本类的断言对象是并发契约（同一条命令不双投、同键回复收敛一次），不是测试夹具的连接预算。
        // 测试档默认每池 2 条且控制池只等 500ms；回复路径一次请求会同时占用外层事务与调试时间线的
        // 独立事务，4 路并发必先耗尽连接，从而把「契约不成立」与「夹具太小」混为一谈。
        // 这里按 DualDataSourceIsolationTests 的既有做法恢复生产池规格（minimum-idle 仍为 0，不给容器添连接）。
        "things-link.datasource.control.maximum-pool-size=6",
        "things-link.datasource.data.maximum-pool-size=10",
        "things-link.datasource.control.minimum-idle=0",
        "things-link.datasource.data.minimum-idle=0"
})
class DeviceAccessHttpBoundaryTests extends AbstractIntegrationTest {

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 领取路径。 */
    private static final String CLAIM_PATH = "/device-access/v1/command/claim";

    /** 回复路径。 */
    private static final String REPLY_PATH = "/device-access/v1/command/reply";

    /** 上报路径；本类只在预算耗尽后调用它，验证跨端点共享同一份额度。 */
    private static final String REPORT_PATH = "/device-access/v1/property/report";

    /** 测试设备密钥明文。 */
    private static final String SECRET = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    /** 真实 HTTP 客户端。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** 真实监听端口。 */
    @LocalServerPort
    private int port;

    /** 接入配置仓储。 */
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
                execute(owner, "DELETE FROM ts_device_command_claim WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM ts_device_command WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_command_definition WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 并发领取：每条命令只被领取一次，序号都为 1，且不出现重复投递。 */
    @Test
    void concurrentClaimsDeliverEachCommandExactlyOnce() throws Exception {
        Fixture fixture = seed();
        Set<UUID> seeded = new HashSet<>();
        for (int index = 0; index < 4; index++) {
            seeded.add(seedAcceptedCommand(fixture));
        }

        int rounds = 4;
        ExecutorService pool = Executors.newFixedThreadPool(rounds);
        try {
            List<Callable<HttpResult>> tasks = new ArrayList<>();
            for (int index = 0; index < rounds; index++) {
                tasks.add(() -> post(fixture, CLAIM_PATH, "{}"));
            }
            List<Future<HttpResult>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            Set<UUID> delivered = new HashSet<>();
            for (Future<HttpResult> future : futures) {
                HttpResult result = future.get();
                assertThat(result.status()).isIn(200, 204);
                if (result.status() == 200) {
                    JsonNode command = JSON.readTree(result.body()).get("commands").get(0);
                    assertThat(delivered.add(UUID.fromString(command.get("commandId").asString())))
                            .as("同一条命令不得并发投递给两个领取者").isTrue();
                    assertThat(command.get("attempt").asInt()).isEqualTo(1);
                }
            }
            assertThat(delivered).as("四条命令必须各被领取一次").isEqualTo(seeded);
            assertThat(post(fixture, CLAIM_PATH, "{}").status()).isEqualTo(204);
            assertThat(claimRowCount(fixture)).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 并发同键同结果回复：终态收敛一次，领取事实不重复，全部按受理或重放接受。 */
    @Test
    void concurrentIdenticalRepliesConvergeOnce() throws Exception {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture);
        post(fixture, CLAIM_PATH, "{}");
        UUID replyMessage = Uuid7.generate();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<HttpResult>> tasks = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                tasks.add(() -> post(fixture, REPLY_PATH,
                        replyBody(commandId, replyMessage, "SUCCESS", "{\"duration\":12}", null)));
            }
            for (Future<HttpResult> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                assertThat(future.get().status()).as("重复回复必须被接受为重放").isEqualTo(202);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(commandState(commandId)).isEqualTo("SUCCEEDED/1");
        assertThat(claimRowCount(fixture)).as("并发回复不得产生第二次领取").isEqualTo(1);
        assertThat(claimStatus(fixture)).isEqualTo("REPLIED");
    }

    /** 并发同键异结果回复：一个胜出，另一个必须被拒为冲突，事实只按胜出者收敛。 */
    @Test
    void concurrentConflictingRepliesRejectOneSide() throws Exception {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture);
        post(fixture, CLAIM_PATH, "{}");
        UUID replyMessage = Uuid7.generate();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<HttpResult>> tasks = List.of(
                    () -> post(fixture, REPLY_PATH,
                            replyBody(commandId, replyMessage, "SUCCESS", "{\"duration\":12}", null)),
                    () -> post(fixture, REPLY_PATH,
                            replyBody(commandId, replyMessage, "FAILED", "{\"duration\":99}", "DEVICE_REJECTED")));
            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResult> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                statuses.add(future.get().status());
            }
            assertThat(statuses).as("一个胜出（202）、一个被拒（409）").containsExactlyInAnyOrder(202, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(commandState(commandId)).as("终态只能有一个").isIn("SUCCEEDED/1", "FAILED/1");
        assertThat(claimRowCount(fixture)).isEqualTo(1);
    }

    /**
     * 凭据到期后立即拒绝：首次请求就走数据库，不依赖缓存过期。
     *
     * <p>领取与上报各用一台设备：第一次失败会触发该设备的认证退避，第二台设备才能独立证明「首次即拒」，
     * 否则第二次请求会（正确地）被退避拦成 429，把结论搅成两种原因的混合。</p>
     */
    @Test
    void expiredCredentialIsRejectedImmediately() throws SQLException {
        Fixture claimFixture = seed();
        Fixture reportFixture = seed();
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE dev_credential SET expires_at = now() - interval '1 minute'"
                    + " WHERE project_id = ? AND device_id = ?", claimFixture.projectId(), claimFixture.deviceId());
            execute(owner, "UPDATE dev_credential SET expires_at = now() - interval '1 minute'"
                    + " WHERE project_id = ? AND device_id = ?", reportFixture.projectId(), reportFixture.deviceId());
        }

        assertThat(post(claimFixture, CLAIM_PATH, "{}").status()).isEqualTo(401);
        assertThat(post(reportFixture, REPORT_PATH, "{}").status()).isEqualTo(401);
    }

    /** 轮询同样消耗与 MQTT 共享的设备预算：耗尽后领取与上报都必须被拒为 429。 */
    @Test
    void pollingSharesDeviceBudgetWithOtherEndpoints() throws SQLException {
        Fixture fixture = seed();
        HttpResult limited = null;
        for (int index = 0; index < 80; index++) {
            HttpResult result = post(fixture, CLAIM_PATH, "{}");
            if (result.status() == 429) {
                limited = result;
                break;
            }
            assertThat(result.status()).as("预算耗尽前只能是受理或空结果").isIn(200, 204);
        }
        assertThat(limited).as("持续轮询必须最终被预算拒绝").isNotNull();
        assertThat(JSON.readTree(limited.body()).get("errorCode").asString()).isEqualTo("RATE_LIMITED");
        assertThat(limited.retryAfter()).isNotNull();
        assertThat(post(fixture, REPORT_PATH, "{}").status())
                .as("同一设备的其他端点读取同一份额度").isEqualTo(429);
    }

    /** 命令端点的异常报文：体积与媒体类型边界与上报端点一致。 */
    @Test
    void commandEndpointsEnforceSameBoundaries() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture);

        HttpResult oversized = send(REPLY_PATH, fixture, "x".repeat(70 * 1024), "application/json");
        assertThat(oversized.status()).isEqualTo(413);
        assertThat(JSON.readTree(oversized.body()).get("errorCode").asString()).isEqualTo("PAYLOAD_TOO_LARGE");

        HttpResult wrongType = send(REPLY_PATH, fixture,
                replyBody(commandId, Uuid7.generate(), "SUCCESS", "{}", null), "text/plain");
        assertThat(wrongType.status()).isEqualTo(415);
        assertThat(JSON.readTree(wrongType.body()).get("errorCode").asString())
                .isEqualTo("UNSUPPORTED_CONTENT_TYPE");

        HttpResult malformed = send(REPLY_PATH, fixture, "not-json", "application/json");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(JSON.readTree(malformed.body()).get("errorCode").asString()).isEqualTo("PAYLOAD_INVALID");
    }

    /**
     * 发送一次设备面请求（默认 JSON 媒体类型）。
     *
     * @param fixture 独占夹具
     * @param path 路径
     * @param body 请求体
     * @return 结果
     */
    private HttpResult post(Fixture fixture, String path, String body) {
        return send(path, fixture, body, "application/json");
    }

    /**
     * 以指定媒体类型发送请求。
     *
     * @param path 路径
     * @param fixture 独占夹具
     * @param body 请求体
     * @param contentType 媒体类型
     * @return 结果
     */
    private HttpResult send(String path, Fixture fixture, String body, String contentType) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", contentType)
                .header("X-TC-Device-Key", fixture.projectKey() + "/ax2d_device")
                .header("X-TC-Device-Secret", SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body(),
                    response.headers().firstValue("Retry-After").orElse(null));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("设备面请求失败", exception);
        }
    }

    /**
     * 构造命令回复报文。
     *
     * @param commandId 命令 ID
     * @param messageId 回复消息 ID
     * @param status 状态
     * @param output 输出对象 JSON
     * @param errorCode 失败码
     * @return 报文
     */
    private static String replyBody(UUID commandId, UUID messageId, String status, String output, String errorCode) {
        return "{\"commandId\":\"" + commandId + "\",\"messageId\":\"" + messageId + "\",\"occurredAt\":\""
                + Instant.now() + "\",\"status\":\"" + status + "\",\"output\":" + output
                + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\"") + "}";
    }

    /**
     * 播种待领取命令。
     *
     * @param fixture 独占夹具
     * @return 命令 ID
     * @throws SQLException 写入失败
     */
    private UUID seedAcceptedCommand(Fixture fixture) throws SQLException {
        UUID commandId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    INSERT INTO ts_device_command
                        (id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                         command_key, input_schema, output_schema, request_payload, status, idempotency_key,
                         requested_by, timeout_seconds, attempt_count, max_attempts, next_attempt_at, deadline_at,
                         trace_id, accepted_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'reboot', '{}', '{}', '{}'::jsonb, 'ACCEPTED', ?, ?, 30, 0, 3,
                            now(), NULL, 'ax2d-command', now())
                    """, commandId, fixture.tenantId(), fixture.projectId(), fixture.deviceId(), fixture.deviceId(),
                    fixture.definitionId(), commandId.toString(), fixture.accountId());
        }
        return commandId;
    }

    /**
     * 读取命令状态与投递序号。
     *
     * @param commandId 命令 ID
     * @return {@code 状态/投递序号}
     * @throws SQLException 查询失败
     */
    private String commandState(UUID commandId) throws SQLException {
        return text("SELECT status || '/' || attempt_count FROM ts_device_command WHERE id = ?", commandId);
    }

    /**
     * 读取最近一次领取事实的状态。
     *
     * @param fixture 独占夹具
     * @return 领取状态
     * @throws SQLException 查询失败
     */
    private String claimStatus(Fixture fixture) throws SQLException {
        return text("SELECT status FROM ts_device_command_claim WHERE project_id = ?"
                + " ORDER BY attempt_no DESC LIMIT 1", fixture.projectId());
    }

    /**
     * 统计项目的领取事实行数。
     *
     * @param fixture 独占夹具
     * @return 行数
     * @throws SQLException 查询失败
     */
    private int claimRowCount(Fixture fixture) throws SQLException {
        return count("SELECT count(*) FROM ts_device_command_claim WHERE project_id = ?", fixture.projectId());
    }

    /** 查询单列文本；未命中返回 null。 */
    private String text(String sql, Object... arguments) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** 参数化计数。 */
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
     * 播种独占租户、项目、设备、命令定义、凭据与 HTTP 平面。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(),
                "ax2d_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'HTTP边界独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name)"
                    + " VALUES (?, ?, '{noop}unused', 'HTTP边界 OWNER')", fixture.accountId(),
                    fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, 'HTTP边界项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax2d_type', 'HTTP边界类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, input_schema, output_schema,
                         timeout_seconds)
                    VALUES (?, ?, ?, ?, 'reboot', '重启', '{}', '{}', 30)
                    """, fixture.definitionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax2d_device', 'HTTP边界设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_credential
                        (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                    VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    sha256(SECRET));
        }
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                com.things.link.shared.message.TransportProtocol.HTTP, 30));
        return fixture;
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
     * @param body 响应体
     * @param retryAfter Retry-After 头；无则 null
     */
    private record HttpResult(int status, String body, String retryAfter) {
    }

    /**
     * 独占夹具身份。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceId 设备
     * @param typeId 设备类型
     * @param definitionId 命令定义
     * @param accountId 命令请求人
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, UUID definitionId,
                           UUID accountId, String projectKey) {
    }
}
