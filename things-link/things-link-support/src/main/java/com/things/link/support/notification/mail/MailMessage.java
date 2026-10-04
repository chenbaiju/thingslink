package com.things.link.support.notification.mail;

/**
 * 一封待发送的邮件。
 *
 * <h2>为什么强制要有纯文本正文</h2>
 * {@code html} 可以为空，{@code text} 不行。只发 HTML 的邮件在几乎所有反垃圾评分模型
 * 里都会被扣分（正常的事务邮件都是 multipart/alternative），而验证码邮件一旦进了
 * 垃圾箱，用户根本不会知道自己没收到。纯文本部分的成本只是多写一段字符串，
 * 换掉的是「注册流程有一部分用户静默失败」这种最难排查的问题。
 *
 * <p>另一个理由：命令行邮件客户端和部分邮件预览只渲染纯文本部分。
 * 验证链接必须在纯文本里以完整 URL 出现，不能只藏在 {@code <a href>} 里。
 *
 * @param to      收件地址。这里<b>不做格式校验</b> —— 校验属于入口的 Bean Validation，
 *                到了这一层地址已经是可信数据，重复校验只会让两处的规则慢慢分叉
 * @param subject 主题
 * @param text    纯文本正文，必填，理由见上
 * @param html    HTML 正文，可为 {@code null}。为空时发单段纯文本邮件，
 *                非空时发 multipart/alternative
 */
public record MailMessage(String to, String subject, String text, String html) {

    /**
     * 紧凑构造器：拦掉空值。
     *
     * <p>发一封收件人为空的邮件不会报错，只会在 SMTP 层拿到一个含义模糊的
     * 拒绝码，且那时已经离出错的地方很远了。
     */
    public MailMessage {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("收件地址不能为空");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("邮件主题不能为空");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("纯文本正文不能为空，理由见 MailMessage 类注释");
        }
    }

    /**
     * 构造一封纯文本邮件。
     *
     * @param to      收件地址
     * @param subject 主题
     * @param text    正文
     * @return 邮件
     */
    public static MailMessage text(String to, String subject, String text) {
        return new MailMessage(to, subject, text, null);
    }

    /**
     * 构造一封 HTML + 纯文本双版本邮件。
     *
     * @param to      收件地址
     * @param subject 主题
     * @param text    纯文本正文，必须包含 HTML 版本里的全部关键信息（尤其是链接）
     * @param html    HTML 正文
     * @return 邮件
     */
    public static MailMessage html(String to, String subject, String text, String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("HTML 正文为空时请使用 MailMessage.text(...)");
        }
        return new MailMessage(to, subject, text, html);
    }

    /**
     * 是否为 HTML 邮件。
     *
     * @return {@code true} 表示需要按 multipart/alternative 发送
     */
    public boolean isMultipart() {
        return html != null && !html.isBlank();
    }

}
