package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.AppBrowserRefreshCookieCodec;
import com.things.link.enduser.infrastructure.security.NimbusAppBrowserRefreshCookieCodec;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR0107真实HTTP/Cookie头与PG会话副作用验收；JDK客户端不宣称具备浏览器Cookie/WebLocks语义。
 * 独立opt-in方法由真实浏览器脚本取得协议验收，不替代未来生产WebApp宿主资格。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
class AppBrowserAuthHttpIntegrationTests {
    /** 真实浏览器测试代理与HTTP来源一致，明确是允许的loopback开发例外。 */
    private static final String ORIGIN = "http://localhost:18766";
    /** 专库避免并行测试全局清理或不可变历史残留影响认证事务。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("app_browser_auth_http").withUsername("thingslink").withPassword("thingslink");
    /** 独立Redis保留真实认证限流，不连接开发实例。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 临时测试密钥只用于本上下文，不能复用App/Console/PUSH用途。 */
    private static final byte[] COOKIE_KEY = bytes(32, 93);
    /** 真实JWS解码用于断言Cookie epoch，不能将其当浏览器解析器。 */
    private static final AppBrowserRefreshCookieCodec CODEC = new NimbusAppBrowserRefreshCookieCodec(
            "http-test", Map.of("http-test", COOKIE_KEY), ORIGIN, true);
    /** JSON检验严格公开字段，不把Cookie秘密写入响应断言输出。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 测试专用登录口令，不读取开发账号。 */
    private static final String PASSWORD = "Browser-test-password-93";
    /** HTTP端点随机端口，不开放固定后端服务。 */
    @Value("${local.server.port}") private int port;
    /** 业务必须通过普通APP角色和真实范围恢复。 */
    @Autowired private JdbcTemplate application;
    /** 密码使用生产编码器播种，避免mock认证成功。 */
    @Autowired private PasswordEncoder passwords;
    /** 真实Console签发器用于验证JWT用途隔离，不模拟App解码成功。 */
    @Autowired private TokenIssuer consoleTokens;
    /** 编码故障只用于单例反例，其他路径仍真实签验。 */
    @MockitoSpyBean private AppBrowserRefreshCookieCodec cookieCodec;
    /** Owner仅负责独占夹具和核查会话变化。 */
    private JdbcTemplate owner;
    /** 第一租户/项目用户。 */
    private Account first;
    /** 第二租户/项目用户，检验跨账号原子替换。 */
    private Account second;
    /** 手动传Cookie的HTTP探针不冒称浏览器自动附带Cookie。 */
    private HttpClient client;

    static { DATABASE.start(); REDIS.start(); }

    /** 每例独立两租户用户，HTTP调用不预先注入TenantContext。 */
    @BeforeEach void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink"));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        first = account(); second = account();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 清理HTTP客户端网络资源，UUID事实由专库容器回收。 */
    @AfterEach void closeClient() { if (client != null) client.shutdownNow(); }

    /** 成功响应不含refresh，Cookie属性严格且真实轮换保持epoch，退出撤族并同Path删除。 */
    @Test void loginRefreshAndLogoutUseBoundCookieAndRestrictedPublicResponse() throws Exception {
        String epoch = epoch(1);
        var login = login(first, PASSWORD, epoch, null);
        JsonNode logged = json(login, 200);
        assertThat(logged.propertyNames()).containsExactlyInAnyOrder("accessToken", "accessExpiresAt", "appUserId", "projectId");
        assertThat(logged.path("appUserId").asString()).isEqualTo(first.user().toString());
        String original = setCookie(login);
        attributes(original, false);
        String envelope = cookieValue(original);
        var verified = CODEC.decode(envelope);
        assertThat(verified.browserEpoch()).isEqualTo(epoch);
        assertThat(logged.toString().contains(envelope) || logged.toString().contains(verified.refreshToken())).isFalse();
        long maxAge = Long.parseLong(Arrays.stream(original.split(";"))
                .map(String::trim).filter(value -> value.startsWith("Max-Age=")).findFirst().orElseThrow().substring(8));
        assertThat(maxAge).isPositive().isLessThanOrEqualTo(Duration.between(Instant.now(), verified.expiresAt()).getSeconds() + 2);
        var refreshed = send("refresh", epochBody(epoch), envelope, ORIGIN, "application/json");
        json(refreshed, 200);
        String rotated = cookieValue(setCookie(refreshed));
        assertThat(CODEC.decode(rotated).browserEpoch()).isEqualTo(epoch);
        assertThat(CODEC.decode(rotated).refreshToken().equals(verified.refreshToken())).isFalse();
        var logout = send("logout", epochBody(epoch), rotated, ORIGIN, "application/json");
        assertThat(logout.statusCode()).isEqualTo(204);
        attributes(setCookie(logout), true);
        assertThat(active(first)).isZero();
        var missingLogout = send("logout", epochBody(epoch), null, ORIGIN, "application/json");
        assertThat(missingLogout.statusCode()).isEqualTo(204);
        assertThat(missingLogout.headers().allValues("Set-Cookie").size()).isZero();
    }

