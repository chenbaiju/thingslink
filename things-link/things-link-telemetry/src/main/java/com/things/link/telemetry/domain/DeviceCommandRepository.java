package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 设备命令与尝试的持久化端口。 */
public interface DeviceCommandRepository {
    /** 公开专用受理在插入时同时保存来源，不给历史命令补记Key。 */
    boolean createPublic(DeviceCommand command, UUID keyId);
    /** 验证原命令来源；字符串前缀不是权限证明。 */
    boolean hasPublicOrigin(UUID projectId, UUID commandId, UUID keyId);
    /** @param command 新命令事实 @return 是否插入；幂等键冲突返回 false */ boolean create(DeviceCommand command);
    /** @param attempt 首次或重试尝试 */ void createAttempt(DeviceCommandAttempt attempt);
    /** @return 同项目业务幂等键命令 */ Optional<DeviceCommand> findByIdempotencyKey(UUID projectId, String key);
    /** @return 路径范围内命令 */ Optional<DeviceCommand> findById(UUID projectId, UUID deviceId, UUID commandId);
    /** @return 数据面按已确权项目查命令；连接设备仍由状态 CAS 核对 */
    Optional<DeviceCommand> findByCommandId(UUID projectId, UUID commandId);
    /** ADR0070：实际非只读事务按可信三元组锁命令并重读；调用方先取得新工作的project许可。 */
    Optional<DeviceCommand> lockByIdentity(UUID tenantId, UUID projectId, UUID commandId);
    /** ADR0070：同事务消费当前有效领取，旧token/attempt或未到期事实必须无操作。 */
    boolean consumeRetryClaim(DueCommand due);
    /** ADR0070：在命令锁下收束冻结工作，保留已发生的派发失败与响应超时事实。 */
    boolean stopForProjectFreeze(UUID tenantId, UUID projectId, UUID commandId, int expectedAttempt, Instant at);
    /** 当前MQTT能力丢失时按原准入/到期边界永久终止属性设置，保留已经发生的attempt诊断。 */
    boolean stopForPropertyCapability(UUID tenantId, UUID projectId, UUID commandId, int expectedAttempt, Instant at);
    /** ADR0196：原接收关系失效，按原准入/有效领取边界永久终止且保留已有尝试诊断。 */
    boolean stopForUnavailableReceiver(UUID tenantId, UUID projectId, UUID commandId, int expectedAttempt, Instant at);
    /** @return 命令全部尝试 */ List<DeviceCommandAttempt> findAttempts(UUID projectId, UUID commandId);
    /** EMQX 接受后以 attemptNo CAS 推进派发状态。 */
    boolean markDispatched(UUID projectId, UUID commandId, int attemptNo, Instant publishedAt);
    /** 发布失败后把当前 attempt 由 PENDING CAS 为 FAILED，并写入派发失败诊断码。 */
    boolean markAttemptDispatchFailed(UUID projectId, UUID commandId, int attemptNo,
                                      String failureCode, String failureMessage, Instant at);
    /** 派发重试耗尽后把仍为 ACCEPTED 的命令 CAS 为 TIMED_OUT（DISPATCH_RETRY_EXHAUSTED）。 */
    boolean terminalDispatchRetryExhausted(UUID projectId, UUID commandId, int attemptNo, Instant at);
    /** 未耗尽时安排下一次派发：命令保持 ACCEPTED，清除响应窗口并按退避写 next_attempt_at。 */
    boolean scheduleDispatchRetry(UUID projectId, UUID commandId, int attemptNo, Instant nextAttemptAt);
    /** 使用 reply messageId 与状态 CAS 推进 ACK/终态；快速回复可从 PENDING/ACCEPTED 直接收敛。 */
    boolean applyReply(UUID projectId, UUID commandId, UUID connectionDeviceId, UUID replyMessageId,
                       String replyStatus, String responseJson, String errorCode, String errorMessage, Instant at);
    /** 通过受控 SECURITY DEFINER 函数跨项目领取到期命令。 */
    List<DueCommand> claimDue(int limit);
    /**
     * 设备重连时把「因设备离线而未投递」的命令的下次派发时刻提前到现在（只提前、不推后）。
     *
     * <p>只命中「命令仍为 ACCEPTED、当前 attempt 因设备无会话而 FAILED、尝试未耗尽、未被其他扫描租约持有」的行。
     * 交付本身仍由既有重投状态机与下行链路执行，本方法只让退避不再多等。</p>
     *
     * @param tenantId 权威租户
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 刚重连的设备（承载连接的设备）
     * @return 被提前的命令条数
     */
    int accelerateOfflinePending(UUID tenantId, UUID projectId, UUID deviceId);
    /**
     * 统计该设备的待发命令数（§6 待发命令队列预算）。
     *
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 目标设备
     * @return ACCEPTED／DISPATCHED／ACKNOWLEDGED 三种未终态命令数
     */
    long countPending(UUID projectId, UUID deviceId);
    /** 将当前尝试标超时；未耗尽时把命令 CAS 回 ACCEPTED，耗尽时进入 TIMED_OUT。 */
    RetryDecision prepareRetry(UUID projectId, UUID commandId, int currentAttempt, int maxAttempts, Instant now);

    /** @return 锁后数据库实时时间，不能使用事务开始时间判断截止。 */
    Instant databaseNow();
    /** @return 当前推送PENDING中最老尝试的年龄秒数；受限函数不返回业务身份。 */
    double oldestPendingAgeSeconds();
    /** 领取型最后响应窗口超时：只收束父命令，不伪造推送或设备回复事实。 */
    boolean timeoutFinalClaim(UUID projectId, UUID commandId, int expectedAttempt, Instant at);

    /** ADR0143：领取分支是身份的一部分，不能由消费时的状态重新猜测。 */
    enum ClaimKind {
        /** 推送尚未取得交付回执。 */ PUSH_PENDING_TIMEOUT,
        /** 推送响应窗口到期。 */ PUSH_RESPONSE_TIMEOUT,
        /** 推送失败退避到期。 */ PUSH_DISPATCH_RETRY,
        /** 领取型次数耗尽。 */ PULL_FINAL_TIMEOUT
    }

    /** @param tenantId 租户 @param projectId 项目 @param commandId 命令 @param retryToken 本次真实领取身份 @param expectedAttempt 领取时当前尝试 @param claimKind 领取分支 */
    record DueCommand(UUID tenantId, UUID projectId, UUID commandId, UUID retryToken, int expectedAttempt, ClaimKind claimKind) { }
    /** 超时扫描 CAS 结论。 */
    enum RetryDecision { /** 创建下一 attempt。 */ RETRY, /** 已耗尽并终态。 */ TIMED_OUT, /** 已处理。 */ NOOP }
}
