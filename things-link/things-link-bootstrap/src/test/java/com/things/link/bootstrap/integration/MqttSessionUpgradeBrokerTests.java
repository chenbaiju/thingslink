package com.things.link.bootstrap.integration;

import com.sun.net.httpserver.HttpServer;
import com.things.link.ingestion.application.BrokerHandoffDispatcher;
import com.things.link.ingestion.infrastructure.BrokerHandoffMetrics;
import com.things.link.ingestion.infrastructure.BrokerHandoffQualificationRecorder;
import com.things.link.ingestion.infrastructure.DurableUplinkMqttIngress;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.support.mqtt.BrokerIngressReadiness;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 整体升级的真实Broker队列与生产ingress恢复；历史认证响应只裁剪新增字段，不绕过真实凭据。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MqttSessionUpgradeBrokerTests extends WebhookFixture {
    /** 独占Kafka接收真实生产分派结果，绝不访问开发Kafka。 */
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0")
            .withStartupTimeout(Duration.ofSeconds(120));
    static { KAFKA.start(); }
    /** 服务身份由生产认证校验，固定Client ID不能更换。 */
    private static final String INGRESS_USER = "upgrade-owned-ingress";
    private static final String INGRESS_PASSWORD = "upgrade-owned-ingress-password-32-bytes";
    private static final String DEVICE_SECRET = "71".repeat(32);
    private static final String API_KEY = "upgrade-owned", API_SECRET = "upgrade-owned-api-secret";
    /** 历史配置固定为60365e50原字节，不随当前部署配置变化。 */
    private static final String LEGACY_SHA256 = "ecb2a3184e98907e19b1720648be6c2dd59a2a2bf2115f811fc913eb0d325ef3";
    /** 自动单例仅暂不启动，恢复时另外构造真实类并显式启动/关闭；不替换认证或分派。 */
    @MockitoBean private DurableUplinkMqttIngress dormantAutomaticIngress;
    @Autowired private BrokerIngressReadiness readiness;
    @Autowired private BrokerHandoffDispatcher dispatcher;
    @Autowired private BrokerHandoffMetrics metrics;
    @Autowired private BrokerHandoffQualificationRecorder recorder;
    @Autowired private MeterRegistry meters;
    @Autowired private com.things.link.device.application.DeviceMqttAccessService mqttAccess;
    private final AtomicReference<Throwable> historicalProxyFailure = new AtomicReference<>();
    @Value("${things-link.security.broker-callback.secret}") private String callbackSecret;

    /** 后端真实身份启用，但自动单例在本夹具中被抑制，避免与升级前观察者争抢固定ID。 */
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> true);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.ingress.handoff.enabled", () -> true);
        registry.add("things-link.ingress.handoff.broker-uri", () -> "tcp://127.0.0.1:9");
        registry.add("things-link.ingress.handoff.username", () -> INGRESS_USER);
        registry.add("things-link.ingress.handoff.password", () -> INGRESS_PASSWORD);
        registry.add("things-link.ingress.handoff.qualification.enabled", () -> false);
    }
    /** 所有实例先关闭自己的连接再由类清理独占Kafka。 */
    @AfterAll static void closeKafka() { KAFKA.stop(); }
    /** 仅清理本轮设备边沿，父夹具随后回收项目图。 */
    @AfterEach void cleanupConnections() {
        readiness.markUnavailable();
        for (String table : List.of("dev_connection", "dev_credential", "sys_outbox_event")) {
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
        }
        assertThat(historicalProxyFailure.getAndSet(null)).as("historical contract proxy must not hide HTTP failures").isNull();
    }

    /** 旧设备会话隔离与固定ingress未ACK消息必须在同次Broker重启中分别成立。 */
    static java.util.stream.Stream<Arguments> baselines() {
        return java.util.stream.Stream.of(4, 5).flatMap(version -> java.util.stream.Stream.of(true, false).map(oldNamespace -> Arguments.of(version, oldNamespace)));
    }
    @ParameterizedTest @MethodSource("baselines")
    void wholeUpgradePreservesIngressButIsolatesLegacyDeviceSession(int version, boolean oldNamespace) throws Exception {
        owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?", device);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) "
                        + "VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'upgrade-owned')",
                tenant, project, device, DEVICE_SECRET);
        String username = owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project)
                + "/" + owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?", String.class, device);
        String wireId = "legacy-original-" + device;
        String down = "tc/v1/" + username + "/down/command", up = "tc/v1/" + username + "/up/property/report";
        HttpServer legacyAuth = legacyAuthentication(oldNamespace);
        Testcontainers.exposeHostPorts(port, legacyAuth.getAddress().getPort());
        byte[] historical = Files.readAllBytes(Path.of("src/test/resources/mqtt/" + (oldNamespace ? "pre-adr0194-base.hocon" : "pre-adr0200-base.hocon")));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(historical)))
                .isEqualTo(oldNamespace ? LEGACY_SHA256 : "5f47707dfdf2034982d53bdd7e7da7277e98d251292fcb70aa397dc5fbcaba66");
        String legacy = configure(new String(historical, StandardCharsets.UTF_8))
                .replace("http://host.testcontainers.internal:" + port + "/api/v1/emqx/auth",
                        "http://host.testcontainers.internal:" + legacyAuth.getAddress().getPort() + "/auth")
                .replace("http://host.testcontainers.internal:" + port + "/api/v1/emqx/acl",
                        "http://host.testcontainers.internal:" + legacyAuth.getAddress().getPort() + "/acl");
        String candidate = configure(Files.readString(Path.of("../../deploy/emqx/base.hocon")));
        try (var broker = new GenericContainer<>("emqx/emqx:6.2.3")
                .withExposedPorts(1883, 18083)
                .withCopyToContainer(Transferable.of(legacy.getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/base.hocon")
                .withCopyToContainer(Transferable.of((API_KEY + ":" + API_SECRET + ":administrator\n")
                        .getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/upgrade-keys")
                .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(120)))) {
            broker.start();
            byte[] originalEnvelope;
            try (var ingress = awaitLegacyAuthenticationReady(broker);
                 var consumer = consumer()) {
                assertThat(ingress.connack.body()[1]).isZero();
                assertThat(ingress.subscribe(BrokerIngressProperties.INTERNAL_TOPIC, 1).body()[2]).isEqualTo((byte) 1);
                readiness.markReady();
                try (var old = wire(broker, version, wireId, username, DEVICE_SECRET)) {
                    assertThat(old.connack.body()[0]).isZero(); assertThat(old.connack.body()[1]).isZero();
                    assertThat(old.subscribe(down, 1).body()[version == 5 ? 3 : 2]).isEqualTo((byte) 1);
                    old.forbiddenPublish(up); assertThat(old.read().header()).isEqualTo(0x40);
                    var received = ingress.read();
                    assertThat(received.header() & 0xF7).isEqualTo(0x32);
                    var body = new DataInputStream(new ByteArrayInputStream(received.body()));
                    assertThat(body.readUTF()).isEqualTo(BrokerIngressProperties.INTERNAL_TOPIC);
                    body.readUnsignedShort(); originalEnvelope = body.readAllBytes();
                    var envelope = json.readTree(originalEnvelope);
                    assertThat(envelope.path("clientId").asText()).isEqualTo(wireId);
                    assertThat(envelope.path("topic").asText()).isEqualTo(up);
                    assertThat(envelope.path("authenticatedIdentity").path("deviceId").asText()).isEqualTo(device.toString());
                    old.disconnectWithLongExpiry();
                }
                // 明确不发送PUBACK，旧固定服务会话必须把该消息留到升级后的实际Java ingress。
                ingress.disconnectWithLongExpiry();
                publish(broker, (oldNamespace ? "" : "tc/private/device/" + device + "/0/") + down, "old-offline-queue".getBytes(StandardCharsets.UTF_8));
                readiness.markUnavailable(); legacyAuth.stop(0);
                broker.getDockerClient().stopContainerCmd(broker.getContainerId()).withTimeout(10).exec();
                broker.copyFileToContainer(Transferable.of(candidate.getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/base.hocon");
                broker.getDockerClient().startContainerCmd(broker.getContainerId()).exec();
                // Docker重启可重新分配随机端口，不能沿用GenericContainer启动时缓存的映射。
                await().atMost(Duration.ofSeconds(90)).ignoreExceptions().until(() -> client.send(
                        HttpRequest.newBuilder(URI.create("http://" + broker.getHost() + ":" + mapped(broker, 18083) + "/status"))
                                .version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200);
                double acceptedBefore = accepted();
                var actual = new DurableUplinkMqttIngress(new BrokerIngressProperties(true,
                        "tcp://" + broker.getHost() + ":" + mapped(broker, 1883), INGRESS_USER, INGRESS_PASSWORD),
                        dispatcher, metrics, readiness, recorder);
                try {
                    actual.applicationReady();
                    await().atMost(Duration.ofSeconds(45)).until(readiness::isReady);
                    AtomicReference<RawUplinkMessage> delivered = new AtomicReference<>();
                    await().atMost(Duration.ofSeconds(30)).until(() -> {
                        for (var record : consumer.poll(Duration.ofMillis(200))) {
                            var message = json.readValue(record.value(), RawUplinkMessage.class);
                            if (device.equals(message.deviceId())) delivered.set(message);
                        }
                        return delivered.get() != null;
                    });
                    var restored = delivered.get();
                    assertThat(restored.topic()).isEqualTo(up); assertThat(restored.clientId()).isEqualTo(wireId);
                    assertThat(restored.payload()).containsExactly((byte) 'x');
                    assertThat(restored.authenticatedIdentity().deviceId()).isEqualTo(device);
                    assertThat(restored.receivedAt().toEpochMilli()).isEqualTo(json.readTree(originalEnvelope).path("publishedAtMs").asLong());
                    await().atMost(Duration.ofSeconds(10)).until(() -> accepted() > acceptedBefore);
                    assertThat(meters.get(BrokerHandoffMetrics.SESSION_PRESENT).gauge().value()).isEqualTo(1);
                    try (var fresh = wire(broker, version, wireId, username, DEVICE_SECRET)) {
                        assertThat(fresh.connack.body()[0]).as("0194前裸会话隔离，0200前同命名空间会话必须恢复").isEqualTo((byte) (oldNamespace ? 0 : 1));
                        assertThat(fresh.connack.body()[1]).isZero();
                        if (!oldNamespace) assertThat(fresh.message(down)).containsExactly("old-offline-queue".getBytes(StandardCharsets.UTF_8));
                        quiet(fresh);
                        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner.queryForObject(
                                "SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL AND mqtt_connection_id IS NOT NULL", Integer.class, device)).isEqualTo(1));
                        assertThat(fresh.subscribe(down, 1).body()[version == 5 ? 3 : 2]).isEqualTo((byte) 1);
                        byte[] current = "new-isolated-route".getBytes(StandardCharsets.UTF_8);
                        publish(broker, "tc/private/device/" + device + "/0/" + down, current);
                        assertThat(fresh.message(down)).containsExactly(current); quiet(fresh);
                    }
                } finally { actual.close(); }
                try (var audit = wire(broker, 4, BrokerIngressProperties.CLIENT_ID, INGRESS_USER, INGRESS_PASSWORD)) {
                    assertThat(audit.connack.body()[0]).isEqualTo((byte) 1); quiet(audit);
                    audit.disconnectWithLongExpiry();
                }
            }
        } finally { legacyAuth.stop(0); }
    }

    /** 历史合同夹具保留真实认证/当前范围查询，仅裁剪当时尚不存在的字段；不是旧二进制回退资格。 */
    private HttpServer legacyAuthentication(boolean oldNamespace) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/auth", exchange -> {
            try {
                if (!callbackSecret.equals(exchange.getRequestHeaders().getFirst("X-Broker-Callback-Token"))) { exchange.sendResponseHeaders(403, -1); return; }
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/emqx/auth"))
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .header("Content-Type", "application/json").header("X-Broker-Callback-Token", callbackSecret)
                        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),
                        HttpResponse.BodyHandlers.ofString());
                ObjectNode body = (ObjectNode) json.readTree(response.body());
                if (oldNamespace) body.remove("clientid_override");
                if (body.get("client_attrs") instanceof ObjectNode attrs) {
                    attrs.remove("tc_auth_connection_id");
                    if (oldNamespace) { attrs.remove("tc_auth_mountpoint"); attrs.remove("tc_auth_wire_client_id"); }
                }
                byte[] bytes = json.writeValueAsBytes(body);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.statusCode(), bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception failure) { historicalProxyFailure.compareAndSet(null, failure); exchange.sendResponseHeaders(503, -1); }
            finally { exchange.close(); }
        });
        // 旧HOCON不传nonce；不能让当前ACL补猜票据，也不能把旧配置改成新配置冒充升级。
        server.createContext("/acl", exchange -> {
            try {
                if (!callbackSecret.equals(exchange.getRequestHeaders().getFirst("X-Broker-Callback-Token"))) { exchange.sendResponseHeaders(403, -1); return; }
                Map<String, Object> body = json.readValue(exchange.getRequestBody().readAllBytes(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
                String user = (String) body.get("username"), topic = (String) body.get("topic"), action = (String) body.get("access");
                boolean subscribe = "1".equals(action) || "subscribe".equals(action);
                boolean publish = "2".equals(action) || "publish".equals(action);
                boolean allowed = INGRESS_USER.equals(user) && subscribe && BrokerIngressProperties.INTERNAL_TOPIC.equals(topic);
                if (!INGRESS_USER.equals(user) && user != null && topic != null) {
                    var original = com.things.link.device.application.DeviceMqttIdentity.parse(body).orElse(null);
                    String[] route = user.split("/", 2), segments = topic.split("/");
                    if (original != null && route.length == 2 && segments.length >= 6 && segments[0].equals("tc")
                            && segments[1].equals("v1") && segments[2].equals(route[0]) && segments[3].equals(route[1])) {
                        var current = mqttAccess.currentConfiguration(route[0], route[1], original.identity());
                        allowed = current.isPresent() && current.getAsLong() == original.configVersion()
                                && ((subscribe && segments[4].equals("down")) || (publish && segments[4].equals("up")));
                    }
                }
                byte[] bytes = json.writeValueAsBytes(Map.of("result", allowed ? "allow" : "deny"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception failure) { historicalProxyFailure.compareAndSet(null, failure); exchange.sendResponseHeaders(503, -1); }
            finally { exchange.close(); }
        });
        server.start(); return server;
    }
    /** 当前与历史配置仅替换独占地址、秘密和API身份；升级时不修改或清空数据目录。 */
    private String configure(String source) {
        return source.replace("host.docker.internal:8080", "host.testcontainers.internal:" + port)
                .replace("dev-only-broker-callback-secret-do-not-use-in-production", callbackSecret)
                + "\napi_key.bootstrap_file = \"/opt/emqx/etc/upgrade-keys\"\n";
    }
    /** 每次从Docker当前inspect获取映射，保留同一容器及数据而不假设随机端口稳定。 */
    private int mapped(GenericContainer<?> broker, int internal) {
        var info = broker.getDockerClient().inspectContainerCmd(broker.getContainerId()).exec();
        return Integer.parseInt(info.getNetworkSettings().getPorts()
                .getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(internal))[0].getHostPortSpec());
    }
    /** 明确持久会话，既不换Client ID也不发送清会话标志。 */
    private PublicMqttWire wire(GenericContainer<?> broker, int version, String id, String user, String password) throws Exception {
        var socket = new java.net.Socket(broker.getHost(), mapped(broker, 1883));
        try { return new PublicMqttWire(socket, version, id, user, password, false, false); }
        catch (Exception | AssertionError failure) { socket.close(); throw failure; }
    }
    /** 原40秒准备窗口内确认拒绝与成功握手，成功固定会话直接用于后续持久队列验证。 */
    private PublicMqttWire awaitLegacyAuthenticationReady(GenericContainer<?> broker) throws Exception {
        AtomicReference<PublicMqttWire> established = new AtomicReference<>();
        try {
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                    .ignoreExceptionsInstanceOf(IOException.class).untilAsserted(() -> {
                        // 非所有者 Client ID 必须经过认证回调并收到拒绝 CONNACK。
                        try (var probe = wire(broker, 4, "auth-readiness-" + UUID.randomUUID(),
                                INGRESS_USER, INGRESS_PASSWORD)) {
                            assertThat(probe.connack.header()).isEqualTo(0x20);
                            assertThat(probe.connack.body()[1]).isNotZero();
                        }
                        assertThat(historicalProxyFailure.get()).as("历史认证代理不得隐瞒 HTTP 回调故障").isNull();
                        // 只在消息基线建立前重试握手IO；保留固定ID、持久会话与原7秒单次读取预算。
                        var ingress = wire(broker, 4, BrokerIngressProperties.CLIENT_ID, INGRESS_USER, INGRESS_PASSWORD);
                        try {
                            assertThat(ingress.connack.header()).isEqualTo(0x20);
                            assertThat(ingress.connack.body()[1]).isZero();
                            assertThat(historicalProxyFailure.get()).as("历史认证代理不得隐瞒 HTTP 回调故障").isNull();
                            established.set(ingress);
                        } catch (Exception | AssertionError failure) {
                            try { ingress.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                            throw failure;
                        }
                    });
            return established.get();
        } catch (RuntimeException | Error failure) {
            var ingress = established.getAndSet(null);
            if (ingress != null) {
                try { ingress.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
    /** 由真实Kafka消费事实确认分派，不以mock调用次数作为持久证据。 */
    private KafkaConsumer<String, String> consumer() {
        var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "upgrade-" + UUID.randomUUID(), "auto.offset.reset", "earliest", "enable.auto.commit", false),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of("tc.device.uplink.raw")); return consumer;
    }
    /** 只有生产ingress在Kafka确认后完成手动ACK才累计该指标。 */
    private double accepted() { return meters.get(BrokerHandoffMetrics.HANDOFF_RESULTS).tag("result", "accepted").counter().count(); }
    /** 探针正文不携带秘密，Broker API用于升级前旧写者和升级后新路由对照。 */
    private void publish(GenericContainer<?> broker, String topic, byte[] payload) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://" + broker.getHost() + ":" + mapped(broker, 18083) + "/api/v5/publish"))
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((API_KEY + ":" + API_SECRET).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("topic", topic, "qos", 1, "retain", false,
                        "payload_encoding", "base64", "payload", Base64.getEncoder().encodeToString(payload))))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
    }
    /** 无首字节且PING仍存活，不能以断连冒充队列隔离。 */
    private void quiet(PublicMqttWire wire) throws Exception {
        wire.socket.setSoTimeout(700); assertThatThrownBy(wire.in::readUnsignedByte).isInstanceOf(java.net.SocketTimeoutException.class);
        wire.socket.setSoTimeout(5000); wire.send(0xC0, new byte[0]); assertThat(wire.read().header()).isEqualTo(0xD0);
    }
}
