package com.things.link.iam.support;

import com.things.link.support.notification.mail.MailMessage;
import com.things.link.support.notification.mail.MailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记录所有「发出」的邮件，不做任何网络交互。
 *
 * <p>用 {@code @Primary} 顶掉真实发送器，而不是靠「不配 {@code spring.mail.host}」——
 * 那样得到的是 {@code LoggingMailSender}，它只打日志、拿不到内容，
 * 测不了「信里那条链接能不能用」。
 */
public class RecordingMailSender implements MailSender {

    /** 从链接里抠出令牌，模拟用户点开邮件。 */
    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_\\-%]+)");

    // 发信在 mail-* 线程上，断言在测试线程上，必须是并发安全的容器
    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(MailMessage message) {
        sent.add(message);
    }

    /** 清空已记录的邮件。每个用例开始前调用。 */
    public void clear() {
        sent.clear();
    }

    /**
     * 轮询直到攒够 {@code expected} 封或超时。
     *
     * <p>发信是 {@code @Async} 的，断言时它可能还没跑完 —— 直接断言会得到一个
     * 时快时慢的测试，而那种测试最后总会被人加上 {@code @Disabled}。
     *
     * @param expected 期望封数
     * @param timeout  最长等待
     * @return 已收到的邮件
     */
    public List<MailMessage> await(int expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (sent.size() < expected && Instant.now().isBefore(deadline)) {
            sleep(20);
        }
        return List.copyOf(sent);
    }

    /**
     * 断言「不该发信」时用：等一段时间，确认始终没有信。
     *
     * <p>不能只在调用后立刻断言为空 —— 异步发信本来就还没开始，
     * 那样的测试即使实现是错的也会通过。
     *
     * @param window 观察窗口
     * @return 全程为空返回 true
     */
    public boolean stayedEmptyFor(Duration window) {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            if (!sent.isEmpty()) {
                return false;
            }
            sleep(20);
        }
        return sent.isEmpty();
    }

    /**
     * 从纯文本正文里取出链接中的令牌。
     *
     * <p>刻意从<b>纯文本</b>而不是 HTML 里取：这样顺带钉住了「纯文本正文必须含完整
     * 可点 URL」这条约束 —— 命令行邮件客户端只渲染那一段。
     *
     * @param mail 邮件
     * @return 令牌明文
     * @throws IllegalStateException 正文里没有带 token 的链接
     */
    public static String tokenFrom(MailMessage mail) {
        Matcher matcher = TOKEN_IN_LINK.matcher(mail.text());
        if (!matcher.find()) {
            throw new IllegalStateException("纯文本正文里没有带 token 的链接: " + mail.subject());
        }
        return matcher.group(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 把记录器装进测试上下文。用 {@code @Import(RecordingMailSender.Configuration.class)} 引入。 */
    @TestConfiguration
    public static class Configuration {

        @Bean
        @Primary
        public RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }
    }

}
