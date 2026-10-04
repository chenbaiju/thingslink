package com.things.link.iam.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 邮箱验证令牌记录。
 *
 * <p>这里<b>没有明文字段</b>，也不该有。明文只在签发的那一次出现（写进邮件），
 * 此后服务端只持有它的 SHA-256。
 *
 * @param id         令牌 ID（UUIDv7）
 * @param accountId  归属账号
 * @param purpose    用途，决定有效期与可执行的操作，见 {@link EmailVerificationPurpose}
 * @param email      签发时要证实的邮箱地址。<b>改邮箱场景下与 {@code sys_account.email} 不同</b>，
 *                   消费时必须比对本字段，理由见迁移 {@code V20260803_0300} 的列注释
 * @param createdAt  签发时刻
 * @param expiresAt  过期时刻
 * @param consumedAt 被消费的时刻，{@code null} 表示尚未使用
 * @param invalidatedAt 被主动作废的时刻，{@code null} 表示未作废。与 {@code consumedAt}
 *                   不同：那是「用过了」，这是「还没用就作废了」——重置密码在签发新令牌
 *                   与重置成功后都会作废同账号其余未用令牌（迁移 V20260803_0400）
 */
public record EmailVerificationToken(
        UUID id,
        UUID accountId,
        EmailVerificationPurpose purpose,
        String email,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt,
        Instant invalidatedAt) {

    /**
     * 是否仍可使用。
     *
     * <p><b>这个方法不能用来做并发安全的一次性判断。</b>「先 isUsable 再 UPDATE」
     * 在并发下会让同一个链接被消费两次 —— 两个请求都读到未消费。真正的仲裁是
     * 仓储里那条带 {@code consumed_at IS NULL} 条件的 UPDATE 的影响行数
     * （与注册时邮箱唯一性交给唯一索引是同一个道理）。
     *
     * <p>本方法只用于日志与排查：回答「当时这条记录是什么状态」。
     *
     * @param now 当前时刻
     * @return 未消费、未作废且未过期返回 true
     */
    public boolean isUsable(Instant now) {
        return consumedAt == null && invalidatedAt == null && now.isBefore(expiresAt);
    }

}
