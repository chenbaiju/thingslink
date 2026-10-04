package com.things.link.bootstrap.device.access;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.ingestion.application.access.DeviceAccessPushPort;
import com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.testing.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 设备面 TCP 的重连、跨实例接管与代次校验：本地会话句柄不能单独作为「可以推送」的依据。
 *
 * <p>另一个实例完成接管时，本实例的连接在自己的下一次读帧或心跳超时之前并不知情，本地句柄仍是「活的」。
 * 若只信任本地注册表，这段时间里的下行会被投给一个数据库已判为关闭的会话。本类用真实 PostgreSQL 会话事实与
 * 真实 TLS 连接验证：跨实例接管后旧连接被拒、本地推送目标立即失效，重连则产生新代次并成为新的推送目标。</p>
 */
@org.springframework.context.annotation.Import(com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=true",
        "things-link.access.tcp.enabled=true",
        "things-link.access.tcp.port=0",
        "things-link.access.tcp.instance-id=node-a",
        // 心跳取冻结下界 10 秒：失效窗口因此是 30 秒，「窗口内活动仍存活」用例才不至于等上几分钟。
        "things-link.access.tcp.heartbeat-seconds=10",
        // 把派发退避拉到远超用例时长：重连补投用例的「退避被提前」只可能来自加速，不会自然到期变成假绿。
        "things-link.command.dispatch-retry-backoff=600s"
})
class DeviceAccessTcpReconnectTests extends com.things.link.testing.AbstractKafkaIntegrationTest {

    /** 权威身份每进程生成，运维标签不是owner。 */
    @Autowired private com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpRuntime runtime;

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试证书与私钥所在目录。 */
    private static final Path TLS_FIXTURE_DIRECTORY = Path.of("..", "things-link-ingestion", "src", "test",
            "resources", "tls").toAbsolutePath().normalize();

    /** 测试设备密钥明文。 */
    private static final String SECRET = "cccc3333dddd4444eeee5555ffff6666cccc3333dddd4444eeee5555ffff6666";

    /** 另一个实例的标识：用它直接建立会话即等价于「另一实例完成了接管」。 */
    private static final String OTHER_INSTANCE = "node-b";

    /** 信任测试自签证书的客户端上下文。 */
    private static volatile SSLContext clientContext;

    /** 被测 TCP 接入服务器（本类里扮演 node-a）。 */
    @Autowired
    private DeviceAccessTcpServer server;

    /** 会话事实端口：用它模拟另一实例的接管。 */
    @Autowired
    private DeviceAccessSessionPort sessionPort;

    /** 会话内推送端口：直接观察「本地句柄是否仍可写」。 */
    @Autowired
    private DeviceAccessPushPort pushPort;

    /** 接入配置仓储。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** 命令受信核心：走真实受理路径产生 attempt 与下行 Outbox。 */
    @Autowired
    private DeviceCommandService commandService;

    /** 真实下行消费者：把 Outbox 信封送进协议出口。 */
    @Autowired
    private com.things.link.ingestion.infrastructure.protocol.tcp.TcpCommandDownlinkKafkaConsumer downlinkConsumer;

    /** 到期命令扫描器：测试显式驱动它执行权威的续重迁移。 */
    @Autowired
    private DeviceCommandTimeoutScanner timeoutScanner;

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

