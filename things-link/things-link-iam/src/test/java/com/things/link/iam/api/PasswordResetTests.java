package com.things.link.iam.api;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.iam.application.EmailVerificationService;
import com.things.link.iam.domain.EmailVerificationPurpose;
import com.things.link.iam.support.RecordingMailSender;
import com.things.link.support.notification.mail.MailMessage;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 找回密码与重置密码的端到端测试（S4 切片 5，ADR 0013）。
 *
 * <h2>这里真正要钉住的是「重置之后攻击者还进不进得来」</h2>
 * 「能用新口令登录」只是最表层的一条。重置密码存在的理由是账号<b>可能已经被别人
 * 拿到了</b>，所以下面三条缺一不可，且每一条失守都不会有任何症状：
 * <ul>
 *   <li>旧会话必须全部作废 —— 否则攻击者手里的刷新令牌照样能一直续期</li>
 *   <li>其余未用的重置链接必须作废 —— 否则他手里那条 30 分钟的链接还能再改一次</li>
 *   <li>未验证的邮箱不得走这条流程 —— 否则「用别人邮箱注册再找回」就成立了</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Import(RecordingMailSender.Configuration.class)
@DisplayName("找回与重置密码（S4 切片 5）")
class PasswordResetTests extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OLD_PASSWORD = "correct-horse-battery-staple";
    private static final String NEW_PASSWORD = "another-perfectly-fine-passphrase";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthRateLimiter rateLimiter;

    @Autowired
    private RecordingMailSender mailSender;

    @Autowired
    private EmailVerificationService emailVerificationService;

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
    @DisplayName("已验证邮箱走完整流程：收到重置信，改口令后能用新口令登录")
    void resetsPassword() throws Exception {
        String email = registerAndVerify();
        mailSender.clear();

        assertThat(forgot(email).getResponse().getStatus()).isEqualTo(204);

        MailMessage mail = awaitSingleMail();
        assertThat(mail.subject()).contains("重置");
        // 纯文本里必须有完整可点的 URL，且落点是重置页而不是验证页
        assertThat(mail.text()).contains("/#/auth/reset-password?token=");

        assertThat(reset(RecordingMailSender.tokenFrom(mail), NEW_PASSWORD)
                .getResponse().getStatus()).isEqualTo(204);

        assertThat(login(email, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * 重置的全部意义所在。只换口令而不踢会话的话，攻击者手里的刷新令牌能一直续期，
     * 这次重置等于没做 —— 而用户会以为自己已经把账号夺回来了。
     */
    @Test
    @DisplayName("重置后此前的会话全部失效")
    void revokesExistingSessions() throws Exception {
        String email = registerAndVerify();
        MvcResult loggedIn = login(email, OLD_PASSWORD);
        Cookie refreshCookie = refreshCookieOf(loggedIn);

        // 先确认这个会话此刻确实是好用的，否则下面的断言可能是假阳性
        assertThat(refreshWith(refreshCookie).getResponse().getStatus()).isEqualTo(200);

        mailSender.clear();
        forgot(email);
        reset(RecordingMailSender.tokenFrom(awaitSingleMail()), NEW_PASSWORD);

        assertThat(refreshWith(refreshCookie).getResponse().getStatus())
                .as("重置密码必须踢掉全部旧会话，否则攻击者手里的刷新令牌照样能续期")
                .isEqualTo(401);
    }

    /**
     * 与注册验证相反的那条决定（迁移 V20260803_0400）：验证链接的副本只能把邮箱标记为
     * 已验证，而重置链接的每一个副本都是一次完整的账号接管。
     */
    @Test
    @DisplayName("再次申请找回会作废上一封里的链接")
    void newRequestInvalidatesPreviousLink() throws Exception {
        String email = registerAndVerify();
        mailSender.clear();

        forgot(email);
        String firstToken = RecordingMailSender.tokenFrom(awaitSingleMail());
        mailSender.clear();

        forgot(email);
        String secondToken = RecordingMailSender.tokenFrom(awaitSingleMail());

        MvcResult withOld = reset(firstToken, NEW_PASSWORD);
        assertThat(withOld.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(withOld)).isEqualTo(20021);

        assertThat(reset(secondToken, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
    }

    /**
     * 重置成功后作废其余未用令牌。攻击者可能在用户之前就触发过一次找回，
     * 手里攥着一条 30 分钟内有效的链接；用户改完口令之后，那条必须立刻失效。
     *
     * <p>这里绕开 {@code /password/forgot} 直接签发两个令牌 —— 那个接口本身会先作废
     * 旧的，正常路径下不会出现两条并存。测的是 {@code reset} 里那一步纵深防御。
     */
    @Test
    @DisplayName("重置成功后作废其余尚未使用的重置令牌")
    void resetInvalidatesOtherOutstandingTokens() throws Exception {
        String email = registerAndVerify();
        UUID accountId = accountIdOf(email);
        mailSender.clear();

        String attackerToken = emailVerificationService
                .issue(accountId, email, EmailVerificationPurpose.RESET_PASSWORD, null).rawToken();
        String userToken = emailVerificationService
                .issue(accountId, email, EmailVerificationPurpose.RESET_PASSWORD, null).rawToken();

        assertThat(reset(userToken, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        MvcResult withAttackerToken = reset(attackerToken, "yet-another-passphrase-here");
        assertThat(withAttackerToken.getResponse().getStatus())
                .as("用户改完口令后，别人手里那条链接必须同时失效")
                .isEqualTo(400);
        assertThat(codeOf(withAttackerToken)).isEqualTo(20021);
    }

    /**
     * 未验证的邮箱拿不到重置令牌 —— 那正是「用别人的邮箱注册再把账号找回来」的入口，
     * 也是邮箱验证必须排在忘记密码之前的全部原因。
     *
     * <p>但它<b>不能什么都不做</b>：那样「注册了却没验证」的人就走进了死路 ——
     * 进不去、也收不到任何东西，而界面上那句话既不能确认也不能否认。
     * 改发验证邮件解开死路而不泄露任何东西：对外仍是同一个 204。
     */
    @Test
    @DisplayName("未验证的邮箱改发验证邮件，而不是重置邮件")
    void unverifiedEmailGetsVerificationInstead() throws Exception {
        String email = uniqueEmail();
        register(email);
        awaitSingleMail();  // 注册时的那封验证信
        mailSender.clear();

        assertThat(forgot(email).getResponse().getStatus()).isEqualTo(204);

        MailMessage mail = awaitSingleMail();
        assertThat(mail.subject())
                .as("未验证时给的是验证信，不能是重置信 —— 重置令牌能直接改口令")
                .contains("验证")
                .doesNotContain("重置");
        assertThat(mail.text()).contains("/#/auth/verify-email?token=");

        // 而且确实没有签发任何重置令牌：拿到的链接只能验证邮箱
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM sys_email_verification_token
                         WHERE purpose = 'RESET_PASSWORD'
                        """, Integer.class))
                .isZero();
    }

    /** 验证完邮箱之后再申请一次，就走上正常路径了 —— 这条闭合了上面那个出口。 */
    @Test
    @DisplayName("补验证之后再申请找回，就能拿到重置链接")
    void verifyingThenRetryingWorks() throws Exception {
        String email = uniqueEmail();
        register(email);
        awaitSingleMail();
        mailSender.clear();

        // 第一次申请：邮箱还没验证，拿到的是验证信
        forgot(email);
        String verifyToken = RecordingMailSender.tokenFrom(awaitSingleMail());
        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}""".formatted(verifyToken)))
                .andReturn();
        mailSender.clear();

        // 第二次申请：这回是真的重置信
        forgot(email);
        MailMessage mail = awaitSingleMail();
        assertThat(mail.subject()).contains("重置");
        assertThat(reset(RecordingMailSender.tokenFrom(mail), NEW_PASSWORD)
                .getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("未注册的邮箱返回 204，且不发信")
    void unknownEmailLooksIdentical() throws Exception {
        MvcResult result = forgot(uniqueEmail());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(mailSender.stayedEmptyFor(Duration.ofMillis(500))).isTrue();
    }

    @Test
    @DisplayName("同一个重置链接不能用第二次")
    void tokenIsSingleUse() throws Exception {
        String email = registerAndVerify();
        mailSender.clear();
        forgot(email);
        String token = RecordingMailSender.tokenFrom(awaitSingleMail());

        reset(token, NEW_PASSWORD);
        MvcResult second = reset(token, "a-third-distinct-passphrase");

        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(second)).isEqualTo(20021);
    }

    /**
     * 口令强度先于令牌消费校验。顺序反了的话，用户提交一个太短的口令就会把唯一的
     * 重置链接消耗掉 —— 他得到「口令太短」的提示，然后发现链接也失效了，
     * 只能重新走一遍找回流程。口令输错是最常见的一种失败，不是理论问题。
     */
    @Test
    @DisplayName("口令太短时拒绝，且不消耗令牌")
    void weakPasswordDoesNotBurnTheToken() throws Exception {
        String email = registerAndVerify();
        mailSender.clear();
        forgot(email);
        String token = RecordingMailSender.tokenFrom(awaitSingleMail());

        MvcResult blockedDefault = reset(token, "ThingsLink123!");
        assertThat(blockedDefault.getResponse().getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(blockedDefault.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(20011);
        MvcResult tooShort = reset(token, "short");
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);

        assertThat(reset(token, NEW_PASSWORD).getResponse().getStatus())
                .as("口令没通过校验时不该消耗掉用户唯一的那条链接")
                .isEqualTo(204);
    }

    @Test
    @DisplayName("找回次数超限返回 429")
    void forgotIsRateLimited() throws Exception {
        String email = registerAndVerify();

        // 前 3 次放行（MAX_RESETS_PER_EMAIL），第 4 次必须被拦
        for (int i = 0; i < 3; i++) {
            assertThat(forgot(email).getResponse().getStatus()).isEqualTo(204);
        }
        MvcResult blocked = forgot(email);

        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(codeOf(blocked)).isEqualTo(10029);
    }

    // ---------------------------------------------------------------- 辅助

    /** 注册一个账号并点开验证链接，返回其邮箱。找回密码的前提条件。 */
    private String registerAndVerify() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = RecordingMailSender.tokenFrom(awaitSingleMail());
        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}""".formatted(token)))
                .andReturn();
        return email;
    }

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, OLD_PASSWORD)))
                .andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andReturn();
    }

    private MvcResult forgot(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andReturn();
    }

    private MvcResult reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"%s"}""".formatted(token, newPassword)))
                .andReturn();
    }

    private MvcResult refreshWith(Cookie refreshCookie) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie)).andReturn();
    }

    private Cookie refreshCookieOf(MvcResult result) {
        Cookie[] cookies = result.getResponse().getCookies();
        assertThat(cookies).as("登录响应里必须有刷新令牌 Cookie").isNotEmpty();
        return cookies[0];
    }

    private UUID accountIdOf(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
    }

    private MailMessage awaitSingleMail() {
        List<MailMessage> sent = mailSender.await(1, Duration.ofSeconds(5));
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

    private static String uniqueEmail() {
        return "reset-" + UUID.randomUUID() + "@example.com";
    }

    private static int codeOf(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.get("code").asInt();
    }

}
