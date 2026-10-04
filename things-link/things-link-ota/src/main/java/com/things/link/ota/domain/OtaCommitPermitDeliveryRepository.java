package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 固定提交许可的真实有界交付；未知只可重传同一许可字节。 */
public interface OtaCommitPermitDeliveryRepository {
    /** 可信单条领取，业务事务随后建立原范围。 */
    Optional<Claim> claimOne();
    /** 当前真实交付能力，不能由调用方伪造租户范围。 */
    Optional<Claim> authoritativeClaim(UUID permitId, UUID token);
    /** 原单次观察能力；失租后仍允许保留晚到观察。 */
    Optional<Transport> authoritativeTransport(UUID id, UUID reservationToken);
    /** 先持久预留一次发送，重试固定原许可和首次路由。 */
    Optional<Transport> reserveSend(Claim claim, String topic);
    /** 暂时不能发送只退避，不扣传输次数、不改变原期限。 */
    boolean deferIneligible(Claim claim, String reason);
    /** 只追加首次最终传输观察，不等于设备已提交。 */
    boolean recordObservation(Transport transport, String outcome, Integer httpStatus, String errorCode);
    /** 仅当前租约可采用真实观察改变交付头。 */
    boolean settleCurrent(Claim claim, UUID transportId);
    /** 失租在途记录未知并安排剩余预算，不能复活旧发送能力。 */
    boolean recoverExpired(Claim claim);
    /** 原期限或次数耗尽后持久关闭交付，不伪造设备终态。 */
    boolean exhaustDue(Claim claim, String reason);
    /** 当前完整资格失效只暂停当前活动并关闭新发送。 */
    boolean pauseSecurity(Claim claim, String reason);

    /** 原许可、当前交付修订与实际租约，正文从不可变许可取得。 */
    record Claim(OtaCommitPermit permit, long jobRevision, long revision, String status, int transportCount,
            UUID leaseToken, Instant leaseUntil, UUID activeTransportId, String topic) { }
    /** 原传输能力与当前交付租约分离；晚回执不恢复发送权。 */
    record Transport(UUID id, OtaCommitPermit permit, int transportNo, UUID reservationToken,
            UUID deliveryLeaseToken, Instant reservedAt, String topic) { }
}
