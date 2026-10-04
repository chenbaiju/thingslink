package com.things.link.bootstrap.device.access;

import com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration;
import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapAccessService;
import com.things.link.ingestion.infrastructure.protocol.coap.DeviceAccessCoapServer;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.telemetry.application.PropertyIngestionService;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 同一候选上 MQTT 与三个接入平面并存时的整合行为（接入合同 §4.4／§4.5、AX-6 的混合负载与隔离）。
 *
 * <p>本类回答三个只有「四协议同进程」才能回答的问题，全部结论都要求真实 PostgreSQL／Kafka／TLS 会话：</p>
 * <ol>
 *   <li>混合负载互不串线：同一项目里四个设备分别走 MQTT、HTTP、TCP、CoAP 上报，各自只落自己的当前值与历史，
 *       平面资格也互不通用（未开通该平面的设备一律拒绝，而不是被别人的凭据带进来）。</li>
 *   <li>跨协议去重：同一设备「切平面」后重放同一 {@code messageId}（HTTP 先受理、改绑 TCP 后重放），
 *       命中同一份接入幂等事实 → 不重复落地、不覆盖首次受理协议；同键异载荷映射为
 *       {@code IDEMPOTENCY_CONFLICT}（TCP 回 {@code ERROR}）而不是当成新请求。</li>
 *   <li>MQTT 与接入平面共用一层业务幂等：MQTT 已投递的 {@code messageId} 由接入平面重放时，
 *       接入层会受理（新的幂等键），但业务事实仍只有一份。</li>
 * </ol>
 *
 * <p>MQTT 一侧按既有链路直接调用标准信封入口（EMQX 回调／raw 消费者最终调用的同一个函数），因为本类要证明的是
 * 「同候选上的隔离与幂等」而不是 Broker 自身行为；三协议一侧全部走真实线协议。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "things-link.access.tcp.enabled=true",
        "things-link.access.tcp.port=0",
        "things-link.access.tcp.instance-id=node-a",
        "things-link.access.coap.enabled=true",
        "things-link.access.coap.port=0",
        // 心跳取冻结下界 10 秒：本类不做超时判定，但让夹具失败能快速暴露。
        "things-link.access.tcp.heartbeat-seconds=10",
        // 本片验证同候选上的并存、隔离与去重，不验证预算：认证预算是跨协议共享的按来源 IP 固定窗口，
        // 同一 JVM 内的其他用例与多次往返会先把它耗尽，从而把 DEVICE_DISABLED 掩盖成 RATE_LIMITED。
        // 预算本身由 AX-1f／AX-3e 的专项用例覆盖，这里抬高以免整合结论被无关预算污染。
        "things-link.access.budget.auth-per-minute-per-ip=6000",
        "things-link.access.budget.auth-per-minute-per-device=6000"
})
@TestPropertySource(properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"
})
@Import({DeviceDataPlaneTopicsTestConfiguration.class, DeviceProtocolKafkaTopicsTestConfiguration.class})
class DeviceAccessMixedProtocolIntegrationTests extends AbstractKafkaIntegrationTest {

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试证书与私钥所在目录。 */
    private static final Path TLS_FIXTURE_DIRECTORY = Path.of("..", "things-link-ingestion", "src", "test",
            "resources", "tls").toAbsolutePath().normalize();

    /** HTTP 平面设备密钥明文。 */
    private static final String HTTP_SECRET = "1111222233334444555566667777888811112222333344445555666677778888";

    /** TCP 平面设备密钥明文。 */
    private static final String TCP_SECRET = "9999888877776666555544443333222299998888777766665555444433332222";

    /** CoAP 平面设备密钥明文。 */
    private static final String COAP_SECRET = "aaaabbbbccccddddeeeeffff00001111aaaabbbbccccddddeeeeffff00001111";

    /** HTTP 平面设备键。 */
    private static final String HTTP_DEVICE = "mixed_http";

    /** TCP 平面设备键。 */
    private static final String TCP_DEVICE = "mixed_tcp";

    /** CoAP 平面设备键。 */
    private static final String COAP_DEVICE = "mixed_coap";

    /** 存量 MQTT 设备密钥明文。 */
    private static final String MQTT_SECRET = "5555666677778888999900001111222255556666777788889999000011112222";

