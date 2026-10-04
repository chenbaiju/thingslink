package com.things.link.support.notification.mail;

/**
 * 邮件投递失败。
 *
 * <h2>为什么不是 BusinessException / 不带错误码</h2>
 * {@code BusinessException} 会被 {@code GlobalExceptionHandler} 翻译成一个 HTTP
 * 错误响应返回给调用方，而邮件发送发生在<b>事务提交之后、请求响应之外</b>
 * （见 {@code package-info.java}），那时已经没有 HTTP 响应可写了。
 *
 * <p>更重要的是语义：SMTP 发不出去是<b>服务端设施的问题</b>，不是调用方做错了什么。
 * 给它一个错误码会诱导调用方把它当业务错误往外抛，于是「邮件服务器抽风」
 * 被呈现成「你的注册请求有问题」。
 */
public class MailDeliveryException extends RuntimeException {

    /**
     * @param message 失败描述。<b>不要把邮件正文放进来</b> ——
     *                验证链接、验证码都在正文里，异常信息会进日志，
     *                而日志的访问范围比邮件收件人宽得多
     * @param cause   底层异常
     */
    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

}
