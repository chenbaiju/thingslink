package com.things.link.rule.application;

import com.things.link.shared.message.RuleNotificationDeliveryRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 规则通知投递状态机端口；所有方法都通过受限数据库函数跨越后台消费者缺少 RLS 上下文的边界。
 *
 * <p>外部网络调用绝不包含在这些方法的事务里。数据库只负责 CAS 领取、成功终态、有限重试与死信事实，
 * 从而让 Kafka 重投、多实例扫描和进程崩溃都不能并发推进同一次尝试。</p>
 */
public interface RuleNotificationDeliveryStore {

    /** @return Kafka 入口对不可变投递事实的接管裁决 */
    Acceptance accept(RuleNotificationDeliveryRequest request);

    /** Kafka 入口接管裁决；显式区分幂等重放与必须进入 DLQ 的合同冲突。 */
    enum Acceptance {
        /** 当前尝试已有可领取的 QUEUED 事实。 */
        READY,
        /** 同一不可变请求已由在途、重试或终态事实接管，本次安全结束。 */
        IDEMPOTENT_REPLAY,
        /** 请求与既有事实冲突、越过尝试序号或缺少重试重排队事实。 */
        REJECTED
    }

    /** @return 按 dispatch token 在真实调用前将 QUEUED CAS 到 SENDING */
    boolean startClaimed(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            UUID dispatchToken,
            Instant startedAt,
            Instant recoveryAt);

    /** 跨项目、按租户领取 QUEUED 规则通知；领取不增加 attempt。 */
    DispatchClaim claimDispatches(int limit, Duration lease);

    /** @return 跨项目最老 QUEUED/SENDING 投递年龄，用于低基数积压告警 */
    default DispatchAges dispatchAges() {
        return new DispatchAges(Duration.ZERO, Duration.ZERO);
    }

    /** @return 未执行外部调用时按 token 释放投递租约 */
    boolean releaseDispatch(
            UUID projectId, UUID deliveryId, UUID dispatchToken, Duration retryDelay);

    /**
     * 把当前 SENDING 尝试推进为 DELIVERED 终态。
     *
     * @param projectId 项目隔离轴
     * @param deliveryId 投递事实 ID
     * @param attemptNo 当前尝试序号
     * @param providerMessageId 低敏供应商诊断 ID
     * @param deliveredAt 送达完成 UTC 时刻
     * @return CAS 是否成功
     */
    boolean markDelivered(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            String providerMessageId,
            Instant deliveredAt);

    /**
     * 把可恢复失败登记为 RETRY_SCHEDULED。
     *
     * @param projectId 项目隔离轴
     * @param deliveryId 投递事实 ID
     * @param attemptNo 当前尝试序号
     * @param nextAttemptAt 下一次尝试 UTC 时刻
     * @param errorCode 固定低基数失败码
     * @param updatedAt 状态更新时间
     * @return CAS 是否成功
     */
    boolean markRetry(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            Instant nextAttemptAt,
            String errorCode,
            Instant updatedAt);

    /**
     * 把永久失败或耗尽尝试的投递推进为 DEAD_LETTER 终态。
     *
     * @param projectId 项目隔离轴
     * @param deliveryId 投递事实 ID
     * @param attemptNo 当前尝试序号
     * @param errorCode 固定低基数失败码
     * @param terminalAt 终态 UTC 时刻
     * @return CAS 是否成功
     */
    boolean markDeadLetter(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            String errorCode,
            Instant terminalAt);

    /**
     * 跨项目领取到期重试；数据库用 {@code FOR UPDATE SKIP LOCKED} 保证多实例互斥。
     *
     * @param limit 单次领取上限
     * @param lease 崩溃恢复租约
     * @return 已原子推进为 SENDING 的冻结投递快照
     */
    RetryClaim claimDueRetries(int limit, Duration lease);

    /**
     * ADR0069：只读复核真实claim候选的不可变身份及有效租约，不取得通知行锁。
     * 最终requeue/stop必须再以token、状态、lease和attempt关系CAS，不能把本次快照当作锁凭据。
     */
    boolean matchesClaimedRetry(RetryCandidate candidate, UUID leaseToken, Instant now);

    /**
     * ADR0069：冻结时终止已合法领取的到期重试，不制造QUEUED/Outbox中间事实。
     * @param expectedAttemptNo claim返回的本次尝试号，过期SENDING沿当前attempt
     * @return 真实租约与状态CAS是否推进终态；false保留后来的领取者或终态
     */
    boolean stopClaimedRetryForProjectFreeze(UUID tenantId, UUID projectId, UUID deliveryId,
            UUID leaseToken, int expectedAttemptNo, Instant now);

