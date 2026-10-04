package com.things.link.ota.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 安装前原子停止持久闭环，普通方法加入已锁完整图的事务；可信候选只定位不授权。 */
public interface OtaInstallStopRepository {
    /** 当前RLS范围内设备存在永久方向冲突。 */
    boolean deviceConflicted(UUID deviceId);
    /** 原管理取消下尚未安装的执行候选。 */
    Optional<OtaJobProgressRepository.Context> nextCandidate();
    /** 候选缺资格持久退避，不能改变作业状态。 */
    boolean deferCandidate(OtaJobProgressRepository.Context expected);
    /** 唯一操作及固定基线、来源、取消请求事实同事务，不新增执行阶段。 */
    boolean create(OtaJobProgressRepository.Context expected, OtaInstallStopOperation operation, String reason);
    /** 原作业<b>当前尝试</b>的下载授权id（含尚未封存），按id升序与DB函数当前尝试的array_agg(id ORDER BY id)逐序一致。 */
    List<UUID> authorizationIds(UUID jobId, int attemptNo);
    /** 原操作完整固定证据。 */
    Optional<OtaInstallStopOperation> find(UUID operationId);
    /** 原作业尝试最多一个操作。 */
    Optional<OtaInstallStopOperation> findForJob(UUID jobId, int attemptNo);
    /** 原方向事实和当前查询控制。 */
    Optional<Control> control(UUID operationId);
    /** 原设备报告身份只读重放。 */
    Optional<OtaInstallStopReportResult> findReport(UUID deviceId, UUID reportId);
    /** 当前查询已有报告，避免永久冲突反复重试。 */
    Optional<OtaInstallStopReportResult> findReportForStatusQuery(UUID queryId);
    /** 跨主动与查询报告共享原序号。 */
    Optional<OtaInstallStopReportResult> findReportBySequence(UUID operationId, long reportSeq);
    /** 无报告返回零，不能重置已知序号。 */
    long latestSequence(UUID operationId);
    /** 原操作已保守预留实际发送，不宣称设备已收到。 */
    boolean hasOperationSendReservation(UUID operationId);
    /** 报告、不可变方向事实、真实状态和共同阈值同事务。 */
    boolean acceptReport(OtaJobProgressRepository.Context expected, OtaInstallStopReport report, String disposition, String reason);
    /** 已有原操作且尚未收束的只读查询候选。 */
    Optional<OtaInstallStopOperation> nextStatusCandidate();
    /** 当前操作状态查询持久退避。 */
    boolean deferStatusCandidate(UUID operationId);
    /** 原操作独立固定六十秒查询，不更新执行期限。 */
    boolean createStatusQuery(OtaInstallStopStatusQuery query);
    /** 历史固定状态查询。 */
    Optional<OtaInstallStopStatusQuery> findStatusQuery(UUID queryId);
    /** 当前查询指针。 */
    Optional<OtaInstallStopStatusQuery> currentStatusQuery(UUID operationId);
    /** 原Broker窗口及当前消费、到期围栏。 */
    boolean statusAdoptionAllowed(UUID queryId, Instant brokerReceivedAt);
    /** 到期竞争只围栏状态查询。 */
    boolean expireStatusQuery(UUID queryId);

    /** 原方向首次证明互斥且不可变，矛盾标记不能清除。 */
    record Control(UUID operationId, UUID acceptedReportId, UUID stoppedReportId, UUID installWonReportId, Instant conflictedAt,
            UUID currentQueryId, Instant nextQueryAt, UUID externalEvidenceId, String externalEvidenceKind) { }
}
