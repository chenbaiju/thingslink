package com.things.link.iam.application;

import com.things.link.support.notification.mail.MailDeliveryException;
import com.things.link.support.notification.mail.MailExecutorConfiguration;
import com.things.link.support.notification.mail.MailMessage;
import com.things.link.support.notification.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * 把 {@link EmailVerificationRequested} 变成一封真实的邮件。
 *
 * <h2>AFTER_COMMIT + @Async 两个注解缺一不可</h2>
 * <ul>
 *   <li><b>{@code AFTER_COMMIT}</b>：在事务里发信，回滚之后邮件也收不回来 ——
 *       用户会拿到一封指向不存在账号的验证链接。注册事务会因为邮箱唯一索引冲突
 *       而回滚，这不是假想的场景</li>
 *   <li><b>{@code @Async}</b>：AFTER_COMMIT 的监听器仍然跑在<b>原来那个请求线程</b>上，
 *       只是挪到了提交之后。不加 {@code @Async} 的话，注册接口的响应时间照样要
 *       等 SMTP 握手完成 —— 一次几百毫秒起步的网络往返，用户看到的就是点了注册后转圈</li>
 * </ul>
 *
 * <h2>发信失败不重试，也不抛给调用方</h2>
 * 这里已经在事务之外、请求之外，抛异常没有任何人接得住（只会变成异步线程里一条
 * 无人处理的栈）。而这两类邮件都有自助出路：验证信可以点「重发」，
 * 重置信可以再走一次「忘记密码」。
 *
 * <p>为它加一套持久化重试队列的收益很低、复杂度很高 —— 那套东西的正确归宿是
 * S6 的告警通知（架构文档 9 节的 {@code notification_delivery}），
 * 那里的邮件没有人在旁边等着、也没有重发按钮。
 */
