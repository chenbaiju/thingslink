package com.things.link.bootstrap.device.access;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapAccessService;
import com.things.link.ingestion.infrastructure.protocol.coap.DeviceAccessCoapServer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration;
import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Option;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConfig;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.CertificateType;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.x509.KeyManagerCertificateProvider;
import org.eclipse.californium.scandium.dtls.x509.StaticNewAdvancedCertificateVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设备面 CoAP/DTLS 的真实客户端旅程（接入合同 §3.4）：只注册 DTLS 端点、未认证一律 4.01、资源未接线回 5.01。
 *
 * <p>用真实 Californium/Scandium 客户端而不是内存替身：本片要证明的正是"DTLS 1.2＋服务端证书＋自定义认证选项"
 * 这条冻结契约与"不提供明文"的姿态，因此握手、证书校验、选项编解码与响应码都必须真实发生。</p>
 */
@SpringBootTest(properties = {
        "things-link.access.coap.enabled=true",
        "things-link.access.coap.port=0"
})
@TestPropertySource(properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"
})
@Import({DeviceDataPlaneTopicsTestConfiguration.class, DeviceProtocolKafkaTopicsTestConfiguration.class})
class DeviceAccessCoapAuthenticationTests extends AbstractKafkaIntegrationTest {

    /** 测试证书与私钥所在目录。 */
    private static final Path TLS_FIXTURE_DIRECTORY = Path.of("..", "things-link-ingestion", "src", "test",
            "resources", "tls").toAbsolutePath().normalize();

    /** 测试设备密钥明文。 */
    private static final String SECRET = "ffff6666aaaa7777bbbb8888cccc9999ffff6666aaaa7777bbbb8888cccc9999";

    /** 与被测端点同源的 DTLS 客户端。 */
    private DeviceAccessCoapServer server;

    /** 接入配置仓储。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** 命令受信核心：走真实受理路径验证领取闭环。 */
    @Autowired
    private DeviceCommandService commandService;

    /** 与生产一致的 JSON 映射器，用于解析响应体与构造夹具报文。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 应用角色连接。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 显式事务，保证 RLS 范围在连接首次取出前已设置。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 本类独占夹具。 */
    private final List<Fixture> fixtures = new ArrayList<>();
    private final java.util.Map<UUID, UUID> acceptedReports = new java.util.HashMap<>();

    /** 被测端点；通过 Spring 注入以复用生产装配。 */
    @Autowired
    private void setServer(DeviceAccessCoapServer server) {
        this.server = server;
    }

    /**
     * 指向测试 TLS 夹具（CoAP 与 TCP 共用同一份 PEM 契约）。
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
        // 受理响应早于 Kafka 消费；清理前等待已受理消息提交，不改变请求重放的时序。
        acceptedReports.forEach((messageId, projectId) ->
                org.awaitility.Awaitility.await().atMost(30, java.util.concurrent.TimeUnit.SECONDS)
                        .untilAsserted(() -> assertThat(count(
                                "SELECT count(*) FROM sys_inbox_message WHERE project_id = ? AND message_id = ?",
                                projectId, messageId)).isEqualTo(1)));
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Connection owner = ownerConnection()) {
                owner.setAutoCommit(false);
                for (Fixture fixture : fixtures) {
                    execute(owner, "DELETE FROM public.ts_property_point_internal WHERE project_id = ?",
                            fixture.projectId());
                    execute(owner, "DELETE FROM sys_inbox_message WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM dev_shadow WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM ts_device_command_claim WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM ts_device_command WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM dev_command_definition WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM dev_device_model_binding_history WHERE project_id = ?",
                            fixture.projectId());
                    execute(owner, "DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
                    execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                    execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
                }
                owner.commit();
                break;
            } catch (SQLException conflict) {
                // Kafka异步写入与清理的外键锁可能成环；整次清理回滚后仅重试死锁。
                if (!"40P01".equals(conflict.getSQLState()) || attempt == 2) throw conflict;
            }
        }
        fixtures.clear();
        acceptedReports.clear();
    }

    /** 端点只注册 DTLS：没有任何明文 UDP 端点可被使用。 */
    @Test
    void endpointExposesOnlyDtlsTransport() {
        assertThat(server.endpointCount()).as("CoAP 端点数量").isEqualTo(1);
        assertThat(server.boundPort()).isPositive();
    }

