package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
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
 * S12-3l真实生产静态制品与Chromium/Firefox/WebKit公开壳旅程，分享读取使用真实PG与Redis。
 * owner仅播种合法已发布历史，绝不以测试HostDescriptor或此夹具宣称D-145生产发布资格。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@EnabledIfSystemProperty(named = "webapp.pwa.verify", matches = "true")
@OwnedTestContainers({"DATABASE", "REDIS"})
class AppWebAppPwaJourneyTests {
    /** 单一Origin同时用于浏览器Cookie及WebSocket白名单。 */
    private static final String JOURNEY_ORIGIN = com.things.link.bootstrap.fixture.WebAppJourneyOrigin.origin();
    /** 专用真实迁移库，避免其他全量测试回收共享应用夹具。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webapp_pwa").withUsername("thingslink").withPassword("thingslink");
    /** 真实限流及会话依赖，不能连接开发Redis。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 只用于本测试用户，环境不会写入Vite公开配置。 */
    private static final String PASSWORD = "WebApp-journey-test-password-93";
    /** 独立Cookie测试用途密钥，不复用App/Console JWT。 */
    private static final byte[] COOKIE_KEY = cookieKey();
    /** 随机真实Servlet端口，由旅程静态服务代理连接。 */
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

    /** 只证明测试目录实际可写，不声明生产持久挂载。 */
    private static final Path SHARE_LOG_PATH = logDirectory();
    /** 独立诊断目录，不混写开发日志。 */
    private static Path logDirectory() {
        try { return Files.createTempDirectory("webapp-share-security-"); }
        catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }
    static { DATABASE.start(); REDIS.start(); }

    /** 三引擎实际SW缓存、分享离线清理、损坏安装与双制品升级；不替代TLS及实机资格。 */
    @Test void actualPublicShellServiceWorkerAcrossBrowsers() throws Exception {
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("webapp_pwa");
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink"));
        var share = com.things.link.bootstrap.fixture.WebAppShareJourneyFixture.seed(owner, passwords.encode(PASSWORD));
        var data = share.data();
        Fixture first = data.runtime();
        Path root = repository();
        Path log = Files.createTempFile("webapp-pwa-journey-", ".log");
        ProcessBuilder builder = new ProcessBuilder("node", root.resolve("scripts/tests/webapp-pwa-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("WEBAPP_JOURNEY_PORT", String.valueOf(java.net.URI.create(JOURNEY_ORIGIN).getPort())),
                Map.entry("WEBAPP_PWA_BACKEND_URL", "http://127.0.0.1:" + port),
                Map.entry("WEBAPP_PWA_ID", share.shareId().toString()),
                Map.entry("WEBAPP_PWA_SECRET", share.secret()),
                Map.entry("WEBAPP_PWA_FIRST_ID", data.first().toString()),
                Map.entry("WEBAPP_PWA_SECOND_ID", data.second().toString()),
                Map.entry("WEBAPP_PWA_PROJECT_ID", first.projectId().toString())));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(600, TimeUnit.SECONDS)).as("WebApp旅程有限截止；诊断文件%s", log).isTrue();
            // Node仅输出固定里程碑与脱敏错误；将失败证据带入CI，避免只留下Runner临时路径。
            System.out.println(Files.readString(log));
            assertThat(process.exitValue()).as("WebApp旅程诊断文件%s", log).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        assertThat(owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id=? "
                        + "AND revoked_at IS NULL", Integer.class, first.appUserId())).isZero();
        assertThat(application.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
    }

    /** 不依赖Maven当前模块目录，向上定位唯一受版本控制脚本。 */
    private static Path repository() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("scripts/tests/webapp-pwa-journey.cjs"))) path = path.getParent();
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
        registry.add("things-link.dashboard.share-runtime.enabled", () -> true);
        registry.add("things-link.dashboard.share-runtime.host-origin", () -> JOURNEY_ORIGIN);
        registry.add("things-link.dashboard.share-runtime.allow-loopback-http", () -> true);
        registry.add("things-link.dashboard.share-runtime.security-log-path", SHARE_LOG_PATH::toString);
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