@Component
@EnableConfigurationProperties(ConsoleProperties.class)
public class EmailVerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationMailer.class);

    /**
     * 控制台里承接验证链接的页面路径。
     *
     * <p>与前端 {@code staticRoutes.ts} 里的静态路由一一对应，改一处必须改两处 ——
     * 不一致的表现是用户点开邮件得到 404，而后端日志里一切正常。
     */
    private static final String VERIFY_PATH = "/auth/verify-email";

    /** 承接重置链接的页面路径。同样与前端路由一一对应。 */
    private static final String RESET_PATH = "/auth/reset-password";

    private final MailSender mailSender;
    private final String consoleBaseUrl;

    public EmailVerificationMailer(MailSender mailSender, ConsoleProperties consoleProperties) {
        this.mailSender = mailSender;
        this.consoleBaseUrl = consoleProperties.baseUrl();
    }

    /**
     * 按用途发出对应的那封信。
     *
     * @param event 签发事件，携带令牌明文
     */
    @Async(MailExecutorConfiguration.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationRequested(EmailVerificationRequested event) {
        // switch 表达式且**没有 default 分支**：EmailVerificationPurpose 加了新值
        // 而这里没跟上时，编译会直接失败。这正是想要的 —— 漏掉一个用途的表现
        // 本来会是「静默不发信」，那种缺陷没有人会及时发现
        MailMessage message = switch (event.purpose()) {
            case REGISTER_VERIFY -> verificationMail(event);
            case RESET_PASSWORD -> passwordResetMail(event);
            // G3-LOCAL-11c：公开邮箱保持不可变；CHANGE_EMAIL仅历史预留，不接线。
            // 走到这里说明有人加了签发路径却没加对应的信；发一封「验证邮箱」
            // 会让用户收到一封说不通的信，所以宁可不发并报错
            case CHANGE_EMAIL -> null;
        };

        if (message == null) {
            log.error("没有为用途 {} 定义邮件模板，本次不发信 accountId={}",
                    event.purpose(), event.accountId());
            return;
        }

        try {
            mailSender.send(message);
        } catch (MailDeliveryException e) {
            // 记账号 ID 不记邮箱：这条日志在找回密码场景下会变成一份注册用户清单。
            // 用 warn 不用 error —— 单封信发失败可以自助恢复，
            // 真正需要人介入的是 SmtpMailSender 那边持续失败的情况
            log.warn("邮件发送失败，用户需要自行重试 accountId={} purpose={}",
                    event.accountId(), event.purpose(), e);
        }
    }

    /**
     * 注册验证信。
     *
     * <p>纯文本正文里<b>必须有完整可点的 URL</b>：命令行邮件客户端与纯文本预览只渲染
     * 这一段，链接藏在 HTML 的 {@code <a href>} 里的话这些用户根本点不到。
     *
     * <p>HTML 刻意做得很朴素：没有外链图片、没有 CSS 文件、没有跟踪像素。
     * 事务邮件里每多一个外部资源，就多一分被反垃圾评分扣分的可能，
     * 而验证邮件进了垃圾箱等于注册流程静默失败。
     *
     * @param event 签发事件
     * @return 邮件
     */
    private MailMessage verificationMail(EmailVerificationRequested event) {
        String link = link(VERIFY_PATH, event.rawToken());
        long hours = ceilHours(remaining(event));

        return MailMessage.html(event.email(), "验证你的 ThingsLink 邮箱", """
                你好，

                请打开下面的链接验证你的邮箱地址：

                %s

                链接将在 %d 小时后失效。

                如果这不是你本人的操作，忽略这封邮件即可，你的账号不会有任何变化。

                —— ThingsLink
                """.formatted(link, hours), """
                <div style="font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;font-size:14px;line-height:1.7;color:#1f2329">
                  <p>你好，</p>
                  <p>请点击下面的按钮验证你的邮箱地址：</p>
                  <p><a href="%s" style="display:inline-block;padding:10px 20px;background:#1677ff;color:#fff;text-decoration:none;border-radius:4px">验证邮箱</a></p>
                  <p>按钮无法点击时，请复制以下地址到浏览器打开：<br><span style="color:#646a73">%s</span></p>
                  <p style="color:#646a73">链接将在 %d 小时后失效。如果这不是你本人的操作，忽略这封邮件即可。</p>
                  <p style="color:#646a73">—— ThingsLink</p>
                </div>
                """.formatted(link, link, hours));
    }

    /**
     * 密码重置信。
     *
     * <p>与验证信的差别不只是换个动词：这封信必须明确写出<b>「不是你本人操作就忽略，
     * 你的口令不会改变」</b>。收到一封自己没申请的重置信是真实会发生的事（别人打错
     * 邮箱、或者有人在试探），不写这句会让用户以为账号已经被改掉了，
     * 从而慌乱地去点那个链接 —— 而那正是钓鱼邮件想要的反应。
     *
     * @param event 签发事件
     * @return 邮件
     */
    private MailMessage passwordResetMail(EmailVerificationRequested event) {
        String link = link(RESET_PATH, event.rawToken());
        // 重置令牌只有 30 分钟，按小时算会显示成 0，所以这封信用分钟
        long minutes = ceilMinutes(remaining(event));

        return MailMessage.html(event.email(), "重置你的 ThingsLink 口令", """
                你好，

                我们收到了重置这个账号口令的请求。打开下面的链接设置新口令：

                %s

                链接将在 %d 分钟后失效，且只能使用一次。

                如果这不是你本人的操作，忽略这封邮件即可 —— 你的口令不会有任何改变。

                —— ThingsLink
                """.formatted(link, minutes), """
                <div style="font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;font-size:14px;line-height:1.7;color:#1f2329">
                  <p>你好，</p>
                  <p>我们收到了重置这个账号口令的请求。点击下面的按钮设置新口令：</p>
                  <p><a href="%s" style="display:inline-block;padding:10px 20px;background:#1677ff;color:#fff;text-decoration:none;border-radius:4px">设置新口令</a></p>
                  <p>按钮无法点击时，请复制以下地址到浏览器打开：<br><span style="color:#646a73">%s</span></p>
                  <p style="color:#646a73">链接将在 %d 分钟后失效，且只能使用一次。</p>
                  <p style="color:#646a73">如果这不是你本人的操作，忽略这封邮件即可 —— 你的口令不会有任何改变。</p>
                  <p style="color:#646a73">—— ThingsLink</p>
                </div>
                """.formatted(link, link, minutes));
    }

    /**
     * 拼出带令牌的完整链接。
     *
     * <p>控制台当前使用 {@code createWebHashHistory()}，邮件这种从浏览器地址栏直接进入
     * 的外链必须带 {@code /#}。如果发成 {@code /auth/reset-password?token=...}，
     * 开发服务器虽然会回退到 {@code index.html}，但 Vue Router 看到的是空 hash，
     * 最终落到根路径并被守卫送回登录页。
     *
     * @param path     控制台页面路径
     * @param rawToken 令牌明文
     * @return 完整 URL
     */
    private String link(String path, String rawToken) {
        // 令牌进的是查询串，必须 URL 编码。令牌是 Base64URL（无 + / =），
        // 当前不会产生需要转义的字符 —— 但依赖这一点等于把两处实现绑死了：
        // 哪天 OpaqueToken 换成别的编码，这里会静默产生打不开的链接
        return consoleBaseUrl + "/#" + path + "?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    /**
     * 距离过期还有多久。
     *
     * <p>算「现在到 expiresAt」而不是直接用 {@code purpose.ttl()}：信里写的应当是
     * 用户读到它时的实际剩余时间，而签发与投递之间隔着一次异步调度。
     *
     * @param event 签发事件
     * @return 剩余时长
     */
    private Duration remaining(EmailVerificationRequested event) {
        return Duration.between(Instant.now(), event.expiresAt());
    }

    /**
     * 剩余时长换算成小时，<b>向上取整</b>。
     *
     * <h2>为什么必须向上取整</h2>
     * 截断会让 24 小时的链接在信里写成「23 小时」——签发到投递之间隔着一次异步调度，
     * 哪怕只有 1 秒，剩余就是 23h59m59s，{@code toHours()} 直接抹掉零头。
     * 这是真实发出去的邮件里出现过的错误，不是推演。
     *
     * <p>向上取整之后，用户看到的是他实际拥有的那个整数时长；
     * 投递真的被延迟很久时，这个数仍然会如实变小。
     *
     * @param remaining 剩余时长
     * @return 至少为 1 的小时数
     */
    private static long ceilHours(Duration remaining) {
        return Math.max(1, (remaining.toMinutes() + 59) / 60);
    }

    /**
     * 剩余时长换算成分钟，向上取整。理由同 {@link #ceilHours}：
     * 截断会把 30 分钟的重置链接写成「29 分钟」。
     *
     * @param remaining 剩余时长
     * @return 至少为 1 的分钟数
     */
    private static long ceilMinutes(Duration remaining) {
        return Math.max(1, (remaining.toSeconds() + 59) / 60);
    }

}
