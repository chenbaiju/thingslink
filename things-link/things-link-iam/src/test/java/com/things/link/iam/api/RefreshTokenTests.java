package com.things.link.iam.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 刷新令牌的端到端测试（S1 切片 4）。
 *
 * <p>覆盖架构文档 7.3 的「刷新令牌轮换并支持撤销」，重点在<b>失效路径</b>：
 * 轮换后旧令牌不可用、复用触发整族作废、退出后不可再刷新、账号停用后刷新被拒。
 * 成功路径只有一条，而上面每一条失效路径漏掉一个都是真实的越权。
 */
@AutoConfigureMockMvc
@DisplayName("刷新令牌（S1 切片 4）")
class RefreshTokenTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String COOKIE_NAME = "tc_refresh";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 限流器是进程级单例，计数会跨测试类累计。不清的话，几个共用测试邮箱的类
     * 合起来就会撞上限额，表现为一堆与限流无关的用例莫名 429 —— 排查时极容易
     * 怀疑到被测逻辑上去。
     */
    @Autowired
    private AuthRateLimiter rateLimiter;

    private UUID accountId;

    @BeforeEach
    void seed() {
        rateLimiter.clear();

        // project_member 引用 account，必须先删。同一个 Testcontainers 容器在
        // 模块内所有测试类之间共享，别的类建的项目会留在这里
        jdbcTemplate.update("DELETE FROM sys_project_member");
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");

        UUID tenantId = Uuid7.generate();
        accountId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "测试租户");
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name, email_verified_at)
                VALUES (?, ?, ?, ?, now())
                """, accountId, "owner@example.com", passwordEncoder.encode(PASSWORD), "测试所有者");
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_member (id, tenant_id, account_id)
                VALUES (?, ?, ?)
                """, Uuid7.generate(), tenantId, accountId);
    }

    /** 从 Set-Cookie 响应头里取出刷新令牌值。 */
    private static String refreshCookieOf(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(COOKIE_NAME + "="))
                .map(h -> h.substring((COOKIE_NAME + "=").length(), h.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "响应里没有 " + COOKIE_NAME + " Cookie：" + response.getHeaderNames()));
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.com","password":"%s"}""".formatted(PASSWORD)))
                .andReturn();
    }

    private MvcResult refresh(String rawToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE_NAME, rawToken)))
                .andReturn();
    }

    @Test
    @DisplayName("登录时刷新令牌只走 Cookie，不出现在响应体里")
    void refreshTokenNeverAppearsInBody() throws Exception {
        MockHttpServletResponse response = login().getResponse();
        String rawToken = refreshCookieOf(response);

        assertThat(response.getContentAsString())
                .as("刷新令牌一旦进响应体，JavaScript 就能读到，HttpOnly 也就白设了")
                .doesNotContain(rawToken);
        assertThat(JSON.readTree(response.getContentAsString()).has("refreshToken")).isFalse();
    }

    /**
     * HttpOnly / Secure / SameSite / Path 四项各挡一类攻击，缺一项都不会有功能症状。
     */
    @Test
    @DisplayName("Cookie 带齐 HttpOnly、SameSite 与收窄的 Path")
    void cookieCarriesSecurityAttributes() throws Exception {
        String header = login().getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(COOKIE_NAME + "="))
                .findFirst().orElseThrow();

        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("SameSite=Strict");
        assertThat(header)
                .as("Path 收窄到认证接口，避免每个业务请求都携带这个长期凭据")
                .contains("Path=/api/v1/auth");
    }

    /**
     * 注意这里<b>没有</b>断言「新访问令牌与旧的不同」。
     *
     * <p>JWT 的 {@code iat} / {@code exp} 只有秒级精度，同一秒内为同一身份签发的两把
     * 令牌声明完全相同，因此字节也完全相同。这不是缺陷：两把令牌的权限与过期时刻
     * 本就一样。断言它们不同只会得到一个依赖执行速度的随机失败用例
     * （本用例第一次写成那样，确实失败了）。
     *
     * <p>真正该断言的是刷新令牌换了、新访问令牌可用 —— 后者见
     * {@link #refreshedAccessTokenWorks()}。
     */
    @Test
    @DisplayName("刷新会轮换出新的刷新令牌")
    void refreshRotatesRefreshToken() throws Exception {
        String firstRefresh = refreshCookieOf(login().getResponse());

        MockHttpServletResponse refreshed = refresh(firstRefresh).getResponse();

        assertThat(refreshed.getStatus()).isEqualTo(200);
        assertThat(refreshCookieOf(refreshed))
                .as("每次刷新都必须换一把新的，否则被窃取的令牌可用到过期为止")
                .isNotEqualTo(firstRefresh);
        assertThat(JSON.readTree(refreshed.getContentAsString()).get("accessToken").asString())
                .isNotBlank();
    }

    @Test
    @DisplayName("轮换后的旧刷新令牌立即失效")
    void rotatedTokenIsRejected() throws Exception {
        String firstRefresh = refreshCookieOf(login().getResponse());
        refresh(firstRefresh);

        MvcResult reused = refresh(firstRefresh);

        assertThat(reused.getResponse().getStatus()).isEqualTo(401);
        assertThat(JSON.readTree(reused.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(20020);
    }

    /**
     * <b>本类最重要的一条。</b>
     *
     * <p>轮换本身只缩短窗口，发现不了盗用。真正的价值在于：旧令牌再次出现说明它被
     * 复制过，此时必须把整族作废——包括攻击者或真用户手里那把**刚换到的、仍然有效的**
     * 新令牌。只作废被复用的那一条是不够的。
     */
    @Test
    @DisplayName("复用旧令牌会作废整族，连刚换到的新令牌一起失效")
    void reuseRevokesEntireFamily() throws Exception {
        String firstRefresh = refreshCookieOf(login().getResponse());
        String secondRefresh = refreshCookieOf(refresh(firstRefresh).getResponse());

        // 攻击者（或被复制走的那一方）拿旧令牌来刷
        refresh(firstRefresh);

        // 此时刚才那把合法的新令牌也必须一起作废
        assertThat(refresh(secondRefresh).getResponse().getStatus())
                .as("只作废被复用的那一条是不够的：另一方手里的新令牌仍然有效，"
                        + "等于检测到了入侵却没有把入侵者赶出去")
                .isEqualTo(401);

        List<String> revoked = jdbcTemplate.queryForList(
                "SELECT revoked_at::text FROM sys_refresh_token WHERE account_id = ?",
                String.class, accountId);
        assertThat(revoked).allSatisfy(v -> assertThat(v).isNotNull());
    }

    @Test
    @DisplayName("退出登录后刷新令牌失效，且 Cookie 被清除")
    void logoutRevokesSession() throws Exception {
        String rawToken = refreshCookieOf(login().getResponse());

        MockHttpServletResponse logout = mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE_NAME, rawToken)))
                .andReturn().getResponse();

        assertThat(logout.getStatus()).isEqualTo(204);
        assertThat(logout.getHeaders(HttpHeaders.SET_COOKIE))
                .as("清除用的 Cookie 属性必须与签发时一致，否则浏览器会当成另一个 Cookie，"
                        + "旧的原样留着——表现是「退出后刷新页面又登回去了」")
                .anySatisfy(h -> assertThat(h).contains(COOKIE_NAME + "=").contains("Max-Age=0")
                        .contains("Path=/api/v1/auth"));

        assertThat(refresh(rawToken).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("退出登录是幂等的，令牌无效时同样返回 204")
    void logoutIsIdempotent() throws Exception {
        assertThat(mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE_NAME, "not-a-real-token")))
                .andReturn().getResponse().getStatus())
                .as("让「退出」失败没有任何安全收益，只会把用户困在他想离开的会话里")
                .isEqualTo(204);

        assertThat(mockMvc.perform(post("/api/v1/auth/logout"))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("没有 Cookie 时刷新返回 401 而非 500")
    void refreshWithoutCookieIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(20020);
    }

    /**
     * 刷新是账号停用真正生效的地方。
     *
     * <p>少了这一步，「停用账号」只在访问令牌的 15 分钟内有效——之后对方用刷新令牌
     * 又能换一把新的，停用实际上从未生效。
     */
    @Test
    @DisplayName("账号被停用后无法再刷新")
    void refreshRejectedAfterAccountDisabled() throws Exception {
        String rawToken = refreshCookieOf(login().getResponse());

        jdbcTemplate.update("UPDATE sys_account SET status = 'DISABLED' WHERE id = ?", accountId);

        assertThat(refresh(rawToken).getResponse().getStatus())
                .as("刷新必须回库复核账号状态，否则停用只在访问令牌过期前有效")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("被移出租户后无法再刷新")
    void refreshRejectedAfterMembershipRemoved() throws Exception {
        String rawToken = refreshCookieOf(login().getResponse());

        jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE account_id = ?", accountId);

        assertThat(refresh(rawToken).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("刷新后的访问令牌可用于访问受保护接口")
    void refreshedAccessTokenWorks() throws Exception {
        String rawToken = refreshCookieOf(login().getResponse());
        String newAccess = JSON.readTree(refresh(rawToken).getResponse().getContentAsString())
                .get("accessToken").asString();

        MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
                .andReturn();

        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(me.getResponse().getContentAsString());
        assertThat(body.get("accountId").asString()).isEqualTo(accountId.toString());
    }

    /**
     * 数据库里不能存明文——库被读走时，明文等于攻击者可以直接冒充任何人的会话。
     */
    @Test
    @DisplayName("数据库中不保存令牌明文")
    void databaseStoresOnlyHash() throws Exception {
        String rawToken = refreshCookieOf(login().getResponse());

        Integer matches = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_refresh_token
                 WHERE encode(token_hash, 'escape') LIKE ?
                """, Integer.class, "%" + rawToken + "%");

        assertThat(matches).isZero();
        // 存的确实是 SHA-256：固定 32 字节
        assertThat(jdbcTemplate.queryForObject(
                "SELECT length(token_hash) FROM sys_refresh_token LIMIT 1", Integer.class))
                .isEqualTo(32);
    }

}
