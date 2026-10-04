package com.things.link.project.application;

import java.util.UUID;

/**
 * 租户 WebSocket 并发连接的跨实例租约端口。
 *
 * <p>套餐连接数按租户共享，不能由任一实例的本机 Map 单独判定。实现使用带过期时刻的
 * Redis 租约；Redis 只承担即时保护，不是连接事实的永久账本。</p>
 */
public interface RealtimeConnectionLease {

    /**
     * 为一个已认证会话申请租户共享连接名额。
     *
     * @param ownerTenantId 项目归属租户 ID，不得使用跨租户协作者 JWT 的自身租户 ID
     * @param connectionId 本机 WebSocket 会话 ID
     * @param connectionLimit 套餐并发连接上限；NULL 仅表示权威策略显式不限，0 表示禁用
     * @return 租约判定结果
     */
    LeaseDecision acquire(ConnectionLease lease, Long connectionLimit);

    /**
     * 以实现自身的实例前缀生成稳定租约标识，登记、心跳和释放必须复用同一 member。
     *
     * @param ownerTenantId 项目归属租户 ID
     * @param connectionId 本机 WebSocket 会话 ID
     * @return 尚未登记的稳定租约标识
     */
    ConnectionLease create(UUID ownerTenantId, String connectionId);

    /**
     * 续期已登记连接。已有连接即便续期时租户已超额也不能被踢下线，否则会放大重连风暴。
     *
     * @param lease 已登记租约
     * @return 明确续期结果；Console/App保留既有降级，分享必须在LOST或UNAVAILABLE时关闭
     */
    RenewDecision renew(ConnectionLease lease);

    /**
     * 尽力释放租约。Redis 故障时由租约过期时间兜底，不能阻塞本机会话清理。
     *
     * @param lease 待释放租约
     */
    void release(ConnectionLease lease);

    /** 租约申请的有限结果集合，用于固定故障策略。 */
    enum LeaseDecision {
        /** Redis 已登记连接。 */
        ACQUIRED,
        /** 租户套餐名额已满或套餐明确为 0。 */
        REJECTED,
        /** Redis 不可用；Console/App回退本机有限上限；分享拒绝建立。 */
        UNAVAILABLE
    }

    /** 已登记租约的续期结果。 */
    enum RenewDecision {
        /** Redis 确认成员仍存在并延长到期时刻。 */
        RENEWED,
        /** 成员已经过期或不存在；不得绕过 acquire 把它重新加入。 */
        LOST,
        /** Redis 不可用；Console/App等待下次心跳，分享立即关闭。 */
        UNAVAILABLE
    }

    /**
     * 跨实例租约标识。
     *
     * @param ownerTenantId 项目归属租户 ID
     * @param member Redis ZSET 中由实例与连接共同组成的唯一成员
     */
    record ConnectionLease(UUID ownerTenantId, String member) {
    }
}
