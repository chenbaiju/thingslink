package com.things.link.bootstrap.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Value;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** ADR0200：只延迟真实Broker原回调，不用重建属性代替线协议资格。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookMqttConnectionBrokerTests extends WebhookFixture {
    @Value("${things-link.security.broker-callback.secret}") String callbackSecret;
    private static final String PASSWORD = "a9".repeat(32);
    private static final Path TLS = Path.of("../things-link-ingestion/src/test/resources/tls");
    private final HttpClient callbacks = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService proxyThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final List<String> authOutcomes = new CopyOnWriteArrayList<>();
    private final List<String> authStages = new CopyOnWriteArrayList<>();
    private final AtomicLong authStageOrigin = new AtomicLong();
    private final AtomicReference<Throwable> proxyFailure = new AtomicReference<>();
    private final AtomicReference<AuthGate> gate = new AtomicReference<>();
    private HttpServer proxy;
    private GenericContainer<?> broker;
    private String username;

    /** 保存未改写的原始字节与服务器签发身份，队列仅用于控制到达次序。 */
    private record Event(String kind, String body, String nonce, String deviceId) { }
    /** 仅暂停第一份已提交的实际认证响应，不能另行签发或伪造成功。 */
    private static final class AuthGate {
        final CompletableFuture<String> signed = new CompletableFuture<>();
        final CountDownLatch release = new CountDownLatch(1);
    }

    @BeforeAll void start() throws Exception {
        proxy = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        proxy.setExecutor(proxyThreads);
        proxy.createContext("/api/v1/emqx/", this::forward);
        proxy.start();
        org.testcontainers.Testcontainers.exposeHostPorts(proxy.getAddress().getPort());
        String config = Files.readString(Path.of("../../deploy/emqx/base.hocon"))
                .replace("host.docker.internal:8080", "host.testcontainers.internal:" + proxy.getAddress().getPort())
                .replace("dev-only-broker-callback-secret-do-not-use-in-production", callbackSecret)
                + "\nlisteners.ssl.default.ssl_options.certfile = \"/opt/emqx/etc/order-cert.pem\"\n"
                + "listeners.ssl.default.ssl_options.keyfile = \"/opt/emqx/etc/order-key.pem\"\n";
        broker = new GenericContainer<>("emqx/emqx:6.2.3").withExposedPorts(1883, 8883, 18083)
                .withCopyToContainer(Transferable.of(Files.readAllBytes(TLS.resolve("device-access-test-cert.pem")), 0444), "/opt/emqx/etc/order-cert.pem")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(TLS.resolve("device-access-test-key.pem")), 0444), "/opt/emqx/etc/order-key.pem")
                .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/base.hocon")
                .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));
        broker.start();
    }

    /** Broker仍运行时保留代理与应用，最终关闭全部本类拥有的资源。 */
    @AfterAll void stop() {
        try { if (broker != null) broker.stop(); }
        finally { if (proxy != null) proxy.stop(0); proxyThreads.close(); callbacks.close(); }
    }
    @BeforeEach void seed() {
        events.clear(); authOutcomes.clear(); authStages.clear(); authStageOrigin.set(System.nanoTime()); proxyFailure.set(null);
        owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?", device);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'wire-order')", tenant, project, device, PASSWORD);
        username = owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project) + "/"
                + owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?", String.class, device);
    }
    @AfterEach void cleanup() {
        var blocked = gate.getAndSet(null); if (blocked != null) blocked.release.countDown();
        for (String table : List.of("dev_connection", "dev_mqtt_connection_ticket", "dev_mqtt_session_cursor", "dev_credential", "sys_outbox_event"))
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
        assertThat(proxyFailure.get()).as("proxy must not hide callback failures").isNull();
    }
    static Stream<Arguments> transports() {
        return Stream.of(4, 5).flatMap(version -> Stream.of(false, true).map(tls -> Arguments.of(version, tls)));
    }

    /** 真实接管产生旧断连；晚送该原字节不能关闭新连接。 */
    @ParameterizedTest @MethodSource("transports")
    void oldDisconnectAfterTakeoverCannotCloseNewConnection(int version, boolean tls) throws Exception {
        try (var old = wire(version, tls, "takeover", true)) {
            var first = connectedExcept((String) null); assertThat(replay(first)).isTrue();
            try (var current = wire(version, tls, "takeover", true)) {
                var second = connectedExcept(first.nonce()); assertThat(replay(second)).isTrue();
                var late = event("disconnected", first.nonce()); assertThat(replay(late)).isTrue();
                assertThat(replay(late)).isTrue(); assertActive(second); assertThat(sources()).isEqualTo(1);
                assertAllowed(current); assertThat(rows("dev_connection")).isEqualTo(2);
            }
        }
    }
    /** 原断连先投递形成墓碑；后到connected不能凭合法五属性复活。 */
    @ParameterizedTest @MethodSource("transports")
    void disconnectedBeforeConnectedCannotCreateOnlineFact(int version, boolean tls) throws Exception {
        Event connected;
        try (var wire = wire(version, tls, "reverse", true)) { connected = connectedExcept((String) null); }
        assertThat(replay(event("disconnected", connected.nonce()))).isTrue();
        assertThat(replay(connected)).isFalse(); assertThat(rows("dev_connection")).isZero();
        assertThat(sources()).isZero(); assertThat(status()).isEqualTo("OFFLINE");
    }
    /** 接管后新断连抢先到达，关闭被取代者且禁止迟到新connected再次上线。 */
    @ParameterizedTest @MethodSource("transports")
    void newerDisconnectFirstClosesPriorSession(int version, boolean tls) throws Exception {
        try (var old = wire(version, tls, "new-close", true)) {
            var first = connectedExcept((String) null); replay(first); Event second;
            try (var next = wire(version, tls, "new-close", true)) { second = connectedExcept(first.nonce()); }
            assertThat(replay(event("disconnected", second.nonce()))).isTrue();
            assertThat(replay(second)).isFalse(); assertThat(replay(event("disconnected", first.nonce()))).isTrue();
            assertThat(status()).isEqualTo("OFFLINE"); assertThat(sources()).isEqualTo(2);
            assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", Integer.class, device)).isZero();
        }
    }
    /** 同一实际回调重复投递只保存一条连接和两个在线边沿。 */
    @ParameterizedTest @MethodSource("transports")
    void actualCallbacksAreIdempotent(int version, boolean tls) throws Exception {
        Event connected;
        try (var wire = wire(version, tls, "repeat", true)) {
            connected = connectedExcept((String) null); assertThat(replay(connected)).isTrue(); assertThat(replay(connected)).isTrue();
            assertAllowed(wire); assertThat(rows("dev_connection")).isEqualTo(1); assertThat(sources()).isEqualTo(1);
        }
        var disconnected = event("disconnected", connected.nonce());
        assertThat(replay(disconnected)).isTrue(); assertThat(replay(disconnected)).isTrue();
        assertThat(rows("dev_connection")).isEqualTo(1); assertThat(sources()).isEqualTo(2);
    }
    /** 低次序认证在物理接管上最后到达也不得获得新订阅许可，必须重新认证。 */
    @ParameterizedTest @MethodSource("transports")
    void delayedOriginalAuthenticationFailsClosedAndFreshReconnectRecovers(int version, boolean tls) throws Exception {
        var blocked = new AuthGate(); gate.set(blocked);
        // Only TLS needs a handshake before holding the original response. Open plain TCP at CONNECT time.
        // The callback ordering test must not spend its three-second auth budget on a TLS handshake.
        var preparedTlsTransport = tls ? transport(true) : null;
        try (var currentTransport = preparedTlsTransport; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var late = executor.submit(() -> connect(transport(tls), version, "late-auth", true));
            String originalNonce = blocked.signed.get(2, TimeUnit.SECONDS);
            try (var current = connect(currentTransport == null ? transport(false) : currentTransport,
                    version, "late-auth", true)) {
                int currentResult = Byte.toUnsignedInt(current.connack.body()[1]);
                if (currentResult != 0) {
                    // MQTT3/5 都可能在旧认证挂起时拒绝第二个 CONNECT；拒绝码按协议编码。
                    // 这时没有发生物理接管：旧连接可能完成认证，也可能已到达 Broker 的认证超时。
                    int notAuthorized = version == 5 ? 0x87 : 0x05;
                    assertThat(current.connack.header()).isEqualTo(0x20);
                    assertThat(current.connack.body()[0]).isZero();
                    assertThat(currentResult).as("version=%s tls=%s outcomes=%s stages=%s",
                            version, tls, authOutcomes, authStages).isEqualTo(notAuthorized);
                    assertThat(authOutcomes).noneMatch(outcome -> outcome.equals("current:200:allow"));
                    blocked.release.countDown();
                    try (var old = late.get(5, TimeUnit.SECONDS)) {
                        int oldResult = Byte.toUnsignedInt(old.connack.body()[1]);
                        if (oldResult == 0) {
                            var original = connectedExcept((String) null);
                            assertThat(original.nonce()).isEqualTo(originalNonce);
                            assertThat(replay(original)).isTrue();
                            assertAllowed(old);
                            assertActive(original);
                        } else {
                            assertThat(oldResult).isEqualTo(notAuthorized);
                            assertThat(events).noneMatch(event -> event.kind().equals("connected")
                                    && event.nonce().equals(originalNonce));
                            assertThat(rows("dev_connection")).isZero();
                        }
                    }
                    if (events.stream().anyMatch(event -> event.kind().equals("connected")
                            && event.nonce().equals(originalNonce))) {
                        assertThat(replay(event("disconnected", originalNonce))).isTrue();
                    }
                    assertThat(status()).isEqualTo("OFFLINE");
                    try (var fresh = wire(version, tls, "late-auth", true)) {
                        var recovered = connectedExcept(originalNonce);
                        assertThat(replay(recovered)).isTrue(); assertAllowed(fresh); assertActive(recovered);
                    }
                    return;
                }
                // CONNACK已证明新连接先完成物理认证；立即放行旧响应，不把事件轮询与数据库回放塞进3秒认证预算。
                blocked.release.countDown();
                var currentEvent = connectedExcept(originalNonce); assertThat(replay(currentEvent)).isTrue();
                try (var old = late.get(5, TimeUnit.SECONDS)) {
                    assertThat(old.connack.body()[1]).isZero();
                    // 物理接管后的旧连接可能没有 connected 回调；若 Broker 发出原字节，仍必须拒绝其重放。
                    var oldEvent = events.stream().filter(e -> e.kind().equals("connected")
                            && e.nonce().equals(originalNonce)).findFirst();
                    if (oldEvent.isPresent()) assertThat(replay(oldEvent.orElseThrow())).isFalse();
                    assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE mqtt_connection_id=?",
                            Integer.class, UUID.fromString(originalNonce))).isZero();
                    var denied = old.subscribe("tc/v1/" + username + "/down/command", 1);
                    assertThat(denied.header()).isEqualTo(0x90);
                    assertThat(Byte.toUnsignedInt(denied.body()[denied.body().length - 1])).isGreaterThanOrEqualTo(0x80);
                    assertThat(replay(event("disconnected", currentEvent.nonce()))).isTrue();
                    assertThat(status()).isEqualTo("OFFLINE");
                    try (var fresh = wire(version, tls, "late-auth", true)) {
                        var recovered = connectedExcept(List.of(originalNonce, currentEvent.nonce()));
                        assertThat(replay(recovered)).isTrue(); assertAllowed(fresh); assertActive(recovered);
                    }
                }
            } finally { blocked.release.countDown(); gate.compareAndSet(blocked, null); }
        }
    }
    /** 同配置恢复持久订阅及Session Present，但每次取得不同原连接票据。 */
    @ParameterizedTest @MethodSource("transports")
    void persistentSessionRetainsStableNameAndUsesFreshTicket(int version, boolean tls) throws Exception {
        Event first;
        try (var wire = wire(version, tls, "persistent", false)) {
            assertThat(wire.connack.body()[0]).isZero(); first = connectedExcept((String) null); replay(first); assertAllowed(wire);
            wire.disconnectWithLongExpiry();
        }
        replay(event("disconnected", first.nonce()));
        try (var recovered = wire(version, tls, "persistent", false)) {
            assertThat(recovered.connack.body()[0]).isEqualTo((byte) 1);
            var second = connectedExcept(first.nonce()); assertThat(second.nonce()).isNotEqualTo(first.nonce());
            assertThat(json.readTree(second.body()).path("clientid").asString()).isEqualTo(json.readTree(first.body()).path("clientid").asString());
            assertThat(replay(second)).isTrue(); assertActive(second); assertAllowed(recovered);
        }
    }
    /** 各Client ID有各自游标；其中一个断连不改变设备仍在线的事实。 */
    @ParameterizedTest @MethodSource("transports")
    void independentClientIdsRetainOnlineUntilLastConnection(int version, boolean tls) throws Exception {
        Event first; Event second;
        try (var other = wire(version, tls, "other", true)) {
            second = connectedExcept((String) null); replay(second);
            try (var one = wire(version, tls, "one", true)) { first = connectedExcept(second.nonce()); replay(first); }
            replay(event("disconnected", first.nonce())); assertActive(second); assertAllowed(other);
            assertThat(status()).isEqualTo("ONLINE"); assertThat(sources()).isEqualTo(1);
        }
        replay(event("disconnected", second.nonce())); assertThat(status()).isEqualTo("OFFLINE"); assertThat(sources()).isEqualTo(2);
    }

    /** 认证/ACL原样转发；生命周期原文捕获后由测试控制次序，不改变生产规则。 */
    private void forward(HttpExchange exchange) {
        try (exchange) {
            if (!callbackSecret.equals(exchange.getRequestHeaders().getFirst("X-Broker-Callback-Token"))) {
                exchange.sendResponseHeaders(403, -1); return;
            }
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readNBytes(65537), StandardCharsets.UTF_8);
            if (body.length() > 65536) throw new IllegalStateException("callback exceeds fixture bound");
            if (path.contains("/events/")) {
                var value = json.readTree(body);
                if (events.size() >= 256) throw new IllegalStateException("callback queue exceeded");
                events.add(new Event(path.substring(path.lastIndexOf('/') + 1), body,
                        value.path("tc_auth_connection_id").asString(), value.path("tc_auth_device_id").asString()));
                exchange.sendResponseHeaders(200, -1); return;
            }
            boolean authentication = path.endsWith("/auth");
            AuthGate blocked = authentication ? gate.getAndSet(null) : null;
            String authKind = blocked == null ? "current" : "held";
            if (authentication) authStage(authKind, "received");
            var response = send(path, body);
            if (authentication) {
                authStage(authKind, "backend-returned");
                authOutcomes.add(authKind + ":"
                        + response.statusCode() + ":"
                        + json.readTree(response.body()).path("result").asText("missing"));
            }
            if (blocked != null) {
                blocked.signed.complete(json.readTree(response.body()).path("client_attrs").path("tc_auth_connection_id").asString());
                // 生产Broker保留3秒HTTP超时；夹具只防死锁，不抢先替Broker裁决认证结果。
                if (!blocked.release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("auth gate was not released");
                authStage(authKind, "released");
            }
            byte[] responseBody = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.statusCode(), responseBody.length); exchange.getResponseBody().write(responseBody);
            if (authentication) authStage(authKind, "written");
        } catch (Throwable failure) {
            if (exchange.getRequestURI().getPath().endsWith("/auth"))
                authStage("proxy", "failed-" + failure.getClass().getSimpleName());
            proxyFailure.compareAndSet(null, failure);
        }
    }
    /** 只记录认证代理阶段和单调耗时，不输出请求、凭据、票据或响应正文。 */
    private void authStage(String kind, String stage) {
        authStages.add(kind + ":" + stage + ":" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - authStageOrigin.get()) + "ms");
    }
    private HttpResponse<String> send(String path, String body) throws Exception {
        return callbacks.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                .header("X-Broker-Callback-Token", callbackSecret).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private boolean replay(Event event) throws Exception {
        var response = send("/api/v1/emqx/events/" + event.kind(), event.body());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("accepted").asBoolean();
    }
    private Event connectedExcept(String nonce) { return connectedExcept(nonce == null ? List.of() : List.of(nonce)); }
    private Event connectedExcept(List<String> excluded) {
        await().atMost(Duration.ofSeconds(8)).until(() -> events.stream().anyMatch(e -> e.deviceId().equals(device.toString()) && e.kind().equals("connected") && !excluded.contains(e.nonce())));
        return events.stream().filter(e -> e.deviceId().equals(device.toString()) && e.kind().equals("connected") && !excluded.contains(e.nonce())).findFirst().orElseThrow();
    }
    private Event event(String kind, String nonce) {
        await().atMost(Duration.ofSeconds(8)).until(() -> events.stream().anyMatch(e -> e.kind().equals(kind) && e.nonce().equals(nonce)));
        return events.stream().filter(e -> e.kind().equals(kind) && e.nonce().equals(nonce)).findFirst().orElseThrow();
    }
    private void assertActive(Event event) {
        assertThat(owner.queryForObject("SELECT count(*) FROM dev_connection WHERE mqtt_connection_id=? AND disconnected_at IS NULL", Integer.class, UUID.fromString(event.nonce()))).isEqualTo(1);
        assertThat(status()).isEqualTo("ONLINE");
    }
    private int sources() { return owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", Integer.class, project); }
    private String status() { return owner.queryForObject("SELECT status FROM dev_device WHERE id=?", String.class, device); }
    private void assertAllowed(PublicMqttWire wire) throws Exception {
        var packet = wire.subscribe("tc/v1/" + username + "/down/command", 1);
        assertThat(packet.header()).isEqualTo(0x90); assertThat(packet.body()[packet.body().length - 1]).isEqualTo((byte) 1);
    }
    /** TLS校验证书与主机名，四种组合均使用生产认证规则。 */
    private PublicMqttWire wire(int version, boolean tls, String client, boolean clean) throws Exception {
        return wire(transport(tls), version, client, clean);
    }
    private PublicMqttWire wire(java.net.Socket transport, int version, String client, boolean clean) throws Exception {
        try {
            var wire = connect(transport, version, client, clean);
            assertThat(wire.connack.body()[1]).as("Broker CONNACK; auth outcomes=%s, stages=%s, proxy failure=%s",
                    authOutcomes, authStages, proxyFailure.get() == null ? "none" : proxyFailure.get().getClass().getSimpleName()).isZero();
            return wire;
        } catch (Exception | AssertionError failure) { transport.close(); throw failure; }
    }
    private PublicMqttWire connect(java.net.Socket transport, int version, String client, boolean clean) throws Exception {
        try { return new PublicMqttWire(transport, version, client, username, PASSWORD, clean, false); }
        catch (Exception | AssertionError failure) { transport.close(); throw failure; }
    }
    private java.net.Socket transport(boolean tls) throws Exception {
        if (!tls) return new java.net.Socket(broker.getHost(), broker.getMappedPort(1883));
        else {
            var trust = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); trust.load(null);
            try (var input = Files.newInputStream(TLS.resolve("device-access-test-cert.pem"))) { trust.setCertificateEntry("owned", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input)); }
            var managers = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
            var context = javax.net.ssl.SSLContext.getInstance("TLS"); context.init(null, managers.getTrustManagers(), null);
            var socket = (javax.net.ssl.SSLSocket) context.getSocketFactory().createSocket("localhost", broker.getMappedPort(8883));
            try {
                var parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters); socket.setSoTimeout(5000); socket.startHandshake();
                return socket;
            } catch (Exception | AssertionError failure) { socket.close(); throw failure; }
        }
    }
}
