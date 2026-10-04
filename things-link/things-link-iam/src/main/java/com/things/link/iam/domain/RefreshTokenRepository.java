package com.things.link.iam.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 刷新令牌仓储。
 *
 * <p>所有方法都以<b>哈希</b>而非明文为入参或查询条件。仓储层看不到明文是刻意的：
 * 哪怕将来有人在这里加一条 debug 日志，也泄露不出可用的凭据。
 */
public interface RefreshTokenRepository {

    /**
     * 登记一个新签发的刷新令牌。
     *
     * @param token     令牌记录
     * @param tokenHash 令牌明文的 SHA-256
     * @param userAgent 客户端 User-Agent，可为 {@code null}
     * @param clientIp  客户端 IP，可为 {@code null}
     */
    void save(RefreshToken token, byte[] tokenHash, String userAgent, String clientIp);

    /**
     * 按哈希查找令牌。
     *
     * <p>返回的记录<b>可能已撤销或已被轮换</b> —— 调用方必须自行判断状态。
     * 仓储不在这里过滤：复用检测恰恰需要看到那些不可用的记录，
     * 过滤掉的话「令牌被复用」和「令牌根本不存在」就无法区分了。
     *
     * @param tokenHash 令牌明文的 SHA-256
     * @return 匹配的记录
     */
    Optional<RefreshToken> findByHash(byte[] tokenHash);

    /**
     * 标记某令牌已被轮换。
     *
     * @param id         被轮换的令牌 ID
     * @param replacedBy 取代它的新令牌 ID
     */
    void markRotated(UUID id, UUID replacedBy);

    /**
     * 撤销整族令牌。
     *
     * <p>用于退出登录与复用检测。已撤销的记录不会被重复更新
     * （{@code revoked_at IS NULL} 条件），撤销时刻因此保持为首次撤销的时间。
     *
     * @param familyId  轮换族 ID
     * @param revokedAt 撤销时刻
     * @return 实际撤销的条数
     */
    int revokeFamily(UUID familyId, Instant revokedAt);

    /**
     * 撤销某账号的<b>全部</b>会话。
     *
     * <p>用于密码重置。重置密码的典型场景就是「账号可能已经被别人拿到了」——
     * 只换口令而不踢掉现有会话的话，攻击者手里的刷新令牌照样能一直续期，
     * 重置这个动作等于没做。
     *
     * <p>与 {@link #revokeFamily} 的区别是范围：那个只作废一条轮换链（一台设备），
     * 这个作废这个人的所有设备。副作用是用户自己也会被全部登出，这是对的 ——
     * 他刚设了新口令，重新登一次是他预期之内的事。
     *
     * @param accountId 账号 ID
     * @param revokedAt 撤销时刻
     * @return 实际撤销的条数
     */
    int revokeAllForAccount(UUID accountId, Instant revokedAt);

    /**
     * 限批删除已超过保留窗口的过期令牌。
     *
     * <p>过期令牌已不能刷新，也不再参与复用检测；限批清理避免长期运行后会话表无限增长，
     * 同时避免一次大删除长时间占用数据库连接。
     *
     * @param cutoff 严格早于该时刻的令牌才可删除
     * @param maximumRows 本轮最大删除条数
     * @return 实际删除条数
     */
    int deleteExpiredBefore(Instant cutoff, int maximumRows);

}
