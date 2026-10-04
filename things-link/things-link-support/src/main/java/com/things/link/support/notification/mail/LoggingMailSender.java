package com.things.link.support.notification.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 不发信，把邮件内容打到日志的实现。未配置 {@code spring.mail.host} 时自动生效。
 *
 * <h2>它是开发默认值，不是降级兜底</h2>
 * 本地跑一次注册流程就要往真实 SMTP 发一封信是不可接受的：个人/企业邮箱的日发信量
 * 在几十到几百封量级，超了会临时限流甚至封禁授权码。调试注册流程轻易就能发上几十封。
 *
 * <p>因此本地默认<b>什么都不配</b>，验证链接直接从应用日志里复制。
 *
 * <h2>⚠️ 生产环境落到这个实现会静默地不发信</h2>
 * 用户点了注册、看到「请查收邮件」，然后永远收不到。这是最难被发现的一类故障 ——
 * 没有报错、没有异常、监控全绿。所以：
 * <ul>
 *   <li>装配时打一条 WARN（见 {@link MailConfiguration}），而不是 INFO</li>
 *   <li>每次「发送」都以 WARN 记录，日志里不会只有孤零零一条启动告警</li>
 * </ul>
 * G3-SEC-1：MailConfiguration在prod拒绝退回本实现；启动守卫同时校验SMTP和Secure Cookie。
 */
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(MailMessage message) {
        // 这里刻意把**纯文本正文完整打出来**，与 SmtpMailSender 只记收件人和主题相反。
        // 那边不记正文是因为验证链接不该进日志；这边正文进日志正是唯一目的 ——
        // 没有别的地方能拿到那个链接。两者的取舍不同，是因为本实现只在本地跑。
        log.warn("""
                【邮件未真实发送】未配置 spring.mail.host，以下内容仅打印到日志。
                  收件人: {}
                  主  题: {}
                  正  文:
                {}""", message.to(), message.subject(), message.text());
    }

}
