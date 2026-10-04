package com.things.link.support.notification.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本模块的邮件装配与 Spring Boot 邮件自动配置能否共存。
 *
 * <h2>这个测试是补的，不是设计出来的</h2>
 * {@link MailConfigurationTests} 直接 new 出配置类调方法，验的是<b>选择逻辑</b>；
 * 它验不了 bean 注册期的冲突。而真实的故障恰恰发生在那一层：本模块原先把 bean 命名为
 * {@code mailSender}，与 Boot 的 {@code MailSenderPropertiesConfiguration} 撞名，
 * Boot 4 默认禁止覆盖，于是<b>应用直接启动失败</b>。
 *
 * <p>它没有被任何测试发现，因为 Boot 那个 bean <b>只在 {@code spring.mail.host}
 * 有值时才注册</b>——而当时没有任何测试配过它。本地开发、CI、全套集成测试都是绿的，
 * 唯独第一次真正配上 SMTP 的环境起不来。
 *
 * <p>所以这里的两个用例都必须<b>显式配上 {@code spring.mail.host}</b>，
 * 那正是原来缺失的那个条件。
 */
@DisplayName("邮件装配与 Boot 自动配置共存")
class MailAutoConfigurationClashTests {

    /**
     * 关掉 bean 覆盖，与生产一致。
     *
     * <p>不关的话这个测试就没有意义：{@code AnnotationConfigApplicationContext} 默认
     * 允许覆盖，撞名的两个 bean 会静默地互相顶掉，测试照样绿 —— 而生产环境启动失败。
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withAllowBeanDefinitionOverriding(false)
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(MailConfiguration.class);

    @Test
    @DisplayName("配置了 spring.mail.host 时上下文能启动，并选中 SMTP 实现")
    void contextStartsWithSmtpConfigured() {
        runner.withPropertyValues(
                        "spring.mail.host=smtp.example.com",
                        "spring.mail.port=465",
                        "spring.mail.username=noreply@example.com",
                        "spring.mail.password=not-a-real-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MailSender.class)).isInstanceOf(SmtpMailSender.class);
                });
    }

    @Test
    @DisplayName("未配置 spring.mail.host 时上下文同样能启动，落到日志实现")
    void contextStartsWithoutSmtp() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MailSender.class)).isInstanceOf(LoggingMailSender.class);
        });
    }

}
