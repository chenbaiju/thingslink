package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.AppAuthRateLimiter;
import com.things.link.iam.infrastructure.security.JwtProperties;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * App 终端用户认证接口的集成验收（S11-2a）。
 *
 * <p>与 {@code EndUserIsolationIntegrationTests} 共用真实 PostgreSQL/TimescaleDB +
 * Redis 容器，自己负责夹具清理。这里直接走 SQL 种子而非预置服务，是因为认证验收
 * 关注的是「登录/刷新/注销/改密」这些信任边界，预置服务本身已在管理面单测覆盖。
 *
 * <h2>为什么这条测试必须在 bootstrap 而非 enduser</h2>
 * 只有 bootstrap 把 iam（控制台安全链 + 控制台 {@code jwtEncoder}）+ enduser（App 安全链）
 * 装进同一个上下文，才能钉住「App 令牌与控制台令牌双向互斥」这个密码学隔离边界 ——
 * 在 enduser 模块单测里根本没有控制台链可供互斥。
 */
@DisplayName("App 终端用户认证（S11-2a）")
@AutoConfigureMockMvc
class AppAuthApiTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AppAuthRateLimiter rateLimiter;

    /** 控制台令牌签发器 + 配置：用于在测试里直接签一枚控制台令牌做互斥。 */
    @Autowired
    @Qualifier("jwtEncoder")
    private JwtEncoder consoleJwtEncoder;

    @Autowired
    private JwtProperties consoleJwtProperties;

    /** 本用例插入的租户，按依赖顺序回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 项目 → 归属租户，回收项目级事实时用于建立正确的项目上下文。 */
    private final Map<UUID, UUID> projectTenants = new LinkedHashMap<>();

    private UUID tenantId;
    private UUID projectId;
    private String projectKey;
    private UUID userId;

    @BeforeEach
    void clearRateLimiter() {
        // 共享 Redis 容器，跨用例计数必须清零，否则限流测试会误触上一用例的额度
        rateLimiter.clear();
    }

    @AfterEach
    void cleanup() {
        try {
            // app_refresh_token 是上下文建立类 RLS 豁免表，无需上下文直接删（且必须最先删，
            // 它外键指向 app_user / sys_project）
            jdbcTemplate.update("DELETE FROM app_refresh_token");
            for (Map.Entry<UUID, UUID> entry : projectTenants.entrySet()) {
                TenantContext.set(new TenantScope(entry.getValue(), entry.getKey(), Uuid7.generate()));
                jdbcTemplate.update("DELETE FROM app_user_role WHERE project_id = ?", entry.getKey());
            }
            for (UUID tenantId : tenantIds) {
                TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
                jdbcTemplate.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
            }
            TenantContext.clear();
            for (UUID projectId : projectTenants.keySet()) {
                jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
        } finally {
            TenantContext.clear();
        }
    }

    // ---------------------------------------------------------------- 登录

    @Test
    @DisplayName("登录成功返回访问令牌与刷新令牌，且访问令牌可访问受保护端点")
    void loginSuccessReturnsTokenPair() throws Exception {
        seedStandardUser();

        MvcResult result = mockMvc.perform(login(PASSWORD))
                .andExpect(status().isOk())
                .andReturn();

        Tokens tokens = tokensOf(result);
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();

        // 访问令牌被 App 链接受：打受保护端点改密（原口令正确）应 204
        mockMvc.perform(post("/api/v1/app/auth/password")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("oldPassword", PASSWORD, "newPassword", "new-password-456"))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("密码错误、用户不存在、坏 projectKey、锁定、无角色统一 60006")
    void loginFailuresAreMergedInto60006() throws Exception {
        seedStandardUser();

        mockMvc.perform(login("wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60006));

        // 用户不存在（租户里没有这个用户名）
        mockMvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("projectKey", projectKey, "username", "ghost", "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60006));

        // 坏 projectKey
        mockMvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("projectKey", "no-such-project", "username", "alice", "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60006));
    }

    @Test
    @DisplayName("锁定的终端用户无法登录（60006）")
    void lockedUserCannotLogin() throws Exception {
        seedUserWithStatus("LOCKED");

        mockMvc.perform(login(PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60006));
    }

    @Test
    @DisplayName("无有效项目角色的终端用户无法登录（60006）")
    void userWithoutRoleCannotLogin() throws Exception {
        tenantId = newTenant("租户");
        projectKey = uniqueProjectKey();
        projectId = newProject(tenantId, projectKey);
        userId = newUser(tenantId, "alice", PASSWORD, "ACTIVE");
        // 刻意不插 app_user_role

        mockMvc.perform(login(PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60006));
    }

    @Test
    @DisplayName("登录连续失败触发低基数限流（第 11 次 429）")
    void loginIsRateLimited() throws Exception {
        seedStandardUser();

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("wrong-password")).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("wrong-password"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(10029));
    }

    // ---------------------------------------------------------------- 刷新

    @Test
    @DisplayName("刷新成功轮换令牌，旧令牌复用触发整族撤销")
    void refreshRotatesAndDetectsReuse() throws Exception {
        seedStandardUser();
        Tokens first = tokensOf(mockMvc.perform(login(PASSWORD)).andExpect(status().isOk()).andReturn());

        Tokens second = tokensOf(mockMvc.perform(refresh(first.refreshToken()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(second.refreshToken()).isNotBlank().isNotEqualTo(first.refreshToken());

        // 复用旧的（已被轮换的）令牌 → 60007，且整族作废
        mockMvc.perform(refresh(first.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60007));
        // 新令牌同属一族，也被连带作废
        mockMvc.perform(refresh(second.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60007));
    }

    @Test
    @DisplayName("缺失/伪造刷新令牌统一 60007")
    void refreshRejectsMissingOrForgedToken() throws Exception {
        seedStandardUser();

        mockMvc.perform(post("/api/v1/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60007));

        mockMvc.perform(refresh("forged-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60007));
    }

    // ---------------------------------------------------------------- 注销

    @Test
    @DisplayName("注销后旧刷新令牌失效，幂等返回 204")
    void logoutRevokesSession() throws Exception {
        seedStandardUser();
        Tokens tokens = tokensOf(mockMvc.perform(login(PASSWORD)).andExpect(status().isOk()).andReturn());

        mockMvc.perform(post("/api/v1/app/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("refreshToken", tokens.refreshToken()))))
                .andExpect(status().isNoContent());

        mockMvc.perform(refresh(tokens.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60007));

        // 幂等：再次注销同样 204
        mockMvc.perform(post("/api/v1/app/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("refreshToken", tokens.refreshToken()))))
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- 改密

    @Test
    @DisplayName("改密成功后旧口令登录失败、旧刷新令牌失效")
    void changePasswordRevokesAllSessions() throws Exception {
        seedStandardUser();
        Tokens tokens = tokensOf(mockMvc.perform(login(PASSWORD)).andExpect(status().isOk()).andReturn());

        mockMvc.perform(post("/api/v1/app/auth/password")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("oldPassword", PASSWORD, "newPassword", "new-password-456"))))
                .andExpect(status().isNoContent());

        // 旧刷新令牌被撤销
        mockMvc.perform(refresh(tokens.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60007));
        // 旧口令登录失败，新口令成功
        mockMvc.perform(login(PASSWORD)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("projectKey", projectKey, "username", "alice", "password", "new-password-456"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("改密原口令错误返回 60008")
    void changePasswordRejectsWrongOldPassword() throws Exception {
        seedStandardUser();
        Tokens tokens = tokensOf(mockMvc.perform(login(PASSWORD)).andExpect(status().isOk()).andReturn());

        mockMvc.perform(post("/api/v1/app/auth/password")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("oldPassword", "nope", "newPassword", "new-password-456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(60008));
    }

    // ---------------------------------------------------------------- 双令牌互斥

    @Test
    @DisplayName("App 令牌与控制台令牌双向互斥（密码学隔离）")
    void appAndConsoleTokensAreMutuallyExclusive() throws Exception {
        seedStandardUser();
        Tokens appTokens = tokensOf(mockMvc.perform(login(PASSWORD)).andExpect(status().isOk()).andReturn());

        // App 令牌打控制台端点 → 被 console decoder 拒成 401
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + appTokens.accessToken()))
                .andExpect(status().isUnauthorized());

        // 控制台令牌打 App 端点 → 被 App decoder 拒成 60009
        mockMvc.perform(post("/api/v1/app/auth/password")
                        .header("Authorization", "Bearer " + consoleToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("oldPassword", PASSWORD, "newPassword", "new-password-456"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60009));
    }

    @Test
    @DisplayName("无令牌访问 App 受保护端点返回 60009")
    void appProtectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(post("/api/v1/app/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("oldPassword", PASSWORD, "newPassword", "new-password-456"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(60009));
    }

    // ---------------------------------------------------------------- 夹具

    /** 标准夹具：一个租户 + 一个项目 + 一名 ACTIVE 用户 + 一个 APP_ADMIN 角色。 */
    private void seedStandardUser() {
        tenantId = newTenant("租户");
        projectKey = uniqueProjectKey();
        projectId = newProject(tenantId, projectKey);
        userId = newUser(tenantId, "alice", PASSWORD, "ACTIVE");
        addRole(tenantId, projectId, userId);
    }

    private void seedUserWithStatus(String status) {
        tenantId = newTenant("租户");
        projectKey = uniqueProjectKey();
        projectId = newProject(tenantId, projectKey);
        userId = newUser(tenantId, "alice", PASSWORD, status);
        addRole(tenantId, projectId, userId);
    }

    private UUID newTenant(String name) {
        UUID tenantId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, name);
        tenantIds.add(tenantId);
        return tenantId;
    }

    private UUID newProject(UUID tenantId, String key) {
        UUID projectId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, projectId, tenantId, "项目-" + projectId, key);
        projectTenants.put(projectId, tenantId);
        return projectId;
    }

    private UUID newUser(UUID tenantId, String username, String password, String status) {
        UUID userId = Uuid7.generate();
        TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                    VALUES (?, ?, ?, ?, ?)
                    """, userId, tenantId, username, passwordEncoder.encode(password), status);
        } finally {
            TenantContext.clear();
        }
        return userId;
    }

    private void addRole(UUID tenantId, UUID projectId, UUID userId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role)
                    VALUES (?, ?, ?, ?, 'APP_ADMIN')
                    """, Uuid7.generate(), tenantId, projectId, userId);
        } finally {
            TenantContext.clear();
        }
    }

    private static String uniqueProjectKey() {
        return "pk" + Uuid7.generate().toString().replace("-", "").substring(0, 16);
    }

    /** 用控制台密钥签一枚控制台令牌（subject 是任意 accountId，供互斥测试）。 */
    private String consoleToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(consoleJwtProperties.issuer())
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("tid", tenantId.toString())
                .claim("pid", projectId.toString())
                .build();
        return consoleJwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    // ---------------------------------------------------------------- 请求助手

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String password) {
        return post("/api/v1/app/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("projectKey", projectKey, "username", "alice", "password", password)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refresh(String refreshToken) {
        return post("/api/v1/app/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("refreshToken", refreshToken)));
    }

    private Tokens tokensOf(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Tokens(node.get("accessToken").asText(), node.get("refreshToken").asText());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private record Tokens(String accessToken, String refreshToken) {
    }
}
