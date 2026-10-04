package com.things.link.ota.domain;

import java.util.Optional;
import java.util.UUID;

/** 健康、确认许可与成功原子图，所有写操作参与现有真实事务。 */
public interface OtaConfirmationRepository {
    /** 只取实际进入健康阶段的已采用进度，不能取任意后续观察的启动标识。 */
    Optional<UUID> candidateBoot(UUID jobId, int attemptNo);
    /** 原健康序号事实，用于不更新时间的只读重放。 */
    Optional<OtaHealthReceipt> findHealth(UUID jobId, int attemptNo, long healthSeq);
    /** 首条健康观察，作为稳定窗口的不可变起点。 */
    Optional<OtaHealthReceipt> firstHealth(UUID jobId, int attemptNo);
    /** 最高已观察健康序号，重放不能刷新窗口。 */
    Optional<OtaHealthReceipt> latestHealth(UUID jobId, int attemptNo);
    /** 原修订CAS与健康事实、可选确认阶段和唯一许可同事务。 */
    boolean acceptHealth(OtaJobProgressRepository.Context expected, OtaHealthReceipt receipt,
            String nextStatus, String reason, OtaCommitPermit permit);
    /** 原尝试唯一许可，历史字节和期限不更新。 */
    Optional<OtaCommitPermit> findPermit(UUID jobId, int attemptNo);
    /** 存在实际网络前发送预留；不声称网络必然发生或设备必然收到。 */
    boolean hasSendReservation(UUID permitId);
    /** 原设备提交回执身份，重复回执不重做转换。 */
    Optional<OtaCommitReceipt> findCommitReceipt(UUID deviceId, UUID receiptId);
    /** 真实提交回执、设备确认事实和成功或恢复责任同事务，不能先单独返回成功。 */
    boolean acceptCommit(OtaJobProgressRepository.Context expected, OtaCommitReceipt receipt,
            String nextStatus, String reason, UUID deviceReceiptId);
}
