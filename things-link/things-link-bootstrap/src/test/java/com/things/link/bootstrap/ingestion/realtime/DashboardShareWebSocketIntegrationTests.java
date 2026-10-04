package com.things.link.bootstrap.ingestion.realtime;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.ingestion.infrastructure.RealtimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
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

/** S12-2c4真实HTTP Upgrade与PG/Redis分享旅程，不使用MockMvc或伪造会话，夹具不授予D-145资格。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
class DashboardShareWebSocketIntegrationTests {
    /** 与Flyway创建的非owner角色一致，网络业务不能使用迁移账号。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 专库避免不可变发布历史污染其他全量测试，容器由OwnedTestContainers在本类上下文物理关闭后回收。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_share_websocket").withUsername("thingslink").withPassword("thingslink");
    /** 独立随机端口Redis，确保真实PUBLISH不接触开发中间件。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    /** 在业务连接池及Flyway建立之前启动两个专用真实容器。 */
    private static final String DATABASE_URL = startDatabase();
    /** 解析真实网络帧，不允许DTO掩盖额外业务值。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 随机监听端口由真实Servlet容器提供。 */
    @Value("${local.server.port}") private int port;
    /** 发送真正PUBLISH，而不是直接调用注册表。 */
    @Autowired private StringRedisTemplate redis;
    /** 仅为来源保护先于DB反例填满真实固定来源窗口。 */
    @Autowired private DashboardShareProtectionService protection;
    /** 使用生产频道配置，避免测试写死绕过实际订阅频道。 */
    @Autowired private RealtimeProperties realtime;
    /** 确認业务数据源仍为普通RLS角色。 */
    @Autowired private JdbcTemplate application;
    /** 每例只建立自己UUID命名的不可变历史。 */
    private JdbcTemplate owner;
    /** 模型、绑定、精确运行版本均由既有真实夹具管线创建。 */
    private DataFixture data;
    /** 仅本测试可写临时目录，不证明生产持久挂载。 */
    private static final Path LOG_PATH = logDirectory();
    /** shareId是唯一匿名主体，不由账号或App用户代替。 */
    private UUID shareId;
    /** secret只保留于测试内存和私有子协议。 */
    private String secret;
    /** 封存事实仅保存单向摘要。 */
    private String hash;
    /** 请求上下文与真实当前应用指针一致。 */
    private Fixture fixture;
    /** 包括断言失败路径也必须主动关闭网络连接。 */
    private final List<WebSocket> sockets = new ArrayList<>();
    /** 每例网络客户端独立释放选择器与连接。 */
    private HttpClient client;

    /** 创建完整版本和封存变量scope；网络请求不注入TenantContext。 */
    @BeforeEach void seed() throws Exception {
        // 前例可能故意填满127.0.0.1来源桶；只等待真实窗口经过，不删除保护事实。
        Thread.sleep(1100);
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE_URL, "thingslink", "thingslink"));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("dashboard_share_websocket");
        data = WebAppDataRuntimeFixture.seed(owner); fixture = data.runtime(); newShare();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 无论断言成功失败都释放真实网络资源，专库由容器回收。 */
    @AfterEach void closeClients() {
        sockets.forEach(WebSocket::abort);
        if (client != null) client.shutdownNow();
    }

    /** Cookie和Authorization完全忽略，只有私有share协议确权；服务器不回显秘密并只发送失效提示。 */
    @Test void upgradesIndependentShareIdentityAndAcknowledgesExactSubscription() throws Exception {
        Frames frames = new Frames();
        WebSocket socket = connect(secret, "https://share.example.test", frames, true);
        assertThat(socket.getSubprotocol()).isEqualTo("tc.share.properties.v1");
        socket.sendText(subscribe(data.first()), true).get(5, TimeUnit.SECONDS);
        JsonNode ack = frames.next();
        assertThat(ack.propertyNames()).containsExactlyInAnyOrder("type", "requestId", "subscriptionId", "count");
        assertThat(ack.path("type").asString()).isEqualTo("SUBSCRIBED");
        assertThat(ack.path("requestId").asString()).isEqualTo("share-network");
        assertThat(ack.path("count").asInt()).isEqualTo(1);
        publish();
        JsonNode hint = frames.next();
        assertThat(hint.propertyNames()).containsExactlyInAnyOrder("type", "subscriptionId", "devices");
        assertThat(hint.path("type").asString()).isEqualTo("INVALIDATE");
        assertThat(hint.path("devices").get(0).path("deviceId").asString()).isEqualTo(data.first().toString());
        assertThat(hint.toString()).doesNotContain(secret, hash, "37.125", "value", "actorAccountId");
        assertThat(application.queryForObject("SELECT count(*) FROM dash_share_token", Integer.class)).isZero();
    }

    /** 物理HTTP Origin和私有协议必须完整，Cookie/Authorization不能替代匿名capability。 */
    @Test void rejectsInvalidOriginAndWrongCapabilityBeforeUpgrade() throws Exception {
        for (String origin : new String[] {null, "null", "https://share.example.test.attacker.invalid", "http://share.example.test"}) {
            rejected(secret, origin, 403);
        }
        rejected(randomSecret(), "https://share.example.test", 404);
    }

    /** 同一个分享至多两条真实活动连接，关闭后原租约释放允许重新握手。 */
    @Test void thirdConnectionIsRejectedAndClosedSlotCanBeReused() throws Exception {
        Frames one = new Frames(); Frames two = new Frames();
        WebSocket first = connect(secret, "https://share.example.test", one, false);
        connect(secret, "https://share.example.test", two, false);
        rejected(secret, "https://share.example.test", 429);
        first.sendClose(WebSocket.NORMAL_CLOSURE, "release slot").get(5, TimeUnit.SECONDS);
        assertThat(one.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1000);
        Frames replacement = new Frames();
        WebSocket third = connect(secret, "https://share.example.test", replacement, false);
        third.sendText(subscribe(data.first()), true).get(5, TimeUnit.SECONDS);
        assertThat(replacement.next().path("type").asString()).isEqualTo("SUBSCRIBED");
    }

    /** 静默连接也每5秒回库复核撤销，无需新SUBSCRIBE或Redis提示触发失效。 */
    @Test void revokedQuietConnectionClosesOnProactiveRevalidation() throws Exception {
        Frames frames = new Frames();
        WebSocket socket = connect(secret, "https://share.example.test", frames, false);
        socket.sendText(subscribe(data.first()), true).get(5, TimeUnit.SECONDS);
        assertThat(frames.next().path("type").asString()).isEqualTo("SUBSCRIBED");
        owner.update("UPDATE dash_share_token SET revoked_at=clock_timestamp(),revoked_by=? WHERE id=?", fixture.actorId(), shareId);
        assertThat(frames.closed.poll(7, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(frames.messages.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    /** 订阅只允许三字段；同模型另一变量候选以及客户端project/runtimeContext均不能扩权。 */
    @Test void rejectsCrossVariableKeysAndAdditionalSubscriptionContext() throws Exception {
        for (String body : List.of(subscribe(data.second()), subscribe(data.first()).replace("temperature", "secret"),
                "{\"projectId\":\"" + fixture.projectId() + "\"," + subscribe(data.first()).substring(1))) {
            Frames frames = new Frames();
            WebSocket socket = connect(secret, "https://share.example.test", frames, false);
            socket.sendText(body, true).get(5, TimeUnit.SECONDS);
            assertThat(frames.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(frames.messages.poll(100, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    /** 活动租约真实Redis脚本故障导致1011，不能继承Console本机降级继续维持连接。 */
    @Test void redisLeaseFailureClosesQuietConnection() throws Exception {
        Frames frames = new Frames();
        WebSocket socket = connect(secret, "https://share.example.test", frames, false);
        socket.sendText(subscribe(data.first()), true).get(5, TimeUnit.SECONDS);
        assertThat(frames.next().path("type").asString()).isEqualTo("SUBSCRIBED");
        String key = "share:ws:connections:{" + shareId + "}";
        redis.delete(key);
        redis.opsForValue().set(key, "wrong-type", Duration.ofSeconds(30));
        try {
            assertThat(frames.closed.poll(7, TimeUnit.SECONDS)).isEqualTo(1011);
            assertThat(frames.messages.poll(100, TimeUnit.MILLISECONDS)).isNull();
        } finally { redis.delete(key); }
    }

    /** 来源桶满时即使DB定位函数不可执行也优先429，真实HTTP不能先执行capability定位。 */
    @Test void sourceProtectionRejectsBeforeUnavailableDatabaseLocator() throws Exception {
        Thread.sleep(1100);
        owner.execute("REVOKE EXECUTE ON FUNCTION resolve_dashboard_share_identity(uuid,character varying) FROM thingslink_app");
        try {
            for (int count = 0; count < 4; count++) {
                try (var ignored = protection.acquireSource("127.0.0.1")) { /* 真实Redis滑动窗口仍然计数。 */ }
            }
            rejected(secret, "https://share.example.test", 429);
        } finally {
            owner.execute("GRANT EXECUTE ON FUNCTION resolve_dashboard_share_identity(uuid,character varying) TO thingslink_app");
        }
    }

    /** 已冻结share版本不需要App四字段上下文或Console项目选择。 */
    private String subscribe(UUID device) {
        var root = JSON.createObjectNode().put("type", "SUBSCRIBE").put("requestId", "share-network");
        root.putArray("devices").addObject().put("deviceId", device.toString()).putArray("propertyKeys").add("temperature");
        return root.toString();
    }

    /** 真正Redis推送含值事实；WebSocket只能投影已选键而不能排队业务值。 */
    private void publish() {
        var update = new DeviceRealtimeUpdate(UUID.randomUUID(), fixture.tenantId(), fixture.projectId(),
                data.first(), data.model(), "1.0.0", Instant.now(), 7, "share-network",
                Map.of("temperature", "37.125"), Map.of("temperature", "NUMBER"));
        assertThat(redis.convertAndSend(realtime.getChannelPrefix() + fixture.projectId(), JSON.writeValueAsString(update))).isPositive();
    }

    /** 真实浏览器握手语义只在协议传secret，未要求浏览器不保证提供的Referer。 */
    private WebSocket connect(String token, String origin, Frames frames, boolean ambientCredentials) throws Exception {
        // 所有JDK连接来自同一个真实remoteAddr，固定间隔保留4/s来源保护而不删除Redis计量。
        Thread.sleep(300);
        var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .subprotocols("tc.share.properties.v1", "share." + token);
        if (origin != null) builder.header("Origin", origin);
        if (ambientCredentials) builder.header("Cookie", "session=ignored").header("Authorization", "Bearer ignored-console-token");
        WebSocket socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/shares/" + shareId + "/properties"), frames)
                .get(8, TimeUnit.SECONDS);
        sockets.add(socket); return socket;
    }

    /** 必须收到具体HTTP拒绝，不能把连接异常或超时误当作授权反例成功。 */
    private void rejected(String token, String origin, int status) {
        assertThatThrownBy(() -> connect(token, origin, new Frames(), true)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        failure -> assertThat(failure.getResponse().statusCode()).isEqualTo(status));
    }

    /** 测试专库与Redis同时在真实Servlet服务器初始化之前启动。 */
    private static String startDatabase() { DATABASE.start(); REDIS.start(); return DATABASE.getJdbcUrl(); }
    /** 本地临时目录仅证明日志机制，不冒称生产持久卷验收。 */
    private static Path logDirectory() {
        try { return Files.createTempDirectory("thingslink-share-ws-security-"); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    /** 新token拥有相同冻结变量scope，但独立secret/selector保证跨分享游标反例不是错误凭据。 */
    private void newShare() throws Exception {
        shareId = UUID.randomUUID(); secret = randomSecret();
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        insertToken(shareId, hash, "HOST_ORIGIN", false);
        scope("sensor", 0, List.of(data.first()));
        scope("sensors", 1, List.of(data.first(), data.second()));
        seal(shareId);
    }

    /** 所有候选先于creation_result封存写入，复合FK保留真实版本及模型归属。 */
    private void scope(String key, int position, List<UUID> devices) {
        owner.update("""
                INSERT INTO dash_share_scope(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,position,thing_model_version_id) VALUES (?,?,?,?,?,?,?,?)
                """, fixture.tenantId(), fixture.projectId(), fixture.authorized().dashboardId(),
                fixture.authorized().dashboardVersionId(), shareId, key, position, data.model());
        for (int index = 0; index < devices.size(); index++) owner.update("""
                INSERT INTO dash_share_scope_device(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,device_id,thing_model_version_id,position) VALUES (?,?,?,?,?,?,?,?,?)
                """, fixture.tenantId(), fixture.projectId(), fixture.authorized().dashboardId(),
                fixture.authorized().dashboardVersionId(), shareId, key, devices.get(index), data.model(), index);
    }

    /** 按DB权威时钟播种合法scope为空的静态封存token，不绕过不可变UPDATE触发器。 */
    private void insertToken(UUID id, String tokenHash, String policy, boolean expired) {
        owner.update("""
                INSERT INTO dash_share_token(id,tenant_id,project_id,dashboard_id,dashboard_version_id,project_generation,
                    secret_hash,host_compatibility,referer_policy,created_at,expires_at,creator_account_id)
                VALUES (?,?,?,?,?,0,?,'{"minInclusive":"1.0.0","maxExclusive":"1.0.1"}'::jsonb,?,
                    clock_timestamp()-(? * interval '1 hour'),clock_timestamp()+(? * interval '1 hour'),?)
                """, id, fixture.tenantId(), fixture.projectId(), fixture.authorized().dashboardId(),
                fixture.authorized().dashboardVersionId(), tokenHash, policy, expired ? 2 : 0, expired ? -1 : 1, fixture.actorId());
    }
    /** 模拟已完成同事务签发的封存记录；未知和未封存历史仍不能成为运行身份。 */
    private void seal(UUID id) {
        owner.update("""
                INSERT INTO dash_share_creation_result(tenant_id,project_id,dashboard_id,account_id,idempotency_key_digest,
                    request_digest,share_id,created_at)
                SELECT tenant_id,project_id,dashboard_id,creator_account_id,
                    encode(digest(id::text,'sha256'),'hex'),repeat('b',64),id,created_at FROM dash_share_token WHERE id=?
                """, id);
    }
    /** 每个合法历史凭据保持hash唯一，测试间不能复用零字节secret污染专库唯一约束。 */
    private static String randomSecret() {
        byte[] value = new byte[32];
        new java.security.SecureRandom().nextBytes(value);
        return "sh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
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
        registry.add("things-link.dashboard.share-runtime.enabled", () -> true);
        registry.add("things-link.dashboard.share-runtime.host-origin", () -> "https://share.example.test");
        registry.add("things-link.dashboard.share-runtime.security-log-path", LOG_PATH::toString);
        registry.add("things-link.outbox.publisher.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("things-link.notification.retry.enabled", () -> "false");
    }
}
