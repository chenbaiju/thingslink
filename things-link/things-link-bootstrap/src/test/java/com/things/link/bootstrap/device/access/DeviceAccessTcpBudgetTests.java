package com.things.link.bootstrap.device.access;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpMetrics;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.application.DeviceCommandMetrics;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 设备面 TCP 的连接与握手预算（接入合同 §6「另设握手、连接预算」）。
 *
 * <p>两项预算都必须挡在认证之前才有意义：连接额度在**接受连接**时就计入（否则慢握手不占额度、指标上也看不见），
 * 首帧（TLS 握手＋认证帧）受独立的握手预算约束（否则一个连上不发帧的客户端能一直占着额度）。用例把连接上限设为
 * 1、握手预算设为 2 秒、失效窗口保持 30 秒，从而能区分「被握手预算关掉」与「被心跳窗口关掉」。</p>
 */
@org.springframework.context.annotation.Import(com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=true",
        "things-link.access.tcp.enabled=true",
        "things-link.access.tcp.port=0",
        "things-link.access.tcp.instance-id=node-a",
        "things-link.access.tcp.heartbeat-seconds=10",
        // 实例连接上限取 1：第二个连接必须被拒，且拒绝原因可诊断。
        "things-link.access.tcp.max-connections=1",
        // 握手预算取 2 秒，远小于 3 × 10 秒的失效窗口：关闭时间本身就是判据。
        "things-link.access.tcp.handshake-timeout-seconds=2",
        // 待发队列预算取 1：第二条命令受理时即应打点，且两条都必须保留。
        "things-link.access.budget.pending-per-device=1"
})
class DeviceAccessTcpBudgetTests extends com.things.link.testing.AbstractKafkaIntegrationTest {

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试证书与私钥所在目录。 */
    private static final Path TLS_FIXTURE_DIRECTORY = Path.of("..", "things-link-ingestion", "src", "test",
            "resources", "tls").toAbsolutePath().normalize();

    /** 测试设备密钥明文。 */
    private static final String SECRET = "dddd4444eeee5555ffff6666aaaa7777dddd4444eeee5555ffff6666aaaa7777";

    /** 信任测试自签证书的客户端上下文。 */
    private static volatile SSLContext clientContext;

    /** 被测 TCP 接入服务器。 */
    @Autowired
    private DeviceAccessTcpServer server;

    /** 连接预算指标：连接数是本片的核心可观测量。 */
    @Autowired
    private DeviceAccessTcpMetrics metrics;

    /** 校验独占限流策略确实被设备面解析。 */
    @Autowired
    private EffectiveQuotaPolicyProvider quotaPolicyProvider;

    /** 接入配置仓储。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** 命令受信核心：走真实受理路径验证待发队列预算。 */
    @Autowired
    private DeviceCommandService commandService;

    /** 指标注册表：直接观察待发超预算打点。 */
    @Autowired
    private MeterRegistry meterRegistry;

