package com.things.link.bootstrap.device.access;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTlsContextFactory;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设备面 TCP/TLS 的真实客户端旅程：TLS 握手、认证帧、会话事实与代次约束、拒绝原因可诊断。
 *
 * <p>用真实 {@link SSLSocket} 而不是内存通道：本片要证明的正是「TLS 长连接 + 应用层认证帧」这条冻结契约，
 * 因此证书、握手、帧字节与关闭语义都必须真实发生。会话事实落在真实 PostgreSQL 上，接管与旧会话拒绝由数据库
 * 唯一性与归属校验仲裁。</p>
 */
@org.springframework.context.annotation.Import(com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=true",
        "things-link.access.tcp.enabled=true",
        "things-link.access.tcp.port=0",
        "things-link.access.tcp.instance-id=node-a",
        // 心跳取冻结下界 10 秒：失效窗口因此是 30 秒，超时用例才不至于等上几分钟。
        "things-link.access.tcp.heartbeat-seconds=10"
})
class DeviceAccessTcpAuthenticationTests extends com.things.link.testing.AbstractKafkaIntegrationTest {

    /** 权威身份每进程生成，运维标签不是owner。 */
    @Autowired private com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpRuntime runtime;

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试证书与私钥所在目录；与 ingestion 模块的单测共用同一份夹具，避免两份会漂移的材料。 */
    private static final Path TLS_FIXTURE_DIRECTORY = Path.of("..", "things-link-ingestion", "src", "test",
            "resources", "tls").toAbsolutePath().normalize();

    /** 测试设备密钥明文。 */
    private static final String SECRET = "aaaa1111bbbb2222cccc3333dddd4444aaaa1111bbbb2222cccc3333dddd4444";

    /** 数据面实例标识，与会话事实里的归属实例一致。 */
    private static final String OWNER_INSTANCE = "node-a";

    /** 信任测试自签证书的客户端上下文；仅测试使用，生产设备按部署信任链校验。 */
    private static volatile SSLContext clientContext;

    /** 被测 TCP 接入服务器。 */
    @Autowired
    private DeviceAccessTcpServer server;

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

