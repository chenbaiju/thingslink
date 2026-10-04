package com.things.link.iam.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.iam.support.RecordingMailSender;
import com.things.link.support.notification.mail.MailMessage;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 邮箱验证的端到端测试（S4 切片 3，ADR 0013）。
 *
 * <p>这里把邮件发送替换成一个记录器，断言的是<b>什么时候该发信、什么时候不该发</b>，
 * 以及信里那条链接是否真的能走通验证接口。真正的 SMTP 交互由
 * {@code SmtpMailSenderTests} 覆盖，两者关心的不是一回事。
 */
@AutoConfigureMockMvc
@Import(RecordingMailSender.Configuration.class)
@DisplayName("邮箱验证接口（S4 切片 3）")
class EmailVerificationApiTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthRateLimiter rateLimiter;

    @Autowired
    private RecordingMailSender mailSender;

    @BeforeEach
    void reset() {
        rateLimiter.clear();
        mailSender.clear();
        jdbcTemplate.update("DELETE FROM sys_email_verification_token");
        jdbcTemplate.update("DELETE FROM sys_project_member");
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
        jdbcTemplate.update("DELETE FROM sys_tenant");
    }

    @Test
    @DisplayName("注册后收到验证信，点开链接即完成验证")
    void registerThenVerify() throws Exception {
        String email = uniqueEmail();
        register(email);

        MailMessage mail = awaitSingleMail();
        assertThat(mail.to()).isEqualTo(email);

        // 纯文本里必须有完整可点的 URL：命令行客户端只渲染这一段
        assertThat(mail.text()).contains("http").contains("/#/auth/verify-email?token=");

        MvcResult result = verify(RecordingMailSender.tokenFrom(mail));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("email").asText())
                .isEqualTo(email);
        assertThat(verifiedAt(email)).isNotNull();
    }

    /**
     * 事务边界的核心断言。
     *
     * <p>注册在邮箱冲突时会整体回滚（连同刚建的租户）。发信如果不是挂在
     * {@code AFTER_COMMIT} 上，这里就会发出一封指向<b>不存在的账号</b>的验证信 ——
     * 用户点开只会得到 20021，而他刚刚明明看到「该邮箱已被注册」。
     */
    @Test
    @DisplayName("注册失败回滚时不发信")
    void doesNotSendWhenRegistrationRollsBack() throws Exception {
        String email = uniqueEmail();
        register(email);
        awaitSingleMail();
        mailSender.clear();

        MvcResult conflict = register(email);

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(mailSender.stayedEmptyFor(Duration.ofMillis(500)))
                .as("注册事务回滚了，验证信不该发出去")
                .isTrue();
    }

    @Test
    @DisplayName("同一个链接点第二次报 20021")
    void secondClickIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = RecordingMailSender.tokenFrom(awaitSingleMail());

        verify(token);
        MvcResult second = verify(token);

        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(second)).isEqualTo(20021);
    }

    @Test
    @DisplayName("伪造的令牌报 20021，与已使用的令牌无从区分")
    void forgedTokenIsRejected() throws Exception {
        MvcResult result = verify("this-is-not-a-real-token");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo(20021);
    }

    @Test
    @DisplayName("未验证账号可以重发，拿到的新链接同样有效")
    void resendSendsAnotherUsableLink() throws Exception {
        String email = uniqueEmail();
        register(email);
        awaitSingleMail();
        mailSender.clear();

        assertThat(resend(email).getResponse().getStatus()).isEqualTo(204);

        MvcResult result = verify(RecordingMailSender.tokenFrom(awaitSingleMail()));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * 账号枚举防护。这个接口不需要登录，区分开就是一个比注册接口（20010）
     * 更便宜的枚举通道 —— 那个至少还要提交合法口令并真的建出账号来。
     */
    @Test
    @DisplayName("未注册的邮箱同样返回 204，且不发信")
    void unknownEmailLooksIdentical() throws Exception {
        MvcResult result = resend(uniqueEmail());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(mailSender.stayedEmptyFor(Duration.ofMillis(500))).isTrue();
    }

    @Test
    @DisplayName("已验证的邮箱返回 204，但不再发信")
    void verifiedEmailIsNotResent() throws Exception {
        String email = uniqueEmail();
        register(email);
        verify(RecordingMailSender.tokenFrom(awaitSingleMail()));
        mailSender.clear();

        assertThat(resend(email).getResponse().getStatus()).isEqualTo(204);
        assertThat(mailSender.stayedEmptyFor(Duration.ofMillis(500)))
                .as("已验证还再发一封只会让用户困惑")
                .isTrue();
    }

    /**
     * 重发每次都真的往外发一封信，配额是花钱买的。限流失守的后果不是服务变慢，
     * 而是发信账号被服务商封禁，那时所有人的验证信都发不出去。
     */
    @Test
    @DisplayName("重发次数超限返回 429")
    void resendIsRateLimited() throws Exception {
        String email = uniqueEmail();
        register(email);

        // 前 3 次放行（MAX_RESENDS_PER_EMAIL），第 4 次必须被拦
        for (int i = 0; i < 3; i++) {
            assertThat(resend(email).getResponse().getStatus()).isEqualTo(204);
        }
        MvcResult blocked = resend(email);

        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(codeOf(blocked)).isEqualTo(10029);
    }

    // ---------------------------------------------------------------- 辅助

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andReturn();
    }

    private MvcResult verify(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}""".formatted(token)))
                .andReturn();
    }

    private MvcResult resend(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andReturn();
    }

    private Instant verifiedAt(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT email_verified_at FROM sys_account WHERE email = ?", Instant.class, email);
    }

    /**
     * 等一封信到达。
     *
     * <p>发信是 {@code @Async} 的，断言时它可能还没跑完 —— 直接断言会得到一个
     * 时快时慢的测试，而那种测试最后总会被人加上 {@code @Disabled}。
     *
     * @return 唯一那封信
     */
    private MailMessage awaitSingleMail() {
        List<MailMessage> sent = mailSender.await(1, Duration.ofSeconds(5));
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

    private static String uniqueEmail() {
        return "verify-" + UUID.randomUUID() + "@example.com";
    }

    private static int codeOf(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.get("code").asInt();
    }

}
