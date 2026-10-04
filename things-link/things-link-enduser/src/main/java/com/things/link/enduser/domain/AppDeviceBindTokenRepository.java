package com.things.link.enduser.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 一次性设备绑定令牌仓储（ADR 0037、G2-A1b）。
 *
 * <p>所有方法只接受哈希，不接受明文。表走项目轴 RLS，调用方必须先建立与令牌所属项目
 * 一致的租户/项目上下文；显式 {@code projectId} 条件再提供第二道范围限制。
 *
 * <p>G2-A1c/A1e 在本契约上复用行锁、尝试递增与消费 CAS。调用方必须把消费 CAS 与设备
 * 关系变更放在同一事务，避免先消费后绑定或转移失败留下不可恢复的半状态（ADR 0037）。
 */
public interface AppDeviceBindTokenRepository {

    /**
     * 保存新签发的令牌事实。
     *
     * @param token     不含秘密的令牌元数据
     * @param tokenHash 令牌明文的 32 字节 SHA-256
     * @return 保存成功为 true；极低概率哈希碰撞时为 false
     */
    boolean save(AppDeviceBindToken token, byte[] tokenHash);

    /**
     * 在当前项目内按哈希读取令牌，包括已消费或已过期记录。
     *
     * <p>不能在仓储中过滤不可用记录：后续消费服务需要区分重放、过期、尝试耗尽与从未
     * 存在，才能给出确定结果并留下正确审计。是否向外合并错误语义由 API 层决定。
     *
     * @param projectId 项目 ID
     * @param tokenHash 令牌明文的 32 字节 SHA-256
     * @return 匹配的令牌事实
     */
    Optional<AppDeviceBindToken> findByProjectAndHash(UUID projectId, byte[] tokenHash);

    /**
     * 按项目与哈希锁定令牌事实，串行化同一令牌的并发消费。
     *
     * @param projectId 项目 ID
     * @param tokenHash 令牌 SHA-256
     * @return 当前项目可见的令牌事实
     */
    Optional<AppDeviceBindToken> findByProjectAndHashForUpdate(UUID projectId, byte[] tokenHash);

    /**
     * 为一次已命中哈希的业务复核递增尝试次数。
     *
     * @param projectId 项目 ID
     * @param tokenId   令牌 ID
     * @return 实际更新行数；已耗尽或状态漂移时为 0
     */
    int incrementAttempt(UUID projectId, UUID tokenId);

    /**
     * 以条件更新记录成功消费。
     *
     * @param projectId 项目 ID
     * @param tokenId   令牌 ID
     * @param appUserId 消费人
     * @param consumedAt 消费时刻
     * @return 实际更新行数；并发已消费时为 0
     */
    int consume(UUID projectId, UUID tokenId, UUID appUserId, java.time.Instant consumedAt);
}
