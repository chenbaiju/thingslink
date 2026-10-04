package com.things.link.bootstrap.project.isolation;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 租户隔离的<b>端到端</b>验证（S1 切片 5，了结 S0-5C 登记的最后一项）。
 *
 * <h2>这条链路此前从未被完整验证过</h2>
 * <pre>
 * 登录 → 令牌 → TenantScopeFilter → TenantContext
 *      → TenantAwareDataSource 写 app.tenant_id → RLS 策略 → 查询结果被裁剪
 * </pre>
 *
 * 已有的 {@code RowLevelSecurityTests} 验证的是**机制本身**，它在测试里手工调
 * {@code TenantContext.set()}。那证明了「策略配置是对的」，但完全没有覆盖
 * 「真实 HTTP 请求能不能把租户送到数据库会话里」—— 而在切片 5 之前，
 * {@code TenantContextFilter} 确实只清理不填充，这半条链路是断的。
 *
 * <h2>为什么用探针表而不是业务表</h2>
 * 到目前为止所有带 {@code tenant_id} 的表都豁免了 RLS（account / tenant_member /
 * refresh_token / idempotency_record，都因为认证发生在租户身份确立之前）。
 * 没有任何一张真实表能用来做这个断言。详见探针表迁移的注释。
 *
 * <h2>探针接口刻意不带租户条件</h2>
 * {@code SELECT * FROM rls_http_probe} 没有 {@code WHERE tenant_id = ?}。
 * 这正是要测的东西：<b>应用层漏写租户条件时，RLS 这道防线还在不在</b>。
 * 带上条件的话，测试通过只能说明「我记得写 where」，证明不了第二道防线。
 */
