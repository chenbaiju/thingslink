package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.application.ApplicationManagementService;
import com.things.link.dashboard.application.DashboardManagementService;
import com.things.link.dashboard.application.publication.ApplicationPublicationService;
import com.things.link.dashboard.application.publication.DashboardPublicationService;
import com.things.link.enduser.application.AppUserDashboardGrantManagementService;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.OwnedTestContainers;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.AfterAll;
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

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实制品、普通角色发布、实际CLI切换、真实Chromium；owner仅播种身份，不插入业务发布历史。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
@EnabledIfSystemProperty(named = "thingslink.test.host-browser", matches = "true")
class DashboardHostBrowserJourneyTests {
    /** 专用数据库随容器销毁，不能污染开发或生产事实。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(DockerImageName
            .parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("host_browser").withUsername("thingslink").withPassword("host-browser-test-only");
    /** 完整安全链的真实限流/会话依赖，不使用开发Redis。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 仅供专库测试用户，不能进入前端公开配置或日志。 */
    private static final String PASSWORD = "Host-browser-test-97-unique";
    /** 为同源代理取得可用端口，绑定失败不得接管其他进程。 */
    private static final int FRONT_PORT = frontPort();
    /** 复制已封存制品，坏字节反例与清理均不触碰1a原件。 */
    private static final Path REGISTRY = copyRegistry();
    static { DATABASE.start(); REDIS.start(); }
    /** 实际Servlet端口由Spring分配，不假设服务已运行。 */
    @Value("${local.server.port}") private int port;
    /** 创建真实草稿，不通过owner伪造业务版本。 */
    @Autowired private DashboardManagementService dashboards;
    /** 普通运行角色的真实看板发布及宿主准入。 */
    @Autowired private DashboardPublicationService dashboardPublications;
    /** 精确引用已发布看板的真实应用草稿入口。 */
    @Autowired private ApplicationManagementService applications;
    /** 应用发布与宿主共享准入同事务完成。 */
    @Autowired private ApplicationPublicationService applicationPublications;
    /** 授权通过既有管理服务，保留角色与CAS检查。 */
    @Autowired private AppUserDashboardGrantManagementService grants;
    /** 真实认证口令编码，不替换登录验签。 */
    @Autowired private PasswordEncoder passwords;
    /** 独立确认业务上下文使用非owner角色。 */
    @Autowired private JdbcTemplate runtime;

