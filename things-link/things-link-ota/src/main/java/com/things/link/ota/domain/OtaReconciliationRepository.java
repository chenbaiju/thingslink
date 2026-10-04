package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 原恢复责任的只读查询与独立安全收束，加入已经锁定完整图的业务事务。 */
public interface OtaReconciliationRepository {
    /** 可信跨域只读候选，后续必须重新建立实际范围并锁完整图。 */
    Optional<OtaJobProgressRepository.Context> nextCandidate();
    /** 当前已失去查询资格的候选退避，不占用或修改任何查询期限。 */
    boolean deferCandidate(OtaJobProgressRepository.Context expected);
    /** 独立到期竞争，已消费或围栏窗口不可再次采用。 */
    boolean expireQuery(UUID queryId);
    /** 真实恢复修订、来源、原许可和五分钟冷却满足时创建固定查询；不修改作业修订。 */
    boolean createQuery(OtaJobProgressRepository.Context expected, OtaReconciliationQuery query);
    /** 当前真实作用域内读取历史不可变查询。 */
    Optional<OtaReconciliationQuery> findQuery(UUID queryId);
    /** 当前控制头指向的查询，不把历史窗口当作当前能力。 */
    Optional<OtaReconciliationQuery> currentQuery(UUID jobId, int attemptNo);
    /** 原设备报告标识的不可变观察，用于先验证身份再只读重放。 */
    Optional<OtaReconciliationReceipt> findReport(UUID deviceId, UUID reportId);
    /** 一个挑战已接纳的唯一报告，用于在业务层拒绝换报告标识重用挑战。 */
    Optional<OtaReconciliationReceipt> findReportForQuery(UUID queryId);
    /** 真实网络前发送预留存在，不声明网络必然发生。 */
    boolean hasSendReservation(UUID queryId);
    /** 已持图锁事务内的时钟及窗口预检，后续写入仍必须执行最终CAS。 */
    boolean adoptionAllowed(OtaJobProgressRepository.Context expected, UUID queryId, Instant brokerReceivedAt);
    /** 独立设备证明、报告、作业成功历史及取消收束同事务；失败使调用方撤销设备写入。 */
    boolean acceptReport(OtaJobProgressRepository.Context expected, OtaReconciliationReceipt receipt,
            boolean succeeded, String reason, UUID deviceReceiptId);
}