    /** 不残留夹具事实。 */
    @AfterEach
    void clearFixtures() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                execute(owner, "DELETE FROM ts_device_command_attempt WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM ts_device_command WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_connection WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_command_definition WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 跨实例接管：旧连接被拒、本地推送目标立即失效，而不是继续往一个已关闭的会话写。 */
    @Test
    void crossInstanceTakeoverInvalidatesLocalPushTarget() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket local = authenticated(fixture)) {
            assertThat(sessionState(fixture)).as("本实例持有第一代会话").contains(runtime.id() + "/1");
            assertThat(push(fixture)).as("接管前推送可达").isEqualTo(DeviceAccessPushPort.PushOutcome.DELIVERED);
            assertThat(readFrame(local).type()).isEqualTo(DeviceAccessTcpFrameType.DOWNLINK);

            DeviceAccessSessionPort.Establishment takeover = inProject(fixture, () -> sessionPort.establish(
                    new DeviceAccessSessionPort.EstablishmentRequest(fixture.tenantId(), fixture.projectId(),
                            fixture.deviceId(), TransportProtocol.TCP, "node-b-connection", OTHER_INSTANCE,
                            "10.0.0.9", 30_000L)));
            assertThat(takeover).isInstanceOf(DeviceAccessSessionPort.Establishment.Allowed.class);
            assertThat(((DeviceAccessSessionPort.Establishment.Allowed) takeover).established().generation())
                    .as("接管后会话代次递增").isEqualTo(2L);
            assertThat(sessionState(fixture)).as("活跃会话已归属另一实例").contains("node-b/2");

            assertThat(push(fixture)).as("本地句柄虽仍「活着」，但已不是当前活跃会话，必须拒绝写入")
                    .isEqualTo(DeviceAccessPushPort.PushOutcome.DEFERRED_NOT_OWNER);

            // 旧连接的下一次发帧必须被拒：代次校验发生在会话事实上，而不是只发生在注册表里。
            writeFrame(local, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFramePayload(local, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("AUTH_REQUIRED");
        }

        assertThat(disconnectReason(fixture, 1)).as("第一代会话必须记为被接管").isEqualTo("replaced_by_new_session");
    }

    /** 重连：新连接产生新代次、成为新的推送目标，推送不会落在已断开的旧连接上。 */
    @Test
    void reconnectCreatesNewGenerationAndBecomesThePushTarget() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket first = authenticated(fixture)) {
            assertThat(sessionState(fixture)).contains(runtime.id() + "/1");
        }
        assertThat(awaitSessionState(fixture, 1)).as("第一次连接必须收敛为已断开").contains("CLOSED");