@AutoConfigureMockMvc
@DisplayName("租户隔离端到端（BACKEND_ARCHITECTURE.md 第 7 节）")
class TenantIsolationEndToEndTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    /**
     * 探针接口。<b>刻意不写租户过滤条件</b>，见类注释。
     */
    @TestConfiguration
    static class ProbeConfiguration {

        @RestController
        static class RlsProbeController {

            private final JdbcTemplate jdbcTemplate;

            RlsProbeController(JdbcTemplate jdbcTemplate) {
                this.jdbcTemplate = jdbcTemplate;
            }

            @GetMapping("/test-only/rls-probe")
            List<String> notes() {
                // 没有 WHERE tenant_id = ? —— 唯一的隔离来源是 RLS
                return jdbcTemplate.queryForList(
                        "SELECT note FROM rls_http_probe ORDER BY note", String.class);
            }
        }

        // 不要再写一个 @Bean 去注册上面这个 Controller：@Configuration 的嵌套成员类
        // 已经会被自动注册，加了会变成注册两次，报「Ambiguous mapping」。踩过一次
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String tokenOfTenantA;
    private String tokenOfTenantB;

    @BeforeEach
    void seed() throws Exception {
        // 注意这里**没有** DELETE FROM rls_http_probe。
        //
        // 测试用的连接是应用角色 thingslink_app，它同样受 RLS 约束 —— 没有租户
        // 上下文时那条 DELETE 一行也删不掉，是个静默的空操作。留着比写一条
        // 「看起来在清理、实际什么都没做」的语句诚实。
        //
        // 每次运行都用新生成的租户 ID，历史行对本次运行不可见，不影响断言。
        // project_member 引用 account，必须先删 —— 同一个 Testcontainers 容器在
        // 本模块的所有测试类之间共享，别的类建的项目会留在这里。
        // 删除顺序永远是「先删引用方，再删被引用方」
        jdbcTemplate.update("DELETE FROM sys_project_member");
        clearRawPropertyPointsBeforeAllProjectFixtureReset();
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");

        UUID tenantA = createTenantWithOwner("甲租户", "owner-a@example.com");
        UUID tenantB = createTenantWithOwner("乙租户", "owner-b@example.com");

        insertProbe(tenantA, "甲租户的数据");
        insertProbe(tenantB, "乙租户的数据");

        tokenOfTenantA = login("owner-a@example.com");
        tokenOfTenantB = login("owner-b@example.com");
    }

    private UUID createTenantWithOwner(String tenantName, String email) {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, tenantName);
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name, email_verified_at)
                VALUES (?, ?, ?, ?, now())
                """, accountId, email, passwordEncoder.encode(PASSWORD), tenantName + "所有者");
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_member (id, tenant_id, account_id)
                VALUES (?, ?, ?)
                """, Uuid7.generate(), tenantId, accountId);
        return tenantId;
    }

    /**
     * 以指定租户的身份插入一行探针数据。
     *
     * <p><b>必须先设置租户上下文</b>：测试连的是应用角色，写入同样受 RLS 的
     * {@code WITH CHECK} 约束 —— 直接插会报
     * {@code new row violates row-level security policy}。
     *
     * <p>这个「障碍」本身就是一条证据：连造数据都绕不过策略，说明它确实在起作用。
     * 本用例最初正是在这里失败的，当时的注释还写着「种子数据不受 RLS 约束」，
     * 那句话是错的。
     */
    private void insertProbe(UUID tenantId, String note) {
        TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
        try {
            jdbcTemplate.update("INSERT INTO rls_http_probe (id, tenant_id, note) VALUES (?, ?, ?)",
                    Uuid7.generate(), tenantId, note);
        } finally {
            TenantContext.clear();
        }
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    private List<String> probeAs(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/test-only/rls-probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode array = JSON.readTree(result.getResponse().getContentAsString());
        return JSON.convertValue(array, List.class);
    }

    /**
     * <b>本类的核心断言，也是 S0-5C 悬了最久的那一条。</b>
     */
    @Test
    @DisplayName("真实登录后的请求只能看到自己租户的数据")
    void requestSeesOnlyOwnTenantRows() throws Exception {
        assertThat(probeAs(tokenOfTenantA))
                .as("查询没有写租户条件，隔离完全靠 RLS —— 看到别人的数据就说明"
                        + "「HTTP 请求 → 过滤器 → 会话变量 → 策略」这条链路某处断了")
                .containsExactly("甲租户的数据");

        assertThat(probeAs(tokenOfTenantB))
                .containsExactly("乙租户的数据");
    }

    /**
     * 没有令牌时连接口都进不来（401），因此不存在「无租户上下文却读到了数据」的可能。
     * 这条与下一条一起构成 fail-closed 的完整证明。
     */
    @Test
    @DisplayName("未认证请求被挡在接口之外")
    void unauthenticatedRequestIsRejected() throws Exception {
        assertThat(mockMvc.perform(get("/test-only/rls-probe")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    /**
     * 直接用应用角色连库、且不设租户会话变量时，必须一行也读不到（fail-closed）。
     *
     * <p>这条防的是「RLS 策略被误配成默认放行」：那样上面的隔离断言可能因为
     * 恰好只有一行数据而侥幸通过，这里则会立刻暴露。
     */
    @Test
    @DisplayName("没有租户上下文时一行也读不到")
    void readsNothingWithoutTenantContext() {
        // TenantAwareDataSource 在没有 TenantContext 时写入空串，
        // app_current_tenant() 得到 NULL，策略应当拦下全部行
        List<String> notes = jdbcTemplate.queryForList(
                "SELECT note FROM rls_http_probe", String.class);

        assertThat(notes)
                .as("无租户上下文时读到了数据 —— 说明策略是默认放行的，"
                        + "那么前面那条隔离断言的通过只是侥幸")
                .isEmpty();
    }

    /**
     * 请求结束后必须清理，否则线程复用会让下一个请求继承上一个的租户 ——
     * 最严重的一类 bug：偶发、只在高并发下出现、后果是跨租户数据泄露。
     */
    @Test
    @DisplayName("请求结束后租户上下文被清理，不会串到下一个请求")
    void tenantContextDoesNotLeakBetweenRequests() throws Exception {
        probeAs(tokenOfTenantA);

        // 同一个测试线程紧接着直接查库。若上下文残留，这里会读到甲租户的数据
        assertThat(jdbcTemplate.queryForList("SELECT note FROM rls_http_probe", String.class))
                .as("上一个请求的租户范围泄露到了后续操作 —— 清理没有生效")
                .isEmpty();
    }

}
