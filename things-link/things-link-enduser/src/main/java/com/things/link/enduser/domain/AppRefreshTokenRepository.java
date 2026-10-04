package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 终端用户（App）刷新令牌仓储。
 *
 * <p>所有方法都以<b>哈希</b>而非明文为入参或查询条件。仓储层看不到明文是刻意的：
 * 哪怕将来有人在这里加一条 debug 日志，也泄露不出可用的凭据。
 *
 * <p><b>本表豁免 RLS</b>（上下文建立类例外，见 {@code V20260817_0200} 文件注释）：
 * 刷新请求只带一个令牌，此时租户/项目上下文都还没建立。仓储按 {@code token_hash}
 * 直接命中、不依赖 RLS；命中后的状态复核（{@code app_user.status} 与
 * {@code app_user_role.status}）由应用服务在恢复上下文后完成。
 */
public interface AppRefreshTokenRepository {

    /**
     * 登记一个新签发的刷新令牌。
     *
     * @param token     令牌记录
     * @param tokenHash 令牌明文的 SHA-256
     */
    void save(AppRefreshToken token, byte[] tokenHash);

    /**
     * 按哈希查找令牌。
     *
     * <p>返回的记录<b>可能已撤销或已被轮换</b> —— 调用方必须自行判断状态。仓储不在这里
     * 过滤：复用检测恰恰需要看到那些不可用的记录，过滤掉的话「令牌被复用」和
     * 「令牌根本不存在」就无法区分了。
     *
     * @param tokenHash 令牌明文的 SHA-256
     * @return 匹配的记录
     */
    Optional<AppRefreshToken> findByHash(byte[] tokenHash);

    /**
     * ADR0097：仅标记仍未轮换且未撤销的令牌，调用方必须已持用户会话锁。
     *
     * @param id         被轮换的令牌 ID
     * @param replacedBy 取代它的新令牌 ID
     * @return 实际标记行数，调用方必须要求恰好1行，否则回滚本次签发
     */
    int markRotated(UUID id, UUID replacedBy);

    /**
     * 撤销整族令牌。
     *
     * <p>用于退出登录与复用检测。已撤销的记录不会被重复更新（{@code revoked_at IS NULL}
     * 条件），撤销时刻因此保持为首次撤销的时间。
     * ADR0097：调用方持用户会话锁；复用检测的独立事务只执行本更新，不重新申请用户或项目锁。
     *
     * @param familyId  轮换族 ID
     * @param revokedAt 撤销时刻
     * @return 实际撤销的条数
     */
    int revokeFamily(UUID familyId, Instant revokedAt);

    /**
     * 撤销某终端用户的<b>全部</b>会话。
     *
     * <p>用于改密。改密撤销全部会话的原因与控制台密码重置同构（见 iam 的
     * {@code RefreshTokenRepository#revokeAllForAccount}）：只换口令而不踢掉现有会话，
     * 攻击者手里的刷新令牌照样能一直续期，改密等于没做。
     * ADR0097：原事务必须已持该用户会话锁，禁止并发签发在本更新快照之后追加漏撤销会话。
     *
     * @param appUserId 终端用户 ID
     * @param revokedAt 撤销时刻
     * @return 实际撤销的条数
     */
    int revokeAllForUser(UUID appUserId, Instant revokedAt);

    /**
     * ADR0144：全族均超过保留窗口时，限批删除无前驱引用的链头；活跃族不截断。
     *
     * @param cutoff      严格早于该时刻的令牌才可删除
     * @param maximumRows 本轮最大删除条数
     * @return 实际删除条数
     */
    int deleteExpiredBefore(Instant cutoff, int maximumRows);

}
