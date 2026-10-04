package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.bootstrap.fixture.WebAppDeviceCanvasFixture;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12-3j真实WebApp源码/Vite/Chromium与真实PG设备画布旅程，不mockAPI、不替换App.vue。
 * owner仅播种合法已发布历史，绝不以测试HostDescriptor或此夹具宣称D-145生产发布资格。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@EnabledIfSystemProperty(named = "webapp.realtime.verify", matches = "true")
@OwnedTestContainers({"DATABASE", "REDIS"})
class AppWebAppRealtimeJourneyTests {
    /** 单一Origin同时用于浏览器Cookie及WebSocket白名单。 */
    private static final String JOURNEY_ORIGIN = com.things.link.bootstrap.fixture.WebAppJourneyOrigin.origin();
    /** 专用真实迁移库，避免其他全量测试回收共享应用夹具。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webapp_realtime").withUsername("thingslink").withPassword("thingslink");
    /** 真实限流及会话依赖，不能连接开发Redis。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 只用于本测试用户，环境不会写入Vite公开配置。 */
    private static final String PASSWORD = "WebApp-journey-test-password-93";
    /** 独立Cookie测试用途密钥，不复用App/Console JWT。 */
    private static final byte[] COOKIE_KEY = cookieKey();
    /** 随机真实Servlet端口，由Vite开发代理连接。 */
    @Value("${local.server.port}") private int port;
    /** 真实普通APP角色必须参与所有运行HTTP读取。 */
    @Autowired private JdbcTemplate application;
    /** 仅播种密码使用真实编码器。 */
    @Autowired private PasswordEncoder passwords;

    /** 发布走真实Redis订阅链而非直接调用WebSocket registry。 */
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    /** 沿生产统一频道配置。 */
    @Autowired private com.things.link.ingestion.infrastructure.RealtimeProperties realtime;
    /** 沿实际Spring JSON配置编码共享事件。 */
    @Autowired private tools.jackson.databind.ObjectMapper mapper;

    static { DATABASE.start(); REDIS.start(); }

