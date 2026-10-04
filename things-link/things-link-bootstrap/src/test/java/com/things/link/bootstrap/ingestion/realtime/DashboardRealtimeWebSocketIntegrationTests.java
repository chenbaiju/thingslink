package com.things.link.bootstrap.ingestion.realtime;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ingestion.infrastructure.RealtimeProperties;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S12-2b2：JDK客户端通过真实HTTP Upgrade进入双端点，PG/RLS确权后经真实Redis收到无值提示。
 * 本类不使用MockMvc或会话适配替身；独立注册专库与Redis，避免继承配置在网络服务器早期装配时抢占连线。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "things-link.ingestion.dashboard-realtime.app-allowed-origins=https://app.example.test",
        "things-link.ingestion.dashboard-realtime.console-allowed-origins=https://console.example.test"
})
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
class DashboardRealtimeWebSocketIntegrationTests {
    /** 与Flyway创建的非owner角色一致，网络业务不能使用迁移账号。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 专库避免不可变发布历史污染其他全量测试，容器由OwnedTestContainers在本类上下文物理关闭后回收。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_realtime").withUsername("thingslink").withPassword("thingslink");
    /** 独立随机端口Redis，确保真实PUBLISH不接触开发中间件。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    /** 在业务连接池及Flyway建立之前启动两个专用真实容器。 */
    private static final String DATABASE_URL = startDatabase();
    /** 解析真实网络帧，不允许DTO掩盖额外业务值。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 随机监听端口由真实Servlet容器提供。 */
    @Value("${local.server.port}") private int port;
    /** 与生产App decoder配套的真实签名器。 */
    @Autowired private AppTokenIssuer appTokens;
    /** Console真实签名器用于双身份隔离验证。 */
    @Autowired private TokenIssuer consoleTokens;
    /** 发送真正PUBLISH，而不是直接调用注册表。 */
    @Autowired private StringRedisTemplate redis;
    /** 使用生产频道配置，避免测试写死绕过实际订阅频道。 */
    @Autowired private RealtimeProperties realtime;
    /** 确認业务数据源仍为普通RLS角色。 */
    @Autowired private JdbcTemplate application;
    /** 每例只建立自己UUID命名的不可变历史。 */
    private JdbcTemplate owner;
    /** 模型、绑定、精确运行版本均由既有真实夹具管线创建。 */
    private DataFixture data;
    /** 请求上下文与真实当前应用指针一致。 */
    private Fixture fixture;
    /** 包括断言失败路径也必须主动关闭网络连接。 */
    private final List<WebSocket> sockets = new ArrayList<>();
    /** 每例网络客户端独立释放选择器与连接。 */
    private HttpClient client;