    /** 明文 CoAP 连不上：DTLS 端口不会回应任何未加密报文（§8.2「禁用明文」）。 */
    @Test
    void plaintextCoapGetsNoResponse() throws Exception {
        seed();

        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout(1_500);
            // 最小合法 CoAP 头：ver=1、CON、TKL=0、code=0.02(POST)、mid=1；DTLS 端点应当完全不理会。
            byte[] plaintext = {(byte) 0x40, (byte) 0x02, 0x00, 0x01};
            socket.send(new java.net.DatagramPacket(plaintext, plaintext.length,
                    java.net.InetAddress.getLoopbackAddress(), server.boundPort()));

            assertThatThrownBy(() -> {
                byte[] buffer = new byte[64];
                socket.receive(new java.net.DatagramPacket(buffer, buffer.length));
            }).as("未加密报文不得得到任何响应").isInstanceOf(java.net.SocketTimeoutException.class);
        }
    }

    /** 重传／响应丢失后的重复请求：同一 messageId 只产生一次业务事实，且重放返回首次受理时刻。 */
    @Test
    void retransmittedRequestIsDeduplicated() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        String body = "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + java.time.Instant.now()
                + "\",\"payload\":{\"temperature\":19.5}}";
        String deviceKey = fixture.projectKey() + "/coap_device";

        // 第一次：真实受理。第二次模拟设备没收到业务响应后的重传（同一 messageId，新 MID）。
        CoapResponse first = send(DeviceAccessCoapAccessService.PROPERTY_REPORT, body, deviceKey, SECRET);
        CoapResponse replay = send(DeviceAccessCoapAccessService.PROPERTY_REPORT, body, deviceKey, SECRET);

        assertThat(first).isNotNull();
        assertThat(replay).isNotNull();
        assertThat(replay.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
        // 冻结口径：同键同摘要的重放返回**首次**受理结果，让设备能对齐同一次尝试。
        assertThat(JSON.readTree(replay.getPayload()).get("receivedAt").asString())
                .isEqualTo(JSON.readTree(first.getPayload()).get("receivedAt").asString());

        org.awaitility.Awaitility.await().atMost(30, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(count("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id = ?"
                    + " AND device_id = ?", fixture.projectId(), fixture.deviceId()))
                    .as("重传不得产生第二个时序点").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM sys_inbox_message WHERE project_id = ? AND message_id = ?",
                    fixture.projectId(), messageId)).isEqualTo(1);
        });
    }

    /** DTLS 对端身份：设备必须能验证平台证书（响应上下文里的对端身份就是服务端证书主体）。 */
    @Test
    void dtlsPeerIdentityIsPlatformCertificate() throws Exception {
        Fixture fixture = seed();

        CoapResponse response = send(DeviceAccessCoapAccessService.PROPERTY_REPORT,
                "{\"messageId\":\"" + Uuid7.generate() + "\",\"occurredAt\":\"" + java.time.Instant.now()
                        + "\",\"payload\":{\"temperature\":2}}",
                fixture.projectKey() + "/coap_device", SECRET);

        assertThat(response).isNotNull();
        java.security.Principal peer = response.advanced().getSourceContext().getPeerIdentity();
        assertThat(peer).as("DTLS 会话必须携带对端身份，而不是匿名的加密通道").isNotNull();
        assertThat(peer.getName()).as("对端身份来自服务端证书主体")
                .contains(testCertificate().getSubjectX500Principal().getName());
    }

    /**
     * 恢复：认证失败进入退避后，退避期内即使凭据正确也被拒（不能靠"改对密码"绕过退避），退避到期后自动恢复。
     */
    @Test
    void deviceRecoversAfterAuthenticationBackoffExpires() throws Exception {
        Fixture fixture = seed();
        String deviceKey = fixture.projectKey() + "/coap_device";
        String body = "{\"messageId\":\"" + Uuid7.generate() + "\",\"occurredAt\":\"" + java.time.Instant.now()
                + "\",\"payload\":{\"temperature\":3}}";

        assertThat(send(DeviceAccessCoapAccessService.PROPERTY_REPORT, body, deviceKey, "f".repeat(64)).getCode())
                .as("错误凭据必须被拒").isEqualTo(CoAP.ResponseCode.UNAUTHORIZED);
        assertThat(send(DeviceAccessCoapAccessService.PROPERTY_REPORT, body, deviceKey, SECRET).getCode())
                .as("退避期内正确凭据同样被拒，避免用改对密码绕过退避")
                .isEqualTo(CoAP.ResponseCode.TOO_MANY_REQUESTS);

        // 冻结退避从 1 秒起（1s→2s→4s…上限 60s）：等它过去后同一设备必须能自己恢复。
        Thread.sleep(2_500);
        assertThat(send(DeviceAccessCoapAccessService.PROPERTY_REPORT, body, deviceKey, SECRET).getCode())
                .as("退避到期后必须恢复受理").isEqualTo(CoAP.ResponseCode.CHANGED);
    }

    /** 平台不改写冻结的重传参数：ACK_TIMEOUT=2s、MAX_RETRANSMIT=4（§3.4 客户端沿用 RFC 7252 默认）。 */
    @Test
    void retransmissionDefaultsStayAtRfcValues() {
        assertThat(server.ackTimeoutMillis()).as("ACK_TIMEOUT 必须保持 2000ms").isEqualTo(2_000);
        assertThat(server.maxRetransmissions()).as("MAX_RETRANSMIT 必须保持 4").isEqualTo(4);
    }

    /** 传输确认与业务结果分离：业务结果必须由独立响应承载，而不是搭在空 ACK 上（§3.4）。 */
    @Test
    void businessResultArrivesAsSeparateResponse() throws Exception {
        Fixture fixture = seed();

        CoapResponse response = send(DeviceAccessCoapAccessService.PROPERTY_REPORT,
                "{\"messageId\":\"" + Uuid7.generate() + "\",\"occurredAt\":\"" + java.time.Instant.now()
                        + "\",\"payload\":{\"temperature\":1}}",
                fixture.projectKey() + "/coap_device", SECRET);

        assertThat(response).isNotNull();
        assertThat(response.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
        // 捎带响应会是 ACK；分离响应是独立消息（CON／NON）。这条断言把"空 ACK 只表示传输确认"钉在线上。
        assertThat(response.advanced().getType())
                .as("业务结果必须是独立响应，不能与传输 ACK 合并")
                .isIn(CoAP.Type.CON, CoAP.Type.NON);
    }

    /** 属性上报闭环：真实 DTLS → 2.04 受理体 → 标准管道落地当前值／历史／计量输入事实。 */
    @Test
    void propertyReportLandsInStandardPipeline() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();

        CoapResponse response = send(DeviceAccessCoapAccessService.PROPERTY_REPORT,
                "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + java.time.Instant.now()
                        + "\",\"payload\":{\"temperature\":27.5}}",
                fixture.projectKey() + "/coap_device", SECRET);

        assertThat(response).isNotNull();
        assertThat(response.getCode()).as("属性上报成功码为 2.04").isEqualTo(CoAP.ResponseCode.CHANGED);
        assertThat(JSON.readTree(response.getPayload()).get("status").asString()).isEqualTo("ACCEPTED");

        org.awaitility.Awaitility.await().atMost(30, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(count("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id = ?"
                    + " AND device_id = ?", fixture.projectId(), fixture.deviceId())).isEqualTo(1);
            assertThat(text("SELECT reported::text FROM dev_shadow WHERE project_id = ? AND device_id = ?",
                    fixture.projectId(), fixture.deviceId())).contains("27.5");
            assertThat(count("SELECT count(*) FROM sys_inbox_message WHERE project_id = ? AND message_id = ?",
                    fixture.projectId(), messageId)).isEqualTo(1);
        });
    }

    /** 无待领取命令：2.04 且空体，让设备稍后再问，而不是把「没有」编码成业务数据。 */
    @Test
    void claimWithoutCommandsReturnsEmptyChanged() throws Exception {
        Fixture fixture = seed();

        CoapResponse response = send(DeviceAccessCoapAccessService.COMMAND_CLAIM, "{}",
                fixture.projectKey() + "/coap_device", SECRET);

        assertThat(response).isNotNull();
        assertThat(response.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
        assertThat(response.getPayload()).isEmpty();
    }

    /** 领取闭环：真实受理产生的命令可被 CoAP 领取（2.05＋冻结字段），回复后进入终态。 */
    @Test
    void claimThenReplyDrivesCommandToTerminalState() throws Exception {
        Fixture fixture = seed();
        UUID commandId = submitCommand(fixture, "ax4b-claim");

        CoapResponse claim = send(DeviceAccessCoapAccessService.COMMAND_CLAIM, "{}",
                fixture.projectKey() + "/coap_device", SECRET);

        assertThat(claim).isNotNull();
        assertThat(claim.getCode()).as("有命令时为 2.05 Content").isEqualTo(CoAP.ResponseCode.CONTENT);
        var commands = JSON.readTree(claim.getPayload()).get("commands");
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).get("commandId").asString()).isEqualTo(commandId.toString());
        assertThat(commands.get(0).get("commandKey").asString()).isEqualTo("reboot");
        assertThat(commands.get(0).get("attempt").asInt()).isEqualTo(1);
        assertThat(commands.get(0).get("leaseExpiresAt").asString()).isNotBlank();
        assertThat(commandState(commandId)).isEqualTo("DISPATCHED/1");

        CoapResponse reply = send(DeviceAccessCoapAccessService.COMMAND_REPLY,
                "{\"commandId\":\"" + commandId + "\",\"messageId\":\"" + Uuid7.generate()
                        + "\",\"occurredAt\":\"" + java.time.Instant.now()
                        + "\",\"status\":\"SUCCESS\",\"output\":{}}",
                fixture.projectKey() + "/coap_device", SECRET);

        assertThat(reply).isNotNull();
        assertThat(reply.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
        assertThat(commandState(commandId)).as("传输响应成功不等于设备执行成功，终态由命令事实追踪")
                .isEqualTo("SUCCEEDED/1");
    }

    /** 回复引用不存在的命令：4.04 且不写入任何命令事实。 */
    @Test
    void replyForUnknownCommandIsNotFound() throws Exception {
        Fixture fixture = seed();

        CoapResponse reply = send(DeviceAccessCoapAccessService.COMMAND_REPLY,
                "{\"commandId\":\"" + Uuid7.generate() + "\",\"messageId\":\"" + Uuid7.generate()
                        + "\",\"occurredAt\":\"" + java.time.Instant.now()
                        + "\",\"status\":\"SUCCESS\",\"output\":{}}",
                fixture.projectKey() + "/coap_device", SECRET);

        assertThat(reply).isNotNull();
        assertThat(reply.getCode()).isEqualTo(CoAP.ResponseCode.NOT_FOUND);
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

    /** @return 命令状态与投递序号 */
    private String commandState(UUID commandId) throws SQLException {
        String state = text("SELECT status || '/' || attempt_count FROM ts_device_command WHERE id = ?", commandId);
        return state == null ? "NONE" : state;
    }

    /** 参数化计数。 */
    private int count(String sql, Object... arguments) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /** 查询单列文本；未命中返回 null。 */
    private String text(String sql, Object... arguments) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** 缺少认证选项的真实 DTLS 请求必须被拒为 4.01，而不是 2.04 或超时。 */
    @Test
    void dtlsRequestWithoutCredentialsIsUnauthorized() throws Exception {
        seed();

        CoapResponse response = send("/device-access/v1/property/report", "{\"messageId\":\"x\"}", null, null);

        assertThat(response).as("DTLS 握手与请求必须有响应").isNotNull();
        assertThat(response.getCode()).isEqualTo(CoAP.ResponseCode.UNAUTHORIZED);
    }

    /** 凭据正确的真实 DTLS 请求进入业务层；资源尚未接线时必须回 5.01，绝不伪造 2.04。 */
    @Test
    void authenticatedDtlsRequestReachesBusinessLayer() throws Exception {
        Fixture fixture = seed();

        CoapResponse response = send("/device-access/v1/property/report", "{\"messageId\":\"" + Uuid7.generate()
                + "\",\"occurredAt\":\"" + java.time.Instant.now() + "\",\"payload\":{\"temperature\":1}}",
                fixture.projectKey() + "/coap_device", SECRET);

        assertThat(response).isNotNull();
        assertThat(response.getCode())
                .as("AX-4b 已接线业务：属性上报必须回到真实受理语义（2.04＋ACCEPTED），"
                        + "5.01 只保留给未来新增但尚未接线的资源")
                .isEqualTo(CoAP.ResponseCode.CHANGED);
        assertThat(JSON.readTree(response.getPayload()).get("status").asString()).isEqualTo("ACCEPTED");
    }

    /** 未知资源回 4.04（不泄露资源清单之外的信息）。 */
    @Test
    void unknownResourceIsNotFound() throws Exception {
        Fixture fixture = seed();

        CoapResponse response = send("/device-access/v1/unknown", "{}", fixture.projectKey() + "/coap_device",
                SECRET);

        assertThat(response).isNotNull();
        assertThat(response.getCode()).isEqualTo(CoAP.ResponseCode.NOT_FOUND);
    }

    /**
     * 发送一次真实 DTLS CoAP 请求。
     *
     * @param path 资源路径
     * @param body 请求体 JSON
     * @param deviceKey 设备身份选项值；为空表示不带
     * @param credential 凭据选项值；为空表示不带
     * @return 响应；无响应时为空
     * @throws Exception 客户端构造或发送失败
     */
    private CoapResponse send(String path, String body, String deviceKey, String credential) throws Exception {
        Configuration configuration = Configuration.createStandardWithoutFile();
        DtlsConnectorConfig clientConfig = new DtlsConnectorConfig.Builder(configuration)
                // 客户端也只启用冻结套件：握手成功即证明服务端协商出的就是它（而不是被降级到别的套件）。
                .setAsList(DtlsConfig.DTLS_CIPHER_SUITES, CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                // Scandium 要求证书套件必须配一份身份材料，客户端也不例外；服务端把客户端证书模式设为 NONE，
                // 既不索要也不校验它——因此"用一份服务端并不信任的自签客户端证书仍能握手成功"恰好证明没有 mTLS 要求。
                .setCertificateIdentityProvider(new KeyManagerCertificateProvider(clientKeyManager(),
                        CertificateType.X_509))
                .setAdvancedCertificateVerifier(new StaticNewAdvancedCertificateVerifier.Builder()
                        // 只测试使用：信任仓库内的自签测试证书，生产按部署信任链校验。
                        .setTrustedCertificates(testCertificate())
                        .setSupportedCertificateTypes(List.of(CertificateType.X_509))
                        .build())
                .build();
        CoapEndpoint endpoint = new CoapEndpoint.Builder()
                .setConfiguration(configuration)
                // 客户端也必须认识 65001／65002，否则 65001 作为关键选项无法出现在请求里。
                .setOptionRegistry(new org.eclipse.californium.core.coap.option.MapBasedOptionRegistry(
                        org.eclipse.californium.core.coap.option.StandardOptionRegistry.getDefaultOptionRegistry(),
                        new org.eclipse.californium.core.coap.option.OpaqueOptionDefinition(
                                DeviceAccessCoapServer.DEVICE_KEY_OPTION, "TC-Device-Key"),
                        new org.eclipse.californium.core.coap.option.OpaqueOptionDefinition(
                                DeviceAccessCoapServer.CREDENTIAL_OPTION, "TC-Credential")))
                .setConnector(new DTLSConnector(clientConfig))
                .build();
        // 不用 CoapClient.Builder：它的可选字段（query）未设置时 create() 会 NPE；直接用 URI 构造更稳。
        CoapClient client = new CoapClient("coaps://127.0.0.1:" + server.boundPort() + path)
                .setEndpoint(endpoint);
        Request request = Request.newPost();
        request.setURI("coaps://127.0.0.1:" + server.boundPort() + path);
        request.setPayload(body.getBytes(StandardCharsets.UTF_8));
        request.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_JSON);
        if (deviceKey != null) {
            request.getOptions().addOption(new Option(DeviceAccessCoapServer.DEVICE_KEY_OPTION, deviceKey));
        }
        if (credential != null) {
            request.getOptions().addOption(new Option(DeviceAccessCoapServer.CREDENTIAL_OPTION, credential));
        }
        try {
            CoapResponse response = client.advanced(request);
            if (DeviceAccessCoapAccessService.PROPERTY_REPORT.equals(path)
                    && response != null && response.getCode() == CoAP.ResponseCode.CHANGED) {
                UUID messageId = UUID.fromString(JSON.readTree(body).get("messageId").asString());
                Fixture fixture = fixtures.stream()
                        .filter(candidate -> deviceKey.equals(candidate.projectKey() + "/coap_device"))
                        .findFirst().orElseThrow();
                acceptedReports.put(messageId, fixture.projectId());
            }
            return response;
        } finally {
            endpoint.destroy();
            client.shutdown();
        }
    }

    /** @return 测试客户端身份；服务端不索要客户端证书，这里只为满足 Scandium 的配置校验 */
    private static javax.net.ssl.X509KeyManager clientKeyManager() {
        return new com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTlsContextFactory()
                .serverKeyManager(new org.springframework.core.io.FileSystemResource(
                                TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem").toFile()),
                        new org.springframework.core.io.FileSystemResource(
                                TLS_FIXTURE_DIRECTORY.resolve("device-access-test-key.pem").toFile()), null);
    }

    /** @return 仓库内的仅测试用自签服务端证书 */
    private static X509Certificate testCertificate() throws Exception {
        try (var input = java.nio.file.Files.newInputStream(
                TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem"))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    /**
     * 播种独占租户、项目、直连设备、凭据与 CoAP 平面。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(),
                "ax4b_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'CoAP独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name)"
                    + " VALUES (?, ?, '{noop}unused', 'CoAP OWNER')", fixture.accountId(),
                    fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key)"
                    + " VALUES (?, ?, 'CoAP项目', ?)", fixture.projectId(), fixture.tenantId(),
                    fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax4a_type', 'CoAP类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, input_schema, output_schema,
                         timeout_seconds)
                    VALUES (?, ?, ?, ?, 'reboot', '重启', '{}', '{}', 30)
                    """, fixture.definitionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'coap_device', 'CoAP设备', 'ONLINE')
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
                "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\","
                        + "\"minimum\":-40,\"maximum\":125}},\"events\":{},\"commands\":{}}");
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                TransportProtocol.COAP, 30));
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
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, UUID definitionId,
                           UUID accountId, String projectKey) {
    }
}
