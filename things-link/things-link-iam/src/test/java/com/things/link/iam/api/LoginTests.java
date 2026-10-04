package com.things.link.iam.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 登录接口的端到端测试。
 *
 * <p>连真实 PostgreSQL：认证路径涉及 {@code lower(email)} 的唯一索引与部分索引、
 * 跨表 join，用替身测不出这些。
 */
@AutoConfigureMockMvc
@DisplayName("登录（S1 切片 1）")
class LoginTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    /**
     * 限流器是进程级单例，计数会跨测试类累计。本类反复用同一个邮箱登录，
     * 不清的话后面的用例会莫名 429 —— 而报错看起来与被测逻辑毫无关系。
     */
    @Autowired
    private AuthRateLimiter rateLimiter;

    private UUID tenantId;
    private UUID accountId;

    @BeforeEach
    void seed() {
        rateLimiter.clear();

        // 顺序：先删引用方再删被引用方，否则外键会挡住
        // project_member 引用 account，必须先删。同一个 Testcontainers 容器在
        // 模块内所有测试类之间共享，别的类建的项目会留在这里
        jdbcTemplate.update("DELETE FROM sys_project_member");
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");

        tenantId = Uuid7.generate();
        accountId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "测试租户");
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name, email_verified_at)
                VALUES (?, ?, ?, ?, now())
                """, accountId, "Owner@Example.com", passwordEncoder.encode(PASSWORD), "测试所有者");
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_member (id, tenant_id, account_id)
                VALUES (?, ?, ?)
                """, Uuid7.generate(), tenantId, accountId);
    }

    /** 抹掉 traceId，使两次请求的响应体可比较。 */
    private static String withoutTraceId(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString()
                .replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"<any>\"");
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andReturn();
    }

    /**
     * 令牌里<b>没有角色声明</b>（ADR 0012 校准后删除）。
     *
     * <p>角色放进令牌就会过期：管理员把某人降级之后，对方手里的令牌在剩余有效期内
     * 仍按原角色渲染菜单。改为每次请求回库查 —— 而且角色现在是**项目**角色，
     * 与登录时确定的租户无关。
     *
     * <p>也没有 pid：登录时不选项目，用户要先去项目列表选一个。
     */
    @Test
    @DisplayName("凭据正确时签发带租户声明的令牌，且不含角色与项目")
    void issuesTokenWithTenantClaim() throws Exception {
        MvcResult result = login("owner@example.com", PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());

        Jwt jwt = jwtDecoder.decode(body.get("accessToken").asString());
        assertThat(jwt.getSubject())
                .as("subject 用账号 ID 而非邮箱：邮箱可改，subject 必须稳定")
                .isEqualTo(accountId.toString());
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsString("role"))
                .as("角色不该进令牌：放进去就会过期，降级要等到令牌失效才生效")
                .isNull();
        assertThat(jwt.getClaimAsString("pid"))
                .as("登录时不选项目，用户要先去项目列表选一个")
                .isNull();
        assertThat(body.get("expiresAt").asString()).isNotBlank();
    }

    /**
     * 邮箱大小写不敏感。迁移里的唯一索引建在 {@code lower(email)} 上，
     * 查询若写成 {@code email = ?} 就会出现「注册时用大写、登录时用小写查不到，
     * 而唯一索引又不许重新注册」的死锁状态。
     */
    @Test
    @DisplayName("邮箱大小写不敏感")
    void emailIsCaseInsensitive() throws Exception {
        assertThat(login("OWNER@EXAMPLE.COM", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * 账号不存在与口令错误必须返回**完全相同**的响应，否则形成账号枚举漏洞：
     * 攻击者可以用任意邮箱试探，从差异中得到一份有效账号清单。
     */
    @Test
    @DisplayName("账号不存在与口令错误的响应完全一致（防账号枚举）")
    void doesNotRevealWhetherAccountExists() throws Exception {
        MvcResult wrongPassword = login("owner@example.com", "wrong-password");
        MvcResult noSuchAccount = login("nobody@example.com", PASSWORD);

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(noSuchAccount.getResponse().getStatus())
                .isEqualTo(wrongPassword.getResponse().getStatus());
        // traceId 每个请求都不同，是唯一允许的差异，比较前两边都要归一化
        assertThat(withoutTraceId(noSuchAccount))
                .as("两种失败的响应体必须一字不差，包括错误码与提示文案")
                .isEqualTo(withoutTraceId(wrongPassword));
    }

    @Test
    @DisplayName("账号被停用时返回 403 而非 401")
    void rejectsDisabledAccount() throws Exception {
        jdbcTemplate.update("UPDATE sys_account SET status = 'DISABLED' WHERE id = ?", accountId);

        MvcResult result = login("owner@example.com", PASSWORD);

        // 与凭据错误区分开：口令是对的，问题在账号状态，重试无用。
        // 这不构成账号枚举风险 —— 能走到这一步说明口令已经正确
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(20002);
    }

    @Test
    @DisplayName("登录成功后记录登录时间")
    void recordsLoginTime() throws Exception {
        login("owner@example.com", PASSWORD);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_login_at IS NOT NULL FROM sys_account WHERE id = ?", Boolean.class, accountId))
                .isTrue();
    }

    @Test
    @DisplayName("参数校验失败返回 10001 且带逐项明细")
    void validatesRequest() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":""}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(10001);
        assertThat(body.get("details")).hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * 明文口令绝不能出现在响应里 —— 这条看似多余，但请求体反射回显是很常见的
     * 实现失误（例如统一日志或错误处理把整个请求对象序列化出去）。
     */
    @Test
    @DisplayName("响应中不出现明文口令")
    void neverEchoesPassword() throws Exception {
        assertThat(login("owner@example.com", "wrong-password").getResponse().getContentAsString())
                .doesNotContain("wrong-password");
        assertThat(login("owner@example.com", PASSWORD).getResponse().getContentAsString())
                .doesNotContain(PASSWORD);
    }

}
