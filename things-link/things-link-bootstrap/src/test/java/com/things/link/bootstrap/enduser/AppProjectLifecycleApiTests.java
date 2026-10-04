package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.enduser.infrastructure.security.AppJwtProperties;
import com.things.link.iam.infrastructure.security.JwtProperties;
import com.things.link.project.application.ProjectService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR0064决策4：认证后HTTP生命周期快照统一覆盖真实App控制器，不能代替后续应用事务写许可。
 * 使用S11合法SQL种子及真实登录；不替换项目许可，故障仅在真实查询完成后执行真实错误SQL。
 */
@AutoConfigureMockMvc
class AppProjectLifecycleApiTests extends AbstractIntegrationTest {

    /** 与S11夹具一致的口令，数据库只保存真实编码结果。 */
    private static final String PASSWORD = "secret123";
    /** 经过安全链和真实控制器的HTTP入口。 */
    @Autowired private MockMvc mockMvc;
    /** 与生产一致的JSON信封。 */
    @Autowired private ObjectMapper mapper;
    /** 真实OWNER删除，不能用手改deleted_at代替原红例。 */
    @Autowired private ProjectService projectService;
    /** 真实密码匹配和改密副作用。 */
    @Autowired private PasswordEncoder passwordEncoder;
    /** 检验HTTP范围进入SQL及异常后的清理。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 只在隔离反例临时保存/恢复一个默认IP限流键，不清空共享Redis。 */
    @Autowired private StringRedisTemplate redis;
    /** 错配项目仍使用生产签发器及真实签名，而非伪造Authentication。 */
    @Autowired private AppTokenIssuer tokenIssuer;
    /** 缺声明场景使用真实App签名密钥，明确只破坏身份声明。 */
    @Autowired @Qualifier("appJwtEncoder") private JwtEncoder appEncoder;
    /** App签发者与控制台密码学边界保持现配置。 */
    @Autowired private AppJwtProperties appProperties;
    /** 控制台令牌必须仍被App安全链拒绝。 */
    @Autowired @Qualifier("jwtEncoder") private JwtEncoder consoleEncoder;
    /** 控制台测试令牌用真实issuer。 */
    @Autowired private JwtProperties consoleProperties;
    /** 不伪造生命周期结果，只在真实查询之后触发一次真实SQL异常。 */
    @MockitoSpyBean private JdbcProjectRepository projectRepository;
    /** 从创建前登记独占身份，失败清理不能触及其他测试数据。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());

    /** 每例独占项目/用户身份维度，login及request另显式设置独占IP，双维度均不借用共享回环桶。 */
    @BeforeEach
    void prepare() throws SQLException {
        seedFixture();
    }

    /** 默认IP耗尽仍可独占登录，默认桶不能增长；直接核验本例真实Redis桶和有效TTL证明隔离生效。 */
    @Test
    void fixtureLoginDoesNotConsumeUnrelatedDefaultIpQuota() throws Exception {
        String defaultKey = "app:rl:ip:127.0.0.1";
        // Redis 7.4原子保存值和绝对到期时间；恢复同一到期点，不延长其他测试的剩余窗口。
        List<?> previous = redis.execute(new DefaultRedisScript<>("""
                local value = redis.call('GET', KEYS[1])
                local expiry = redis.call('PEXPIRETIME', KEYS[1])
                redis.call('SET', KEYS[1], '60', 'PX', 300000)
                return {value or '', expiry}
                """, List.class), List.of(defaultKey));
        assertThat(previous).hasSize(2);
        try {
            assertThat(redis.opsForValue().get(defaultKey)).isEqualTo("60");
            Tokens tokens = login();
            assertThat(tokens.accessToken()).isNotBlank();
            assertThat(redis.opsForValue().get(defaultKey)).isEqualTo("60");
            String ownKey = "app:rl:ip:" + fixture.clientIp();
            assertThat(redis.opsForValue().get(ownKey)).isEqualTo("1");
            assertThat(redis.getExpire(ownKey, java.util.concurrent.TimeUnit.MILLISECONDS)).isPositive();
        } finally {
            // -2表示原键不存在，-1表示原键永久；已到期的原键会被PEXPIREAT立即删除。
            redis.execute(new DefaultRedisScript<>("""
                    local expiry = tonumber(ARGV[2])
                    if expiry == -2 then
                        redis.call('DEL', KEYS[1])
                    else
                        redis.call('SET', KEYS[1], ARGV[1])
                        if expiry >= 0 then redis.call('PEXPIREAT', KEYS[1], expiry) end
                    end
                    return 1
                    """, Long.class), List.of(defaultKey), previous.get(0).toString(), previous.get(1).toString());
        }
    }

