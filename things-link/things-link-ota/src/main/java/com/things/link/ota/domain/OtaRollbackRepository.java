package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** 原子回退持久闭环，普通方法加入已锁完整图的事务；可信候选只定位不授权。 */
public interface OtaRollbackRepository {
    /** 当前RLS范围内设备存在永久方向冲突。 */
    boolean deviceConflicted(UUID deviceId);
    /** 已具原预检报告的原恢复候选。 */
    Optional<OtaJobProgressRepository.Context> nextCandidate();
    /** 候选缺资格持久退避，不能改变作业状态。 */
    boolean deferCandidate(OtaJobProgressRepository.Context expected);
    /** 唯一操作、待执行阶段、历史及出站事实同事务。 */
    boolean create(OtaJobProgressRepository.Context expected, OtaRollbackOperation operation, String reason);
    /** 原操作完整固定证据。 */
    Optional<OtaRollbackOperation> find(UUID operationId);
    /** 原作业尝试最多一个操作。 */
    Optional<OtaRollbackOperation> findForJob(UUID jobId, int attemptNo);
    /** 原方向事实和当前查询控制。 */
    Optional<Control> control(UUID operationId);
    /** 原设备报告身份只读重放。 */
    Optional<OtaRollbackReportResult> findReport(UUID deviceId, UUID reportId);
    /** 当前查询已有报告，避免永久冲突反复重试。 */
    Optional<OtaRollbackReportResult> findReportForStatusQuery(UUID queryId);
    /** 跨主动与查询报告共享原序号。 */
    Optional<OtaRollbackReportResult> findReportBySequence(UUID operationId, long reportSeq);
    /** 无报告返回零，不能重置已知序号。 */
    long latestSequence(UUID operationId);
    /** 全部认证报告观察的最大计数，空不等于零证据。 */
    OptionalLong maximumObservedCommitted(UUID operationId);
    /** 原操作已保守预留实际发送，不宣称设备已收到。 */
    boolean hasOperationSendReservation(UUID operationId);
    /** 报告、不可变方向事实、真实状态和共同阈值同事务。 */
    boolean acceptReport(OtaJobProgressRepository.Context expected, OtaRollbackReport report, String disposition, String reason);
    /** 已有原操作且尚未收束的只读查询候选。 */
    Optional<OtaRollbackOperation> nextStatusCandidate();
    /** 当前操作状态查询持久退避。 */
    boolean deferStatusCandidate(UUID operationId);
    /** 原操作独立固定六十秒查询，不更新执行期限。 */
    boolean createStatusQuery(OtaRollbackStatusQuery query);
    /** 历史固定状态查询。 */
    Optional<OtaRollbackStatusQuery> findStatusQuery(UUID queryId);
    /** 当前查询指针。 */
    Optional<OtaRollbackStatusQuery> currentStatusQuery(UUID operationId);
    /** 原Broker窗口及当前消费、到期围栏。 */
    boolean statusAdoptionAllowed(UUID queryId, Instant brokerReceivedAt);
    /** 到期竞争只围栏状态查询。 */
    boolean expireStatusQuery(UUID queryId);

    /** 原方向首次证明互斥且不可变，矛盾标记不能清除。 */
    record Control(UUID operationId, UUID acceptedReportId, UUID commitWonReportId, Instant conflictedAt,
            UUID currentQueryId, Instant nextQueryAt) { }
}
