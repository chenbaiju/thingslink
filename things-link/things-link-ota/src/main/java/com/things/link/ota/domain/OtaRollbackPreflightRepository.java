package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** 原恢复责任的只读回退预检，加入已经锁定完整图的业务事务。 */
public interface OtaRollbackPreflightRepository {
    /** 可信跨域只读候选，后续必须重新建立实际范围并锁完整图。 */
    Optional<OtaJobProgressRepository.Context> nextCandidate();
    /** 当前已失去查询资格的候选退避，不占用或修改任何查询期限。 */
    boolean deferCandidate(OtaJobProgressRepository.Context expected);
    /** 独立到期竞争，已消费或围栏窗口不可再次采用。 */
    boolean expireQuery(UUID queryId);
    /** 真实恢复修订、来源、原授权和五分钟冷却满足时创建固定查询；不修改作业修订。 */
    boolean createQuery(OtaJobProgressRepository.Context expected, OtaRollbackPreflightQuery query);
    /** 当前真实作用域内读取历史不可变查询。 */
    Optional<OtaRollbackPreflightQuery> findQuery(UUID queryId);
    /** 当前控制头指向的查询，不把历史窗口当作当前能力。 */
    Optional<OtaRollbackPreflightQuery> currentQuery(UUID jobId, int attemptNo);
    /** 原设备报告标识的不可变观察，用于先验证身份再只读重放。 */
    Optional<OtaRollbackPreflightResult> findReport(UUID deviceId, UUID reportId);
    /** 一个挑战已接纳的唯一报告，用于在业务层拒绝换报告标识重用挑战。 */
    Optional<OtaRollbackPreflightResult> findReportForQuery(UUID queryId);
    /** 真实网络前发送预留存在，不声明网络必然发生。 */
    boolean hasSendReservation(UUID queryId);
    /** 已持图锁事务内的时钟及窗口预检，后续写入仍必须执行最终CAS。 */
    boolean adoptionAllowed(OtaJobProgressRepository.Context expected, UUID queryId, Instant brokerReceivedAt);
    /** 原报告、历史预检裁决、查询消费和审计同事务；不改变任何设备或作业状态。 */
    boolean acceptReport(OtaJobProgressRepository.Context expected, OtaRollbackPreflightReceipt receipt,
            String disposition, String reason, byte[] qualificationCanonical);
    /** 当前查询的报告；旧查询迟到不得覆盖当前资格。 */
    Optional<OtaRollbackPreflightResult> latestReport(UUID jobId, int attemptNo);
    /** 全部查询已观察的最大提交计数；空值不解释为零。 */
    OptionalLong maxObservedCommitted(UUID jobId, int attemptNo);
}
