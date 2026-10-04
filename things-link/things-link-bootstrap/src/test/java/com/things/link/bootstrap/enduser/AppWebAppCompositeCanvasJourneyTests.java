package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.bootstrap.fixture.WebAppCompositeCanvasFixture;
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
 * S12-3h真实WebApp源码/Vite/Chromium与真实PG设备画布旅程，不mockAPI、不替换App.vue。
 * owner仅播种合法已发布历史，绝不以测试HostDescriptor或此夹具宣称D-145生产发布资格。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@EnabledIfSystemProperty(named = "webapp.composite.verify", matches = "true")
@OwnedTestContainers({"DATABASE", "REDIS"})
class AppWebAppCompositeCanvasJourneyTests {
    /** 专用真实迁移库，避免其他全量测试回收共享应用夹具。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webapp_composite_canvas").withUsername("thingslink").withPassword("thingslink");
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

    /** 使用HTTP实际Jackson配置验证字节事实，不能另配有利于测试的解析器。 */
    @Autowired private tools.jackson.databind.ObjectMapper mapper;
    /** 真实复合Profile校验与规范化实现，反射只在测试检查既有私有纯函数。 */
    @Autowired private com.things.link.device.infrastructure.schema.JacksonThingModelSchemaValidator validator;

    static { DATABASE.start(); REDIS.start(); }

    /** 真实PG多设备共享选择、本地表格翻页、当前值、晚到围栏与撤权；JDK启动真实Chromium而非模拟浏览器。 */
    @Test void actualCompositeValuesAndLocalPaging() throws Exception {
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("webapp_composite_canvas");
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink"));
        var data = WebAppCompositeCanvasFixture.seed(owner, passwords.encode(PASSWORD));
        Fixture first = data.runtime();
        // 独立loopback控制口只存在于测试进程，浏览器仍通过真实业务HTTP重新鉴权。
        var control = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        control.createContext("/revoke", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            owner.update("UPDATE app_user_device SET status='CLOSED',updated_at=now() WHERE app_user_id=? AND device_id=?", first.appUserId(), data.first());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        control.createContext("/canonical", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) { exchange.sendResponseHeaders(413, -1); exchange.close(); return; }
            captured.compareAndSet(null, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        control.start();
        Path root = repository();
        Path log = Files.createTempFile("webapp-composite-canvas-journey-", ".log");
        ProcessBuilder builder = new ProcessBuilder("node", root.resolve("scripts/tests/webapp-composite-canvas-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("WEBAPP_COMPOSITE_BACKEND_URL", "http://127.0.0.1:" + port),
                Map.entry("WEBAPP_COMPOSITE_APP_KEY", first.appKey()),
                Map.entry("WEBAPP_COMPOSITE_USERNAME", compact(first.appUserId())),
                Map.entry("WEBAPP_COMPOSITE_PASSWORD", PASSWORD),
                Map.entry("WEBAPP_COMPOSITE_USER_ID", first.appUserId().toString()),
                Map.entry("WEBAPP_COMPOSITE_PROJECT_ID", first.projectId().toString()),
                Map.entry("WEBAPP_COMPOSITE_FIRST_ID", data.first().toString()),
                Map.entry("WEBAPP_COMPOSITE_SECOND_ID", data.second().toString()),
                Map.entry("WEBAPP_COMPOSITE_CONTROL_URL", "http://127.0.0.1:" + control.getAddress().getPort())));
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
        assertCanonicalHttpValues(owner, data, captured.get());
    }

    /**
     * 将真实HTTP子树同生产规范器字节对照；证明本次Java/Jackson/PG样本，不泛化所有JSON数值词法。
     * PG JSONB会先规范1e0/1.000，故原始上传拼写不是HTTP必须保留的事实。
     */
    private void assertCanonicalHttpValues(JdbcTemplate owner, WebAppCompositeCanvasFixture.DataFixture data, String body) {
        assertThat(body).as("必须捕获浏览器实际current响应").isNotNull();
        // 观察器也必须保留原HTTP十进制，不能用double解析结果校验精确生产响应。
        tools.jackson.databind.JsonNode response = mapper.readerFor(tools.jackson.databind.JsonNode.class)
                .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(body);
        var model = mapper.readTree(owner.queryForObject("SELECT model_snapshot::text FROM dev_thing_model_version WHERE id=?", String.class, data.model()));
        int values = 0;
        for (var property : response.path("devices").get(0).path("values")) {
            assertThat(property.path("state").asString()).isEqualTo("VALUE");
            String key = property.path("propertyKey").asString();
            var value = property.path("value");
            String definition = model.path("properties").path(key).path("schema").toString();
            validator.validateInstance(definition, value, com.things.link.device.application.schema.ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
            tools.jackson.databind.JsonNode canonical = org.springframework.test.util.ReflectionTestUtils.invokeMethod(validator, "canonicalize", value);
            assertThat(canonical).isNotNull();
            assertThat(mapper.writeValueAsBytes(canonical)).containsExactly(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (key.equals("payload")) {
                assertThat(value.path("big").asString()).isEqualTo("9007199254740993");
                assertThat(value.path("decimal").decimalValue()).isEqualByComparingTo(
                        new java.math.BigDecimal("0.12345678901234567890123456789"));
                assertThat(value.path("escaped").asString()).isEqualTo("中文😀");
                assertThat(value.path("exp").decimalValue().compareTo(java.math.BigDecimal.ONE)).isZero();
                assertThat(value.path("trailing").decimalValue().compareTo(java.math.BigDecimal.ONE)).isZero();
                assertThat(value.path("tiny").decimalValue().signum()).isPositive();
                assertThat(canonical.toString()).contains("中文😀", "9007199254740993");
                // 原始HTTP字节中逐字段核词法，避免parse→serialize自我比较掩盖数值表示差异。
                for (String numeric : java.util.List.of("big", "decimal", "exp", "trailing", "tiny")) {
                    assertThat(body).contains("\"" + numeric + "\":" + value.path(numeric).toString());
                }
                assertThat(body).contains("\"escaped\":\"中文😀\"");
            } else assertThat(value.size()).isEqualTo(256);
            values++;
        }
        assertThat(values).isEqualTo(2);
    }

    /** 不依赖Maven当前模块目录，向上定位唯一受版本控制脚本。 */
    private static Path repository() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("scripts/tests/webapp-composite-canvas-journey.cjs"))) path = path.getParent();
        assertThat(path).as("WebApp旅程脚本仓库根目录").isNotNull();
        return path;
    }
    /** 现有运行夹具用户名和projectKey均为UUID规范去分隔形式。 */
    private static String compact(java.util.UUID id) { return id.toString().replace("-", ""); }
    /** 固定测试向量，不作为部署密钥生成策略。 */
    private static byte[] cookieKey() { byte[] value = new byte[32]; Arrays.fill(value, (byte) 94); return value; }

    /** 实际WebApp测试端口与Cookie独立Origin一致，HTTP仅明确loopback开发例外。 */
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
        registry.add("things-link.security.app-browser.enabled", () -> true);
        registry.add("things-link.security.app-browser.origin", com.things.link.bootstrap.fixture.WebAppJourneyOrigin::origin);
        registry.add("things-link.security.app-browser.allow-loopback-http", () -> true);
        registry.add("things-link.security.app-browser.cookie-active-key-id", () -> "webapp-journey");
        registry.add("things-link.security.app-browser.cookie-active-key-base64url", () -> Base64.getUrlEncoder().withoutPadding().encodeToString(COOKIE_KEY));
        registry.add("things-link.outbox.publisher.enabled", () -> false);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.notification.retry.enabled", () -> false);
    }
}