    /** 无接入配置的存量 MQTT 设备键。 */
    private static final String MQTT_DEVICE = "mixed_mqtt";

    /** 仅用于「未开通 CoAP 平面」判定的存量设备键；与 MQTT 设备分开，避免同键失败退避掩盖平面判定。 */
    private static final String LEGACY_DEVICE = "mixed_legacy";

    /** 上述存量设备密钥明文。 */
    private static final String LEGACY_SECRET = "6666777788889999000011112222333366667777888899990000111122223333";

    /** 与数据面夹具一致的物模型快照：仅一个可上报的 NUMBER 属性。 */
    private static final String TEMPERATURE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\","
                    + "\"minimum\":-40,\"maximum\":125}},\"events\":{},\"commands\":{}}";

    /** 真实 HTTP 客户端。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** 真实 HTTP 监听端口。 */
    @LocalServerPort
    private int port;

    /** 被测 TCP 接入服务器。 */
    @Autowired
    private DeviceAccessTcpServer tcpServer;

    /** 被测 CoAP 接入服务器；通过 Spring 注入以复用生产装配。 */
    private DeviceAccessCoapServer coapServer;

    /** 接入配置仓储；测试显式改绑平面以模拟协议迁移。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** MQTT 一侧的标准管道入口：与 EMQX 回调／raw 消费者调用的是同一个函数。 */
    @Autowired
    private PropertyIngestionService ingestion;

    /** 应用角色连接。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 显式事务，保证 RLS 范围在连接首次取出前已设置。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 本类独占夹具。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 信任测试自签证书的客户端上下文。 */
    private static volatile SSLContext clientContext;

    /**
     * 注入 CoAP 端点。
     *
     * @param server 被测端点
     */
    @Autowired
    private void setCoapServer(DeviceAccessCoapServer server) {
        this.coapServer = server;
    }

    /**
     * 把部署侧 TLS 配置指向测试夹具（TCP 与 CoAP 共用同一份 PEM 契约）。
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
                // 原始时序点必须先清：物模型版本删除有 MODEL_RAW_REFERENCE_REMAINS 守卫。
                execute(owner, "DELETE FROM public.ts_property_point_internal WHERE project_id = ?",
                        fixture.projectId());
                execute(owner, "DELETE FROM ts_device_message_log WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_message_log_inbox WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_inbox_message WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_usage_counter_daily WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_shadow WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_request WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_connection WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device_model_binding_history WHERE project_id = ?",
                        fixture.projectId());
                execute(owner, "DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 共享标准化器拒绝JSON null时，HTTP与CoAP仍须给出稳定业务拒绝。 */
    @Test
    void nullReportIsBusinessRejectionAcrossHttpAndCoap() throws Exception {
        Fixture fixture = seed();
        HttpResult http = post(fixture, HTTP_DEVICE, HTTP_SECRET, "null");
        assertThat(http.status()).isEqualTo(400);
        assertThat(errorCode(http)).isEqualTo("PAYLOAD_INVALID");
        CoapResponse coap = coapSend(DeviceAccessCoapAccessService.PROPERTY_REPORT,
                "null", fixture.projectKey() + "/" + COAP_DEVICE, COAP_SECRET);
        assertThat(coap).isNotNull();
        assertThat(coap.getCode()).isEqualTo(CoAP.ResponseCode.BAD_REQUEST);
        // CoAP既有拒绝合同使用响应码4.00，不要求HTTP的errorCode字段。
        assertThat(historyCount(fixture, fixture.httpDeviceId())).isZero();
        assertThat(historyCount(fixture, fixture.coapDeviceId())).isZero();
    }

