package com.things.link.bootstrap.integration;

import com.things.link.device.application.DeviceCredentialService;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticator;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.OwnedTestContainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/** 真实TLS认证/控制事务及旧连接；独占Kafka，不连接本机开发消息总线。 */
@OwnedTestContainers({"KAFKA"})
@Import(com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration.class)
@TestPropertySource(properties = {"spring.kafka.admin.auto-create=true", "things-link.access.tcp.enabled=true",
        "things-link.access.tcp.port=0", "things-link.access.tcp.heartbeat-seconds=10"})
class WebhookTcpConfigurationTests extends WebhookFixture {
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    static { KAFKA.start(); }
    private static final Path TLS = Path.of("..", "things-link-ingestion", "src", "test", "resources", "tls").toAbsolutePath().normalize();
    private static final String SECRET = "a3".repeat(32);
    @Autowired private DeviceAccessTcpServer server;
    @Autowired private DeviceCredentialService credentials;
    @Autowired private com.things.link.device.application.DeviceService devices;
    @MockitoSpyBean private DeviceAccessDeviceAuthenticator authenticator;

    /** 随机端口及仓库测试证书，客户端仍验证主机名。 */
    @DynamicPropertySource static void ports(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("things-link.access.tls.certificate", () -> "file:" + TLS.resolve("device-access-test-cert.pem"));
        registry.add("things-link.access.tls.private-key", () -> "file:" + TLS.resolve("device-access-test-key.pem"));
    }