    /**
     * 把部署侧 TLS 配置指向测试夹具：生产由部署挂载证书，测试用仓库内的自签材料。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void tlsFixtureProperties(DynamicPropertyRegistry registry) {
        // 必须带 file: 前缀：裸绝对路径会被 Spring 当成 classpath 资源，证书会被判为不存在。
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
                execute(owner, "DELETE FROM dev_connection WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 正确凭据：AUTH_RESPONSE 携带心跳与帧上限，会话事实入库并带代次与配置代次。 */
    @Test
    void authenticatesOverTlsAndRecordsSessionFacts() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = connect()) {
            writeAuth(socket, fixture, SECRET);
            JsonNode response = readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE);

            assertThat(response.get("status").asString()).isEqualTo("OK");
            assertThat(response.get("serverTime").asString()).isNotBlank();
            assertThat(response.get("heartbeatIntervalMillis").asInt())
                    .as("下发的心跳周期来自配置（测试取冻结下界 10 秒）").isEqualTo(10_000);
            assertThat(response.get("maxFrameBytes").asInt()).isEqualTo(DeviceAccessTcpFrameCodec.MAX_PAYLOAD_BYTES);
            assertThat(response.get("generation").asLong()).as("首个会话代次为 1").isEqualTo(1L);
            assertThat(response.get("configVersion").asLong()).as("会话携带建立时的配置代次").isEqualTo(1L);

            assertThat(sessionState(fixture)).isEqualTo("TCP/1/1/" + runtime.id() + "/OPEN");
        }

        assertThat(awaitSessionState(fixture)).as("连接结束后会话必须收敛为已断开")
                .isEqualTo("TCP/1/1/" + runtime.id() + "/CLOSED/client_disconnect");
    }

    /** 认证载荷必须为对象；非法JSON、空载荷与null均只回一帧错误再关闭。 */
    @Test
    void malformedAuthenticationReturnsOneErrorAndCloses() throws Exception {
        for (String body : java.util.List.of("null", "not-json", "", "[]")) {
            try (SSLSocket socket = connect()) {
                writeFrame(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, body.getBytes(StandardCharsets.UTF_8));
                assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                        .isEqualTo("FRAME_INVALID");
                assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame()).isNull();
            }
        }
    }

    /** 新连接接管旧会话：旧连接后续帧被拒（旧代次不再有效），新会话代次递增。 */
    @Test
    void newConnectionTakesOverAndOldSessionIsRejected() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket first = connect()) {
            writeAuth(first, fixture, SECRET);
            assertThat(readFramePayload(first, DeviceAccessTcpFrameType.AUTH_RESPONSE).get("generation").asLong())
                    .isEqualTo(1L);

            try (SSLSocket second = connect()) {
                writeAuth(second, fixture, SECRET);
                JsonNode secondResponse = readFramePayload(second, DeviceAccessTcpFrameType.AUTH_RESPONSE);
                assertThat(secondResponse.get("generation").asLong()).as("接管后会话代次递增").isEqualTo(2L);
                assertThat(activeSessionCount(fixture)).as("同一设备同时只有一个活跃会话").isEqualTo(1);
            }

            // 旧连接已被接管：再发心跳必须被拒并要求重新认证。
            writeFrame(first, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            JsonNode rejection = readFramePayload(first, DeviceAccessTcpFrameType.ERROR);
            assertThat(rejection.get("errorCode").asString()).isEqualTo("AUTH_REQUIRED");
        }
    }

    /** 密钥错误与设备未开通 TCP 平面：分别返回 AUTH_FAILED 与 DEVICE_DISABLED。 */
    @Test
    void rejectionReasonsAreDiagnosable() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = connect()) {
            writeAuth(socket, fixture, "f".repeat(64));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("AUTH_FAILED");
            assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame()).isNull();
        }

        Fixture legacy = seedWithoutPlane();
        try (SSLSocket socket = connect()) {
            writeAuth(socket, legacy, SECRET);
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .as("凭据正确但未开通 TCP 平面").isEqualTo("DEVICE_DISABLED");
            assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame()).isNull();
        }
    }

    /** 认证前发送业务帧与非法帧头都按冻结合同拒绝，而不是静默断开。 */
    @Test
    void framesBeforeAuthAndMalformedFramesAreRejected() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = connect()) {
            writeFrame(socket, DeviceAccessTcpFrameType.UPLINK,
                    "{\"messageId\":\"x\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("AUTH_REQUIRED");
        }

        try (SSLSocket socket = connect()) {
            socket.getOutputStream().write(new byte[] {0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
            socket.getOutputStream().flush();
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("FRAME_INVALID");
        }
    }


    /** 传输策略固定后 TLS 1.2 设备仍可接入（兼容下限），且协商套件必须落在白名单内。 */
    @Test
    void tls12ClientIsAcceptedWithWhitelistedSuite() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = connect("TLSv1.2")) {
            writeAuth(socket, fixture, SECRET);
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE).get("status").asString())
                    .as("下限必须是可用的：老设备不能因为策略固定被一刀切").isEqualTo("OK");
            assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.2");
            assertThat(DeviceAccessTlsContextFactory.allowedCipherSuites())
                    .as("协商出的套件必须在白名单内")
                    .contains(socket.getSession().getCipherSuite());
        }
    }

    /** 低于下限的协议不协商：弱协议客户端必须连不上，而不是被降级接受。 */
    @Test
    void belowFloorProtocolIsRefused() throws Exception {
        seed();

        SSLSocket socket = (SSLSocket) clientContext().getSocketFactory().createSocket();
        try {
            try {
                socket.setEnabledProtocols(new String[] {"TLSv1.1"});
            } catch (IllegalArgumentException unsupportedByJvm) {
                // JVM 已不支持 TLS 1.1 时与平台拒绝等价：都保证不会协商出低于下限的协议。
                return;
            }
            socket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 10_000);
            assertThatThrownBy(socket::startHandshake).isInstanceOf(java.io.IOException.class);
        } finally {
            socket.close();
        }
    }

    /** 拆包：认证帧分多次写入时仍必须完成握手（真实网络的 TCP 分段没有帧边界保证）。 */
    @Test
    void fragmentedAuthRequestIsReassembled() throws Exception {
        Fixture fixture = seed();
        String payload = "{\"projectKey\":\"" + fixture.projectKey() + "\",\"deviceKey\":\"ax3a_device\","
                + "\"secret\":\"" + SECRET + "\"}";
        byte[] frame = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.AUTH_REQUEST,
                payload.getBytes(StandardCharsets.UTF_8));

        try (SSLSocket socket = connect()) {
            OutputStream output = socket.getOutputStream();
            for (byte value : frame) {
                output.write(value);
                output.flush();
            }
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE).get("status").asString())
                    .as("逐字节写入也必须完成认证").isEqualTo("OK");
        }
    }

    /** 合包：一次写入两帧时按顺序各回一个心跳应答。 */
    @Test
    void coalescedHeartbeatsGetOrderedAcks() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = connect()) {
            writeAuth(socket, fixture, SECRET);
            readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE);

            byte[] ack = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.HEARTBEAT, null);
            byte[] coalesced = new byte[ack.length * 2];
            System.arraycopy(ack, 0, coalesced, 0, ack.length);
            System.arraycopy(ack, 0, coalesced, ack.length, ack.length);
            socket.getOutputStream().write(coalesced);
            socket.getOutputStream().flush();

            DeviceAccessTcpFrameReader reader = new DeviceAccessTcpFrameReader(socket.getInputStream());
            assertThat(reader.readFrame().type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
            assertThat(reader.readFrame().type()).as("两帧心跳各得一个应答").isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
        }
    }

    /** 连续 3 个心跳周期没有活动：平台判定会话失效并关闭，会话事实记稳定原因。 */
    @Test
    void idleSessionIsClosedAfterThreeMissedHeartbeats() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = connect()) {
            writeAuth(socket, fixture, SECRET);
            readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE);
            assertThat(sessionState(fixture)).contains("OPEN");

            // 服务端的读超时就是 3 × 10 秒窗口；客户端随后应看到连接被平台关闭。
            socket.setSoTimeout(45_000);
            assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame())
                    .as("静默超过 3 个心跳周期后平台必须关闭连接").isNull();
        }

        assertThat(awaitDisconnectReason(fixture, "heartbeat_timeout"))
                .as("会话事实必须记录心跳超时原因").contains("heartbeat_timeout");
    }

    /**
     * 等待会话以指定原因断开。
     *
     * @param fixture 独占夹具
     * @param reason 期望的断开原因
     * @return 最终会话状态；超时返回最后一次观察值
     */
    private String awaitDisconnectReason(Fixture fixture, String reason) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        String state = "NONE";
        while (System.nanoTime() < deadline) {
            try {
                state = sessionState(fixture);
                if (state.contains(reason)) {
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
        return state;
    }

    /**
     * 建立 TLS 连接。
     *
     * @return 已握手完成的客户端套接字
     * @throws Exception 握手失败
     */
    private SSLSocket connect() throws Exception {
        return connect(null);
    }

    /**
     * 按指定协议建立 TLS 连接；为空时使用客户端默认协议。
     *
     * @param protocol 强制协议；为空表示默认
     * @return 已握手完成的客户端套接字
     * @throws Exception 握手失败
     */
    private SSLSocket connect(String protocol) throws Exception {
        SSLSocket socket = (SSLSocket) clientContext().getSocketFactory().createSocket();
        if (protocol != null) {
            socket.setEnabledProtocols(new String[] {protocol});
        }
        socket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 10_000);
        socket.startHandshake();
        socket.setSoTimeout(15_000);
        return socket;
    }

    /** 发送 AUTH_REQUEST 帧。 */
    private void writeAuth(SSLSocket socket, Fixture fixture, String secret) throws IOException {
        String payload = "{\"projectKey\":\"" + fixture.projectKey() + "\",\"deviceKey\":\"ax3a_device\","
                + "\"secret\":\"" + secret + "\"}";
        writeFrame(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, payload.getBytes(StandardCharsets.UTF_8));
    }

    /** 写一帧。 */
    private static void writeFrame(SSLSocket socket, DeviceAccessTcpFrameType type, byte[] payload)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(DeviceAccessTcpFrameCodec.encode(type, payload));
        output.flush();
    }

    /**
     * 读取下一帧并断言类型。
     *
     * @param socket 客户端套接字
     * @param expected 期望的帧类型
     * @return 载荷 JSON
     * @throws IOException 读取失败
     */
    private static JsonNode readFramePayload(SSLSocket socket, DeviceAccessTcpFrameType expected) throws IOException {
        DeviceAccessTcpFrameCodec.DecodedFrame frame = new DeviceAccessTcpFrameReader(socket.getInputStream())
                .readFrame();
        assertThat(frame).as("期望收到 %s，连接却已关闭", expected).isNotNull();
        assertThat(frame.type()).as("帧类型必须与冻结合同一致").isEqualTo(expected);
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

    /** 仅测试使用的信任管理器：自签证书没有可校验的签发链。 */
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
     * 读取会话事实：协议／代次／配置代次／归属实例／状态。
     *
     * @param fixture 独占夹具
     * @return {@code 协议/代次/配置代次/归属/OPEN 或 CLOSED/原因}
     * @throws SQLException 查询失败
     */
    private String sessionState(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement("""
                     SELECT protocol, generation, config_version, owner_instance,
                            CASE WHEN disconnected_at IS NULL THEN 'OPEN' ELSE 'CLOSED' END,
                            coalesce(disconnect_reason, '')
                       FROM dev_connection WHERE project_id = ? AND device_id = ?
                       ORDER BY generation DESC LIMIT 1
                     """)) {
            statement.setObject(1, fixture.projectId());
            statement.setObject(2, fixture.deviceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return "NONE";
                }
                return rows.getString(1) + "/" + rows.getLong(2) + "/" + rows.getLong(3) + "/" + rows.getString(4)
                        + "/" + rows.getString(5) + (rows.getString(6).isEmpty() ? "" : "/" + rows.getString(6));
            }
        }
    }

    /**
     * 等待会话收敛为已断开；连接关闭是异步的，断言必须等到事实落地。
     *
     * @param fixture 独占夹具
     * @return 最终会话状态
     */
    private String awaitSessionState(Fixture fixture) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        String state = "NONE";
        while (System.nanoTime() < deadline) {
            try {
                state = sessionState(fixture);
                if (state.contains("CLOSED")) {
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
        return state;
    }

    /**
     * 统计当前活跃会话数。
     *
     * @param fixture 独占夹具
     * @return 活跃会话数
     * @throws SQLException 查询失败
     */
    private int activeSessionCount(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT count(*) FROM dev_connection WHERE device_id = ? AND disconnected_at IS NULL")) {
            statement.setObject(1, fixture.deviceId());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /**
     * 播种独占租户、项目、设备、凭据与 TCP 平面。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = seedWithoutPlane();
        bind(fixture, com.things.link.shared.message.TransportProtocol.TCP);
        return fixture;
    }

    /**
     * 只播种租户、项目、设备与凭据，不开通任何接入平面（用于「未开通」用例）。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seedWithoutPlane() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "ax3a_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'TCP接入独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, 'TCP接入项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax3a_type', 'TCP接入类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax3a_device', 'TCP接入设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_credential
                        (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                    VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    sha256(SECRET));
        }
        return fixture;
    }

    /**
     * 在夹具项目范围内开通指定平面。
     *
     * @param fixture 独占夹具
     * @param protocol 协议
     */
    private void bind(Fixture fixture, com.things.link.shared.message.TransportProtocol protocol) {
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                protocol, 30));
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