    /** Origin/JSON检查必须先于账号认证和会话创建，不能用Referer/宽松body降级。 */
    @Test void originAndStrictJsonFailuresDoNotCreateSessions() throws Exception {
        String body = loginBody(first, PASSWORD, epoch(2));
        for (String origin : new String[] {null, "null", "http://localhost:18767", "http://localhost.attacker.invalid:18766"}) {
            error(send("login", body, null, origin, "application/json"), 403, 60028);
        }
        var duplicateOrigin = request("/api/v1/app/browser-auth/login", body, null, ORIGIN, "application/json")
                .header("Origin", ORIGIN).build();
        error(client.send(duplicateOrigin, HttpResponse.BodyHandlers.ofString()), 403, 60028);
        for (String malformed : List.of(body.substring(0, body.length() - 1) + ",\"extra\":true}",
                "{\"browserEpoch\":\"" + epoch(2) + "\"," + body.substring(1), body + "{}")) {
            error(send("login", malformed, null, ORIGIN, "application/json"), 400, 10001);
        }
        var wrongContentType = send("login", body, null, ORIGIN, "text/plain");
        assertThat(wrongContentType.statusCode()).isIn(400, 415);
        assertThat(wrongContentType.headers().allValues("Set-Cookie").size()).isZero();
        assertThat(active(first)).isZero();
    }

    /** 新页面epoch与旧Cookie不符时不触发轮换、复用撤族或删除；旧过期同样先返回mismatch。 */
    @Test void epochMismatchAndInvalidCookieHaveNoDatabaseOrCookieSideEffects() throws Exception {
        String envelope = cookieValue(setCookie(login(first, PASSWORD, epoch(3), null)));
        var before = facts(first);
        for (String endpoint : List.of("refresh", "logout")) {
            error(send(endpoint, epochBody(epoch(4)), envelope, ORIGIN, "application/json"), 409, 60029);
        }
        var old = CODEC.decode(envelope);
        String expired = CODEC.encode(old.browserEpoch(), old.refreshToken(), Instant.now().minusSeconds(60));
        error(send("refresh", epochBody(epoch(4)), expired, ORIGIN, "application/json"), 409, 60029);
        error(send("refresh", epochBody(epoch(3)), "malformed-envelope", ORIGIN, "application/json"), 401, 60007);
        var duplicate = request("/api/v1/app/browser-auth/refresh", epochBody(epoch(3)), envelope, ORIGIN, "application/json")
                .header("Cookie", "tc_app_refresh=" + envelope).build();
        error(client.send(duplicate, HttpResponse.BodyHandlers.ofString()), 401, 60007);
        assertThat(facts(first)).isEqualTo(before);
    }

    /** 错误新口令不毁旧族；成功跨租户替换只在同事务签发新族并撤旧族。 */
    @Test void crossAccountReplacementPreservesOldSessionOnBadPassword() throws Exception {
        String old = cookieValue(setCookie(login(first, PASSWORD, epoch(5), null)));
        var before = facts(first);
        error(login(second, "incorrect-password", epoch(6), old), 401, 60006);
        assertThat(facts(first)).isEqualTo(before);
        assertThat(active(second)).isZero();
        var success = login(second, PASSWORD, epoch(6), old);
        assertThat(json(success, 200).path("appUserId").asString()).isEqualTo(second.user().toString());
        assertThat(active(first)).isZero();
        assertThat(active(second)).isEqualTo(1);
        assertThat(CODEC.decode(cookieValue(setCookie(success))).browserEpoch()).isEqualTo(epoch(6));
    }