    /** 真实PG当前值、WebSocket失效提示、晚到围栏与REST降级；JDK启动真实Chromium而非模拟浏览器。 */
    @Test void actualRealtimeInvalidationAndRestFallback() throws Exception {
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("webapp_realtime");
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink"));
        var data = WebAppDeviceCanvasFixture.seed(owner, passwords.encode(PASSWORD));
        Fixture first = data.runtime();
        // 独立读取公开resolve先证明owner播种已形成真实可读入口，不经过浏览器或代理绕过失败。
        assertThat(owner.queryForObject("SELECT current_version_id FROM app_application WHERE id=?", java.util.UUID.class,
                first.applicationId())).isEqualTo(first.applicationVersionId());
        var resolution = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/app/applications/" + first.appKey() + "/resolve"))
                .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(resolution.statusCode()).as("专用真实后端公开resolve状态").isEqualTo(200);
        assertThat(mapper.readTree(resolution.body()).path("appKey").asString()).isEqualTo(first.appKey());
        System.out.println("PASS 独立PG当前版本及真实HTTP公开resolve入口核对");
        // 独立loopback控制口只存在于测试进程，浏览器仍通过真实业务HTTP重新鉴权。
        var control = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        control.createContext("/change", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            String operation = exchange.getRequestURI().getPath();
            String value = operation.equals("/change/stale") ? "99.5" : operation.equals("/change/silent") ? "66.5" : "88.5";
            if (!operation.equals("/change/notify")) {
                owner.update("UPDATE dev_shadow SET reported=jsonb_set(reported,'{temperature}',?::jsonb) WHERE device_id=?",
                        value, data.first());
            }
            // 模拟丢提示的真实PG变更；仅完整60秒校准负责发现，不能由测试额外触发网络恢复。
            if (operation.equals("/change/silent")) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
            var update = new com.things.link.shared.message.DeviceRealtimeUpdate(java.util.UUID.randomUUID(), first.tenantId(), first.projectId(),
                    data.first(), data.model(), "1.0.0", java.time.Instant.now(), 8, "browser-realtime-fixture",
                    Map.of("temperature", value), Map.of("temperature", "NUMBER"));
            Long listeners = redis.convertAndSend(realtime.getChannelPrefix() + first.projectId(), mapper.writeValueAsString(update));
            exchange.sendResponseHeaders(listeners != null && listeners > 0 ? 204 : 409, -1);
            exchange.close();
        });
        control.start();
        Path root = repository();
        Path log = Files.createTempFile("webapp-realtime-journey-", ".log");
        ProcessBuilder builder = new ProcessBuilder("node", root.resolve("scripts/tests/webapp-realtime-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("WEBAPP_JOURNEY_PORT", String.valueOf(java.net.URI.create(JOURNEY_ORIGIN).getPort())),
                Map.entry("WEBAPP_REALTIME_BACKEND_URL", "http://127.0.0.1:" + port),
                Map.entry("WEBAPP_REALTIME_APP_KEY", first.appKey()),
                Map.entry("WEBAPP_REALTIME_USERNAME", compact(first.appUserId())),
                Map.entry("WEBAPP_REALTIME_PASSWORD", PASSWORD),
                Map.entry("WEBAPP_REALTIME_USER_ID", first.appUserId().toString()),
                Map.entry("WEBAPP_REALTIME_PROJECT_ID", first.projectId().toString()),
                Map.entry("WEBAPP_REALTIME_FIRST_ID", data.first().toString()),
                Map.entry("WEBAPP_REALTIME_SECOND_ID", data.second().toString()),
                Map.entry("WEBAPP_REALTIME_CONTROL_URL", "http://127.0.0.1:" + control.getAddress().getPort())));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(240, TimeUnit.SECONDS)).as("WebApp旅程有限截止；诊断文件%s", log).isTrue();
            // Node仅输出固定里程碑与脱敏错误；将失败证据带入CI，避免只留下Runner临时路径。
            System.out.println(Files.readString(log));
            assertThat(process.exitValue()).as("WebApp旅程诊断文件%s", log).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); control.stop(0); }
        assertThat(owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id=? "
                        + "AND revoked_at IS NULL", Integer.class, first.appUserId())).isZero();
        assertThat(application.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
    }

    /** 不依赖Maven当前模块目录，向上定位唯一受版本控制脚本。 */
    private static Path repository() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("scripts/tests/webapp-realtime-journey.cjs"))) path = path.getParent();
        assertThat(path).as("WebApp旅程脚本仓库根目录").isNotNull();
        return path;
    }
    /** 现有运行夹具用户名和projectKey均为UUID规范去分隔形式。 */
    private static String compact(java.util.UUID id) { return id.toString().replace("-", ""); }
    /** 固定测试向量，不作为部署密钥生成策略。 */
    private static byte[] cookieKey() { byte[] value = new byte[32]; Arrays.fill(value, (byte) 94); return value; }

    /** 实际WebApp参数端口与Cookie独立Origin一致，HTTP仅明确loopback开发例外。 */
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "thingslink_app");
        registry.add("spring.datasource.password", () -> "thingslink");
        registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> "thingslink");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.ingestion.dashboard-realtime.app-allowed-origins", () -> JOURNEY_ORIGIN);
        registry.add("things-link.security.app-browser.enabled", () -> true);
        registry.add("things-link.security.app-browser.origin", () -> JOURNEY_ORIGIN);
        registry.add("things-link.security.app-browser.allow-loopback-http", () -> true);
        registry.add("things-link.security.app-browser.cookie-active-key-id", () -> "webapp-journey");
        registry.add("things-link.security.app-browser.cookie-active-key-base64url", () -> Base64.getUrlEncoder().withoutPadding().encodeToString(COOKIE_KEY));
        registry.add("things-link.outbox.publisher.enabled", () -> false);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.notification.retry.enabled", () -> false);
    }
}
