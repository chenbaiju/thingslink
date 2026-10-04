package com.things.link.support.notification.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邮件内容的构造约束。
 */
@DisplayName("邮件内容")
class MailMessageTests {

    @Test
    @DisplayName("纯文本邮件不是 multipart")
    void textMessageIsNotMultipart() {
        MailMessage message = MailMessage.text("a@example.com", "主题", "正文");

        assertThat(message.isMultipart()).isFalse();
        assertThat(message.html()).isNull();
    }

    @Test
    @DisplayName("HTML 邮件是 multipart 且保留纯文本版本")
    void htmlMessageKeepsTextAlternative() {
        MailMessage message = MailMessage.html(
                "a@example.com", "主题", "链接: https://example.com/v?t=x", "<a href=\"...\">验证</a>");

        assertThat(message.isMultipart()).isTrue();
        assertThat(message.text()).contains("https://example.com/v?t=x");
    }

    /**
     * 只发 HTML 会被反垃圾评分扣分，且纯文本客户端看不到验证链接。
     * 这条约束靠构造器强制，不能靠调用方自觉。
     */
    @Test
    @DisplayName("纯文本正文缺失时直接拒绝构造")
    void rejectsMissingTextBody() {
        assertThatThrownBy(() -> new MailMessage("a@example.com", "主题", "  ", "<p>x</p>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("纯文本正文");
    }

    @Test
    @DisplayName("收件人或主题为空时直接拒绝构造")
    void rejectsMissingRecipientOrSubject() {
        assertThatThrownBy(() -> MailMessage.text("", "主题", "正文"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailMessage.text("a@example.com", "", "正文"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("html(...) 不接受空 HTML，避免绕开 text(...) 造出伪 multipart")
    void htmlFactoryRejectsBlankHtml() {
        assertThatThrownBy(() -> MailMessage.html("a@example.com", "主题", "正文", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
