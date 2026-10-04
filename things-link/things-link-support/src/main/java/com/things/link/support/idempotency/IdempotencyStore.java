package com.things.link.support.idempotency;

import java.util.Optional;

/**
 * 幂等记录的存储契约。
 *
 * <p>当前由 JdbcIdempotencyStore 以数据库唯一约束仲裁。历史 Redis 前置缓存设想
 * 未实施，必要性与失效合同见 ARCHITECTURE_GAPS.md GAP-TODO-09；不得以 Redis
 * 替代数据库权威或绕过 ADR0102 的认证写请求围栏。
 */
public interface IdempotencyStore {

    /**
     * 尝试抢占执行权。
     *
     * <p>这是整个幂等机制的核心操作，必须是<b>原子</b>的 —— 由数据库的唯一约束
     * 仲裁，而不是「先查后插」。后者在并发下有竞态窗口：两个请求可能都查到不存在，
     * 然后都去执行业务。
     *
     * @param record 待写入的 IN_PROGRESS 记录
     * @return 抢占成功返回 true；已存在同键记录返回 false
     */
    boolean tryAcquire(IdempotencyRecord record);

    /**
     * 按幂等键查已有记录。
     *
     * @param tenantId       租户 ID，可为 null
     * @param projectId      项目 ID，可为 null
     * @param idempotencyKey 幂等键
     * @param method         HTTP 方法
     * @param path           请求路径
     * @return 已有记录
     */
    Optional<IdempotencyRecord> find(java.util.UUID tenantId,
                                     java.util.UUID projectId,
                                     String idempotencyKey,
                                     String method,
                                     String path);

    /**
     * 业务执行成功后把抢占转换为完成墓碑。
     *
     * <p>公共层不保存响应。重复请求必须重新经过认证与范围过滤，再收到明确的
     * “已完成但不可重放”结果；需要稳定返回原业务事实的端点由领域事务实现幂等。
     *
     * @param id 记录 ID
     */
    void complete(java.util.UUID id);

    /**
     * 释放抢占，使该幂等键可以被重新执行。
     *
     * <p>业务执行失败时必须调用。<b>不释放的后果是客户端永远拿不到重试机会</b> ——
     * 记录卡在 IN_PROGRESS，后续重试全部收到 409，直到记录过期。
     *
     * @param id 记录 ID
     */
    void release(java.util.UUID id);

    /**
     * 限批删除已经越过重试窗口的记录，避免高流量下单次清理形成长事务。
     *
     * @param maximumRows 单批上限
     * @return 实际删除行数
     */
    int deleteExpired(int maximumRows);

}
