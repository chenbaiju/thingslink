package com.things.link.iam.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 注册的端到端测试（S1 切片 5b，ADR 0008）。
 *
 * <h2>注册的语义是「创建租户 + 初始账号」，不是「加一个用户」</h2>
 * 因此断言的重点不在「插了一行 account」，而在<b>三者原子成立</b>：
 * 租户、账号、归属关系必须同生同死。失败时留下一个没有归属账号的孤儿租户，
 * 是这个流程最典型也最难收拾的坏结局 —— 没有任何人能进入它，也没有入口能删掉它。
 */
@AutoConfigureMockMvc
@DisplayName("注册（S1 切片 5b）")
class RegistrationTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthRateLimiter rateLimiter;

    @BeforeEach
    void reset() {
        rateLimiter.clear();
        // project_member 引用 account，必须先删。同一个 Testcontainers 容器在
        // 模块内所有测试类之间共享，别的类建的项目会留在这里
        jdbcTemplate.update("DELETE FROM sys_project_member");
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");
    }

    private MvcResult register(String email, String password, String displayName) throws Exception {
        String body = displayName == null
                ? """
                {"email":"%s","password":"%s"}""".formatted(email, password)
                : """
                {"email":"%s","password":"%s","displayName":"%s"}"""
                .formatted(email, password, displayName);

        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static String uniqueEmail() {
        return "new-" + UUID.randomUUID() + "@example.com";
    }

    private static int codeOf(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }

    @Test
    void defaultPasswordIsRejectedBeforeTenantCreation() throws Exception {
        MvcResult result = register(uniqueEmail(), "ThingsLink123!", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo(20011);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_tenant", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_account", Integer.class)).isZero();
    }

    @Test
    @DisplayName("注册成功后同时存在租户、账号与有效归属关系")
    void createsTenantAccountAndOwnership() throws Exception {
        MvcResult result = register(uniqueEmail(), PASSWORD, "张三");

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_tenant", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_account", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_tenant_member", String.class))
                .as("注册者必须归属于自己创建的租户，否则刷新与当前用户复核无法建立租户上下文")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("注册成功不签发会话，邮箱验证前也不能登录")
    void registrationDoesNotIssueSessionBeforeEmailVerification() throws Exception {
        String email = uniqueEmail();
        MvcResult result = register(email, PASSWORD, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .as("邮箱未验证前不能留下刷新令牌 Cookie，否则刷新接口仍可能把用户带进控制台")
                .isEmpty();

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(login)).isEqualTo(20022);
    }

    /**
     * <b>本类最重要的一条。</b>
     *
     * <p>邮箱冲突时整个事务必须回滚，<b>包括此前已经创建的租户</b>。
     * 不回滚的话，每一次重复注册都会在库里留下一个没有归属账号的空租户 ——
     * 而这种数据没有任何人能进入，也没有入口能删。
     */
    @Test
    @DisplayName("邮箱冲突时租户一并回滚，不留孤儿租户")
    void duplicateEmailRollsBackTenant() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD, null);

        int tenantsAfterFirst = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant", Integer.class);

        MvcResult duplicate = register(email, PASSWORD, null);

        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(duplicate)).isEqualTo(20010);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_tenant", Integer.class))
                .as("失败的注册留下了一个孤儿租户 —— 没有归属账号，没人能进入，也没有入口能删")
                .isEqualTo(tenantsAfterFirst);
    }

    /**
     * 唯一性建在 {@code lower(email)} 上，大小写不同必须视为同一个邮箱。
     * 否则同一个人能用 {@code Foo@x.com} 和 {@code foo@x.com} 注册两个账号，
     * 而登录时又只能查到其中一个。
     */
    @Test
    @DisplayName("邮箱唯一性不区分大小写")
    void emailUniquenessIsCaseInsensitive() throws Exception {
        String email = uniqueEmail();
        register(email.toLowerCase(), PASSWORD, null);

        assertThat(register(email.toUpperCase(), PASSWORD, null).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("口令过短返回 400 与逐项明细")
    void rejectsShortPassword() throws Exception {
        MvcResult result = register(uniqueEmail(), "short", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        // DTO 校验先于服务层不变式触发，因此这里是 10001 而非 20011。
        // 两道都保留：10001 能给出逐项明细，20011 保证绕过 DTO 的调用路径也拦得住
        assertThat(codeOf(result)).isEqualTo(10001);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("details"))
                .isNotEmpty();
    }

    @Test
    @DisplayName("邮箱格式非法返回 400")
    void rejectsMalformedEmail() throws Exception {
        assertThat(register("not-an-email", PASSWORD, null).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("不填显示名时由邮箱推导")
    void derivesDisplayNameFromEmail() throws Exception {
        String email = "derive-me-" + UUID.randomUUID() + "@example.com";
        register(email, PASSWORD, null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM sys_account WHERE email = ?", String.class, email))
                .isEqualTo(email.substring(0, email.indexOf('@')));
    }

    /**
     * 注册是未认证的**写**入口，不限流会被刷出大量空租户
     * （ADR 0008 的后果一节点名了这一项）。清理垃圾租户是纯人工成本。
     */
    @Test
    @DisplayName("同一 IP 短时间内大量注册会触发 429")
    void rateLimitsRegistrationByIp() throws Exception {
        int lastStatus = 204;
        for (int i = 0; i < 8; i++) {
            lastStatus = register(uniqueEmail(), PASSWORD, null).getResponse().getStatus();
            if (lastStatus == 429) {
                break;
            }
        }

        assertThat(lastStatus)
                .as("注册不限流等于把「批量开空租户」变成零成本操作")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("响应中不出现明文口令")
    void neverEchoesPassword() throws Exception {
        assertThat(register(uniqueEmail(), PASSWORD, null).getResponse().getContentAsString())
                .doesNotContain(PASSWORD);
        assertThat(register("bad-email", PASSWORD, null).getResponse().getContentAsString())
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("口令以哈希存储，库里没有明文")
    void storesPasswordHashed() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD, null);

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM sys_account WHERE email = ?", String.class, email);

        assertThat(hash).doesNotContain(PASSWORD);
        assertThat(hash)
                .as("带算法前缀才能在将来换算法时不强制全员改密（ADR 0009）")
                .startsWith("{bcrypt}");
    }

}