    /** 同一候选上四协议混合负载：每个设备只落自己的事实，平面资格也互不通用。 */
    @Test
    void fourPlanesCoexistAndStayIsolated() throws Exception {
        Fixture fixture = seed();
        Instant occurredAt = Instant.now();
        UUID httpMessageId = Uuid7.generate();
        UUID tcpMessageId = Uuid7.generate();
        UUID coapMessageId = Uuid7.generate();
        UUID mqttMessageId = Uuid7.generate();

        HttpResult http = post(fixture, HTTP_DEVICE, HTTP_SECRET, report(httpMessageId, occurredAt, "26.5"));
        assertThat(http.status()).isEqualTo(202);
        assertThat(JSON.readTree(http.body()).get("messageId").asString()).isEqualTo(httpMessageId.toString());

        try (SSLSocket socket = authenticated(fixture, TCP_DEVICE, TCP_SECRET)) {
            writeFrame(socket, DeviceAccessTcpFrameType.UPLINK,
                    report(tcpMessageId, occurredAt, "27.5").getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ACCEPTED).get("messageId").asString())
                    .isEqualTo(tcpMessageId.toString());
            writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFrame(socket).type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
        }

        CoapResponse coap = coapSend(DeviceAccessCoapAccessService.PROPERTY_REPORT,
                report(coapMessageId, occurredAt, "28.5"), fixture.projectKey() + "/" + COAP_DEVICE, COAP_SECRET);
        assertThat(coap).as("CoAP 必须回响应").isNotNull();
        assertThat(coap.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
        assertThat(JSON.readTree(coap.getPayload()).get("messageId").asString()).isEqualTo(coapMessageId.toString());

        assertThat(ingestMqtt(fixture, mqttMessageId, occurredAt, 29.5)).isTrue();

        await().atMost(30, SECONDS).untilAsserted(() -> {
            // 四条链路各自落地一次，且项目内总数就是四：既没有丢，也没有互相串线。
            assertThat(historyCount(fixture, fixture.httpDeviceId())).isEqualTo(1);
            assertThat(historyCount(fixture, fixture.tcpDeviceId())).isEqualTo(1);
            assertThat(historyCount(fixture, fixture.coapDeviceId())).isEqualTo(1);
            assertThat(historyCount(fixture, fixture.mqttDeviceId())).isEqualTo(1);
            assertThat(projectHistoryCount(fixture)).isEqualTo(4);
        });
        assertThat(currentValue(fixture, fixture.httpDeviceId())).contains("26.5");
        assertThat(currentValue(fixture, fixture.tcpDeviceId())).contains("27.5");
        assertThat(currentValue(fixture, fixture.coapDeviceId())).contains("28.5");
        assertThat(currentValue(fixture, fixture.mqttDeviceId())).contains("29.5");

        // 平面资格隔离：设备只能走自己开通的平面，凭据正确也不例外。
        HttpResult tcpDeviceOnHttp = post(fixture, TCP_DEVICE, TCP_SECRET, report(Uuid7.generate(), occurredAt, "1"));
        assertThat(tcpDeviceOnHttp.status()).as("TCP 平面设备不得走 HTTP").isEqualTo(403);
        assertThat(errorCode(tcpDeviceOnHttp)).isEqualTo("DEVICE_DISABLED");
        HttpResult mqttDeviceOnHttp = post(fixture, MQTT_DEVICE, MQTT_SECRET,
                report(Uuid7.generate(), occurredAt, "2"));
        assertThat(mqttDeviceOnHttp.status()).as("无接入配置的存量 MQTT 设备不得走 HTTP").isEqualTo(403);
        assertThat(errorCode(mqttDeviceOnHttp)).isEqualTo("DEVICE_DISABLED");
        // 未开通 CoAP 平面的存量设备：4.03。用独立身份而不是刚在 HTTP 上被拒的那台——冻结口径下平面拒绝
        // 同样计入认证失败退避，同一身份在退避窗口内改走另一平面会先命中 4.29，掩盖本片要验证的平面判定。
        CoapResponse legacyDeviceOnCoap = coapSend(DeviceAccessCoapAccessService.PROPERTY_REPORT,
                report(Uuid7.generate(), occurredAt, "3"), fixture.projectKey() + "/" + LEGACY_DEVICE, LEGACY_SECRET);
        assertThat(legacyDeviceOnCoap).isNotNull();
        assertThat(legacyDeviceOnCoap.getCode()).isEqualTo(CoAP.ResponseCode.FORBIDDEN);

        try (SSLSocket socket = connect()) {
            writeAuth(socket, fixture, HTTP_DEVICE, HTTP_SECRET);
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .as("HTTP 平面设备不得在 TCP 平面上认证通过").isEqualTo("DEVICE_DISABLED");
        }

        // 拒绝之后不得留下任何业务副作用：四个设备仍各自只有一个点。
        assertThat(projectHistoryCount(fixture)).isEqualTo(4);
    }

