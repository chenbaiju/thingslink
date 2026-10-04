package com.things.link.ingestion.application;

import java.util.UUID;

/** 分享合同第4节：独立于账号的跨实例两连接租约，依赖故障不得本机降级。 */
public interface DashboardShareConnectionLease {
    /** 仅真实能力确权后申请；满额60056，依赖故障60055。 */
    Lease acquire(UUID shareId, String connectionId);
    /** 只续尚未到期成员；丢失返回false，Redis失败抛60055，均应关闭连接。 */
    boolean renew(Lease lease);
    /** 幂等尽力释放；Redis故障由TTL保守回收，不阻塞本机清理。 */
    void release(Lease lease);
    /** 内部成员不含secret/hash，跨节点同名物理连接也必须唯一。 */
    record Lease(UUID shareId, String member) { }
}
