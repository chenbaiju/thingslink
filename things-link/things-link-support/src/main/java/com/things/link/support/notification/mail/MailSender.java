package com.things.link.support.notification.mail;

/**
 * 出站邮件发送口。
 *
 * <p><b>与 {@code org.springframework.mail.MailSender} 同名但无关。</b>
 * Spring 那个接口的参数是 {@code SimpleMailMessage}，发不了 HTML；本接口是本项目
 * 自己的抽象，实现之一才是包装了 {@code JavaMailSender} 的
 * {@link SmtpMailSender}。同时用到两者时请把 Spring 的那个写全限定名。
 *
 * <p>调用约束（同步阻塞、不要放进事务和请求线程）见
 * {@code package-info.java}，那是使用本接口前必须先看的部分。
 */
public interface MailSender {

    /**
     * 发送一封邮件，阻塞到 SMTP 交互结束。
     *
     * @param message 待发送的邮件
     * @throws MailDeliveryException 发送失败。<b>调用方必须决定怎么处理</b>：
     *                               验证码类邮件的正确处置是记日志后放过，让用户点重发；
     *                               直接把异常抛回 HTTP 接口会让「账号已创建但邮件没发出去」
     *                               表现成注册失败，用户重试时撞上邮箱已存在
     */
    void send(MailMessage message);

}
