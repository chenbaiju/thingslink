package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 只读恢复查询的真实有界交付；未知只可重传同一查询字节。 */
public interface OtaReconciliationDeliveryRepository {
    /** 可信单条领取，业务事务随后建立原范围。 */
    Optional<Claim> claimOne();
    /** 当前真实交付能力，不能由调用方伪造租户范围。 */
    Optional<Claim> authoritativeClaim(UUID queryId, UUID token);
    /** 原单次观察能力；失租后仍允许保留晚到观察。 */
    Optional<Transport> authoritativeTransport(UUID id, UUID reservationToken);
    /** 先持久预留一次发送，重试固定原查询和首次路由。 */
    Optional<Transport> reserveSend(Claim claim, String topic);
    /** 暂时不能发送只退避，不扣传输次数、不改变原期限。 */
    boolean deferIneligible(Claim claim, String reason);
    /** 只追加首次最终传输观察，不等于设备已提交。 */
    boolean recordObservation(Transport transport, String outcome, Integer httpStatus, String errorCode);
    /** 仅当前租约可采用真实观察改变交付头。 */
    boolean settleCurrent(Claim claim, UUID transportId);
    /** 失租在途记录未知并安排剩余预算，不能复活旧发送能力。 */
    boolean recoverExpired(Claim claim);
    /** 原期限或次数耗尽仅关闭发送交付；原报告窗口仍受独立到期围栏控制，不改变作业或活动状态。 */
    boolean exhaustDue(Claim claim, String reason);

    /** 原查询、当前交付修订与实际租约，正文从不可变查询取得。 */
    record Claim(OtaReconciliationQuery query, long revision, String status, int transportCount,
            UUID leaseToken, Instant leaseUntil, UUID activeTransportId, String topic) { }
    /** 原传输能力与当前交付租约分离；晚回执不恢复发送权。 */
    record Transport(UUID id, OtaReconciliationQuery query, int transportNo, UUID reservationToken,
            UUID deliveryLeaseToken, Instant reservedAt, String topic) { }
}