    /** 协议迁移后的同键重放：命中同一份接入幂等事实，不重复落地且不覆盖首次受理协议。 */
    @Test
    void replayAfterPlaneMigrationIsDeduplicatedAndConflictIsRejected() throws Exception {
        Fixture fixture = seed();
        Instant occurredAt = Instant.now();
        UUID messageId = Uuid7.generate();
        // 同一份报文原样重放：接入幂等按「原始请求体摘要」比较，两次必须是同一串字节。
        String body = report(messageId, occurredAt, "31.5");

        HttpResult first = post(fixture, HTTP_DEVICE, HTTP_SECRET, body);
        assertThat(first.status()).isEqualTo(202);
        assertThat(JSON.readTree(first.body()).get("receivedAt").asString()).isNotBlank();
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(historyCount(fixture, fixture.httpDeviceId()))
                .isEqualTo(1));

        // 设备整机切到 TCP 平面（接入配置换协议，配置代次递增），随后重放同一 messageId。
        rebind(fixture, fixture.httpDeviceId(), TransportProtocol.TCP);
        assertThat(bindingProtocol(fixture, fixture.httpDeviceId())).isEqualTo("TCP");

        try (SSLSocket socket = authenticated(fixture, HTTP_DEVICE, HTTP_SECRET)) {
            writeFrame(socket, DeviceAccessTcpFrameType.UPLINK, body.getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ACCEPTED).get("status").asString())
                    .isEqualTo("ACCEPTED");
            writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFrame(socket).type())
                    .as("同键同摘要的重放不是错误：连接必须保持可用且不告警").isEqualTo(
                            DeviceAccessTcpFrameType.HEARTBEAT_ACK);

            // 同键异载荷：必须映射为 IDEMPOTENCY_CONFLICT，且连接仍可用（业务类错误不关连接）。
            writeFrame(socket, DeviceAccessTcpFrameType.UPLINK,
                    report(messageId, occurredAt, "32.5").getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");
            writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFrame(socket).type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
        }

        // 业务事实仍然只有一份，且接入幂等事实保留首次受理协议（HTTP）与首次摘要（未被异载荷覆盖）。
        assertThat(historyCount(fixture, fixture.httpDeviceId())).isEqualTo(1);
        assertThat(inboxCount(fixture, messageId)).isEqualTo(1);
        assertThat(accessRequest(fixture, fixture.httpDeviceId(), messageId)).isEqualTo("HTTP/" + digest(body));
        assertThat(normalizedHandoffCount(fixture, fixture.httpDeviceId(), messageId))
                .as("接入层重放不得再次交接，不能由业务Inbox掩盖重复Kafka记录").isEqualTo(1L);
    }

    /** 协议应答已经晚于发布ACK；冻结end offsets后完整读到边界，不靠空poll判断不存在。 */
    private long normalizedHandoffCount(Fixture fixture, UUID device, UUID message) {
        String topic = com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;
        java.util.Properties properties = new java.util.Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("group.id", "dedup-observer-" + UUID.randomUUID());
        properties.put("enable.auto.commit", "false");
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("default.api.timeout.ms", "10000");
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(properties)) {
            var partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new org.apache.kafka.common.TopicPartition(topic, info.partition())).toList();
            assertThat(partitions).isNotEmpty();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var end = consumer.endOffsets(partitions);
            long count = 0;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                assertThat(System.nanoTime()).as("必须读完冻结的Kafka边界，不能超时假绿").isLessThan(deadline);
                for (var record : consumer.poll(java.time.Duration.ofMillis(100))) {
                    var partition = new org.apache.kafka.common.TopicPartition(record.topic(), record.partition());
                    if (record.offset() >= end.get(partition)) continue;
                    var body = JSON.readTree(record.value());
                    if (body.path("messageId").asString().equals(message.toString())
                            && body.path("deviceId").asString().equals(device.toString())
                            && body.path("projectId").asString().equals(fixture.projectId().toString())
                            && body.path("tenantId").asString().equals(fixture.tenantId().toString())) count++;
                }
            }
            return count;
        }
    }

    /** MQTT 已投递的消息由接入平面重放：接入层受理，但业务事实仍只有一份。 */
    @Test
    void mqttDeliveryThenAccessPlaneReplayKeepsSingleBusinessFact() throws Exception {
        Fixture fixture = seed();
        Instant occurredAt = Instant.now();
        UUID messageId = Uuid7.generate();

        assertThat(ingestMqtt(fixture, messageId, occurredAt, 33.5)).isTrue();
        // 同一信封再次进入标准管道（MQTT 侧重投）必须被业务幂等挡住，而不是再落一个点。
        assertThat(ingestMqtt(fixture, messageId, occurredAt, 33.5)).isFalse();
        await().atMost(30, SECONDS).untilAsserted(() -> {
            assertThat(historyCount(fixture, fixture.mqttDeviceId())).isEqualTo(1);
            assertThat(inboxCount(fixture, messageId)).isEqualTo(1);
        });

        // 设备改绑 HTTP 平面后重放同一 messageId：接入层是新幂等键，业务层必须挡住重复事实。
        rebind(fixture, fixture.mqttDeviceId(), TransportProtocol.HTTP);
        HttpResult replay = post(fixture, MQTT_DEVICE, MQTT_SECRET, report(messageId, occurredAt, "33.5"));
        assertThat(replay.status()).isEqualTo(202);

        assertThat(historyCount(fixture, fixture.mqttDeviceId()))
                .as("MQTT 与接入平面共用一层业务幂等，重放不得产生第二个历史点").isEqualTo(1);
        assertThat(inboxCount(fixture, messageId)).isEqualTo(1);
        assertThat(accessRequest(fixture, fixture.mqttDeviceId(), messageId))
                .as("接入幂等事实记录的是接入平面自身的首次受理").isEqualTo("HTTP/" + digest(report(messageId,
                        occurredAt, "33.5")));
    }

    /**
     * 构造属性上报请求体。
     *
     * @param messageId 消息标识
     * @param occurredAt 设备发生时刻
     * @param temperature 属性值
     * @return 请求体
     */
    private static String report(UUID messageId, Instant occurredAt, String temperature) {
        return "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + occurredAt + "\",\"payload\":{\"temperature\":"
                + temperature + "}}";
    }

    /**
     * 发送一次设备面 HTTP 属性上报。
     *
     * @param fixture 独占夹具
     * @param deviceKey 设备键
     * @param secret 设备密钥明文
     * @param body 请求体
     * @return HTTP 状态与响应体
     */
    private HttpResult post(Fixture fixture, String deviceKey, String secret, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + DeviceAccessCoapAccessService.PROPERTY_REPORT))
                .header("Content-Type", "application/json")
                .header("X-TC-Device-Key", fixture.projectKey() + "/" + deviceKey)
                .header("X-TC-Device-Secret", secret)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("设备面 HTTP 请求失败", exception);
        }
    }

    /**
     * 解析设备面错误码。
     *
     * @param result HTTP 结果
     * @return 冻结错误码
     */
    private static String errorCode(HttpResult result) {
        return JSON.readTree(result.body()).get("errorCode").asString();
    }

    /**
     * 发送一次真实 DTLS CoAP 请求。
     *
     * @param path 资源路径
     * @param body 请求体 JSON
     * @param deviceKey 设备身份选项值
     * @param credential 凭据选项值
     * @return 响应；无响应时为空
     * @throws Exception 客户端构造或发送失败
     */
    private CoapResponse coapSend(String path, String body, String deviceKey, String credential) throws Exception {
        Configuration configuration = Configuration.createStandardWithoutFile();
        DtlsConnectorConfig clientConfig = new DtlsConnectorConfig.Builder(configuration)
                // 客户端也只启用冻结套件：握手成功即证明服务端协商出的就是它（而不是被降级到别的套件）。
                .setAsList(DtlsConfig.DTLS_CIPHER_SUITES, CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
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
        CoapClient client = new CoapClient("coaps://127.0.0.1:" + coapServer.boundPort() + path).setEndpoint(endpoint);
        Request request = Request.newPost();
        request.setURI("coaps://127.0.0.1:" + coapServer.boundPort() + path);
        request.setPayload(body.getBytes(StandardCharsets.UTF_8));
        request.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_JSON);
        request.getOptions().addOption(new Option(DeviceAccessCoapServer.DEVICE_KEY_OPTION, deviceKey));
        request.getOptions().addOption(new Option(DeviceAccessCoapServer.CREDENTIAL_OPTION, credential));
        try {
            return client.advanced(request);
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
        try (var input = Files.newInputStream(TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem"))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    /**
     * MQTT 一侧的标准管道入口。
     *
     * @param fixture 独占夹具
     * @param messageId 消息标识
     * @param occurredAt 设备发生时刻
     * @param temperature 属性值
     * @return true 表示本次真的落库；false 表示被业务幂等判定为重复
     */
    private boolean ingestMqtt(Fixture fixture, UUID messageId, Instant occurredAt, double temperature) {
        Instant receivedAt = Instant.now();
        StandardUplinkMessage message = new StandardUplinkMessage(messageId, fixture.tenantId(), fixture.projectId(),
                fixture.mqttDeviceId(), null, TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", occurredAt, receivedAt, "ax6a-mixed", 32,
                java.util.Map.of("temperature", temperature));
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return ingestion.ingest(message);
        }
    }

    /**
     * 建立 TLS 连接并完成认证。
     *
     * @param fixture 独占夹具
     * @param deviceKey 设备键
     * @param secret 设备密钥明文
     * @return 已完成认证的 TLS 连接
     * @throws Exception 连接或认证失败
     */
    private SSLSocket authenticated(Fixture fixture, String deviceKey, String secret) throws Exception {
        SSLSocket socket = connect();
        writeAuth(socket, fixture, deviceKey, secret);
        assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE).get("status").asString())
                .as("认证必须成功，否则后续业务断言无从谈起").isEqualTo("OK");
        return socket;
    }

    /** 写一帧认证请求。 */
    private static void writeAuth(SSLSocket socket, Fixture fixture, String deviceKey, String secret)
            throws IOException {
        String payload = "{\"projectKey\":\"" + fixture.projectKey() + "\",\"deviceKey\":\"" + deviceKey
                + "\",\"secret\":\"" + secret + "\"}";
        writeFrame(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, payload.getBytes(StandardCharsets.UTF_8));
    }

    /** 建立 TLS 连接。 */
    private SSLSocket connect() throws Exception {
        SSLSocket socket = (SSLSocket) clientContext().getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress("127.0.0.1", tcpServer.boundPort()), 10_000);
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

    /** 读取影子里的当前值。 */
    private String currentValue(Fixture fixture, UUID deviceId) throws SQLException {
        String reported = text("SELECT reported::text FROM dev_shadow WHERE project_id = ? AND device_id = ?",
                fixture.projectId(), deviceId);
        return reported == null ? "" : reported;
    }

    /** 统计某设备的时序点数（历史）。 */
    private int historyCount(Fixture fixture, UUID deviceId) throws SQLException {
        // ts_property_point 是 security_barrier 视图（按 app_current_project 过滤），owner 旁观必须读内部表。
        return count("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id = ? AND device_id = ?",
                fixture.projectId(), deviceId);
    }

    /** 统计项目内全部时序点数：混合负载必须既不丢也不串。 */
    private int projectHistoryCount(Fixture fixture) throws SQLException {
        return count("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id = ?",
                fixture.projectId());
    }

    /** 统计业务侧幂等事实。 */
    private int inboxCount(Fixture fixture, UUID messageId) throws SQLException {
        return count("SELECT count(*) FROM sys_inbox_message WHERE project_id = ? AND message_id = ?",
                fixture.projectId(), messageId);
    }

    /** 读取接入幂等事实的「首次受理协议／首次载荷摘要」。 */
    private String accessRequest(Fixture fixture, UUID deviceId, UUID messageId) throws SQLException {
        return text("SELECT protocol || '/' || payload_digest FROM dev_access_request"
                + " WHERE project_id = ? AND device_id = ? AND message_id = ?",
                fixture.projectId(), deviceId, messageId);
    }

    /** 读取接入配置上的协议，用于确认改绑真的生效。 */
    private String bindingProtocol(Fixture fixture, UUID deviceId) throws SQLException {
        return text("SELECT protocol FROM dev_access_binding WHERE project_id = ? AND device_id = ?",
                fixture.projectId(), deviceId);
    }

    /**
     * 在夹具项目范围内改绑设备的接入平面（模拟整机切协议）。
     *
     * @param fixture 独占夹具
     * @param deviceId 设备
     * @param protocol 新平面
     */
    private void rebind(Fixture fixture, UUID deviceId, TransportProtocol protocol) {
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), deviceId, protocol,
                30));
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
     * 播种独占租户、项目、一个类型与四个设备的接入配置。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "ax6a_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '混合协议整合租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key)"
                    + " VALUES (?, ?, '混合协议整合项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax6a_type', '混合协议类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            seedDevice(owner, fixture, fixture.httpDeviceId(), HTTP_DEVICE, "混合协议 HTTP 设备", HTTP_SECRET);
            seedDevice(owner, fixture, fixture.tcpDeviceId(), TCP_DEVICE, "混合协议 TCP 设备", TCP_SECRET);
            seedDevice(owner, fixture, fixture.coapDeviceId(), COAP_DEVICE, "混合协议 CoAP 设备", COAP_SECRET);
            // 存量 MQTT 设备：有凭据、有物模型，但按冻结口径不写 dev_access_binding（代次视为 0）。
            seedDevice(owner, fixture, fixture.mqttDeviceId(), MQTT_DEVICE, "混合协议 MQTT 设备", MQTT_SECRET);
            seedDevice(owner, fixture, fixture.legacyDeviceId(), LEGACY_DEVICE, "混合协议存量设备", LEGACY_SECRET);
        }
        for (UUID deviceId : List.of(fixture.httpDeviceId(), fixture.tcpDeviceId(), fixture.coapDeviceId(),
                fixture.mqttDeviceId(), fixture.legacyDeviceId())) {
            // 数据面夹具直接建立 1.0.0 与 INITIAL 绑定，接通版本化摄入链（版本发布属控制面）。
            seedThingModelVersion(fixture.tenantId(), fixture.projectId(), fixture.typeId(), deviceId,
                    TEMPERATURE_SNAPSHOT);
        }
        rebind(fixture, fixture.httpDeviceId(), TransportProtocol.HTTP);
        rebind(fixture, fixture.tcpDeviceId(), TransportProtocol.TCP);
        rebind(fixture, fixture.coapDeviceId(), TransportProtocol.COAP);
        return fixture;
    }

    /**
     * 播种单个直连设备与其接入凭据。
     *
     * @param owner owner 连接
     * @param fixture 独占夹具
     * @param deviceId 设备 ID
     * @param deviceKey 设备键
     * @param name 设备名
     * @param secret 设备密钥明文
     * @throws SQLException 写入失败
     */
    private static void seedDevice(Connection owner, Fixture fixture, UUID deviceId, String deviceKey, String name,
                                   String secret) throws SQLException {
        execute(owner, """
                INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ONLINE')
                """, deviceId, fixture.tenantId(), fixture.projectId(), fixture.typeId(), deviceKey, name);
        execute(owner, """
                INSERT INTO dev_credential
                    (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), deviceId, sha256(secret));
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
        return HexFormat.of().formatHex(digestBytes(input.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 计算接入幂等事实使用的载荷摘要。
     *
     * @param body 原始请求体
     * @return 十六进制摘要
     */
    private static String digest(String body) {
        return HexFormat.of().formatHex(digestBytes(body.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 计算 SHA-256。
     *
     * @param input 输入字节
     * @return 摘要字节
     */
    private static byte[] digestBytes(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
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
     * HTTP 结果。
     *
     * @param status 状态码
     * @param body 响应体
     */
    private record HttpResult(int status, String body) {
    }

    /**
     * 独占夹具身份。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param typeId 设备类型
     * @param httpDeviceId HTTP 平面设备
     * @param tcpDeviceId TCP 平面设备
     * @param coapDeviceId CoAP 平面设备
     * @param mqttDeviceId 存量 MQTT 设备
     * @param legacyDeviceId 仅用于平面判定的存量设备
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID typeId, UUID httpDeviceId, UUID tcpDeviceId,
                           UUID coapDeviceId, UUID mqttDeviceId, UUID legacyDeviceId, String projectKey) {
    }
}