    /** 应用角色连接。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 显式事务，保证 RLS 范围在连接首次取出前已设置。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 本类独占夹具。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /**
     * 指向测试 TLS 夹具。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void tlsFixtureProperties(DynamicPropertyRegistry registry) {
        registry.add("things-link.access.tls.certificate",
                () -> "file:" + TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem"));
        registry.add("things-link.access.tls.private-key",
                () -> "file:" + TLS_FIXTURE_DIRECTORY.resolve("device-access-test-key.pem"));
    }

    /** 不残留夹具事实，并等连接额度归还，避免上一个用例的连接影响下一个。 */
    @AfterEach
    void clearFixtures() throws SQLException {
        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(metrics.activeConnections())
                .as("用例结束时连接额度必须已归还").isZero());
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                execute(owner, "DELETE FROM ts_device_command_attempt WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM ts_device_command WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_command_definition WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_connection WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
                if (fixture.quotaPolicyId() != null) {
                    execute(owner, "DELETE FROM sys_quota_policy WHERE id = ?", fixture.quotaPolicyId());
                }
            }
        }
        fixtures.clear();
    }

    /** 超出连接预算的连接被拒且原因可诊断；已有会话不受影响，断开后额度归还。 */
    @Test
    void connectionBudgetRejectsNewConnectionsAndReleasesOnDisconnect() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket established = authenticated(fixture)) {
            try (SSLSocket overCapacity = connect()) {
                JsonNode rejection = readFramePayload(overCapacity, DeviceAccessTcpFrameType.ERROR);
                assertThat(rejection.get("errorCode").asString())
                        .as("过载必须可诊断，而不是裸断开").isEqualTo("RATE_LIMITED");
                assertThat(new DeviceAccessTcpFrameReader(overCapacity.getInputStream()).readFrame()).isNull();
            }

            // 已有会话不被过载拒绝牵连：心跳仍然得到应答。
            writeFrame(established, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFrame(established).type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
        }

        await().atMost(20, SECONDS).untilAsserted(() -> assertThat(metrics.activeConnections()).isZero());

        // 额度归还后同一设备可以重新接入：预算是「同时连接数」而不是「累计次数」。
        try (SSLSocket reconnected = authenticated(fixture)) {
            writeFrame(reconnected, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFrame(reconnected).type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
        }
    }

    /** 连上但不做 TLS 握手／不发首帧：必须在握手预算内被关闭，而不是占着额度等到心跳窗口。 */
    @Test
    void silentConnectionIsClosedInsideHandshakeBudget() throws Exception {
        seed();

        Socket silent = new Socket();
        silent.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 10_000);
        try {
            // 握手预算 2 秒、失效窗口 30 秒：连接数在 10 秒内归还只可能是握手预算生效。
            await().atMost(10, SECONDS).untilAsserted(() -> assertThat(metrics.activeConnections())
                    .as("静默连接必须被握手预算关闭并归还额度").isZero());
            assertThat(server.activeConnectionCount()).isZero();
        } finally {
            silent.close();
        }
    }

    /** 认证面预算：连续失败必须触发退避，而不是让 TCP 端无限次试凭据。 */
    @Test
    void failedAuthenticationConsumesSharedPerDeviceAuthBudget() throws Exception {
        Fixture fixture = seed();

        // 第一次失败按 §6 记入每 projectKey/deviceKey 窗口。
        try (SSLSocket socket = connect()) {
            writeAuth(socket, fixture, "f".repeat(64));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("AUTH_FAILED");
        }

        // 紧接着的第二次必须进入退避（§6 失败指数退避 1s→60s）。没有这一步，TCP 端可以无限次试凭据。
        try (SSLSocket socket = connect()) {
            writeAuth(socket, fixture, "f".repeat(64));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .as("失败后必须退避，而不是继续放行凭据校验").isEqualTo("RATE_LIMITED");
            assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame()).isNull();
        }
    }

    /** 业务预算：TCP 每条业务帧都要扣共享额度，否则一次认证就能换来无限帧上报。 */
    @Test
    void floodingUplinkFramesConsumesSharedBusinessBudget() throws Exception {
        Fixture fixture = seed(true);
        var policy = quotaPolicyProvider.resolveTrustedDeviceProject(fixture.tenantId(), fixture.projectId());
        assertThat(policy.uplinkDeviceRefillPerSecond()).isEqualTo(1L);
        assertThat(policy.uplinkDeviceBurstCapacity()).isEqualTo(2L);

        try (SSLSocket socket = authenticated(fixture)) {
            // 认证先扣一枚令牌；突发 2 保证至少一条上行可受理，后续帧触发限流。
            for (int index = 0; index < 30; index++) {
                writeFrame(socket, DeviceAccessTcpFrameType.UPLINK, report(fixture).getBytes(StandardCharsets.UTF_8));
            }
            writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);

            // 每条请求必须恰有受理或限流结果；心跳应答前排空。
            int rateLimited = 0;
            int accepted = 0;
            DeviceAccessTcpFrameCodec.DecodedFrame frame = readFrame(socket);
            while (frame.type() != DeviceAccessTcpFrameType.HEARTBEAT_ACK) {
                if (frame.type() == DeviceAccessTcpFrameType.ACCEPTED) {
                    assertThat(JSON.readTree(frame.payload()).get("requestType").asString()).isEqualTo("UPLINK");
                    accepted++;
                } else {
                    assertThat(frame.type()).isEqualTo(DeviceAccessTcpFrameType.ERROR);
                    assertThat(JSON.readTree(frame.payload()).get("errorCode").asString()).isEqualTo("RATE_LIMITED");
                    rateLimited++;
                }
                frame = readFrame(socket);
            }
            assertThat(accepted + rateLimited).isEqualTo(30);
            assertThat(accepted).isPositive();
            assertThat(rateLimited).as("超过设备桶后必须回 RATE_LIMITED，而不是静默放行全部帧").isPositive();

            // 业务错误不踢连接：限流之后连接仍可用。
            writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFrame(socket).type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
        }
    }

    /** 构造一条合法属性上报报文。 */
    private static String report(Fixture fixture) {
        return "{\"messageId\":\"" + Uuid7.generate() + "\",\"occurredAt\":\"" + java.time.Instant.now()
                + "\",\"payload\":{\"temperature\":1},\"deviceKey\":\"" + fixture.projectKey() + "\"}";
    }

    /** 发送指定密钥的认证帧。 */
    private void writeAuth(SSLSocket socket, Fixture fixture, String secret) throws IOException {
        String payload = "{\"projectKey\":\"" + fixture.projectKey() + "\",\"deviceKey\":\"ax3e_device\","
                + "\"secret\":\"" + secret + "\"}";
        writeFrame(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, payload.getBytes(StandardCharsets.UTF_8));
    }

    /** 待发队列预算：超出只打点不丢单，且推送形态（不领取）同样能看到信号。 */
    @Test
    void pendingQueueOverBudgetIsRecordedWithoutDroppingCommands() throws Exception {
        Fixture fixture = seed();

        // 设备离线（不建立会话）：受理即进入待发队列，第 2 条开始超预算。
        submitCommand(fixture, "ax3e-queue-1");
        submitCommand(fixture, "ax3e-queue-2");

        assertThat(meterRegistry.find(DeviceCommandMetrics.PENDING_OVER_BUDGET).counter())
                .as("超出待发预算必须打点").isNotNull();
        assertThat(meterRegistry.find(DeviceCommandMetrics.PENDING_OVER_BUDGET).counter().count()).isPositive();
        assertThat(commandCount(fixture)).as("超预算只打点，命令绝不丢弃").isEqualTo(2);
        assertThat(pendingCount(fixture)).isEqualTo(2);
    }

    /**
     * 以真实受理路径提交一条命令。
     *
     * @param fixture 独占夹具
     * @param idempotencyKey 幂等键
     * @return 命令 ID
     */
    private UUID submitCommand(Fixture fixture, String idempotencyKey) {
        return inProject(fixture, () -> commandService.submitTrusted(fixture.projectId(), fixture.deviceId(),
                idempotencyKey, "reboot", JSON.readTree("{}"), fixture.accountId(), null).id());
    }

    /** @return 夹具项目里的命令数 */
    private int commandCount(Fixture fixture) throws SQLException {
        return count("SELECT count(*) FROM ts_device_command WHERE project_id = ?", fixture.projectId());
    }

    /** @return 夹具设备的未终态命令数 */
    private int pendingCount(Fixture fixture) throws SQLException {
        return count("SELECT count(*) FROM ts_device_command WHERE project_id = ? AND target_device_id = ?"
                + " AND status IN ('ACCEPTED','DISPATCHED','ACKNOWLEDGED')",
                fixture.projectId(), fixture.deviceId());
    }

    /** 参数化计数。 */
    private int count(String sql, Object... arguments) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /** 建立连接并完成认证。 */
    private SSLSocket authenticated(Fixture fixture) throws Exception {
        SSLSocket socket = connect();
        String payload = "{\"projectKey\":\"" + fixture.projectKey() + "\",\"deviceKey\":\"ax3e_device\","
                + "\"secret\":\"" + SECRET + "\"}";
        writeFrame(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, payload.getBytes(StandardCharsets.UTF_8));
        assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE).get("status").asString())
                .isEqualTo("OK");
        return socket;
    }

    /** 建立 TLS 连接并完成握手。 */
    private SSLSocket connect() throws Exception {
        SSLSocket socket = (SSLSocket) clientContext().getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 10_000);
        socket.startHandshake();
        socket.setSoTimeout(20_000);
        return socket;
    }

    /** 写一帧。 */
    private static void writeFrame(SSLSocket socket, DeviceAccessTcpFrameType type, byte[] payload)
            throws IOException {
        socket.getOutputStream().write(DeviceAccessTcpFrameCodec.encode(type, payload));
        socket.getOutputStream().flush();
    }

    /** 读取一帧。 */
    private static DeviceAccessTcpFrameCodec.DecodedFrame readFrame(SSLSocket socket) throws IOException {
        DeviceAccessTcpFrameCodec.DecodedFrame frame = new DeviceAccessTcpFrameReader(socket.getInputStream())
                .readFrame();
        assertThat(frame).as("连接在读出帧之前被关闭").isNotNull();
        return frame;
    }

    /** 读取下一帧并断言类型，返回载荷 JSON。 */
    private static JsonNode readFramePayload(SSLSocket socket, DeviceAccessTcpFrameType expected) throws IOException {
        DeviceAccessTcpFrameCodec.DecodedFrame frame = readFrame(socket);
        assertThat(frame.type()).isEqualTo(expected);
        return JSON.readTree(frame.payload());
    }

    /** 惰性构建信任自签证书的客户端上下文。 */
    private static SSLContext clientContext() throws Exception {
        if (clientContext == null) {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustAll()}, new SecureRandom());
            clientContext = context;
        }
        return clientContext;
    }

    /** 仅测试使用的信任管理器。 */
    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * 播种独占租户、项目、直连设备、凭据与 TCP 平面。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        return seed(false);
    }

    /** 为限流用例建立独占的低速率策略，其余用例继续使用默认策略。 */
    private Fixture seed(boolean restrictedBudget) throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), restrictedBudget ? Uuid7.generate() : null,
                "ax3e_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'TCP预算独占租户')", fixture.tenantId());
            if (fixture.quotaPolicyId() != null) {
                execute(owner, """
                        INSERT INTO sys_quota_policy
                            (id, code, uplink_device_refill_per_second, uplink_device_burst_capacity)
                        VALUES (?, ?, 1, 2)
                        """, fixture.quotaPolicyId(), "AX3E_" + fixture.quotaPolicyId().toString().replace("-", "")
                        .substring(0, 27));
                execute(owner, "UPDATE sys_tenant SET quota_policy_id = ? WHERE id = ?",
                        fixture.quotaPolicyId(), fixture.tenantId());
            }
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name)"
                    + " VALUES (?, ?, '{noop}unused', 'TCP预算 OWNER')", fixture.accountId(),
                    fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key)"
                    + " VALUES (?, ?, 'TCP预算项目', ?)", fixture.projectId(), fixture.tenantId(),
                    fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax3e_type', 'TCP预算类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, input_schema, output_schema,
                         timeout_seconds)
                    VALUES (?, ?, ?, ?, 'reboot', '重启', '{}', '{}', 30)
                    """, fixture.definitionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax3e_device', 'TCP预算设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_credential
                        (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                    VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    sha256(SECRET));
        }
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                TransportProtocol.TCP, 30));
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

    /** 计算 SHA-256 摘要。 */
    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
        }
    }

    /** owner 连接。 */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 参数化执行夹具语句。 */
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
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
     * @param quotaPolicyId 测试专用低速率策略；其余用例为 null
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, UUID definitionId,
                           UUID accountId, UUID quotaPolicyId, String projectKey) {
    }
}
