package com.things.link.bootstrap.device.access;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 异常帧分类矩阵：每一种线格式违规都必须回**恰好一帧** {@code ERROR(FRAME_INVALID)} 并关闭，且会话事实留痕。
 *
 * <p>§3.3 冻结的每一种违规（魔数／版本／保留字段／长度／类型／字节不完整）都要能被设备看见并区分于「网络不通」。
 * 用例在**认证之后的会话上**逐项构造坏帧：认证前路径另有 AX-3a 用例覆盖，这里要证明的是会话期内一旦线格式
 * 不可信，平台不会把连接继续当作可用，也不会把同一个故障写成两帧错误。</p>
 */
@org.springframework.context.annotation.Import(com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=true",
        "things-link.access.tcp.enabled=true",
        "things-link.access.tcp.port=0",
        "things-link.access.tcp.instance-id=node-a",
        "things-link.access.tcp.heartbeat-seconds=10",
        "things-link.access.tcp.handshake-timeout-seconds=10"
})
class DeviceAccessTcpFrameRejectionTests extends com.things.link.testing.AbstractKafkaIntegrationTest {

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试证书与私钥所在目录。 */
    private static final Path TLS_FIXTURE_DIRECTORY = Path.of("..", "things-link-ingestion", "src", "test",
            "resources", "tls").toAbsolutePath().normalize();

    /** 测试设备密钥明文。 */
    private static final String SECRET = "eeee5555ffff6666aaaa7777bbbb8888eeee5555ffff6666aaaa7777bbbb8888";

    /** 信任测试自签证书的客户端上下文。 */
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

    /** 头部级违规逐项分类：每一项都回恰好一帧 {@code FRAME_INVALID} 并关闭，会话事实记稳定原因。 */
    @Test
    void everyHeaderViolationReturnsSingleErrorAndClosesSession() throws Exception {
        Fixture fixture = seed();

        List<BadFrame> cases = List.of(
                new BadFrame("魔数", header(0x00, 0x00, 0x01, 0x03, 0, 0)),
                new BadFrame("版本", header(0x54, 0x43, 0x02, 0x03, 0, 0)),
                new BadFrame("保留字段", header(0x54, 0x43, 0x01, 0x03, 9, 0)),
                new BadFrame("长度", header(0x54, 0x43, 0x01, 0x03, 0, 65537)),
                new BadFrame("类型", header(0x54, 0x43, 0x01, 0x33, 0, 0)));

        for (BadFrame badFrame : cases) {
            try (SSLSocket socket = authenticated(fixture)) {
                // 代次必须在会话建立之后读：读早了拿到的是上一代（首例是 0），断言会落在不存在的行上。
                long generation = latestGeneration(fixture);
                socket.getOutputStream().write(badFrame.bytes());
                socket.getOutputStream().flush();

                DeviceAccessTcpFrameCodec.DecodedFrame frame = readFrame(socket);
                assertThat(frame.type()).as("%s 违规必须回 ERROR", badFrame.name())
                        .isEqualTo(DeviceAccessTcpFrameType.ERROR);
                JsonNode error = JSON.readTree(frame.payload());
                assertThat(error.get("errorCode").asString()).as("%s 违规的错误码", badFrame.name())
                        .isEqualTo("FRAME_INVALID");
                assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame())
                        .as("%s 违规只能回一帧，且随后必须关闭", badFrame.name()).isNull();
                assertThat(awaitDisconnectReason(fixture, generation)).as("%s 违规的会话原因", badFrame.name())
                        .isEqualTo("frame_invalid");
            }
        }
    }

    /** 帧中途断开：字节不完整必须与会话「干净断开」区分开（此时已无对端，因此不回帧）。 */
    @Test
    void truncatedFrameIsRecordedAsFrameInvalid() throws Exception {
        Fixture fixture = seed();

        long generation;
        try (SSLSocket socket = authenticated(fixture)) {
            generation = latestGeneration(fixture);
            // 只写 5 字节帧头的一半，然后半关闭输出：服务端读到的就是「帧尚未接收完整」。
            socket.getOutputStream().write(header(0x54, 0x43, 0x01, 0x03, 0, 0), 0, 5);
            socket.getOutputStream().flush();
            socket.shutdownOutput();
        }

        assertThat(awaitDisconnectReason(fixture, generation))
                .as("截断必须记 frame_invalid，而不是消失成 client_disconnect").isEqualTo("frame_invalid");
    }

    /** 建立连接并完成认证。 */
    private SSLSocket authenticated(Fixture fixture) throws Exception {
        SSLSocket socket = connect();
        String payload = "{\"projectKey\":\"" + fixture.projectKey() + "\",\"deviceKey\":\"ax3f_device\","
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
        socket.setSoTimeout(20_000);
        return socket;
    }

    /** 写一帧。 */
    private static void writeFrame(SSLSocket socket, DeviceAccessTcpFrameType type, byte[] payload)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(DeviceAccessTcpFrameCodec.encode(type, payload));
        output.flush();
    }

    /** 读取一帧并断言非空。 */
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

    /**
     * 按冻结布局拼一个帧头。
     *
     * @param magicFirst 魔数首字节
     * @param magicSecond 魔数次字节
     * @param version 版本字节
     * @param type 类型字节
     * @param flags 保留字段（2 字节，大端）
     * @param length 载荷长度（4 字节，大端）
     * @return 10 字节帧头
     */
    private static byte[] header(int magicFirst, int magicSecond, int version, int type, int flags, int length) {
        return new byte[] {(byte) magicFirst, (byte) magicSecond, (byte) version, (byte) type,
                (byte) ((flags >>> 8) & 0xFF), (byte) (flags & 0xFF),
                (byte) ((length >>> 24) & 0xFF), (byte) ((length >>> 16) & 0xFF),
                (byte) ((length >>> 8) & 0xFF), (byte) (length & 0xFF)};
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

    /** @return 当前最新会话代次；没有会话时为 0 */
    private long latestGeneration(Fixture fixture) throws SQLException {
        String generation = text("SELECT coalesce(max(generation), 0)::text FROM dev_connection"
                + " WHERE project_id = ? AND device_id = ?", fixture.projectId(), fixture.deviceId());
        return generation == null ? 0L : Long.parseLong(generation);
    }

    /**
     * 等待指定代次会话收敛并返回断开原因。
     *
     * @param fixture 独占夹具
     * @param generation 会话代次
     * @return 断开原因；超时返回最后一次观察
     */
    private String awaitDisconnectReason(Fixture fixture, long generation) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String reason = "";
        while (System.nanoTime() < deadline) {
            try {
                String current = text("SELECT coalesce(disconnect_reason, '') FROM dev_connection"
                        + " WHERE project_id = ? AND device_id = ? AND generation = ?",
                        fixture.projectId(), fixture.deviceId(), generation);
                if (current != null && !current.isEmpty()) {
                    return current;
                }
                reason = current == null ? "" : current;
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
        return reason;
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
                "ax3f_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'TCP异常帧独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key)"
                    + " VALUES (?, ?, 'TCP异常帧项目', ?)", fixture.projectId(), fixture.tenantId(),
                    fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax3f_type', 'TCP异常帧类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax3f_device', 'TCP异常帧设备', 'ONLINE')
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
     * 一种坏帧用例。
     *
     * @param name 用例名（用于断言说明）
     * @param bytes 线上字节
     */
    private record BadFrame(String name, byte[] bytes) {
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