    /** 持久TCP绑定从版本1开始，不混同MQTT无行默认版本0。 */
    @BeforeEach void seedTcp() {
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'TCP')", device, tenant, project);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'tcp-configuration')", tenant, project, device, SECRET);
    }

    /** 仅清理本轮项目，父夹具随后移除设备。 */
    @AfterEach void cleanTcp() {
        for (String table : List.of("dev_connection", "dev_credential", "dev_access_binding", "sys_outbox_event")) {
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
        }
    }

    /** 原认证完成后的结果在途，轮换提交后不能使用旧凭据建立新会话。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CREDENTIAL", "SWITCH", "DISABLE", "DELETE", "ARCHIVED"})
    void controlBetweenAuthenticationAndEstablishmentRejectsOldIdentity(String operation) throws Exception {
        try (SSLSocket initial = connect()) {
            sendAuth(initial, SECRET);
            assertThat(new DeviceAccessTcpFrameReader(initial.getInputStream()).readFrame().type()).isEqualTo(DeviceAccessTcpFrameType.AUTH_RESPONSE);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var once = new AtomicBoolean();
            doAnswer(call -> {
                Object result = call.callRealMethod();
                if (once.compareAndSet(false, true)) {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("真实认证延迟超时");
                }
                return result;
            }).when(authenticator).authenticate(TransportProtocol.TCP, projectKey(), deviceKey(), SECRET);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor(); SSLSocket pending = connect()) {
                var response = executor.submit(() -> {
                    sendAuth(pending, SECRET);
                    return new DeviceAccessTcpFrameReader(pending.getInputStream()).readFrame();
                });
                try {
                    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                    switch (operation) {
                        case "CREDENTIAL" -> asConsole(() -> credentials.generate(project, device));
                        case "SWITCH" -> change("HTTP", true, "1");
                        case "DISABLE" -> change("TCP", false, "1");
                        case "DELETE" -> asConsole(() -> { devices.delete(project, device); return true; });
                        default -> owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", project);
                    }
                    assertThat(sourceCount()).isEqualTo(operation.equals("ARCHIVED") ? 1 : 2);
                    release.countDown();
                    var frame = response.get(15, TimeUnit.SECONDS);
                    assertThat(frame.type()).isEqualTo(DeviceAccessTcpFrameType.ERROR);
                    String error = switch (operation) {
                        case "CREDENTIAL", "DELETE" -> "AUTH_FAILED";
                        case "ARCHIVED" -> "PROJECT_UNAVAILABLE";
                        default -> "DEVICE_DISABLED";
                    };
                    assertThat(json.readTree(frame.payload()).path("errorCode").asString()).isEqualTo(error);
                    assertThat(pending.getInputStream().read()).isEqualTo(-1);
                    assertThat(sourceCount()).isEqualTo(operation.equals("ARCHIVED") ? 1 : 2);
                    assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device))
                            .isEqualTo(operation.equals("ARCHIVED") ? 1 : 0);
                    assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=?", Integer.class, device)).isEqualTo(1);
                } finally { release.countDown(); }
            }
        }
    }

    /** 真正已连接的旧TLS会话在控制提交后拒绝心跳，新认证只恢复当前配置。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ROUND_TRIP", "DISABLE", "CREDENTIAL", "DELETE"})
    void realControlClosesOldTlsSessionAndCurrentAuthenticationCreatesNewGeneration(String operation) throws Exception {
        try (SSLSocket old = connect()) {
            sendAuth(old, SECRET);
            var first = new DeviceAccessTcpFrameReader(old.getInputStream()).readFrame();
            assertThat(first.type()).isEqualTo(DeviceAccessTcpFrameType.AUTH_RESPONSE);
            String currentSecret = SECRET;
            if (operation.equals("ROUND_TRIP")) { change("HTTP", true, "1"); change("TCP", true, "2"); }
            else if (operation.equals("DISABLE")) { change("TCP", false, "1"); change("TCP", true, "2"); }
            else if (operation.equals("CREDENTIAL")) currentSecret = asConsole(() -> credentials.generate(project, device).plainSecret());
            else asConsole(() -> { devices.delete(project, device); return true; });
            assertThat(sourceCount()).isEqualTo(2);
            old.getOutputStream().write(DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]));
            old.getOutputStream().flush();
            var denied = new DeviceAccessTcpFrameReader(old.getInputStream()).readFrame();
            assertThat(denied.type()).isEqualTo(DeviceAccessTcpFrameType.ERROR);
            assertThat(json.readTree(denied.payload()).path("errorCode").asString()).isEqualTo("AUTH_REQUIRED");
            assertThat(old.getInputStream().read()).isEqualTo(-1);
            assertThat(sourceCount()).isEqualTo(2);
            if (!operation.equals("DELETE")) {
                try (SSLSocket current = connect()) {
                    sendAuth(current, currentSecret);
                    var accepted = new DeviceAccessTcpFrameReader(current.getInputStream()).readFrame();
                    assertThat(accepted.type()).isEqualTo(DeviceAccessTcpFrameType.AUTH_RESPONSE);
                    assertThat(json.readTree(accepted.payload()).path("heartbeatIntervalMillis").asLong()).isEqualTo(10000L);
                    assertThat(sourceCount()).isEqualTo(3);
                    var row = owner.queryForMap("SELECT generation,config_version FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", device);
                    assertThat(((Number) row.get("generation")).longValue()).isEqualTo(2);
                    assertThat(((Number) row.get("config_version")).longValue()).isEqualTo(operation.equals("CREDENTIAL") ? 2 : 3);
                    current.getOutputStream().write(DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]));
                    current.getOutputStream().flush();
                    assertThat(new DeviceAccessTcpFrameReader(current.getInputStream()).readFrame().type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
                }
            } else {
                try (SSLSocket deleted = connect()) {
                    sendAuth(deleted, SECRET);
                    assertThat(new DeviceAccessTcpFrameReader(deleted.getInputStream()).readFrame().type()).isEqualTo(DeviceAccessTcpFrameType.ERROR);
                    assertThat(deleted.getInputStream().read()).isEqualTo(-1);
                    assertThat(sourceCount()).isEqualTo(2);
                }
            }
        }
    }

    /** 会话来源失败不能返回成功或留下半条连接；权限恢复后允许真实重试。 */
    @Test void sourceFailureRollsBackAuthenticatedEstablishment() throws Exception {
        owner.execute("REVOKE INSERT ON sys_outbox_event FROM thingslink_app");
        try (SSLSocket denied = connect()) {
            sendAuth(denied, SECRET);
            assertThat(new DeviceAccessTcpFrameReader(denied.getInputStream()).readFrame()).isNull();
        } finally { owner.execute("GRANT INSERT ON sys_outbox_event TO thingslink_app"); }
        assertThat(sourceCount()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=?", Integer.class, device)).isZero();
        try (SSLSocket allowed = connect()) {
            sendAuth(allowed, SECRET);
            assertThat(new DeviceAccessTcpFrameReader(allowed.getInputStream()).readFrame().type()).isEqualTo(DeviceAccessTcpFrameType.AUTH_RESPONSE);
            assertThat(sourceCount()).isEqualTo(1);
        }
    }

    /** 配置变更使用真实Console管理接口，期望版本保持字符串。 */
    private void change(String protocol, boolean enabled, String version) throws Exception {
        var result = request("PUT", "/api/v1/projects/" + project + "/devices/" + device + "/access-config",
                json.writeValueAsString(Map.of("protocol", protocol, "enabled", enabled, "expectedConfigVersion", version)), token(), Map.of());
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    }

    /** 凭据操作使用实际操作者，事务由原服务提供。 */
    private <T> T asConsole(Supplier<T> operation) {
        TenantContext.set(new TenantScope(tenant, project, account));
        try { return operation.get(); } finally { TenantContext.clear(); }
    }
    /** 只计算公开在线来源，不把其他Outbox类型混算。 */
    private int sourceCount() {
        return owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", Integer.class, project);
    }
    /** @return 本轮真实项目键 */
    private String projectKey() { return owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project); }
    /** @return 本轮真实设备键 */
    private String deviceKey() { return owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?", String.class, device); }

    /** 使用真实认证帧，不提供自报配置或凭据版本。 */
    private void sendAuth(SSLSocket socket, String secret) throws Exception {
        socket.getOutputStream().write(DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.AUTH_REQUEST,
                json.writeValueAsBytes(Map.of("projectKey", projectKey(), "deviceKey", deviceKey(), "secret", secret))));
        socket.getOutputStream().flush();
    }

    /** 独占本机服务，固定测试信任根并验证localhost证书。 */
    private SSLSocket connect() throws Exception {
        var trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null);
        try (var input = Files.newInputStream(TLS.resolve("device-access-test-cert.pem"))) {
            trust.setCertificateEntry("test", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(null, managers.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket("localhost", server.boundPort());
        var parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.setSoTimeout(15000);
        try { socket.startHandshake(); return socket; }
        catch (Throwable failure) { socket.close(); throw failure; }
    }
}
