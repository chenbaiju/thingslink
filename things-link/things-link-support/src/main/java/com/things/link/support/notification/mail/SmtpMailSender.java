package com.things.link.support.notification.mail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * 走 SMTP 的真实发送实现。
 *
 * <h2>为什么走 MimeMessage 而不是 SimpleMailMessage</h2>
 * {@code SimpleMailMessage} 发不了 HTML，也设不了发件显示名和字符集。
 * 字符集这一项在中文场景下是硬需求：不显式指定 UTF-8 时，主题里的中文
 * 在部分客户端会显示成乱码，而这类问题在开发者自己的邮箱里往往看不出来
 * （本地客户端猜对了编码），只有收件人换一个客户端才暴露。
 */
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    /** 邮件头与正文的字符集。中文主题/正文必须显式指定，理由见类注释。 */
    private static final String CHARSET = StandardCharsets.UTF_8.name();

    private final JavaMailSender javaMailSender;
    private final String from;
    private final String fromName;

    /**
     * @param javaMailSender Boot 依据 {@code spring.mail.*} 装配的底层发送器
     * @param from           发件地址，已由 {@link MailConfiguration} 完成回落与校验
     * @param fromName       发件显示名
     */
    public SmtpMailSender(JavaMailSender javaMailSender, String from, String fromName) {
        this.javaMailSender = javaMailSender;
        this.from = from;
        this.fromName = fromName;
    }

    /**
     * 实际使用的发件地址。
     *
     * <p>包级可见，供测试断言「from 回落到 spring.mail.username」这条规则 ——
     * 那条回落逻辑在 {@link MailConfiguration} 的私有方法里，从外部只能通过
     * 装配出来的实例观察到结果。
     *
     * @return 发件地址
     */
    String from() {
        return from;
    }

    @Override
    public void send(MailMessage message) {
        try {
            MimeMessage mime = javaMailSender.createMimeMessage();
            // multipart 开关必须跟着正文形态走：纯文本邮件包一层 multipart 是多余的，
            // 会让邮件源码里多出一段空的 boundary，部分反垃圾规则对此有意见。
            MimeMessageHelper helper = new MimeMessageHelper(mime, message.isMultipart(), CHARSET);

            helper.setFrom(new InternetAddress(from, fromName, CHARSET));
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            if (message.isMultipart()) {
                // 两参重载生成 multipart/alternative：纯文本在前、HTML 在后。
                // 顺序是 RFC 2046 规定的（最后一段是首选版本），写反了会让
                // 支持 HTML 的客户端显示纯文本版本。
                helper.setText(message.text(), message.html());
            } else {
                helper.setText(message.text(), false);
            }

            javaMailSender.send(mime);
            // 只记收件地址和主题，不记正文 —— 正文里有验证链接（MailDeliveryException 同理）。
            log.info("邮件已发送: to={}, subject={}", message.to(), message.subject());
        } catch (MailException | jakarta.mail.MessagingException | UnsupportedEncodingException e) {
            throw new MailDeliveryException(
                    "邮件发送失败: to=%s, subject=%s".formatted(message.to(), message.subject()), e);
        }
    }

}
