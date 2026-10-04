package com.things.link.bootstrap.device.access;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.testing.AbstractKafkaIntegrationTest;
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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备面命令领取与业务回复的真实 HTTP 闭环：领取写租约、回复进终态、重复回复与超时可预期、越权不落事实。
 *
 * <p>真实 socket ＋真实 PostgreSQL：租约与命令终态都由命令事实决定，用替身无法验证「租约内互斥」「重投序号」
 * 与「终态后不再可领取」这些只有数据库能给出的结论。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceAccessHttpCommandTests extends AbstractKafkaIntegrationTest {

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 领取路径。 */
    private static final String CLAIM_PATH = "/device-access/v1/command/claim";

    /** 回复路径。 */
    private static final String REPLY_PATH = "/device-access/v1/command/reply";

    /** 测试设备密钥明文。 */
    private static final String SECRET = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    /** 真实 HTTP 客户端。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** 真实监听端口。 */
    @LocalServerPort
    private int port;

    /** 接入配置仓储；测试以显式项目范围开通 HTTP 平面。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** 命令受信核心：走真实受理路径（而不是直接播种命令）验证领取闭环。 */
    @Autowired
    private DeviceCommandService commandService;

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
                execute(owner, "DELETE FROM ts_device_command_attempt WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ?", fixture.projectId());
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

    /** 无待领取命令时返回 204，让设备按 Retry-After 再问，而不是把「没有」编码成业务数据。 */
    @Test
    void claimWithoutPendingCommandReturnsNoContent() throws SQLException {
        Fixture fixture = seed();

        HttpResult result = post(fixture, CLAIM_PATH, "{}");

        assertThat(result.status()).isEqualTo(204);
        assertThat(result.body()).isEmpty();
        assertThat(result.retryAfter()).isNotNull();
    }

    /** 完整领取→回复→终态闭环：领取带租约与诊断序号，回复后命令终态且不再可领取。 */
    @Test
    void claimThenReplyDrivesCommandToTerminalState() throws Exception {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{\"speed\":3}");

        HttpResult claim = post(fixture, CLAIM_PATH, "{}");

        assertThat(claim.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(claim.body());
        assertThat(body.get("pollAfterMillis").asLong()).isEqualTo(30_000L);
        assertThat(body.get("commands")).hasSize(1);
        JsonNode command = body.get("commands").get(0);
        assertThat(command.get("commandId").asString()).isEqualTo(commandId.toString());
        assertThat(command.get("commandKey").asString()).isEqualTo("reboot");
        assertThat(command.get("input").get("speed").asInt()).isEqualTo(3);
        assertThat(command.get("attempt").asInt()).as("首次投递序号为 1").isEqualTo(1);
        assertThat(command.get("leaseExpiresAt").asString()).isNotBlank();
        assertThat(commandState(commandId)).isEqualTo("DISPATCHED/1");
        assertThat(claimRowCount(fixture, commandId)).isEqualTo(1);

        assertThat(post(fixture, CLAIM_PATH, "{}").status()).as("租约内不重复下发").isEqualTo(204);

        UUID replyMessage = Uuid7.generate();
        HttpResult reply = post(fixture, REPLY_PATH, replyBody(commandId, replyMessage, "SUCCESS",
                "{\"duration\":12}", null));

        assertThat(reply.status()).isEqualTo(202);
        JsonNode replyBody = JSON.readTree(reply.body());
        assertThat(replyBody.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(replyBody.get("commandId").asString()).isEqualTo(commandId.toString());
        assertThat(replyBody.get("messageId").asString()).isEqualTo(replyMessage.toString());
        assertThat(commandState(commandId)).isEqualTo("SUCCEEDED/1");
        assertThat(claimStatus(fixture, commandId)).isEqualTo("REPLIED");
        assertThat(post(fixture, CLAIM_PATH, "{}").status()).as("终态命令不再可领取").isEqualTo(204);
    }

    /** 重复回复：同键同结果按重放接受，同键异结果 409，首次终态不变。 */
    @Test
    void replyDistinguishesReplayFromConflict() throws Exception {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        post(fixture, CLAIM_PATH, "{}");
        UUID replyMessage = Uuid7.generate();
        assertThat(post(fixture, REPLY_PATH, replyBody(commandId, replyMessage, "SUCCESS",
                "{\"duration\":12}", null)).status()).isEqualTo(202);

        HttpResult replay = post(fixture, REPLY_PATH, replyBody(commandId, replyMessage, "SUCCESS",
                "{\"duration\":12}", null));
        assertThat(replay.status()).isEqualTo(202);
        assertThat(commandState(commandId)).isEqualTo("SUCCEEDED/1");

        HttpResult conflict = post(fixture, REPLY_PATH, replyBody(commandId, replyMessage, "FAILED",
                "{\"duration\":99}", "DEVICE_REJECTED"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(JSON.readTree(conflict.body()).get("errorCode").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(commandState(commandId)).isEqualTo("SUCCEEDED/1");
        assertThat(failureCode(commandId)).as("冲突不得改写首次结果").isNull();
    }

    /** ACK 是中间态：命令不终态，租约到期后可再次领取且投递序号递增（超时可预期）。 */
    @Test
    void acknowledgementKeepsCommandClaimableAfterLeaseExpiry() throws Exception {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        post(fixture, CLAIM_PATH, "{}");

        assertThat(post(fixture, REPLY_PATH, replyBody(commandId, Uuid7.generate(), "ACK", "{}", null)).status())
                .isEqualTo(202);
        assertThat(commandState(commandId)).isEqualTo("ACKNOWLEDGED/1");

        expireLease(commandId);

        HttpResult reClaim = post(fixture, CLAIM_PATH, "{}");
        assertThat(reClaim.status()).isEqualTo(200);
        assertThat(JSON.readTree(reClaim.body()).get("commands").get(0).get("attempt").asInt())
                .as("重投只增加投递序号").isEqualTo(2);
        assertThat(commandState(commandId)).isEqualTo("DISPATCHED/2");
    }

    /** 未知命令或越权命令：404 COMMAND_NOT_FOUND，且不写入任何命令事实。 */
    @Test
    void unknownOrForeignCommandIsNotFound() throws Exception {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        post(fixture, CLAIM_PATH, "{}");

        HttpResult unknown = post(fixture, REPLY_PATH, replyBody(Uuid7.generate(), Uuid7.generate(), "SUCCESS",
                "{}", null));
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(JSON.readTree(unknown.body()).get("errorCode").asString()).isEqualTo("COMMAND_NOT_FOUND");

        // 另一台设备（凭据有效）回复本设备的命令：认证通过但命令不属于它，同样找不到且事实不变。
        Device other = seedDevice(fixture, "ax2c_other");
        HttpResult foreign = postAsDevice(fixture, other, REPLY_PATH,
                replyBody(commandId, Uuid7.generate(), "SUCCESS", "{}", null));
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.body()).contains("COMMAND_NOT_FOUND");
        assertThat(commandState(commandId)).isEqualTo("DISPATCHED/1");
    }

    /** 领取条数可请求但被合同上限夹住；非法字段形态返回 400。 */
    @Test
    void claimLimitIsClampedAndMalformedRequestRejected() throws Exception {
        Fixture fixture = seed();
        seedAcceptedCommand(fixture, "{}");
        seedAcceptedCommand(fixture, "{}");
        seedAcceptedCommand(fixture, "{}");

        HttpResult limited = post(fixture, CLAIM_PATH, "{\"limit\":2}");

        assertThat(limited.status()).isEqualTo(200);
        assertThat(JSON.readTree(limited.body()).get("commands")).hasSize(2);

        assertThat(post(fixture, CLAIM_PATH, "{\"limit\":0}").status()).isEqualTo(400);
        assertThat(post(fixture, CLAIM_PATH, "not-json").status()).isEqualTo(400);
    }

    /** 未认证请求在进入业务前被拒：领取与回复都不例外。 */
    @Test
    void unauthenticatedCommandRequestsAreRejected() throws SQLException {
        Fixture fixture = seed();

        assertThat(postWithoutCredentials(CLAIM_PATH, "{}")).isEqualTo(401);
        assertThat(postWithoutCredentials(REPLY_PATH, "{}")).isEqualTo(401);
    }

    /** 真实受理 → 领取 → 回复闭环：经受理产生的命令必须能被领取形态取走（D-189 回归）。 */
    @Test
    void acceptedCommandIsClaimableThroughRealAcceptancePath() throws Exception {
        Fixture fixture = seed();
        UUID commandId = submitCommand(fixture, "ax1h-claim");

        // 领取型协议不得产生推送尝试与下行 Outbox：否则 attempt_count 被推进却没有领取行，命令永久不可领取。
        assertThat(attemptRowCount(commandId)).as("HTTP 平面受理不应创建推送尝试").isZero();
        assertThat(outboxRowCount(fixture, commandId)).as("HTTP 平面受理不应写下行 Outbox").isZero();

        HttpResult claim = post(fixture, CLAIM_PATH, "{}");

        assertThat(claim.status()).as("真实受理产生的命令必须可领取").isEqualTo(200);
        JsonNode body = JSON.readTree(claim.body());
        assertThat(body.get("commands")).hasSize(1);
        assertThat(body.get("commands").get(0).get("commandId").asString()).isEqualTo(commandId.toString());
        assertThat(body.get("commands").get(0).get("attempt").asInt()).isEqualTo(1);

        UUID replyMessage = Uuid7.generate();
        assertThat(post(fixture, REPLY_PATH, replyBody(commandId, replyMessage, "SUCCESS", "{}", null)).status())
                .isEqualTo(202);
        assertThat(commandState(commandId)).isEqualTo("SUCCEEDED/1");
        assertThat(claimStatus(fixture, commandId)).isEqualTo("REPLIED");
    }

    /**
     * 以真实受理路径提交一条命令。
     *
     * @param fixture 独占夹具
     * @param idempotencyPrefix 幂等键前缀
     * @return 命令 ID
     */
    private UUID submitCommand(Fixture fixture, String idempotencyPrefix) {
        return inProject(fixture, () -> commandService.submitTrusted(fixture.projectId(), fixture.deviceId(),
                idempotencyPrefix + "-" + Uuid7.generate(), "reboot", JSON.readTree("{}"),
                fixture.accountId(), null).id());
    }

    /**
     * @param commandId 命令 ID
     * @return 该命令的派发尝试行数
     * @throws SQLException 查询失败
     */
    private int attemptRowCount(UUID commandId) throws SQLException {
        return count("SELECT count(*) FROM ts_device_command_attempt WHERE command_id = ?", commandId);
    }

    /**
     * @param fixture 独占夹具
     * @param commandId 命令 ID
     * @return 该命令的下行 Outbox 行数
     * @throws SQLException 查询失败
     */
    private int outboxRowCount(Fixture fixture, UUID commandId) throws SQLException {
        return count("SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND aggregate_id = ?",
                fixture.projectId(), commandId);
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
     * 发送一次设备面请求。
     *
     * @param fixture 独占夹具
     * @param path 路径
     * @param body 请求体
     * @return 结果
     */
    private HttpResult post(Fixture fixture, String path, String body) {
        return postAsDevice(fixture, new Device(fixture.deviceId(), "ax2c_device", SECRET), path, body);
    }

    /**
     * 以指定设备身份发送请求。
     *
     * @param fixture 独占夹具
     * @param device 设备身份与密钥
     * @param path 路径
     * @param body 请求体
     * @return 结果
     */
    private HttpResult postAsDevice(Fixture fixture, Device device, String path, String body) {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-TC-Device-Key", fixture.projectKey() + "/" + device.deviceKey())
                .header("X-TC-Device-Secret", device.secret())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    /**
     * 发送不带凭据的请求。
     *
     * @param path 路径
     * @param body 请求体
     * @return HTTP 状态码
     */
    private int postWithoutCredentials(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()).status();
    }

    /** 执行一次真实 HTTP 调用。 */
    private HttpResult send(HttpRequest request) {
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
     * @param requestPayload 命令参数 JSON
     * @return 命令 ID
     * @throws SQLException 写入失败
     */
    private UUID seedAcceptedCommand(Fixture fixture, String requestPayload) throws SQLException {
        UUID commandId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    INSERT INTO ts_device_command
                        (id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                         command_key, input_schema, output_schema, request_payload, status, idempotency_key,
                         requested_by, timeout_seconds, attempt_count, max_attempts, next_attempt_at, deadline_at,
                         trace_id, accepted_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'reboot', '{}', '{}', ?::jsonb, 'ACCEPTED', ?, ?, 30, 0, 3,
                            now(), NULL, 'ax2c-command', now())
                    """, commandId, fixture.tenantId(), fixture.projectId(), fixture.deviceId(), fixture.deviceId(),
                    fixture.definitionId(), requestPayload, commandId.toString(), fixture.accountId());
        }
        return commandId;
    }

    /**
     * 追加一台直连设备，用于越权回复用例。
     *
     * @param fixture 独占夹具
     * @param deviceKey 设备短标识
     * @return 新设备身份与密钥
     * @throws SQLException 写入失败
     */
    private Device seedDevice(Fixture fixture, String deviceKey) throws SQLException {
        UUID deviceId = Uuid7.generate();
        // 库里只存摘要，明文用于发请求：两者不能混，否则认证会以 401 的形式失败。
        String secret = deviceKey + "-secret";
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, ?, '越权设备', 'ONLINE')
                    """, deviceId, fixture.tenantId(), fixture.projectId(), fixture.typeId(), deviceKey);
            execute(owner, """
                    INSERT INTO dev_credential
                        (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                    VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), deviceId, sha256(secret));
        }
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), deviceId,
                com.things.link.shared.message.TransportProtocol.HTTP, 30));
        return new Device(deviceId, deviceKey, secret);
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
     * 读取命令失败码。
     *
     * @param commandId 命令 ID
     * @return 失败码；为空时 null
     * @throws SQLException 查询失败
     */
    private String failureCode(UUID commandId) throws SQLException {
        return text("SELECT failure_code FROM ts_device_command WHERE id = ?", commandId);
    }

    /**
     * 读取领取事实状态。
     *
     * @param fixture 独占夹具
     * @param commandId 命令 ID
     * @return 领取状态；无领取事实时 {@code NONE}
     * @throws SQLException 查询失败
     */
    private String claimStatus(Fixture fixture, UUID commandId) throws SQLException {
        return text("SELECT status FROM ts_device_command_claim WHERE project_id = ? AND command_id = ?"
                + " ORDER BY attempt_no DESC LIMIT 1", fixture.projectId(), commandId);
    }

    /**
     * 统计命令的领取事实行数。
     *
     * @param fixture 独占夹具
     * @param commandId 命令 ID
     * @return 行数
     * @throws SQLException 查询失败
     */
    private int claimRowCount(Fixture fixture, UUID commandId) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT count(*) FROM ts_device_command_claim WHERE project_id = ? AND command_id = ?")) {
            statement.setObject(1, fixture.projectId());
            statement.setObject(2, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /**
     * 把租约与响应窗口拨到过去，模拟租约到期。
     *
     * @param commandId 命令 ID
     * @throws SQLException 更新失败
     */
    private void expireLease(UUID commandId) throws SQLException {
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE ts_device_command SET next_attempt_at = now() - interval '1 second',"
                    + " deadline_at = now() - interval '1 second' WHERE id = ?", commandId);
        }
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
     * 播种独占租户、项目、直连设备、命令定义、凭据与 HTTP 平面。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(),
                "ax2c_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'HTTP命令独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name)"
                    + " VALUES (?, ?, '{noop}unused', 'HTTP命令 OWNER')", fixture.accountId(),
                    fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, 'HTTP命令项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax2c_type', 'HTTP命令类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, input_schema, output_schema,
                         timeout_seconds)
                    VALUES (?, ?, ?, ?, 'reboot', '重启', '{}', '{}', 30)
                    """, fixture.definitionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax2c_device', 'HTTP命令设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_credential
                        (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                    VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    sha256(SECRET));
        }
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
     * 一台设备的接入身份与密钥。
     *
     * @param deviceId 设备 ID
     * @param deviceKey 设备短标识
     * @param secret 设备密钥明文（库中只存摘要）
     */
    private record Device(UUID deviceId, String deviceKey, String secret) {
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