    /** 原JSON接口继续返回双令牌并接收body refresh；Cookie不隐式变成普通数据接口身份。 */
    @Test void legacyJsonAuthenticationRemainsCompatibleAndCookieDoesNotAuthenticateData() throws Exception {
        String loginBody = JSON.writeValueAsString(Map.of("projectKey", first.projectKey(), "username", first.username(), "password", PASSWORD));
        var logged = client.send(request("/api/v1/app/auth/login", loginBody, null, null, "application/json").build(), HttpResponse.BodyHandlers.ofString());
        JsonNode tokens = json(logged, 200);
        assertThat(tokens.path("refreshToken").asString()).isNotBlank();
        assertThat(logged.headers().allValues("Set-Cookie").size()).isZero();
        var refreshed = client.send(request("/api/v1/app/auth/refresh", JSON.writeValueAsString(Map.of("refreshToken", tokens.path("refreshToken").asString())),
                null, null, "application/json").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json(refreshed, 200).path("refreshToken").asString().equals(tokens.path("refreshToken").asString())).isFalse();
        String envelope = cookieValue(setCookie(login(second, PASSWORD, epoch(7), null)));
        var dataRequest = HttpRequest.newBuilder(URI.create(backend() + "/api/v1/app/devices"))
                .header("Cookie", "tc_app_refresh=" + envelope).GET().build();
        assertThat(client.send(dataRequest, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    /** 浏览器获得的App access仍与Console JWT双向隔离，Cookie适配不改变原密码学边界。 */
    @Test void browserAccessAndConsoleTokensRemainMutuallyExclusive() throws Exception {
        JsonNode session = json(login(first, PASSWORD, epoch(10), null), 200);
        var consoleRequest = HttpRequest.newBuilder(URI.create(backend() + "/api/v1/auth/me"))
                .header("Authorization", "Bearer " + session.path("accessToken").asString()).GET().build();
        assertThat(client.send(consoleRequest, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        String console = consoleTokens.issue(new AuthenticatedPrincipal(UUID.randomUUID(), first.tenant(), first.project())).value();
        var appRequest = request("/api/v1/app/auth/password", JSON.writeValueAsString(Map.of("oldPassword", PASSWORD,
                "newPassword", "Changed-browser-test-password")), null, null, "application/json")
                .header("Authorization", "Bearer " + console).build();
        var denied = client.send(appRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(denied.body()).path("code").asInt()).isEqualTo(60009);
        assertThat(active(first)).isEqualTo(1);
    }

    /** 匹配代次的过期和真实旧令牌复用沿原会话失败语义清Cookie，不与错代拒绝混淆。 */
    @Test void matchedExpirationAndReusedRefreshClearCookie() throws Exception {
        String epoch = epoch(8);
        String old = cookieValue(setCookie(login(first, PASSWORD, epoch, null)));
        var verified = CODEC.decode(old);
        String expired = CODEC.encode(epoch, verified.refreshToken(), Instant.now().minusSeconds(60));
        var expiration = send("refresh", epochBody(epoch), expired, ORIGIN, "application/json");
        assertThat(json(expiration, 401).path("code").asInt()).isEqualTo(60007);
        attributes(setCookie(expiration), true);
        String rotated = cookieValue(setCookie(send("refresh", epochBody(epoch), old, ORIGIN, "application/json")));
        var reused = send("refresh", epochBody(epoch), old, ORIGIN, "application/json");
        assertThat(json(reused, 401).path("code").asInt()).isEqualTo(60007);
        attributes(setCookie(reused), true);
        assertThat(active(first)).isZero();
        var revokedSuccessor = send("refresh", epochBody(epoch), rotated, ORIGIN, "application/json");
        assertThat(json(revokedSuccessor, 401).path("code").asInt()).isEqualTo(60007);
        attributes(setCookie(revokedSuccessor), true);
    }

    /** 真实数据库后继写入故障导致整轮回滚，HTTP不能把内部错误当作已撤销而删除Cookie。 */
    @Test void databaseRotationFailureDoesNotClearCookieOrCommitPartialRevocation() throws Exception {
        String epoch = epoch(9);
        String original = cookieValue(setCookie(login(first, PASSWORD, epoch, null)));
        var before = facts(first);
        // 只在独占测试库临时注入失败，不修改生产迁移；finally始终撤下触发器与函数。
        owner.execute("CREATE FUNCTION app_refresh_token_http_failure() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'HTTP_TEST_INSERT_FAILURE'; END; $$");
        owner.execute("CREATE TRIGGER app_refresh_token_http_failure BEFORE INSERT ON app_refresh_token "
                + "FOR EACH ROW EXECUTE FUNCTION app_refresh_token_http_failure()");
        try {
            var failed = send("refresh", epochBody(epoch), original, ORIGIN, "application/json");
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(failed.headers().allValues("Set-Cookie").size()).isZero();
            assertThat(failed.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
            assertThat(facts(first)).isEqualTo(before);
        } finally {
            owner.execute("DROP TRIGGER app_refresh_token_http_failure ON app_refresh_token");
            owner.execute("DROP FUNCTION app_refresh_token_http_failure()");
        }
        json(send("refresh", epochBody(epoch), original, ORIGIN, "application/json"), 200);
    }

    /** 真实编码器在新旧会话写入后失败，必须由浏览器服务原事务整体回滚且不输出Cookie。 */
    @Test void cookieEncodingFailureRollsBackReplacementBeforeHttpCommit() throws Exception {
        String epoch = epoch(19);
        String previous = cookieValue(setCookie(login(first, PASSWORD, epoch, null)));
        var before = facts(first);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("test-encoding-failure"))
                .when(cookieCodec).encode(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try {
            error(login(second, PASSWORD, epoch(20), previous), 500, 90000);
            assertThat(facts(first)).isEqualTo(before);
            assertThat(active(second)).isZero();
        } finally { org.mockito.Mockito.reset(cookieCodec); }
        json(send("refresh", epochBody(epoch), previous, ORIGIN, "application/json"), 200);
    }

    /** 可选真实浏览器协议脚本使用同一真实PG/HTTP夹具；默认不重复执行昂贵浏览器验收。 */
    @Test
    @EnabledIfSystemProperty(named = "browser.protocol.verify", matches = "true")
    void realBrowserProtocolAgainstIsolatedBackend() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/tests/browser-auth-protocol.cjs"))) root = root.getParent();
        assertThat(root).as("真实浏览器协议脚本仓库根目录").isNotNull();
        Path output = Files.createTempFile("browser-auth-protocol-", ".log");
        ProcessBuilder builder = new ProcessBuilder("node", root.resolve("scripts/tests/browser-auth-protocol.cjs").toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(Map.of("BROWSER_AUTH_BACKEND_URL", backend(),
                "BROWSER_AUTH_PROJECT_KEY", first.projectKey(), "BROWSER_AUTH_USERNAME", first.username(),
                "BROWSER_AUTH_PASSWORD", PASSWORD, "BROWSER_AUTH_OTHER_PROJECT_KEY", second.projectKey(),
                "BROWSER_AUTH_OTHER_USERNAME", second.username(), "BROWSER_AUTH_OTHER_PASSWORD", PASSWORD));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("浏览器协议有限截止；输出位于%s", output).isTrue();
            assertThat(process.exitValue()).as("浏览器协议输出位于%s", output).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    /** 真实用户/项目/角色父链，由生产密码编码器建立；不通过owner执行业务认证。 */
    private Account account() {
        UUID tenant = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID user = UUID.randomUUID();
        String key = "browser_" + project.toString().replace("-", "");
        String name = "user_" + user.toString().replace("-", "");
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'浏览器HTTP测试租户')", tenant);
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key,status) VALUES (?,?,'浏览器HTTP项目',?,'ACTIVE')", project, tenant, key);
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash,status) VALUES (?,?,?,?,'ACTIVE')", user, tenant, name, passwords.encode(PASSWORD));
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role,status) VALUES (?,?,?,?,'OBSERVER','ACTIVE')", UUID.randomUUID(), tenant, project, user);
        return new Account(tenant, project, user, key, name);
    }
    /** 固定公开登录字段，旧Cookie只是可选替换能力。 */
    private HttpResponse<String> login(Account account, String password, String epoch, String cookie) throws Exception {
        return send("login", loginBody(account, password, epoch), cookie, ORIGIN, "application/json");
    }
    /** 不手拼密码或用户名JSON。 */
    private static String loginBody(Account account, String password, String epoch) {
        return JSON.writeValueAsString(Map.of("projectKey", account.projectKey(), "username", account.username(), "password", password, "browserEpoch", epoch));
    }
    /** refresh/logout唯一公开字段。 */
    private static String epochBody(String epoch) { return JSON.writeValueAsString(Map.of("browserEpoch", epoch)); }
    /** 真实网络请求显式携带探针Cookie，不使用模拟MVC或测试内部服务绕过入口。 */
    private HttpResponse<String> send(String endpoint, String body, String cookie, String origin, String contentType) throws Exception {
        return client.send(request("/api/v1/app/browser-auth/" + endpoint, body, cookie, origin, contentType).build(), HttpResponse.BodyHandlers.ofString());
    }
    /** 仅构造实际HTTP字段，不注入可信租户/账号。 */
    private HttpRequest.Builder request(String path, String body, String cookie, String origin, String contentType) {
        var request = HttpRequest.newBuilder(URI.create(backend() + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) request.header("Origin", origin);
        if (cookie != null) request.header("Cookie", "tc_app_refresh=" + cookie);
        return request;
    }
    /** 错误不能设置Cookie；匹配会话失效可清Cookie的独立反例另验。 */
    private static void error(HttpResponse<String> response, int status, int code) {
        var body = json(response, status);
        assertThat(body.path("code").asInt()).isEqualTo(code);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/json"));
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "traceId", "details");
        assertThat(body.path("message").isString()).isTrue();
        assertThat(body.path("traceId").isString()).isTrue();
        assertThat(body.path("details").isArray()).isTrue();
        assertThat(response.headers().allValues("Set-Cookie").size()).isZero();
    }
    /** 成功错误都无缓存；不在失败信息打印认证响应原文。 */
    private static JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        return JSON.readTree(response.body());
    }
    /** 单值Set-Cookie，避免客户端挑第一值掩盖重复响应。 */
    private static String setCookie(HttpResponse<String> response) {
        List<String> cookies = response.headers().allValues("Set-Cookie"); assertThat(cookies.size()).isEqualTo(1); return cookies.getFirst();
    }
    /** 只提取测试内部封装，不打印它或raw refresh。 */
    private static String cookieValue(String setCookie) { return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')); }
    /** 本类明确HTTP loopback例外；正式Secure属性由HTTPS配置验收覆盖。 */
    private static void attributes(String cookie, boolean deleting) {
        String attributes = cookie.substring(cookie.indexOf(';') + 1);
        assertThat(attributes).contains("Path=/api/v1/app/browser-auth", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain=", "Secure");
        if (deleting) assertThat(attributes).contains("Max-Age=0");
    }
    /** 只读无秘密字段的会话事实，可检测错误路径轮换/撤销。 */
    private List<Map<String, Object>> facts(Account account) {
        return owner.queryForList("SELECT id,family_id,revoked_at,replaced_by FROM app_refresh_token WHERE app_user_id=? ORDER BY id", account.user());
    }
    /** 活动会话数由PG权威事实判定。 */
    private int active(Account account) { return owner.queryForObject("SELECT count(*) FROM app_refresh_token WHERE app_user_id=? AND revoked_at IS NULL", Integer.class, account.user()); }
    /** 随机端口只供当前测试进程与本地代理使用。 */
    private String backend() { return "http://127.0.0.1:" + port; }
    /** 确定规范测试代次不包含账号或口令。 */
    private static String epoch(int value) { return "be_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(16, value)); }
    /** 仅用于固定测试向量，不作为生产随机源。 */
    private static byte[] bytes(int length, int value) { byte[] bytes = new byte[length]; Arrays.fill(bytes, (byte) value); return bytes; }
    /** @param tenant 真实归属 @param project 真实项目 @param user App用户 @param projectKey 登录选择器 @param username 临时用户名 */
    private record Account(UUID tenant, UUID project, UUID user, String projectKey, String username) { }

    /** 独立PG与Redis及显式loopback配置在真实服务器初始化之前固定。 */
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
        registry.add("things-link.security.app-browser.origin", () -> ORIGIN);
        registry.add("things-link.security.app-browser.allow-loopback-http", () -> true);
        registry.add("things-link.security.app-browser.cookie-active-key-id", () -> "http-test");
        registry.add("things-link.security.app-browser.cookie-active-key-base64url", () -> Base64.getUrlEncoder().withoutPadding().encodeToString(COOKIE_KEY));
        registry.add("things-link.outbox.publisher.enabled", () -> false);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.notification.retry.enabled", () -> false);
    }
}
