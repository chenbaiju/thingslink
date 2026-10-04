package com.things.link.iam.application;

import com.things.link.iam.domain.EmailVerificationPurpose;
import com.things.link.iam.support.RecordingMailSender;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.notification.mail.MailMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件正文里的有效期与链接落点。
 *
 * <h2>为什么单独测这个</h2>
 * 第一批真实发出去的验证信里写的是「链接将在 <b>23</b> 小时后失效」，而有效期是 24 小时。
 * 原因是有效期算的是「现在距过期还有多久」，签发到投递之间隔着一次异步调度，
 * 哪怕只有 1 秒，剩余也变成 23h59m59s，{@code toHours()} 直接把零头抹掉。
 *
 * <p>这类错误编译器、集成测试、限流、安全断言全都不会碰到 —— 它只出现在
 * 用户真正读到的那段文字里。所以它需要一条属于自己的测试。
 */
@DisplayName("邮件正文")
class EmailVerificationMailerTests {

    private final RecordingMailSender mailSender = new RecordingMailSender();
    private final EmailVerificationMailer mailer =
            new EmailVerificationMailer(mailSender, new ConsoleProperties("http://console.example.com"));

    @Test
    @DisplayName("24 小时的验证链接写成「24 小时」，不是 23")
    void verificationMailRoundsHoursUp() {
        send(EmailVerificationPurpose.REGISTER_VERIFY, Duration.ofHours(24).minusSeconds(1));

        MailMessage mail = onlyMail();
        assertThat(mail.subject()).isEqualTo("验证你的 ThingsLink 邮箱");
        assertThat(mail.text()).contains("24 小时").doesNotContain("23 小时");
        assertThat(mail.html()).contains("24 小时");
    }

    @Test
    @DisplayName("30 分钟的重置链接写成「30 分钟」，不是 29")
    void resetMailRoundsMinutesUp() {
        send(EmailVerificationPurpose.RESET_PASSWORD, Duration.ofMinutes(30).minusSeconds(1));

        MailMessage mail = onlyMail();
        assertThat(mail.subject()).isEqualTo("重置你的 ThingsLink 口令");
        assertThat(mail.text()).contains("30 分钟").doesNotContain("29 分钟");
    }

    /**
     * 向上取整不能变成「永远显示满额」：投递真的被拖了很久时，
     * 信里的数字必须如实变小，否则用户会以为自己还有一整天。
     */
    @Test
    @DisplayName("投递被拖延时如实显示缩短后的时长")
    void reportsShrunkValidityWhenDeliveryIsDelayed() {
        send(EmailVerificationPurpose.REGISTER_VERIFY, Duration.ofHours(2));

        assertThat(onlyMail().text()).contains("2 小时").doesNotContain("24 小时");
    }

    /** 两类信落在不同的页面上，串了的话用户点开只会拿到 20021。 */
    @Test
    @DisplayName("两类邮件的链接落点各自正确")
    void linksPointAtTheRightPages() {
        send(EmailVerificationPurpose.REGISTER_VERIFY, Duration.ofHours(24));
        assertThat(onlyMail().text())
                .contains("http://console.example.com/#/auth/verify-email?token=");

        mailSender.clear();
        send(EmailVerificationPurpose.RESET_PASSWORD, Duration.ofMinutes(30));
        assertThat(onlyMail().text())
                .contains("http://console.example.com/#/auth/reset-password?token=");
    }

    /**
     * 直接调监听器方法。这里不需要 Spring 上下文：{@code AFTER_COMMIT} 与
     * {@code @Async} 的行为由 {@code EmailVerificationApiTests} 覆盖，
     * 本类只关心正文渲染。
     */
    private void send(EmailVerificationPurpose purpose, Duration remaining) {
        mailer.onVerificationRequested(new EmailVerificationRequested(
                Uuid7.generate(),
                "user@example.com",
                purpose,
                "a-token",
                Instant.now().plus(remaining)));
    }

    private MailMessage onlyMail() {
        var sent = mailSender.await(1, Duration.ofSeconds(1));
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

}