    /** 同一真实发布指针经历CLI切换、浏览器waiting更新、离线与回滚后保持不变。 */
    @Test void samePublishedApplicationSurvivesRealHostUpgradeAndRollback() throws Exception {
        Path root = repository(); Path logDirectory = root.resolve("logs/verify/g3-host-1f"); Files.createDirectories(logDirectory);
        String jar = System.getProperty("thingslink.test.host-tools-jar");
        assertThat(jar).as("必须显式提供实际1e工具JAR").isNotBlank();
        var environment = Map.of("TC_HOST_OPERATOR_JDBC_URL", DATABASE.getJdbcUrl(), "TC_HOST_OPERATOR_USER", DATABASE.getUsername(),
                "TC_HOST_OPERATOR_PASSWORD", DATABASE.getPassword(), "TC_HOST_REGISTRY_DIRECTORY", REGISTRY.toString());
        ProcessBuilder initial = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-jar", jar,
                "activate", "1.1.0", "0", UUID.randomUUID().toString());
        initial.environment().putAll(environment); initial.redirectErrorStream(true).redirectOutput(logDirectory.resolve("browser-initial-activation.json").toFile());
        Process activation = initial.start();
        try { assertThat(activation.waitFor(20, TimeUnit.SECONDS)).isTrue(); assertThat(activation.exitValue()).isZero(); }
        finally { if (activation.isAlive()) activation.destroyForcibly().waitFor(); }
        var owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), actor = UUID.randomUUID(), user = UUID.randomUUID();
        String projectKey = "host_" + project.toString().replace("-", ""), username = "host_" + user.toString().replace("-", "");
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'宿主浏览器验收')", tenant);
        owner.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", tenant);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'{noop}unused','宿主发布者')", actor, actor + "@example.com");
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)", UUID.randomUUID(), tenant, actor);
        owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'宿主项目','sh-1',?)", project, tenant, projectKey);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')", UUID.randomUUID(), project, actor);
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash,status) VALUES (?,?,?,?,'ACTIVE')", user, tenant, username, passwords.encode(PASSWORD));
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role,status) VALUES (?,?,?,?,'OBSERVER','ACTIVE')", UUID.randomUUID(), tenant, project, user);
        UUID dashboard, dashboardVersion, app, appVersion; String appKey;
        TenantContext.set(new TenantScope(tenant, project, actor));
        try {
            var board = dashboards.create(project, "宿主固定看板", """
                    {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},"models":[],"variables":[],
                     "pages":[{"id":"main","title":"宿主验收","components":[
                      {"id":"caption","kind":"TEXT","componentVersion":"1.0.1","layout":{"x":0,"y":0,"w":12,"h":4},
                       "props":{"content":"HOST_SWITCH_SAME_PUBLICATION"},"bindings":{}},
                      {"id":"mark","kind":"IMAGE","componentVersion":"1.0.1","layout":{"x":12,"y":0,"w":12,"h":4},
                       "props":{"resourceId":"device_mark","resourceDigest":"5977f591d5eeb691ee85c7656468a8b5dd1378b70b02a55af574331a0f0f0eaa","alt":"固定资源"},"bindings":{}}
                     ]}]}
                    """.getBytes(StandardCharsets.UTF_8));
            dashboard = board.id();
            dashboardVersion = dashboardPublications.publish(project, dashboard, "0", "0").id();
            grants.update(project, user, dashboard, "0", "ACTIVE");
            var application = applications.create(project, "双宿主同一应用", ("""
                    {"formatVersion":"tc.application/v1","displayName":"双宿主同一应用",
                     "hostCompatibility":{"minInclusive":"1.1.0","maxExclusive":"1.1.2"},
                     "dashboardRefs":[{"dashboardId":"%s","dashboardVersionId":"%s","title":"固定看板"}],"entryDashboardId":"%s"}
                    """).formatted(dashboard, dashboardVersion, dashboard).getBytes(StandardCharsets.UTF_8));
            app = application.id(); appKey = application.appKey();
            appVersion = applicationPublications.publish(project, app, "0", "0").id();
        } finally { TenantContext.clear(); RlsScopeContext.clear(); }
        ProcessBuilder browser = new ProcessBuilder("node", root.resolve("scripts/tests/webapp-host-switch-journey.cjs").toString());
        browser.directory(root.toFile()).redirectErrorStream(true).redirectOutput(logDirectory.resolve("browser-journey.log").toFile());
        browser.environment().putAll(environment);
        browser.environment().putAll(Map.ofEntries(Map.entry("HOST_JOURNEY_BACKEND", "http://127.0.0.1:" + port),
                Map.entry("HOST_JOURNEY_PORT", Integer.toString(FRONT_PORT)), Map.entry("HOST_JOURNEY_APP_KEY", appKey),
                Map.entry("HOST_JOURNEY_USERNAME", username), Map.entry("HOST_JOURNEY_PASSWORD", PASSWORD),
                Map.entry("HOST_JOURNEY_APP_VERSION", appVersion.toString()), Map.entry("HOST_JOURNEY_BOARD_VERSION", dashboardVersion.toString()),
                Map.entry("HOST_JOURNEY_JAR", jar), Map.entry("HOST_JOURNEY_JAVA", Path.of(System.getProperty("java.home"), "bin/java").toString()),
                Map.entry("HOST_JOURNEY_RECEIPT", logDirectory.resolve("browser-receipt.json").toString())));
        Process process = browser.start();
        try {
            assertThat(process.waitFor(240, TimeUnit.SECONDS)).as("浏览器旅程有界截止").isTrue();
            System.out.println(Files.readString(logDirectory.resolve("browser-journey.log")));
            assertThat(process.exitValue()).as("浏览器实际升级与回滚").isZero();
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
        assertThat(owner.queryForObject("SELECT current_version_id FROM app_application WHERE id=?", UUID.class, app)).isEqualTo(appVersion);
        assertThat(owner.queryForObject("SELECT current_version_id FROM dash_dashboard WHERE id=?", UUID.class, dashboard)).isEqualTo(dashboardVersion);
        assertThat(owner.queryForObject("SELECT publication_revision FROM app_application WHERE id=?", Long.class, app)).isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_host_deployment_history", Integer.class)).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT host_version FROM dash_host_deployment", String.class)).isEqualTo("1.1.0");
        assertThat(owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id=? AND revoked_at IS NULL AND replaced_by IS NULL AND expires_at>clock_timestamp()", Integer.class, user)).isZero();
        // ADR0144保留轮换前驱作复用检测证据，不能以强删或误计前驱认领登出。
        assertThat(owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id=? AND replaced_by IS NOT NULL", Integer.class, user)).isPositive();
    }
    /** 仅探测临时端口；后续绑定仍须成功，不停止任何占用进程。 */
    private static int frontPort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    /** 从Maven模块定位受版本控制的真实旅程脚本。 */
    private static Path repository() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("things-link-webapp"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("找不到仓库根"); return root;
    }
    /** 只复制显式提供的真实登记，不自动重新构建或覆盖版本。 */
    private static Path copyRegistry() {
        try {
            Path source = Path.of(System.getProperty("thingslink.test.host-registry"));
            Path copy = Files.createTempDirectory("host-browser-registry-").toRealPath();
            try (var paths = Files.walk(source)) {
                for (Path path : paths.toList()) {
                    Path target = copy.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
                }
            }
            return copy;
        } catch (Exception failure) { throw new IllegalStateException("真实宿主夹具准备失败", failure); }
    }
    /** 仅删除本测试创建的注册副本；日志保留用于审计。 */
    @AfterAll static void cleanup() throws Exception {
        try (var paths = Files.walk(REGISTRY)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    /** 运行连接为普通APP，owner仅作迁移/身份夹具或显式平台操作。 */
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl); registry.add("spring.datasource.username", () -> "thingslink_app");
        registry.add("spring.datasource.password", () -> "host-browser-app-only"); registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
        registry.add("spring.flyway.user", DATABASE::getUsername); registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> "host-browser-app-only");
        registry.add("spring.data.redis.host", REDIS::getHost); registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.dashboard.host.registry-directory", REGISTRY::toString);
        registry.add("things-link.security.app-browser.enabled", () -> true);
        registry.add("things-link.security.app-browser.origin", () -> "http://localhost:" + FRONT_PORT);
        registry.add("things-link.security.app-browser.allow-loopback-http", () -> true);
        registry.add("things-link.security.app-browser.cookie-active-key-id", () -> "host-browser-test");
        registry.add("things-link.security.app-browser.cookie-active-key-base64url", () -> Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        registry.add("things-link.outbox.publisher.enabled", () -> false); registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.notification.retry.enabled", () -> false);
    }
}
