package com.things.link.ota.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** 运行控制事务仓储，调用方负责真实项目许可、管理授权和独立资格检查。 */
public interface OtaCampaignRuntimeRepository {
    /** 项目OTA控制锁，必须早于任何活动、设备、固件或信任锁。 */
    void controlLock(UUID tenant, UUID project);
    /** 普通管理读取仅取得控制锁，不启用完成来源；保持原授权及一致性。 */
    default void readControlLock(UUID tenant, UUID project) { controlLock(tenant, project); }
    /** 数据库当前时钟，用于notBefore门禁。 */
    Instant currentTime();
    /** 精确发布快照引用该key的固件，按规范身份稳定排序。 */
    List<UUID> firmwaresUsingKey(UUID project, String trustDomain, String keyVersion);
    /** 无客户端scope的单条可信领取，租约不是业务状态转移。 */
    Optional<Claim> claimOne();
    /** 用持久token取得真实scope，建立RLS之前使用；无资格授权含义。 */
    Optional<Claim> authoritativeClaim(UUID jobId, UUID token);
    /** 锁定活动、当前批次及其作业，随后调用方才可读取低层资格。 */
    Optional<OtaCampaignRuntime> lockRuntime(UUID project, UUID campaign);
    /** 当前运行投影。 */
    Optional<OtaCampaignRuntime> read(UUID project, UUID campaign);
    /** 首次启动并激活第一批，当前时间由数据库提供。 */
    boolean start(UUID project, UUID campaign, long expectedRevision, UUID actor);
    /** 人工暂停并撤销待准入租约。 */
    boolean pause(UUID project, UUID campaign, long expectedRevision, UUID actor, String reason);
    /** 当前失败恢复候选；仅供控制锁内逐作业复核资格，不代表已允许恢复。 */
    List<UUID> resumeFailureCandidates(UUID project, UUID campaign);
    /** 经调用方当前安全复核后恢复，不重置已派发期限。 */
    boolean resume(UUID project, UUID campaign, long expectedRevision, UUID actor, String reason);
    /** 运行取消原子关闭准入能力，未决作业保持原责任，重复恢复由服务读取原事实。 */
    boolean cancelRuntime(UUID project, UUID campaign, long expectedRevision, UUID actor, String reason);
    /** 最终当前token CAS，首次准入与通知意图同事务。 */
    boolean admit(Claim claim, long credentialVersion, long reportRevision, String reportHash, Instant checkedAt);
    /** 明确不合格才跳过，全批跳过进入失败并暂停。 */
    boolean skip(Claim claim, String reason);
    /** 被撤销固件关联运行活动原子安全暂停。 */
    int securityPause(UUID project, UUID firmware, UUID actor, String reason);
    /** 后台真实租约发现安全问题时暂停对应活动。 */
    boolean securityPauseJob(Claim claim, String reason);
    /** 无客户端scope的单条可信领取重试到期作业，租约不是业务状态转移。 */
    Optional<RetryDue> claimRetryDue();
    /** 完整范围、修订、原时钟及token的原子消费；成功保持作业锁至原事务结束。 */
    boolean consumeRetryClaim(RetryDue due);
    /** 依冻结downloadRetryLimit与retryBackoffSeconds进入重试等待；永久错误或无失败观察拒绝。 */
    boolean beginRetry(UUID tenantId, UUID projectId, UUID jobId, String failureCode, String reason);
    /** 同一jobId与manifest下派发新尝试；停止围栏或冻结预算不足时拒绝。 */
    boolean dispatchRetry(UUID jobId, String reason);
    /** 预算耗尽后进入封闭TIMED_OUT并保存稳定归因，不以其他终态掩盖。 */
    boolean exhaustRetry(UUID jobId, String failureCode, String reason);
    /** 重试等待期安装前安全取消直接收束为取消，不派发新尝试也不重发停止命令。 */
    boolean cancelRetryWait(UUID jobId, String reason);
    /** 是否已存在安装前停止围栏；存在时不得派发新尝试。 */
    boolean hasInstallStopFence(UUID jobId);
    /** 当前作业的持久状态与尝试号，用于重试到期后的最终归类。 */
    Optional<JobState> jobState(UUID jobId);
    /** 是否仍可再派发一次新尝试；由冻结downloadRetryLimit与既有尝试号共同判定。 */
    boolean retryBudgetRemains(UUID jobId);
    /** 只从数据库领取事实构造；最终使用前必须再核对token。
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param campaignId 权威活动
     * @param jobId 权威作业
     * @param batchNumber 稳定批次
     * @param deviceId 权威设备
     * @param jobRevision 当前修订
     * @param token 当前随机租约
     * @param leaseUntil 数据库到期时间
     */
    record Claim(UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, int batchNumber,
            UUID deviceId, long jobRevision, UUID token, Instant leaseUntil) { }

    /** 只从数据库领取事实构造的重试到期范围；不携带凭据或签名正文。
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param campaignId 权威活动
     * @param jobId 权威作业
     * @param deviceId 权威设备
     * @param attemptNo 当前尝试号
     * @param jobRevision 当前修订
     * @param nextAttemptAt 冻结退避到期
     * @param token 本次实际领取的随机能力
     * @param leaseUntil 原数据库租约期限
     * @param failureCode 上一尝试的稳定失败归因
     */
    record RetryDue(UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId,
            int attemptNo, long jobRevision, Instant nextAttemptAt, String failureCode, UUID token, Instant leaseUntil) { }

    /** 数据库当前作业状态与尝试号；用于到期后的最终归类，不作为授权来源。
     * @param status 当前状态
     * @param attemptNo 当前尝试号
     * @param stateVersion 当前修订
     */
    record JobState(String status, int attemptNo, long stateVersion) { }
}
