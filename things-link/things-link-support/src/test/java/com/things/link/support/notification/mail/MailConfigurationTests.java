package com.things.link.support.notification.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 发送实现的选择逻辑。
 *
 * <p>这几条断言看着琐碎，但它们守的是一个没有症状的故障：选错实现时应用照常启动、
 * 接口照常返回 200，只是信永远发不出去。
 */
@DisplayName("邮件发送器装配")
class MailConfigurationTests {

    private final MailConfiguration configuration = new MailConfiguration(new MockEnvironment());

    @Test
    @DisplayName("未配置 SMTP 时落到日志实现，而不是启动失败")
    void fallsBackToLoggingSender() {
        MailSender sender = configuration.outboundMailSender(emptyProvider(), new MailProperties(null, null));

        assertThat(sender).isInstanceOf(LoggingMailSender.class);
    }

    @Test
    void productionNeverFallsBackToLogging() {
        MailConfiguration production = new MailConfiguration(new MockEnvironment().withProperty(
                "spring.profiles.active", "prod"));
        assertThatThrownBy(() -> production.outboundMailSender(emptyProvider(), new MailProperties(null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.mail.host");
        JavaMailSenderImpl blank = new JavaMailSenderImpl();
        blank.setHost(" ");
        assertThatThrownBy(() -> production.outboundMailSender(providerOf(blank), new MailProperties(null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 环境变量空默认值仍可能触发 Boot 创建 JavaMailSender，此时必须按未配置处理。 */
    @Test
    @DisplayName("SMTP host 为空字符串时落到日志实现")
    void blankHostFallsBackToLoggingSender() {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost("  ");

        MailSender sender = configuration.outboundMailSender(
                providerOf(javaMailSender), new MailProperties(null, null));

        assertThat(sender).isInstanceOf(LoggingMailSender.class);
    }

    @Test
    @DisplayName("配置了 SMTP 时使用真实发送实现")
    void usesSmtpSenderWhenConfigured() {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost("smtp.example.com");
        javaMailSender.setUsername("noreply@example.com");

        MailSender sender = configuration.outboundMailSender(
                providerOf(javaMailSender), new MailProperties(null, null));

        assertThat(sender).isInstanceOf(SmtpMailSender.class);
    }

    /**
     * 多数邮箱服务商要求发件地址等于认证账号，不一致会被拒收或判为伪造。
     * 让它自动相等，就少了一个能配错的地方。
     */
    @Test
    @DisplayName("未指定 from 时回落到 spring.mail.username")
    void fromFallsBackToSmtpUsername() {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost("smtp.example.com");
        javaMailSender.setUsername("noreply@example.com");

        String from = resolveFrom(new MailProperties(null, null), javaMailSender);

        assertThat(from).isEqualTo("noreply@example.com");
    }

    @Test
    @DisplayName("显式配置的 from 优先于 username")
    void explicitFromWins() {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost("smtp.example.com");
        javaMailSender.setUsername("account@example.com");

        String from = resolveFrom(new MailProperties("alias@example.com", null), javaMailSender);

        assertThat(from).isEqualTo("alias@example.com");
    }

    /**
     * 配了 host 却确定不了发件地址是配置错误，必须在启动期炸掉。
     * 放过去的话，第一封信会在运行时失败，而那时已经有用户在等验证邮件了。
     */
    @Test
    @DisplayName("配了 SMTP 却没有任何发件地址时启动失败")
    void failsFastWhenFromCannotBeResolved() {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost("smtp.example.com");

        assertThatThrownBy(() -> configuration.outboundMailSender(
                providerOf(javaMailSender), new MailProperties(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("things-link.mail.from");
    }

    @Test
    @DisplayName("未配置显示名时用默认值")
    void fromNameHasDefault() {
        assertThat(new MailProperties(null, null).fromName()).isEqualTo("ThingsLink");
        assertThat(new MailProperties(null, "  ").fromName()).isEqualTo("ThingsLink");
    }

    /** 借装配方法间接触发私有的 from 解析，避免为测试放宽可见性。 */
    private String resolveFrom(MailProperties properties, JavaMailSenderImpl javaMailSender) {
        MailSender sender = configuration.outboundMailSender(providerOf(javaMailSender), properties);
        return ((SmtpMailSender) sender).from();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> emptyProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> providerOf(JavaMailSender javaMailSender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(javaMailSender);
        return provider;
    }

}