    /** 原P0-5红例：旧JWT正常读取后真实OWNER删除；保留事实但列表/详情精确拒绝，注销仍可收束。 */
    @Test
    void deletedProjectRejectsOldJwtReadsButAllowsBodyRefreshLogout() throws Exception {
        Tokens tokens = login();
        assertReadable(tokens.accessToken());
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            projectService.delete(fixture.projectId());
        } finally {
            TenantContext.clear();
        }
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM sys_project WHERE id = ? AND status = 'DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM dev_device WHERE id = ? AND deleted_at IS NULL AND status = 'ONLINE'", fixture.deviceId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_role WHERE project_id = ? AND status = 'ACTIVE'", fixture.projectId())).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM app_user_device WHERE project_id = ? AND status = 'ACTIVE'", fixture.projectId())).isEqualTo(1);
        }
        MvcResult list = request(get("/api/v1/app/devices"), tokens.accessToken());
        MvcResult detail = request(get("/api/v1/app/devices/{id}", fixture.deviceId()), tokens.accessToken());
        SoftAssertions softly = new SoftAssertions();
        for (MvcResult result : List.of(list, detail)) {
            softly.assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(401);
            softly.assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(60009);
            softly.assertThat(result.getHandler()).isNull();
        }
        softly.assertAll();
        mockMvc.perform(post("/api/v1/app/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("refreshToken", tokens.refreshToken()))))
                .andExpect(status().isNoContent());
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM app_refresh_token WHERE project_id = ? AND revoked_at IS NOT NULL", fixture.projectId())).isEqualTo(1);
        }
        assertCleanScope();
    }

    /** 统一过滤真实改密、PUSH注册/吊销、SHARE及CLAIM，不能只修设备列表或将只读误报令牌失效。 */
    @ParameterizedTest(name = "{0}项目阻止所有代表性业务写")
    @ValueSource(strings = {"DELETING", "ARCHIVED"})
    void lifecycleRejectsProtectedMutationsBeforeControllers(String projectStatus) throws Exception {
        Tokens tokens = login();
        UUID installationId = Uuid7.generate();
        // ACTIVE正对照确认路由确实进入已有控制器；无效能力令牌只验证路由，不冒充绑定业务验收。
        assertController(request(push(installationId, "initial-token"), tokens.accessToken()), 204, "AppPushTokenController");
        assertController(request(password("wrong-password"), tokens.accessToken()), 400, "AppAuthController");
        assertController(request(capability("device-shares"), tokens.accessToken()), 400, "AppDeviceShareController");
        assertController(request(capability("device-claims"), tokens.accessToken()), 400, "AppDeviceClaimController");
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE sys_project SET status = ? WHERE id = ?", projectStatus, fixture.projectId());
        }
        List<String> before = facts();
        int expectedStatus = "ARCHIVED".equals(projectStatus) ? 403 : 401;
        int expectedCode = "ARCHIVED".equals(projectStatus) ? 60022 : 60009;
        for (MockHttpServletRequestBuilder mutation : List.of(password(PASSWORD), push(installationId, "replacement-token"),
                delete("/api/v1/app/push-tokens/{id}", installationId), capability("device-shares"), capability("device-claims"))) {
            assertError(request(mutation, tokens.accessToken()), expectedStatus, expectedCode, true);
            assertCleanScope();
        }
        assertThat(facts()).isEqualTo(before);
    }

    /** 归档保持原JWT读取；真实角色失效仍拒绝，生命周期允许不能绕过已有领域授权。 */
    @Test
    void archivedProjectAllowsGetAndHeadButStillRequiresActiveRole() throws Exception {
        Tokens tokens = login();
        assertReadable(tokens.accessToken());
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
        }
        assertReadable(tokens.accessToken());
        assertController(request(head("/api/v1/app/devices/{id}", fixture.deviceId()), tokens.accessToken()), 200, "AppDeviceController");
        assertCleanScope();
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE app_user_role SET status = 'DISABLED' WHERE project_id = ?", fixture.projectId());
        }
        MvcResult denied = request(get("/api/v1/app/devices"), tokens.accessToken());
        assertError(denied, 401, 60009, false);
        assertThat(denied.getHandler()).isInstanceOf(HandlerMethod.class);
    }

    /** 四种项目不可见性统一失败，不泄露存在性；错身份使用真实签发器且保留合法用户。 */
    @ParameterizedTest(name = "{0}项目快照统一60009")
    @EnumSource(Unavailable.class)
    void unavailableProjectIdentityIsRejectedWithoutControllerSideEffects(Unavailable unavailable) throws Exception {
        Tokens original = login();
        assertReadable(original.accessToken());
        UUID tenantId = fixture.tenantId();
        UUID projectId = fixture.projectId();
        if (unavailable == Unavailable.WRONG_TENANT) tenantId = Uuid7.generate();
        if (unavailable == Unavailable.MISSING) projectId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            if (unavailable == Unavailable.DELETING) execute(owner, "UPDATE sys_project SET status = 'DELETING' WHERE id = ?", fixture.projectId());
            if (unavailable == Unavailable.SOFT_DELETED) execute(owner, "UPDATE sys_project SET deleted_at = clock_timestamp() WHERE id = ?", fixture.projectId());
        }
        String token = tokenIssuer.issue(new AppAuthenticatedPrincipal(tenantId, projectId, fixture.userId())).value();
        List<String> before = facts();
        assertError(request(get("/api/v1/app/devices"), token), 401, 60009, true);
        assertError(request(password(PASSWORD), token), 401, 60009, true);
        assertThat(facts()).isEqualTo(before);
        assertCleanScope();
    }

    /** 真实快照查询故障必须早于RLS范围建立并返回90000；清理后下一请求正常完成。 */
    @Test
    void snapshotSqlFailureReturnsSystemErrorAndClearsScopeBeforeRecovery() throws Exception {
        Tokens tokens = login();
        List<String> before = facts();
        AtomicBoolean inject = new AtomicBoolean(true);
        AtomicBoolean queried = new AtomicBoolean();
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(projectRepository);
        doAnswer(invocation -> {
            Object project = invocation.callRealMethod();
            if (inject.getAndSet(false)) {
                queried.set(true);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TenantContext.current()).isEmpty();
                assertThat(RlsScopeContext.current()).isEmpty();
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COALESCE(current_setting('app.tenant_id', true), '')", String.class)).isEmpty();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COALESCE(current_setting('app.project_id', true), '')", String.class)).isEmpty();
                jdbcTemplate.execute("SELECT 1 / 0");
            }
            return project;
        }).when(target).findLiveByIdentity(fixture.tenantId(), fixture.projectId());
        assertError(request(password(PASSWORD), tokens.accessToken()), 500, 90000, true);
        assertThat(queried).isTrue();
        assertThat(facts()).isEqualTo(before);
        assertCleanScope();
        assertController(request(password(PASSWORD), tokens.accessToken()), 204, "AppAuthController");
        assertCleanScope();
    }

    /** 新门禁不能放松无令牌、控制台令牌或签名合法但身份不完整的拒绝。 */
    @Test
    void authenticationStillRejectsMissingConsoleAndIncompleteAppCredentials() throws Exception {
        assertError(request(get("/api/v1/app/devices"), null), 401, 60009, true);
        String console = signed(consoleEncoder, consoleProperties.issuer(), true, true, true);
        assertError(request(get("/api/v1/app/devices"), console), 401, 60009, true);
        for (String malformed : List.of(signed(appEncoder, appProperties.issuer(), false, true, true),
                signed(appEncoder, appProperties.issuer(), true, false, true),
                signed(appEncoder, appProperties.issuer(), true, true, false))) {
            assertError(request(password(PASSWORD), malformed), 401, 60009, true);
        }
        assertCleanScope();
    }

    /** 公开三条路径只能精确排除；近似前缀必须在路由匹配之前接受生命周期门禁。 */
    @Test
    void publicAuthPathPrefixesDoNotBypassLifecycleAdmission() throws Exception {
        Tokens tokens = login();
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE sys_project SET status = 'DELETING' WHERE id = ?", fixture.projectId());
        }
        for (String publicPath : List.of("login", "refresh", "logout")) {
            assertError(request(post("/api/v1/app/auth/" + publicPath + "/extra"), tokens.accessToken()), 401, 60009, true);
        }
        assertCleanScope();
    }

    /** 正常列表及详情均必须返回本例设备，防止空夹具导致假阳性。 */
    private void assertReadable(String token) throws Exception {
        mockMvc.perform(get("/api/v1/app/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(fixture.deviceId().toString()));
        mockMvc.perform(get("/api/v1/app/devices/{id}", fixture.deviceId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(fixture.deviceId().toString()));
        assertCleanScope();
    }

    /** 真实登录同时隔离projectKey/username与clientIp两类限流桶，不借共享127.0.0.1窗口。 */
    private Tokens login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/app/auth/login")
                        .with(servlet -> { servlet.setRemoteAddr(fixture.clientIp()); return servlet; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("projectKey", fixture.projectKey(), "username", "project_delete_app", "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertCleanScope();
        return new Tokens(body.get("accessToken").asText(), body.get("refreshToken").asText());
    }

    /** 控制器接受的合法改密形状；旧密码参数用于成功及真实400对照。 */
    private MockHttpServletRequestBuilder password(String oldPassword) {
        return post("/api/v1/app/auth/password").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("oldPassword", oldPassword, "newPassword", "new-password-456")));
    }

    /** 真实PUSH注册形状，不依赖畸形请求在Bean Validation先行失败。 */
    private MockHttpServletRequestBuilder push(UUID installationId, String token) {
        return put("/api/v1/app/push-tokens").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("installationId", installationId, "provider", "HUAWEI", "token", token)));
    }

    /** 合法DTO但不存在的能力；ACTIVE对照会进入实际消费控制器并统一返回凭据错误。 */
    private MockHttpServletRequestBuilder capability(String path) {
        return post("/api/v1/app/" + path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("token", "missing-capability-" + fixture.projectId())));
    }

    /** 仅装配真实Bearer头，不使用spring-security-test的伪造认证。 */
    private MvcResult request(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.with(servlet -> { servlet.setRemoteAddr(fixture.clientIp()); return servlet; });
        return mockMvc.perform(request).andReturn();
    }

    /** 系统故障与业务拒绝必须有明确JSON错误码，filter拒绝时MVC尚未选controller。 */
    private void assertError(MvcResult result, int statusCode, int errorCode, boolean beforeController) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(statusCode);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(errorCode);
        if (beforeController) assertThat(result.getHandler()).isNull();
    }

    /** ACTIVE对照定位真实controller类型，不能用不存在的路由假称全入口门禁成立。 */
    private void assertController(MvcResult result, int statusCode, String controller) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(statusCode);
        assertThat(result.getHandler()).isInstanceOf(HandlerMethod.class);
        assertThat(((HandlerMethod) result.getHandler()).getBeanType().getSimpleName()).isEqualTo(controller);
    }

    /** 同线程请求退出后清ThreadLocal且新APP连接无SQL范围，后续请求不得继承上次身份。 */
    private void assertCleanScope() {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(current_setting('app.tenant_id', true), '')", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(current_setting('app.project_id', true), '')", String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, fixture.userId())).isZero();
    }

    /** 完整保留口令、角色、关系、安装实例和会话事实，可见任何部分副作用。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("app_user", "app_user_role", "app_user_device", "app_push_token", "app_refresh_token")) {
                // 表名来自固定白名单，参数只锁定本例租户，不读取其他测试事实。
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(t)::text FROM " + table + " t WHERE tenant_id = ? ORDER BY id")) {
                    query.setObject(1, fixture.tenantId());
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(table + rows.getString(1)); }
                }
            }
        }
        return List.copyOf(result);
    }

    /** 用实际签名构造单轴缺失声明，不能直接向SecurityContext注入主体。 */
    private String signed(JwtEncoder encoder, String issuer, boolean tenant, boolean project, boolean user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(issuer).issuedAt(now).expiresAt(now.plusSeconds(300));
        if (tenant) claims.claim("tid", fixture.tenantId().toString());
        if (project) claims.claim("pid", fixture.projectId().toString());
        if (user) claims.subject(fixture.userId().toString());
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    /** 只改变一个生命周期拒绝原因，各分支对外不能暴露项目存在性。 */
    private enum Unavailable {
        /** 项目真实存在但租户错配。 */ WRONG_TENANT,
        /** 项目完全不存在。 */ MISSING,
        /** 已进入删除状态而deleted_at尚为空。 */ DELETING,
        /** 已软删但仍保留ACTIVE枚举值。 */ SOFT_DELETED
    }

    /** @param accessToken 真实HTTP登录JWT @param refreshToken 真实持久会话的短暂明文 */
    private record Tokens(String accessToken, String refreshToken) { }

    /** 最小种子只有一个合法OWNER、App角色及设备绑定，不引入影子、时序点或命令夹具。 */
    private void seedFixture() throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '项目删除App诊断租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, '{noop}unused', '项目删除owner')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)",
                    Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, region, project_key) VALUES (?, ?, 'App删除项目', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, """
                    INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                    VALUES (?, ?, 'project_delete_app', ?, 'ACTIVE')
                    """, fixture.userId(), fixture.tenantId(), passwordEncoder.encode(PASSWORD));
            execute(owner, """
                    INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role, status)
                    VALUES (?, ?, ?, ?, 'APP_ADMIN', 'ACTIVE')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.userId());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status)
                    VALUES (?, ?, ?, 'app_delete_type', 'App删除设备类型', 'STANDARD', 'DIRECT', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'app_delete_device', 'App删除设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO app_user_device (id, tenant_id, project_id, app_user_id, device_id, relation_role, status)
                    VALUES (?, ?, ?, ?, ?, 'PRIMARY', 'ACTIVE')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.userId(), fixture.deviceId());
            owner.commit();
        }
    }

    /** 只清理本例项目与用户，绝不清空共享refresh表或其他用例数据。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM app_push_token WHERE tenant_id = ?", fixture.tenantId());
            execute(owner, "DELETE FROM app_refresh_token WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user_device WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user_role WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM app_user WHERE id = ?", fixture.userId());
            execute(owner, "DELETE FROM sys_project_member WHERE project_id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id = ? AND account_id = ?", fixture.tenantId(), fixture.accountId());
            execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
            execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            owner.commit();
        }
    }

    /** owner只用于种子、独立提交观察和独占清理，HTTP查询仍使用真实APP角色与RLS。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 参数化SQL固定本例身份，不拼接设备或用户输入。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 查询必须有结果，不能把数据库错误或不可见行伪装成零。 */
    private long count(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** @param tenantId 租户 @param projectId 项目 @param accountId 控制台OWNER @param userId App用户 @param typeId 类型 @param deviceId 设备 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID userId, UUID typeId, UUID deviceId) {
        /** 随机项目键使真实登录与Redis限流键不碰其他用例。 */
        private String projectKey() { return "app_delete_" + projectId.toString().replace("-", ""); }
        /** 文档IPv6前缀后使用UUID剩余24位十六进制构成六组，保留随机性而非截取单组导致桶碰撞。 */
        private String clientIp() {
            String suffix = projectId.toString().replace("-", "").substring(8);
            return "2001:db8:" + String.join(":", suffix.substring(0, 4), suffix.substring(4, 8),
                    suffix.substring(8, 12), suffix.substring(12, 16), suffix.substring(16, 20), suffix.substring(20, 24));
        }
    }
}
