package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 通知交付事务仓储；不把Broker回执解释为设备执行或下载授权。 */
public interface OtaNotificationRepository {
    /** 固定条件单条可信领取，无调用方scope。 */
    Optional<Claim> claimOne();
    /** 精确有效能力取得权威范围，随后才能建立RLS。 */
    Optional<Claim> authoritativeClaim(UUID eventId, UUID token);
    /** 原传输能力只允许记录该尝试观察，期限后仍可附加迟到回执。 */
    Optional<Transport> authoritativeTransport(UUID id, UUID reservationToken);
    /** 网络前预算预留，首次冻结路线及规范正文，重试必须字节一致。 */
    Optional<Transport> reserveSend(Claim claim, String topic, byte[] canonical);
    /** 普通报告不合格只退避，不消耗HTTP次数。 */
    boolean deferIneligible(Claim claim, String reason);
    /** 单次最终观察：BROKER_ACCEPTED、REJECTED或UNKNOWN。 */
    boolean recordObservation(Transport transport, String outcome, Integer httpStatus, String errorCode);
    /** 只有当前能力能依据观察推进头，失租恢复一律视为未知。 */
    boolean settleCurrent(Claim claim, UUID transportId);
    /** 失租在途只标记未知并安排剩余预算，不能重发或改已有暂停类型。 */
    boolean recoverExpired(Claim claim);
    /** 原期限到或预算用尽持久收束，不宣称作业终态。 */
    boolean exhaustDue(Claim claim, String reason);
    /** 精确DISPATCHED交付租约保护的当前活动安全暂停。 */
    boolean pauseSecurity(Claim claim, String reason);

    /** 数据库领取事实；正文数组可空且防御复制。
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param campaignId 权威活动
     * @param jobId 权威作业
     * @param deviceId 权威设备
     * @param firmwareId 固件身份
     * @param eventId 稳定通知身份
     * @param credentialVersion 原通知身份代际
     * @param jobAttemptNo 原作业尝试号
     * @param manifestSha256 原清单摘要
     * @param revision 当前交付修订
     * @param status 当前交付状态
     * @param transportCount 已预留次数
     * @param leaseToken 当前交付租约
     * @param leaseUntil 租约期限
     * @param deadline 原作业期限
     * @param activeTransportId 当前传输，可空
     * @param topic 首次冻结路由，可空
     * @param canonical 首次冻结正文，可空
     */
    record Claim(UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId, UUID firmwareId,
            UUID eventId, long credentialVersion, int jobAttemptNo, String manifestSha256, long revision, String status, int transportCount, UUID leaseToken, Instant leaseUntil,
            Instant deadline, UUID activeTransportId, String topic, byte[] canonical) {
        /** 输入数组防御复制。 */
        public Claim { canonical = canonical == null ? null : canonical.clone(); }
        /** 返回独立正文副本。 */
        @Override public byte[] canonical() { return canonical == null ? null : canonical.clone(); }
    }
    /** 一次不可复用传输预留能力。
     * @param id 传输身份
     * @param eventId 原通知身份
     * @param tenantId 租户
     * @param projectId 项目
     * @param campaignId 活动
     * @param jobId 作业
     * @param transportNo 本次传输序号
     * @param reservationToken 仅本次回执能力
     * @param deliveryLeaseToken 预留时的交付租约，与观察能力分离
     * @param deadline 原作业期限
     * @param reservedAt 预留时间
     * @param topic 固定路由
     * @param canonical 固定正文
     */
    record Transport(UUID id, UUID eventId, UUID tenantId, UUID projectId, UUID campaignId, UUID jobId,
            int transportNo, UUID reservationToken, UUID deliveryLeaseToken, Instant deadline, Instant reservedAt, String topic, byte[] canonical) {
        /** 输入正文防御复制。 */
        public Transport { canonical = canonical.clone(); }
        /** 输出正文防御复制。 */
        @Override public byte[] canonical() { return canonical.clone(); }
    }
}
