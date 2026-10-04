package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.bootstrap.fixture.WebAppInteractiveCanvasFixture;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR0110真实命令控制旅程：未知结果同意图重试，ACK与成功由生产命令状态机证明。
 * owner仅播种合法历史与命令定义，不改命令状态、不代表D-145宿主发布资格。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@EnabledIfSystemProperty(named = "webapp.device-control.verify", matches = "true")
@OwnedTestContainers({"DATABASE", "REDIS"})
class AppWebAppDeviceControlJourneyTests {
    /** 专用真实迁移库，避免其他全量测试回收共享应用夹具。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webapp_device_control").withUsername("thingslink").withPassword("thingslink");
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

    /** 生产命令状态机推进派发与设备ACK/SUCCESS，禁止测试SQL修改状态。 */
    @Autowired private com.things.link.telemetry.application.DeviceCommandService commands;
    /** 读取真实Outbox冻结信封，不由测试拼造命令身份或deadline。 */
    @Autowired private tools.jackson.databind.ObjectMapper json;

    static { DATABASE.start(); REDIS.start(); }

    /** 真实命令派发与设备回复入口，事实取自已受理命令及原始Outbox信封。 */
    private void transition(JdbcTemplate owner, Fixture fixture, String action) {
        com.things.link.shared.tenant.TenantContext.set(new com.things.link.shared.tenant.TenantScope(
                fixture.tenantId(), fixture.projectId(), fixture.actorId()));
        try {
            String payload = owner.queryForObject("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='DEVICE_COMMAND_DISPATCH'", String.class, fixture.projectId());
            var dispatch = json.readValue(payload, com.things.link.shared.message.DeviceCommandDispatch.class);
            java.time.Instant now = java.time.Instant.now();
            if ("dispatch".equals(action)) assertThat(commands.markDispatched(dispatch, now)).isTrue();
            else {
                var status = "ack".equals(action) ? com.things.link.shared.message.DeviceCommandReply.Status.ACK
                        : "success".equals(action) ? com.things.link.shared.message.DeviceCommandReply.Status.SUCCESS : null;
                if (status == null) throw new IllegalArgumentException("非法测试回复动作");
                assertThat(commands.applyReply(new com.things.link.shared.message.DeviceCommandReply(UUID.randomUUID(),
                        fixture.tenantId(), fixture.projectId(), dispatch.connectionDeviceId(), dispatch.commandId(), now, now,
                        status, "{\"ok\":true}", null, null, "device-control-journey"))).isTrue();
            }
        } finally {
            com.things.link.shared.tenant.TenantContext.clear();
            com.things.link.shared.tenant.RlsScopeContext.clear();
        }
    }

    /** 浏览器通过真实UI提交与重试，服务端只产生一个命令、一次尝试和一个派发Outbox。 */
    @Test void actualUnknownRetryAndDeviceReplyStatus() throws Exception {
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("webapp_device_control");
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink"));
        var data = WebAppInteractiveCanvasFixture.seed(owner, passwords.encode(PASSWORD));
        Fixture first = data.runtime();
        owner.update("UPDATE app_user_device SET relation_role='PRIMARY' WHERE app_user_id=? AND device_id=?", first.appUserId(), data.first());
        owner.update("""
                INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,description,
                    input_schema,output_schema,timeout_seconds)
                VALUES (?,?,?,?,'adjust','调整设备','仅显式提交；预览不锁定后续版本',
                    '{"type":"object","properties":{"level":{"type":"integer"}},"required":["level"],"additionalProperties":false}'::jsonb,
                    '{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}'::jsonb,300)
                """, UUID.randomUUID(), first.tenantId(), first.projectId(), data.type());
        var control = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        control.createContext("/transition", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            try { transition(owner, first, exchange.getRequestURI().getQuery()); exchange.sendResponseHeaders(204, -1); }
            catch (Throwable failure) { failure.printStackTrace(); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        control.createContext("/facts", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            try {
                int expected = "empty".equals(exchange.getRequestURI().getQuery()) ? 0 : 1;
                assertThat(owner.queryForObject("SELECT count(*) FROM ts_device_command WHERE project_id=?", Integer.class, first.projectId())).isEqualTo(expected);
                assertThat(owner.queryForObject("SELECT count(*) FROM ts_device_command_attempt WHERE project_id=?", Integer.class, first.projectId())).isEqualTo(expected);
                assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='DEVICE_COMMAND_DISPATCH'", Integer.class, first.projectId())).isEqualTo(expected);
                if (expected == 1) assertThat(owner.queryForObject("SELECT app_user_id FROM ts_device_command WHERE project_id=?", UUID.class, first.projectId())).isEqualTo(first.appUserId());
                exchange.sendResponseHeaders(204, -1);
            } catch (Throwable failure) { failure.printStackTrace(); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        control.start();
        Path root = repository(); Path log = Files.createTempFile("webapp-device-control-journey-", ".log");
        ProcessBuilder builder = new ProcessBuilder("node", root.resolve("scripts/tests/webapp-device-control-journey.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.ofEntries(
                Map.entry("WEBAPP_CONTROL_BACKEND_URL", "http://127.0.0.1:" + port),
                Map.entry("WEBAPP_CONTROL_APP_KEY", first.appKey()), Map.entry("WEBAPP_CONTROL_USERNAME", compact(first.appUserId())),
                Map.entry("WEBAPP_CONTROL_PASSWORD", PASSWORD), Map.entry("WEBAPP_CONTROL_FIRST_ID", data.first().toString()),
                Map.entry("WEBAPP_CONTROL_CONTROL_URL", "http://127.0.0.1:" + control.getAddress().getPort())));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("设备控制旅程有限截止；日志%s", log).isTrue();
            System.out.println(Files.readString(log)); assertThat(process.exitValue()).as("设备控制旅程日志%s", log).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); control.stop(0); }
        assertThat(owner.queryForObject("SELECT status FROM ts_device_command WHERE project_id=?", String.class, first.projectId())).isEqualTo("SUCCEEDED");
        assertThat(owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id=? AND revoked_at IS NULL", Integer.class, first.appUserId())).isZero();
    }

    /** 不依赖Maven当前模块目录，向上定位唯一受版本控制脚本。 */
    private static Path repository() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("scripts/tests/webapp-device-control-journey.cjs"))) path = path.getParent();
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
        registry.add("things-link.ingestion.dashboard-realtime.app-allowed-origins", com.things.link.bootstrap.fixture.WebAppJourneyOrigin::origin);
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