    /** 保留真实PG与JWT身份，不依赖前例的账号或项目。 */
    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE_URL, "thingslink", "thingslink"));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("dashboard_realtime");
        data = WebAppDataRuntimeFixture.seed(owner);
        fixture = data.runtime();
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'VIEWER')",
                UUID.randomUUID(), fixture.projectId(), fixture.actorId());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 客户端关闭会触发生产会话注销；专库事实最终由容器回收，不做全表DELETE。 */
    @AfterEach
    void closeClients() {
        sockets.forEach(WebSocket::abort);
        if (client != null) client.shutdownNow();
    }

    /** 两种身份都必须经过真正的101握手、当前订阅ACK及真实Redis提示，且返回值不包含遥测事实。 */
    @Test
    void bothIdentitiesUpgradeSubscribeAndReceiveRedisInvalidationWithoutValues() throws Exception {
        for (boolean app : List.of(true, false)) {
            Frames frames = new Frames();
            WebSocket socket = connect(app, token(app), origin(app), frames);
            assertThat(socket.getSubprotocol()).isEqualTo(protocol(app));
            socket.sendText(subscribe(app), true).get(5, TimeUnit.SECONDS);
            JsonNode ack = frames.next();
            assertThat(ack.propertyNames()).containsExactlyInAnyOrder("type", "requestId", "subscriptionId", "count");
            assertThat(ack.path("type").asString()).isEqualTo("SUBSCRIBED");
            assertThat(ack.path("requestId").asString()).isEqualTo("network-journey");
            assertThat(ack.path("count").asInt()).isEqualTo(1);
            publish();
            JsonNode hint = frames.next();
            assertThat(hint.propertyNames()).containsExactlyInAnyOrder("type", "subscriptionId", "devices");
            assertThat(hint.path("type").asString()).isEqualTo("INVALIDATE");
            assertThat(hint.path("subscriptionId").asString()).isEqualTo(ack.path("subscriptionId").asString());
            assertThat(hint.path("devices")).hasSize(1);
            JsonNode device = hint.path("devices").get(0);
            assertThat(device.propertyNames()).containsExactlyInAnyOrder("deviceId", "propertyKeys");
            assertThat(device.path("deviceId").asString()).isEqualTo(data.first().toString());
            assertThat(device.path("propertyKeys").get(0).asString()).isEqualTo("temperature");
            assertThat(hint.toString()).doesNotContain("37.125", "occurredAt", "shadowVersion", "value");
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
            assertThat(frames.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1000);
        }
    }

    /** 缺失/null/非白名单Origin与跨身份令牌必须在HTTP Upgrade前拒绝，不能先建立会话后遮掩。 */
    @Test
    void handshakeRejectsOriginAndCrossIdentityTokens() {
        for (boolean app : List.of(true, false)) {
            for (String invalidOrigin : new String[] {null, "null", "https://untrusted.example.test", origin(!app)}) {
                rejected(app, token(app), invalidOrigin);
            }
            rejected(app, token(!app), origin(app));
            rejected(app, "invalid.jwt.token", origin(app));
        }
    }

    /** 冻结恢复代次以及失效角色均在握手时回库验证，不能单凭有效签名继续升级连接。 */
    @Test
    void appHandshakeRejectsOldGenerationAndRevokedRole() {
        String original = token(true);
        owner.update("UPDATE sys_project SET lifecycle_generation=1 WHERE id=?", fixture.projectId());
        rejected(true, original, origin(true));
        String current = appTokens.issue(new AppAuthenticatedPrincipal(fixture.tenantId(), fixture.projectId(),
                fixture.appUserId(), 1)).value();
        owner.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?", fixture.appUserId());
        rejected(true, current, origin(true));
    }

    /** 已收到ACK后解绑或撤销Console成员，实际发送前必须关整个连接且不能发送部分成功提示。 */
    @Test
    void revocationClosesBothIdentitiesBeforeAnotherRedisHint() throws Exception {
        for (boolean app : List.of(true, false)) {
            Frames frames = new Frames();
            WebSocket socket = connect(app, token(app), origin(app), frames);
            socket.sendText(subscribe(app), true).get(5, TimeUnit.SECONDS);
            assertThat(frames.next().path("type").asString()).isEqualTo("SUBSCRIBED");
            if (app) owner.update("UPDATE app_user_device SET status='CLOSED',updated_at=now() WHERE app_user_id=? AND device_id=?",
                    fixture.appUserId(), data.first());
            else owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",
                    fixture.projectId(), fixture.actorId());
            publish();
            assertThat(frames.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(frames.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    /** ADR0100的当前grant必须在发送前重读；旧的已确认订阅不能继续代表看板运行权限。 */
    @Test
    void appGrantRevocationClosesConnectionBeforeRedisHint() throws Exception {
        Frames frames = new Frames();
        WebSocket socket = connect(true, token(true), origin(true), frames);
        socket.sendText(subscribe(true), true).get(5, TimeUnit.SECONDS);
        assertThat(frames.next().path("type").asString()).isEqualTo("SUBSCRIBED");
        owner.update("UPDATE app_user_dashboard SET status='REVOKED',revision=revision+1,updated_at=now(),"
                        + "revoked_at=now(),revoked_by=updated_by "
                        + "WHERE app_user_id=? AND dashboard_id=?",
                fixture.appUserId(), fixture.authorized().dashboardId());
        publish();
        assertThat(frames.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(frames.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    /** 有效JWT不能让缺失上下文、过时应用指针或Schema未声明属性进入已确认订阅。 */
    @Test
    void appRejectsMissingOrStaleContextAndUndeclaredProperty() throws Exception {
        var missingContext = JSON.readTree(subscribe(true)).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) missingContext).remove("runtimeContext");
        for (String payload : List.of(missingContext.toString(),
                subscribe(true).replace("\"publicationRevision\":\"2\"", "\"publicationRevision\":\"1\""),
                subscribe(true).replace("temperature", "secret"))) {
            Frames frames = new Frames();
            WebSocket socket = connect(true, token(true), origin(true), frames);
            socket.sendText(payload, true).get(5, TimeUnit.SECONDS);
            assertThat(frames.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(frames.messages.poll(100, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    /** 合同§6一次连接仅能订阅一次，即使重复同帧也关闭，不隐式替换订阅代次。 */
    @Test
    void repeatedSubscriptionClosesConnection() throws Exception {
        Frames frames = new Frames();
        WebSocket socket = connect(true, token(true), origin(true), frames);
        socket.sendText(subscribe(true), true).get(5, TimeUnit.SECONDS);
        assertThat(frames.next().path("type").asString()).isEqualTo("SUBSCRIBED");
        socket.sendText(subscribe(true), true).get(5, TimeUnit.SECONDS);
        assertThat(frames.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    /** ADR0109：真实v2纯告警订阅、项目互证和发送前grant撤销，不从低层替身推定HTTP授权。 */
    @Test
    void appAlarmProtocolUsesRealGrantAndRejectsCrossIdentity() throws Exception {
        for (String jwt : List.of(token(false), "invalid.jwt.token")) {
            assertThatThrownBy(() -> connectAlarm(jwt, origin(true), new Frames()))
                    .isInstanceOf(ExecutionException.class).cause().isInstanceOfSatisfying(WebSocketHandshakeException.class,
                            failure -> assertThat(failure.getResponse().statusCode()).isIn(400, 401, 403));
        }
        assertThatThrownBy(() -> connectAlarm(token(true), origin(false), new Frames()))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        failure -> assertThat(failure.getResponse().statusCode()).isIn(400, 401, 403));
        Frames frames = new Frames();
        WebSocket socket = connectAlarm(token(true), origin(true), frames);
        assertThat(socket.getSubprotocol()).isEqualTo("tc.app.dashboard.v2");
        socket.sendText(alarmSubscribe(), true).get(5, TimeUnit.SECONDS);
        JsonNode ack = frames.next();
        assertThat(ack.propertyNames()).containsExactlyInAnyOrder("type", "requestId", "subscriptionId", "count", "alarmCount");
        assertThat(ack.path("count").asInt()).isZero();
        assertThat(ack.path("alarmCount").asInt()).isEqualTo(1);
        publishAlarm(fixture.projectId());
        JsonNode hint = frames.next();
        assertThat(hint.propertyNames()).containsExactlyInAnyOrder("type", "subscriptionId", "devices", "alarmQueryKeys");
        assertThat(hint.path("devices").size()).isZero();
        assertThat(hint.path("alarmQueryKeys").get(0).asString()).isEqualTo("alarms");
        publishAlarm(UUID.randomUUID());
        assertThat(frames.messages.poll(1100, TimeUnit.MILLISECONDS)).isNull();
        owner.update("UPDATE app_user_dashboard SET status='REVOKED',revision=revision+1,updated_at=now(),"
                        + "revoked_at=now(),revoked_by=updated_by WHERE app_user_id=? AND dashboard_id=?",
                fixture.appUserId(), fixture.authorized().dashboardId());
        publishAlarm(fixture.projectId());
        assertThat(frames.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(frames.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    /** v2每组模型和过滤必须仍来自真实Schema，不能以同设备绑定绕过组资格。 */
    @Test
    void appAlarmProtocolRejectsWrongModelAndScope() throws Exception {
        for (String payload : List.of(alarmSubscribe().replace(data.model().toString(), UUID.randomUUID().toString()),
                alarmSubscribe().replace("WARNING", "CRITICAL"),
                alarmSubscribe().replace(data.first().toString(), UUID.randomUUID().toString()))) {
            Frames frames = new Frames();
            WebSocket socket = connectAlarm(token(true), origin(true), frames);
            socket.sendText(payload, true).get(5, TimeUnit.SECONDS);
            assertThat(frames.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(frames.messages.poll(100, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    /** 使用真实告警依赖与精确当前版本，纯告警不伪造属性键。 */
    private String alarmSubscribe() {
        var root = (tools.jackson.databind.node.ObjectNode) JSON.readTree(subscribe(true));
        root.putArray("devices");
        var alarm = root.putArray("alarms").addObject().put("queryKey", "alarms");
        alarm.putArray("devices").addObject().put("deviceId", data.first().toString())
                .put("expectedModelVersionId", data.model().toString());
        alarm.putArray("conditionStates").add("ACTIVE");
        alarm.putArray("ackStates").add("UNACKNOWLEDGED");
        alarm.putArray("severities").add("WARNING");
        return root.toString();
    }

    /** 提示故障/跨项目反例仅经真实Redis网络，不调用Registry。 */
    private void publishAlarm(UUID project) {
        assertThat(redis.convertAndSend(com.things.link.ingestion.infrastructure.AlarmRealtimeInvalidationBridge.CHANNEL_PREFIX
                + project, JSON.writeValueAsString(Map.of("projectId", project.toString(), "deviceId", data.first().toString())))).isPositive();
    }

    /** 独立v2端点仍携带真实App JWT和精确Origin。 */
    private WebSocket connectAlarm(String jwt, String requestedOrigin, Frames frames) throws Exception {
        WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .subprotocols("tc.app.dashboard.v2", "bearer." + jwt).header("Origin", requestedOrigin)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/app/dashboard"), frames).get(8, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    /** App四字段上下文必须以冻结的精确版本传递；Console只选项目，不携带App上下文。 */
    private String subscribe(boolean app) {
        var root = JSON.createObjectNode().put("type", "SUBSCRIBE").put("requestId", "network-journey");
        root.putArray("devices").addObject().put("deviceId", data.first().toString())
                .putArray("propertyKeys").add("temperature");
        if (app) root.putObject("runtimeContext").put("appKey", fixture.appKey())
                .put("applicationVersionId", fixture.applicationVersionId().toString()).put("publicationRevision", "2")
                .put("dashboardVersionId", fixture.authorized().dashboardVersionId().toString());
        else root.put("projectId", fixture.projectId().toString());
        return root.toString();
    }

    /** 包含明显业务值的真实共享消息，只应在WebSocket中留下选中的键。 */
    private void publish() {
        var update = new DeviceRealtimeUpdate(UUID.randomUUID(), fixture.tenantId(), fixture.projectId(),
                data.first(), data.model(), "1.0.0", Instant.now(), 7, "network-test",
                Map.of("temperature", "37.125", "secret", "\"not subscribed\""),
                Map.of("temperature", "NUMBER", "secret", "TEXT"));
        assertThat(redis.convertAndSend(realtime.getChannelPrefix() + fixture.projectId(),
                JSON.writeValueAsString(update))).isPositive();
    }

    /** 真实JWT只放子协议，不放URL、查询参数或普通Authorization header。 */
    private WebSocket connect(boolean app, String jwt, String requestedOrigin, Frames frames) throws Exception {
        var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .subprotocols(protocol(app), "bearer." + jwt);
        if (requestedOrigin != null) builder.header("Origin", requestedOrigin);
        WebSocket socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port
                + (app ? "/ws/app/properties" : "/ws/dashboard/properties")), frames).get(8, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    /** 必须是非101握手状态；超时、网络断开或测试设置失败不能被误判成授权拒绝。 */
    private void rejected(boolean app, String jwt, String requestedOrigin) {
        assertThatThrownBy(() -> connect(app, jwt, requestedOrigin, new Frames()))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        failure -> assertThat(failure.getResponse().statusCode()).isIn(400, 401, 403));
    }

    /** 两种issuer签名分别使用各自生产decoder，App代次从真实初始项目读取语义固定为0。 */
    private String token(boolean app) {
        return app ? appTokens.issue(new AppAuthenticatedPrincipal(fixture.tenantId(), fixture.projectId(),
                fixture.appUserId(), 0)).value()
                : consoleTokens.issue(new AuthenticatedPrincipal(fixture.actorId(), fixture.tenantId(), fixture.projectId())).value();
    }

    /** 不回选bearer协议，避免凭据进入服务器响应。 */
    private static String protocol(boolean app) { return app ? "tc.app.properties.v1" : "tc.dashboard.properties.v1"; }
    /** 两个端点单独精确Origin白名单。 */
    private static String origin(boolean app) { return app ? "https://app.example.test" : "https://console.example.test"; }
    /** 静态启动专库，仅供本测试的Flyway与普通RLS连接池使用。 */
    private static String startDatabase() { DATABASE.start(); REDIS.start(); return DATABASE.getJdbcUrl(); }

    /** 真正网络客户端回调聚合分片，不调用任何生产会话或授权内部方法。 */
    private static final class Frames implements WebSocket.Listener {
        /** 已完整收到的文本帧，只有测试客户端保留。 */
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        /** 关闭状态证明由真实网络到达，不借注册表状态推断。 */
        private final LinkedBlockingQueue<Integer> closed = new LinkedBlockingQueue<>();
        /** RFC6455分片可由HTTP客户端拆分回调，必须等last才解析。 */
        private final StringBuilder fragments = new StringBuilder();
        /** 请求首个回调，之后逐帧背压。 */
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        /** 合并网络分片后请求下一帧，不依赖框架内部会话对象。 */
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            fragments.append(text);
            if (last) { messages.add(fragments.toString()); fragments.setLength(0); }
            socket.request(1);
            return null;
        }
        /** 记录真实关闭码，由JDK完成关闭握手。 */
        @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.add(statusCode);
            return null;
        }
        /** 有限超时暴露真实网络失败，不用无限轮询把缺失提示隐藏。 */
        JsonNode next() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("真实WebSocket文本帧").isNotNull();
            return JSON.readTree(message);
        }
    }

    /** 单一早期属性来源贯穿随机端口服务器、Flyway、普通RLS业务连接与真实Redis。 */
    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> "thingslink");
        registry.add("spring.flyway.url", () -> DATABASE_URL);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> "thingslink");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.outbox.publisher.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("things-link.notification.retry.enabled", () -> "false");
    }
}
