package com.things.link.support.notification.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 出站邮件的应用侧配置。
 *
 * <h2>为什么不复用 {@code spring.mail.*}</h2>
 * 连接参数（host / port / username / password / 超时）全部由 Spring Boot 的
 * {@code spring.mail.*} 负责，本类<b>只放 Boot 没有的两项</b>：发件显示地址和显示名。
 * 两边都定义 host 的话，就会出现「配置了却不生效」——
 * 因为真正被 {@code JavaMailSenderImpl} 读到的是 Boot 那一套。
 *
 * @param from     发件地址。留空则回落到 {@code spring.mail.username}，
 *                 这对绝大多数邮箱服务商是对的（发件地址必须等于认证账号，
 *                 否则会被拒收或判为伪造）。只有在服务商支持「代发别名」时才需要单独填
 * @param fromName 发件显示名。收件人在收件箱列表里看到的就是它。
 *                 <b>不要填成邮箱地址</b> —— 显示名和地址都是地址时，
 *                 部分客户端会渲染成 {@code a@b.com <a@b.com>} 这种明显不像正经邮件的样子
 */
@ConfigurationProperties(prefix = "things-link.mail")
public record MailProperties(String from, String fromName) {

    /**
     * 紧凑构造器：为显示名提供默认值。
     *
     * <p>{@code from} 不在这里兜底 —— 它要回落到 {@code spring.mail.username}，
     * 而那个值本类看不到。回落逻辑在 {@link MailConfiguration} 里，那里两个配置都拿得到。
     */
    public MailProperties {
        fromName = fromName == null || fromName.isBlank() ? "ThingsLink" : fromName;
    }

}