    /**
     * 在项目事务内把已领取候选恢复为 QUEUED，并绑定新 Outbox 行。
     *
     * @param projectId 项目隔离轴
     * @param deliveryId 稳定投递 ID
     * @param leaseToken 本批跨项目领取令牌
     * @param outboxEventId 新重试 Outbox 行 ID
     * @param nextAttemptNo Kafka 请求将指定的尝试序号
     * @param updatedAt 重排队 UTC 时刻
     * @return 租约和状态 CAS 是否成功
     */
    boolean requeueClaimedRetry(
            UUID projectId,
            UUID deliveryId,
            UUID leaseToken,
            UUID outboxEventId,
            int nextAttemptNo,
            Instant updatedAt);

    /**
     * @param leaseToken 本批候选的数据库租约令牌
     * @param deliveries 已领取候选
     */
    record RetryClaim(UUID leaseToken, List<RetryCandidate> deliveries) {
    }

    /** @param leaseToken 一批数据库投递租约令牌 @param deliveries 冻结投递快照 */
    record DispatchClaim(UUID leaseToken, List<DispatchCandidate> deliveries) {
    }

    /** @param queued 最老可领取事实年龄 @param sending 最老在途事实年龄 */
    record DispatchAges(Duration queued, Duration sending) {
    }

    /** 通知 worker 重建投递信封所需的完整冻结事实。 */
    record DispatchCandidate(
            UUID id,
            UUID tenantId,
            UUID projectId,
            UUID ruleId,
            UUID ruleVersionId,
            UUID messageId,
            UUID sceneId,
            UUID sceneVersionId,
            UUID sceneExecutionId,
            UUID deviceId,
            String channel,
            String recipient,
            String subject,
            String body,
            String traceId,
            int attemptNo,
            Instant enqueuedAt,
            UUID automationId, UUID automationVersionId, UUID automationExecutionId) {
        /** 旧规则/场景候选兼容入口。 */
        public DispatchCandidate(
            UUID id,
            UUID tenantId,
            UUID projectId,
            UUID ruleId,
            UUID ruleVersionId,
            UUID messageId,
            UUID sceneId,
            UUID sceneVersionId,
            UUID sceneExecutionId,
            UUID deviceId,
            String channel,
            String recipient,
            String subject,
            String body,
            String traceId,
            int attemptNo,
            Instant enqueuedAt) {
            this(id, tenantId, projectId, ruleId, ruleVersionId, messageId, sceneId, sceneVersionId, sceneExecutionId, deviceId, channel, recipient, subject, body, traceId, attemptNo, enqueuedAt, null, null, null);
        }

    }

    /**
     * @param id 稳定投递 ID
     * @param tenantId owner tenant ID
     * @param projectId 项目隔离轴
     * @param ruleId 规则来源规则 ID；场景来源为空
     * @param ruleVersionId 规则来源不可变版本 ID；场景来源为空
     * @param messageId 规则来源触发消息 ID；场景来源为空
     * @param sceneId 场景来源场景 ID；规则来源为空
     * @param sceneVersionId 场景来源不可变版本 ID；规则来源为空
     * @param sceneExecutionId 场景来源执行事实 ID；规则来源为空
     * @param deviceId 触发设备 ID
     * @param channel 冻结渠道
     * @param recipient 冻结目标
     * @param subject 冻结标题
     * @param body 冻结正文
     * @param traceId 链路 ID
     * @param nextAttemptNo 下一条 Kafka 请求指定的尝试序号
     */
    record RetryCandidate(
            UUID id,
            UUID tenantId,
            UUID projectId,
            UUID ruleId,
            UUID ruleVersionId,
            UUID messageId,
            UUID sceneId,
            UUID sceneVersionId,
            UUID sceneExecutionId,
            UUID deviceId,
            String channel,
            String recipient,
            String subject,
            String body,
            String traceId,
            int nextAttemptNo,
            UUID automationId, UUID automationVersionId, UUID automationExecutionId) {
        /** 旧规则/场景候选兼容入口。 */
        public RetryCandidate(
            UUID id,
            UUID tenantId,
            UUID projectId,
            UUID ruleId,
            UUID ruleVersionId,
            UUID messageId,
            UUID sceneId,
            UUID sceneVersionId,
            UUID sceneExecutionId,
            UUID deviceId,
            String channel,
            String recipient,
            String subject,
            String body,
            String traceId,
            int nextAttemptNo) {
            this(id, tenantId, projectId, ruleId, ruleVersionId, messageId, sceneId, sceneVersionId, sceneExecutionId, deviceId, channel, recipient, subject, body, traceId, nextAttemptNo, null, null, null);
        }

    }
}
