package com.things.link.support.notification.mail;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SMTP 发送实现生成的 MIME 报文。
 *
 * <p>这里断言的是<b>报文本身</b>而不是「调用了 send」。中文编码和 multipart 结构
 * 出错时不会抛异常，只会让收件人看到乱码或空白邮件 —— 而开发者自己的邮件客户端
 * 常常能猜对编码，于是本地怎么看都是正常的。
 */
@DisplayName("SMTP 邮件发送")
class SmtpMailSenderTests {

    @Test
    @DisplayName("中文主题与发件显示名按 UTF-8 编码")
    void encodesChineseHeaders() throws Exception {
        MimeMessage sent = capture(MailMessage.text("user@example.com", "验证你的邮箱", "验证码 123456"));

        // getSubject / getPersonal 返回的是解码后的值：能正确解码，就说明编码时带上了字符集。
        assertThat(sent.getSubject()).isEqualTo("验证你的邮箱");
        InternetAddress from = (InternetAddress) sent.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("noreply@example.com");
        assertThat(from.getPersonal()).isEqualTo("物联云");
    }

    @Test
    @DisplayName("HTML 邮件同时携带纯文本与 HTML 两个版本")
    void sendsBothAlternatives() throws Exception {
        MimeMessage sent = capture(MailMessage.html(
                "user@example.com",
                "验证你的邮箱",
                "请打开链接完成验证: https://example.com/verify?token=abc",
                "<p>请<a href=\"https://example.com/verify?token=abc\">点此验证</a></p>"));

        // 断言 getContent() 的类型而不是 getContentType()：Content-Type 头要等
        // saveChanges() 才写入，而那发生在真实发送时，这里捕获到的报文还没走到那一步。
        assertThat(sent.getContent()).isInstanceOf(Multipart.class);
        List<String> bodies = collectTextParts(sent);
        // 纯文本版本必须含完整 URL：命令行客户端和纯文本预览只看得到这一段。
        assertThat(bodies).anySatisfy(body ->
                assertThat(body).contains("https://example.com/verify?token=abc").doesNotContain("<a href"));
        assertThat(bodies).anySatisfy(body -> assertThat(body).contains("<a href"));
    }

    @Test
    @DisplayName("纯文本邮件不包成 multipart")
    void plainTextIsNotWrappedInMultipart() throws Exception {
        MimeMessage sent = capture(MailMessage.text("user@example.com", "主题", "正文"));

        assertThat(sent.getContent()).isInstanceOf(String.class).isEqualTo("正文");
    }

    /**
     * 底层异常必须换成本包的异常类型。直接把 {@code MailException} 漏给调用方的话，
     * 调用方要么去 import Spring 的邮件包（依赖倒过来了），要么干脆不处理。
     */
    @Test
    @DisplayName("底层发送失败包装成 MailDeliveryException，且不带出正文")
    void wrapsUnderlyingFailure() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        when(javaMailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailSendException("connection refused")).when(javaMailSender).send(any(MimeMessage.class));

        SmtpMailSender sender = new SmtpMailSender(javaMailSender, "noreply@example.com", "物联云");

        assertThatThrownBy(() -> sender.send(
                MailMessage.text("user@example.com", "主题", "验证链接 https://example.com/verify?token=secret")))
                .isInstanceOf(MailDeliveryException.class)
                .hasMessageContaining("user@example.com")
                // 正文里有验证令牌，异常信息会进日志，日志的可见范围比收件人宽得多。
                .hasMessageNotContaining("secret");
    }

    /** 走一遍真实的 MimeMessageHelper 组装，只把最后的网络发送换成捕获。 */
    private MimeMessage capture(MailMessage message) {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        when(javaMailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        new SmtpMailSender(javaMailSender, "noreply@example.com", "物联云").send(message);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        org.mockito.Mockito.verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }

    /**
     * 递归收集所有文本型叶子节点。
     *
     * <p>不直接断言 {@code getBodyPart(0)}：MimeMessageHelper 默认按
     * mixed → related → alternative 嵌套，层级会随附件等特性变化，写死下标的断言
     * 会在无关改动上误报。
     */
    private List<String> collectTextParts(Part part) throws Exception {
        List<String> bodies = new ArrayList<>();
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                bodies.addAll(collectTextParts(multipart.getBodyPart(i)));
            }
        } else if (content instanceof String text) {
            bodies.add(text);
        }
        return bodies;
    }

}
