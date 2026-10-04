package com.things.link.iam.application;

import com.things.link.iam.domain.EmailVerificationPurpose;

import java.time.Instant;
import java.util.UUID;

/**
 * 「需要给某个邮箱发一封验证信」的进程内事件。
 *
 * <h2>为什么要绕一层事件，而不是签发完直接发信</h2>
 * 签发发生在事务里（要和账号创建同生共死），发信不能在事务里 —— 事务回滚不会把
 * 已经发出去的邮件收回来，用户会收到一封指向不存在账号的验证信。
 *
 * <p>事件让这两件事解耦：发布在事务内，投递由
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 推迟到提交之后。
 * 事务回滚时监听器根本不会被调用 —— 这正是要的行为，而且不需要调用方记得什么。
 *
 * <h2>它带着令牌明文，因此不得离开进程</h2>
 * 这是一个 Spring 的进程内事件，不是消息总线上的消息。<b>不要把它发到 Kafka，
 * 也不要序列化进任何日志或存储</b> —— {@code rawToken} 一旦落地，
 * 「令牌只存哈希」这条设计就白做了。
 *
 * @param accountId 账号 ID
 * @param email     收件地址，即本次要证实的邮箱
 * @param purpose   用途。<b>决定发哪一封信</b>：注册验证与密码重置的正文、链接落点
 *                  与措辞完全不同，混用会让用户收到一封说不通的信
 * @param rawToken  令牌明文，只用于拼进邮件里的链接
 * @param expiresAt 过期时刻，用于在信里告诉用户链接何时失效
 */
public record EmailVerificationRequested(
        UUID accountId,
        String email,
        EmailVerificationPurpose purpose,
        String rawToken,
        Instant expiresAt) {
}