        try (SSLSocket second = authenticated(fixture)) {
            assertThat(sessionState(fixture)).as("重连产生新代次，而不是复用旧会话").contains(runtime.id() + "/2");
            assertThat(push(fixture)).as("推送必须落到重连后的会话").isEqualTo(DeviceAccessPushPort.PushOutcome.DELIVERED);
            JsonNode downlink = readFramePayload(second, DeviceAccessTcpFrameType.DOWNLINK);
            assertThat(downlink.get("commandKey").asString()).isEqualTo("reboot");
        }
    }

    /** 慢连接：窗口内仍有帧的会话不得被心跳窗口误杀（超时是「无活动」判据，不是会话寿命）。 */
    @Test
    void slowConnectionSurvivesWhileFramesArriveWithinTheWindow() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = authenticated(fixture)) {
            // 心跳周期 10 秒、失效窗口 30 秒：等到 20 秒处再发一帧，仍在窗口内，会话必须还活着。
            Thread.sleep(Duration.ofSeconds(20).toMillis());
            writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFrame(socket).type()).as("窗口内的慢连接不得被误判为失效")
                    .isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
            assertThat(sessionState(fixture)).as("会话必须仍在活跃").contains("OPEN");
        }
    }

    /** 重连补投：离线期间接受的命令在设备重连后立即进入投递，而不是继续等完退避。 */
    @Test
    void reconnectStillAcceleratesLegacyOfflineFailure() throws Exception {
        Fixture fixture = seed();
        UUID commandId = submitCommand(fixture, "ax3d-redeliver");

        // 模拟升级前已持久化的离线失败；新广播无会话时不再制造该失败。
        String original = text("SELECT payload FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_DISPATCH'", commandId);
        commandService.recordDispatchFailure(JSON.readValue(original, DeviceCommandDispatch.class),
                com.things.link.shared.message.DeviceCommandDispatchFailure.DISPATCH_DEVICE_OFFLINE, Instant.now());
        assertThat(attemptState(commandId, 1)).isEqualTo("FAILED/DISPATCH_DEVICE_OFFLINE");
        assertThat(nextAttemptInFuture(commandId)).as("离线失败后命令处于退避中").isEqualTo(1);

        try (SSLSocket socket = authenticated(fixture)) {
            await().atMost(15, SECONDS).untilAsserted(() -> assertThat(nextAttemptInFuture(commandId))
                    .as("重连必须把退避提前，而不是让命令继续等").isZero());

            // 交付仍走权威状态机：显式驱动扫描器完成续重（测试 profile 推迟了后台扫描）。
            timeoutScanner.scan();
            assertThat(attemptState(commandId, 2)).as("续重产生第二代尝试").isEqualTo("PENDING/");
            dispatchFromOutbox(fixture, commandId, 2);

            JsonNode downlink = readFramePayload(socket, DeviceAccessTcpFrameType.DOWNLINK);
            assertThat(downlink.get("commandId").asString()).isEqualTo(commandId.toString());
            assertThat(downlink.get("attempt").asInt()).as("重连后投递的是续重后的第二代尝试").isEqualTo(2);
            assertThat(commandState(commandId)).isEqualTo("DISPATCHED/2");
        }
    }

    /**
     * 以真实受理路径产生一条待推送命令并直接推送（不带命令事实，仅验证会话可达性）。
     *
     * @param fixture 独占夹具
     * @return 推送结果
     */
    private DeviceAccessPushPort.PushOutcome push(Fixture fixture) {
        return pushPort.push(new DeviceAccessPushPort.Push(fixture.tenantId(), fixture.projectId(),
                fixture.deviceId(), Uuid7.generate(), "reboot", "{}", 1, Instant.now().plusSeconds(30)));
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
     * 用指定尝试在 Outbox 里冻结的原始信封驱动真实下行消费者。
     *
     * @param fixture 独占夹具
     * @param commandId 命令 ID
     * @param attemptNo 尝试序号
     * @throws SQLException 读取 Outbox 失败
     */
    private void dispatchFromOutbox(Fixture fixture, UUID commandId, int attemptNo) throws SQLException {
        UUID eventId = UUID.fromString(assertPresent(text("SELECT outbox_event_id::text FROM ts_device_command_attempt"
                + " WHERE project_id = ? AND command_id = ? AND attempt_no = ?",
                fixture.projectId(), commandId, attemptNo), "尝试必须带 Outbox 事件"));
        String payload = assertPresent(text("SELECT payload FROM sys_outbox_event WHERE project_id = ? AND id = ?",
                fixture.projectId(), eventId), "Outbox 事件必须存在");
        downlinkConsumer.consume(new ConsumerRecord<>(DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, 0, 0L,
                fixture.deviceId().toString(), JSON.readValue(payload, DeviceCommandDispatch.class)));
    }

    /**
     * 读取指定尝试的状态与失败分类。
     *
     * @param commandId 命令 ID
     * @param attemptNo 尝试序号
     * @return {@code 状态/失败码}
     * @throws SQLException 查询失败
     */
    private String attemptState(UUID commandId, int attemptNo) throws SQLException {
        String state = text("SELECT status || '/' || coalesce(error_code, '') FROM ts_device_command_attempt"
                + " WHERE command_id = ? AND attempt_no = ?", commandId, attemptNo);
        return state == null ? "NONE" : state;
    }

    /**
     * @param commandId 命令 ID
     * @return 命令的下次派发时刻是否仍在未来
     * @throws SQLException 查询失败
     */
    private int nextAttemptInFuture(UUID commandId) throws SQLException {
        return count("SELECT count(*) FROM ts_device_command WHERE id = ?"
                + " AND next_attempt_at > clock_timestamp()", commandId);
    }

    /** 读取命令状态与投递序号。 */
    private String commandState(UUID commandId) throws SQLException {
        return text("SELECT status || '/' || attempt_count FROM ts_device_command WHERE id = ?", commandId);
    }

    /**
     * 断言查询结果存在并原样返回。
     *
     * @param value 查询结果
     * @param message 失败说明
     * @return 非空结果
     */
    private static String assertPresent(String value, String message) {
        assertThat(value).as(message).isNotNull();
        return value;
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
     * 建立连接并完成认证。
     *
     * @param fixture 独占夹具
     * @return 已完成认证的 TLS 连接
     * @throws Exception 连接或认证失败
     */
    private SSLSocket authenticated(Fixture fixture) throws Exception {
        SSLSocket socket = connect();
        String payload = "{\"projectKey\":\"" + fixture.projectKey() + "\",\"deviceKey\":\"ax3d_device\","
                + "\"secret\":\"" + SECRET + "\"}";
        writeFrame(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, payload.getBytes(StandardCharsets.UTF_8));
        assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE).get("status").asString())
                .isEqualTo("OK");
        return socket;
    }

    /** 建立 TLS 连接。 */
    private SSLSocket connect() throws Exception {
        SSLSocket socket = (SSLSocket) clientContext().getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 10_000);
        socket.startHandshake();
        socket.setSoTimeout(40_000);
        return socket;
    }

    /** 写一帧。 */
    private static void writeFrame(SSLSocket socket, DeviceAccessTcpFrameType type, byte[] payload)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(DeviceAccessTcpFrameCodec.encode(type, payload));
        output.flush();
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
     * 读取最近一次会话的协议／归属／代次／状态。
     *
     * @param fixture 独占夹具
     * @return {@code 协议/归属/代次/OPEN 或 CLOSED}
     * @throws SQLException 查询失败
     */
    private String sessionState(Fixture fixture) throws SQLException {
        String state = text("SELECT protocol || '/' || coalesce(owner_instance, '') || '/' || generation || '/'"
                + " || CASE WHEN disconnected_at IS NULL THEN 'OPEN' ELSE 'CLOSED' END"
                + " FROM dev_connection WHERE project_id = ? AND device_id = ? ORDER BY generation DESC LIMIT 1",
                fixture.projectId(), fixture.deviceId());
        return state == null ? "NONE" : state;
    }

    /**
     * 等待指定代次的会话收敛为已断开。
     *
     * @param fixture 独占夹具
     * @param generation 会话代次
     * @return 最终状态
     */
    private String awaitSessionState(Fixture fixture, long generation) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String state = "NONE";
        while (System.nanoTime() < deadline) {
            try {
                state = text("SELECT CASE WHEN disconnected_at IS NULL THEN 'OPEN' ELSE 'CLOSED' END"
                        + " FROM dev_connection WHERE project_id = ? AND device_id = ? AND generation = ?",
                        fixture.projectId(), fixture.deviceId(), generation);
                if (state != null && state.contains("CLOSED")) {
                    return state;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("读取会话事实失败", exception);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return state == null ? "NONE" : state;
    }

    /** 读取指定代次会话的断开原因。 */
    private String disconnectReason(Fixture fixture, long generation) throws SQLException {
        return text("SELECT coalesce(disconnect_reason, '') FROM dev_connection"
                + " WHERE project_id = ? AND device_id = ? AND generation = ?",
                fixture.projectId(), fixture.deviceId(), generation);
    }

    /** 查询单列文本。 */
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
     * 播种独占租户、项目、直连设备、凭据与 TCP 平面。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(),
                "ax3d_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'TCP重连独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name)"
                    + " VALUES (?, ?, '{noop}unused', 'TCP重连 OWNER')", fixture.accountId(),
                    fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key)"
                    + " VALUES (?, ?, 'TCP重连项目', ?)", fixture.projectId(), fixture.tenantId(),
                    fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax3d_type', 'TCP重连类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, input_schema, output_schema,
                         timeout_seconds)
                    VALUES (?, ?, ?, ?, 'reboot', '重启', '{}', '{}', 30)
                    """, fixture.definitionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax3d_device', 'TCP重连设备', 'ONLINE')
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
     * @param accountId 命令请求人
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, UUID definitionId,
                           UUID accountId, String projectKey) {
    }
}
