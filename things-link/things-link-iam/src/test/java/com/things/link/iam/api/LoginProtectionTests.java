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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 登录限流与账号锁定（S1 切片 4b，架构文档 7.3）。
 *
 * <h2>两道防线各自拦什么</h2>
 * <ul>
 *   <li><b>账号锁定</b>盯「某个账号被试了多少次」，能阻止单个口令被爆破出来</li>
 *   <li><b>限流</b>盯请求量，拦的是锁定拦不住的撞库 —— 一个口令喷向大量账号，
 *       每个账号只试一两次，永远触发不了锁定</li>
 * </ul>
 *
 * <h2>本类最要紧的一组断言是「不泄露账号是否存在」</h2>
 * 锁定机制天然想告诉用户「你被锁了」，而这句话对攻击者的含义是「这个邮箱是注册过的」。
 * 下面几条用例就是钉住这个边界的。
 */
@AutoConfigureMockMvc
@DisplayName("登录限流与账号锁定（S1 切片 4b）")
class LoginProtectionTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** 与 AuthenticationService.MAX_FAILED_ATTEMPTS 一致。 */
    private static final int MAX_FAILED_ATTEMPTS = 5;

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

    /** 每个用例用不同邮箱：限流计数器是进程内的，共用邮箱会让用例互相影响。 */
    private String email;

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
        email = "user-" + UUID.randomUUID() + "@example.com";

        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "测试租户");
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name, email_verified_at)
                VALUES (?, ?, ?, ?, now())
                """, accountId, email, passwordEncoder.encode(PASSWORD), "测试所有者");
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_member (id, tenant_id, account_id)
                VALUES (?, ?, ?)
                """, Uuid7.generate(), tenantId, accountId);
    }

    private MvcResult login(String loginEmail, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(loginEmail, password)))
                .andReturn();
    }

    private int failedAttempts() {
        return jdbcTemplate.queryForObject(
                "SELECT failed_login_attempts FROM sys_account WHERE id = ?", Integer.class, accountId);
    }

    private static int codeOf(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").asInt();
    }

    /**
     * <b>本类最重要的一条。</b>
     *
     * <p>{@code login()} 带 {@code @Transactional}，而凭据错误时会抛异常。失败计数
     * 若写在同一个事务里，会被这个异常连同回滚 —— 计数永远停在 0，锁定永远不触发。
     *
     * <p>症状极具欺骗性：接口正确返回 401，日志也正常，只是那道防线根本没接上。
     * 切片 4a 的刷新令牌复用检测踩过一模一样的坑，所以这里单独钉一条。
     */
    @Test
    @DisplayName("登录失败的计数不会被异常回滚掉")
    void failedAttemptSurvivesRollback() throws Exception {
        login(email, "wrong-password");

        assertThat(failedAttempts())
                .as("计数被回滚了。失败计数必须写在独立事务里，"
                        + "否则账号锁定永远不会触发，而且不会有任何症状")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("连续失败达到阈值后账号被临时锁定")
    void locksAccountAfterRepeatedFailures() throws Exception {
        for (int i = 0; i < MAX_FAILED_ATTEMPTS; i++) {
            login(email, "wrong-password");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT locked_until IS NOT NULL FROM sys_account WHERE id = ?", Boolean.class, accountId))
                .isTrue();

        // 此时即便口令正确也进不去
        MvcResult result = login(email, PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo(20003);
    }

    /**
     * 锁定期内用**错误**口令登录，必须返回与平常一样的 20001。
     *
     * <p>返回 20003 的话，攻击者用任意口令试探，凡是返回 20003 的邮箱都是已注册
     * 账号 —— 一个本用于防护的机制反而成了账号枚举通道。
     */
    @Test
    @DisplayName("锁定期内用错误口令登录仍返回 20001，不暴露账号存在")
    void lockedAccountDoesNotLeakExistenceOnWrongPassword() throws Exception {
        for (int i = 0; i < MAX_FAILED_ATTEMPTS; i++) {
            login(email, "wrong-password");
        }

        MvcResult locked = login(email, "still-wrong");
        MvcResult unknown = login("nobody-" + UUID.randomUUID() + "@example.com", "still-wrong");

        assertThat(locked.getResponse().getStatus())
                .as("已锁定的账号与不存在的账号，在口令错误时必须表现完全一致")
                .isEqualTo(unknown.getResponse().getStatus());
        assertThat(codeOf(locked)).isEqualTo(20001);
        assertThat(codeOf(unknown)).isEqualTo(20001);
    }

    /**
     * 持续攻击不能把锁定时间无限往后推，否则「防爆破」会变成「免费的拒绝服务」：
     * 知道某人邮箱就能让他永远登不进去。
     */
    @Test
    @DisplayName("锁定期内继续失败不会延长锁定时间")
    void furtherFailuresDoNotExtendLock() throws Exception {
        for (int i = 0; i < MAX_FAILED_ATTEMPTS; i++) {
            login(email, "wrong-password");
        }
        String firstLockedUntil = jdbcTemplate.queryForObject(
                "SELECT locked_until::text FROM sys_account WHERE id = ?", String.class, accountId);

        for (int i = 0; i < 3; i++) {
            login(email, "wrong-password");
        }
        String afterLockedUntil = jdbcTemplate.queryForObject(
                "SELECT locked_until::text FROM sys_account WHERE id = ?", String.class, accountId);

        assertThat(afterLockedUntil)
                .as("锁定时间被延长了 —— 攻击者只要持续发请求就能让目标账号永远登不进去")
                .isEqualTo(firstLockedUntil);
    }

    @Test
    @DisplayName("登录成功后失败计数清零")
    void successResetsFailedAttempts() throws Exception {
        login(email, "wrong-password");
        login(email, "wrong-password");
        assertThat(failedAttempts()).isEqualTo(2);

        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(failedAttempts())
                .as("记的是「连续」失败次数，一次成功登录就说明之前那串失败不再有意义")
                .isZero();
    }

    /**
     * 锁定到期后必须自动解除，不需要任何人工干预 —— 这正是用 locked_until
     * 而不是 status='LOCKED' 的理由。
     */
    @Test
    @DisplayName("锁定到期后自动解除")
    void lockExpiresAutomatically() throws Exception {
        for (int i = 0; i < MAX_FAILED_ATTEMPTS; i++) {
            login(email, "wrong-password");
        }
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(403);

        // 把锁定时刻拨到过去，等价于时间流逝
        jdbcTemplate.update(
                "UPDATE sys_account SET locked_until = now() - interval '1 minute' WHERE id = ?", accountId);

        assertThat(login(email, PASSWORD).getResponse().getStatus())
                .as("到点必须自动放行，否则临时锁定就变成了需要人工解开的永久锁定")
                .isEqualTo(200);
    }

    /**
     * 限流按邮箱计数，且**不区分账号是否存在** —— 只对存在的账号限流的话，
     * 「是否触发限流」本身就成了账号是否已注册的信号。
     */
    @Test
    @DisplayName("同一邮箱短时间内大量尝试会触发 429，不存在的邮箱同样触发")
    void rateLimitsByEmailRegardlessOfExistence() throws Exception {
        String unknownEmail = "ghost-" + UUID.randomUUID() + "@example.com";

        int lastStatus = 200;
        for (int i = 0; i < 15; i++) {
            lastStatus = login(unknownEmail, "whatever").getResponse().getStatus();
            if (lastStatus == 429) {
                break;
            }
        }

        assertThat(lastStatus)
                .as("不存在的邮箱也必须能触发限流，否则「有没有被限流」就泄露了账号是否存在")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("限流响应用 10029，且不透露任何账号信息")
    void rateLimitResponseRevealsNothing() throws Exception {
        String target = "flood-" + UUID.randomUUID() + "@example.com";

        MvcResult limited = null;
        for (int i = 0; i < 15; i++) {
            MvcResult result = login(target, "whatever");
            if (result.getResponse().getStatus() == 429) {
                limited = result;
                break;
            }
        }

        assertThat(limited).as("15 次尝试都没触发限流，阈值配置可能失效了").isNotNull();
        assertThat(codeOf(limited)).isEqualTo(10029);
        assertThat(limited.getResponse().getContentAsString())
                .as("限流消息里不能出现邮箱、账号是否存在、是否被锁定之类的信息")
                .doesNotContain(target)
                .doesNotContain("锁定")
                .doesNotContain("不存在");
    }

    /**
     * 大小写变换不能绕过限流：{@code Foo@x.com} 与 {@code foo@x.com} 必须计到
     * 同一个桶里，否则攻击者只要变换大小写就能把限额翻好几倍。
     */
    @Test
    @DisplayName("邮箱大小写不同不能绕过限流")
    void rateLimitKeyIsCaseInsensitive() throws Exception {
        String target = "Mixed-" + UUID.randomUUID() + "@Example.com";

        int limitedAt = -1;
        for (int i = 0; i < 15; i++) {
            // 交替用大小写两种写法
            String variant = i % 2 == 0 ? target.toLowerCase() : target.toUpperCase();
            if (login(variant, "whatever").getResponse().getStatus() == 429) {
                limitedAt = i;
                break;
            }
        }

        assertThat(limitedAt)
                .as("变换大小写绕过了限流 —— 计数键必须先归一化")
                .isNotEqualTo(-1);
    }

    @Test
    @DisplayName("正常登录不受限流影响")
    void normalLoginIsNotRateLimited() throws Exception {
        JsonNode body = JSON.readTree(login(email, PASSWORD).getResponse().getContentAsString());

        assertThat(body.get("accessToken").asString()).isNotBlank();
    }

}
