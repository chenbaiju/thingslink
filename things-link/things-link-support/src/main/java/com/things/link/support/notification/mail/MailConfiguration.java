package com.things.link.support.notification.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.mail.health.MailHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 邮件发送器的装配。
 *
 * <h2>为什么在一个 @Bean 方法里判断，而不是用两个 @ConditionalOnXxx</h2>
 * 「有 JavaMailSender 就用 SMTP，否则用日志」写成两个 bean 的话，第二个要靠
 * {@code @ConditionalOnMissingBean}，而<b>用户配置类里的 @ConditionalOnMissingBean
 * 结果取决于 @Bean 方法的声明顺序</b>（Spring Boot 参考文档明确警告过这点）。
 * 把两个方法的顺序调换一下，兜底实现就会永远赢，而这不会有任何报错 ——
 * 表现是生产环境静默地不发信。
 *
 * <p>一个方法里显式 if/else 没有这个隐患，读起来也更直白。
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfiguration {

    private final Environment environment;

    /** 生产profile禁止将验证令牌通过日志邮件输出。 */
    public MailConfiguration(Environment environment) {
        this.environment = environment;
    }

    private static final Logger log = LoggerFactory.getLogger(MailConfiguration.class);

    /**
     * 本模块发送器的 bean 名。
     *
     * <h2>为什么不能叫 mailSender</h2>
     * Spring Boot 的邮件自动配置注册的 bean 就叫 {@code mailSender}
     * （{@code MailSenderPropertiesConfiguration}），而 Boot 4 默认禁止 bean 覆盖。
     * 两边同名的结果是<b>应用直接启动失败</b>：
     * {@code The bean 'mailSender' ... could not be registered}。
     *
     * <p>要命的是它<b>只在配了 {@code spring.mail.host} 时才发生</b> —— 没配的时候
     * Boot 那个 bean 根本不注册，冲突也就不存在。于是本地开发、CI、全部测试都是绿的，
     * 唯独真正要发信的环境起不来。这个坑是配好 QQ 邮箱那一刻才踩到的，
     * 不是推演出来的。
     */
    public static final String OUTBOUND_MAIL_SENDER = "outboundMailSender";

    /**
     * 选择发送实现。
     *
     * <p>{@link ObjectProvider} 而不是直接注入 {@link JavaMailSender}：后者在未配置
     * {@code spring.mail.host} 时根本不存在，直接注入会让整个应用启动失败。
     * 「没配邮件」必须是一个正常状态，本地开发就是这个状态。
     *
     * @param javaMailSenderProvider Boot 依据 {@code spring.mail.*} 装配的发送器，可能不存在
     * @param properties             应用侧邮件配置
     * @return 发送实现
     */
    @Bean(OUTBOUND_MAIL_SENDER)
    public MailSender outboundMailSender(ObjectProvider<JavaMailSender> javaMailSenderProvider,
                                         MailProperties properties) {
        JavaMailSender javaMailSender = javaMailSenderProvider.getIfAvailable();
        if (isSmtpUnconfigured(javaMailSender)) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("Production SMTP sender is required: spring.mail.host");
            }
            log.warn("未配置 spring.mail.host，邮件将只打印到日志、不会真实发送。"
                    + "生产环境出现这条日志意味着注册验证邮件全部丢失，且不会有其他症状。");
            return new LoggingMailSender();
        }

        String from = resolveFrom(properties, javaMailSender);
        log.info("邮件发送已启用: from={} ({})", from, properties.fromName());
        return new SmtpMailSender(javaMailSender, from, properties.fromName());
    }

    /**
     * 判断 SMTP 是否真正完成配置。
     *
     * <p>配置文件使用 {@code ${MAIL_SMTP_HOST:}} 时，Spring Boot 仍可能创建一个
     * {@link JavaMailSenderImpl}，但其 host 是空字符串。只判断 bean 是否存在会误以为 SMTP 已启用，
     * 随后在解析空 username/from 时导致应用启动失败。</p>
     *
     * @param javaMailSender Boot 创建的发送器，可能为空
     * @return 没有发送器，或 Boot 发送器的 host 为空时返回 true
     */
    private static boolean isSmtpUnconfigured(JavaMailSender javaMailSender) {
        if (javaMailSender == null) {
            return true;
        }
        return javaMailSender instanceof JavaMailSenderImpl impl
                && (impl.getHost() == null || impl.getHost().isBlank());
    }

    /**
     * 邮件健康检查。
     *
     * <p>Spring Boot 只要看到 {@code spring.mail.host} 属性存在（即使是空字符串），
     * 就会装配 {@link JavaMailSenderImpl} 并创建 {@link MailHealthIndicator}，
     * 然后尝试连接 SMTP —— 未配置邮件时这是多余的，且会因为密码为空抛
     * {@code AuthenticationFailedException}。
     *
     * <p>这里自定义一个同名 bean 覆盖 Boot 的默认实现：
     * host 为空时直接返回 UP，不尝试连接。
     */
    @Bean
    @ConditionalOnEnabledHealthIndicator("mail")
    public HealthIndicator mailHealthIndicator(ObjectProvider<JavaMailSender> mailSenderProvider) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (isSmtpUnconfigured(mailSender)) {
            return () -> Health.up().withDetail("mail", "SMTP not configured").build();
        }
        return new MailHealthIndicator((JavaMailSenderImpl) mailSender);
    }

    /**
     * 确定发件地址：优先用 {@code things-link.mail.from}，留空则回落到
     * {@code spring.mail.username}。
     *
     * <p>回落是默认路径而不是兜底：绝大多数邮箱服务商要求发件地址等于认证账号，
     * 不一致会被拒收或判为伪造。让它自动相等，就少了一个能配错的地方。
     *
     * @param properties     应用侧配置
     * @param javaMailSender 底层发送器，从中读取认证账号
     * @return 发件地址
     * @throws IllegalStateException 两个来源都为空
     */
    private String resolveFrom(MailProperties properties, JavaMailSender javaMailSender) {
        if (properties.from() != null && !properties.from().isBlank()) {
            return properties.from();
        }
        // getUsername() 只在实现类上，接口没有暴露。Boot 装配出来的一定是这个实现，
        // 但仍然做类型判断 —— 万一有人换了实现，这里要给出可读的错误而不是 ClassCastException。
        if (javaMailSender instanceof JavaMailSenderImpl impl
                && impl.getUsername() != null && !impl.getUsername().isBlank()) {
            return impl.getUsername();
        }
        throw new IllegalStateException(
                "配置了 spring.mail.host 却确定不了发件地址。"
                        + "请设置 things-link.mail.from，或补上 spring.mail.username。");
    }

}
