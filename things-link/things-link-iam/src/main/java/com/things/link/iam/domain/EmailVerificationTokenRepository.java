package com.things.link.iam.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 邮箱验证令牌仓储。
 *
 * <p>与 {@link RefreshTokenRepository} 一样，所有方法都以<b>哈希</b>而非明文为入参。
 * 仓储层看不到明文是刻意的：哪怕将来有人在这里加一条 debug 日志，
 * 也泄露不出一个可用的重置链接。
 */
public interface EmailVerificationTokenRepository {

    /**
     * 登记一个新签发的令牌。
     *
     * @param token     令牌记录，{@code consumedAt} 必须为 {@code null}
     * @param tokenHash 令牌明文的 SHA-256
     * @param requestIp 请求来源 IP，可为 {@code null}。仅用于排查，可伪造
     */
    void save(EmailVerificationToken token, byte[] tokenHash, String requestIp);

    /**
     * 原子地消费一个令牌：命中且仍可用时标记为已消费，并返回该记录。
     *
     * <p><b>一次性语义由这一条 UPDATE 的影响行数仲裁，不是由调用方判断的。</b>
     * 拆成「先查、判断可用、再更新」的话，同一个链接被快速点两次（邮件客户端预取、
     * 用户手抖双击都会造成）就会被消费两次。这与注册时把邮箱唯一性交给唯一索引
     * 是同一个道理：<b>数据库是唯一的仲裁者，应用层的判断只是提前告知。</b>
     *
     * <p>条件里同时包含 {@code purpose}：一个「验证邮箱」的令牌不能被拿去重置密码，
     * 理由见 {@link EmailVerificationPurpose}。
     *
     * @param tokenHash 令牌明文的 SHA-256
     * @param purpose   期望的用途
     * @param now       当前时刻，用于判过期
     * @return 消费成功的记录；令牌不存在、用途不符、已过期或已被消费时为空
     *         —— <b>这四种情况刻意不区分</b>，它们对用户的处置完全一样（重新获取链接），
     *         而区分开会告诉一个拿着随机令牌试探的人「这个令牌真实存在过」
     */
    Optional<EmailVerificationToken> consume(byte[] tokenHash, EmailVerificationPurpose purpose, Instant now);

    /**
     * 作废某账号某用途下全部尚未使用的令牌。
     *
     * <p>用于密码重置：签发新的重置链接时作废旧的，重置成功后作废其余的。
     * <b>注册验证不调用它</b> —— 那里的决定是「重发不作废上一封」，
     * 理由见 {@code EmailVerificationService} 与迁移 {@code V20260803_0400} 的注释。
     *
     * <p>已消费的记录<b>不受影响</b>：排查账号异常时要能看出「这个链接确实被用过」，
     * 把它改写成「已作废」等于抹掉那个事实。
     *
     * @param accountId 账号 ID
     * @param purpose   用途
     * @param now       作废时刻
     * @return 实际作废的条数
     */
    int invalidateOutstanding(UUID accountId, EmailVerificationPurpose purpose, Instant now);

    /**
     * 限批删除已超过保留窗口的过期验证令牌。
     *
     * <p>验证、重置和已作废事实保留一段排查窗口后即可清理；不限制批量会让积压清理形成大事务。
     *
     * @param cutoff 严格早于该时刻的令牌才可删除
     * @param maximumRows 本轮最大删除条数
     * @return 实际删除条数
     */
    int deleteExpiredBefore(Instant cutoff, int maximumRows);

}
