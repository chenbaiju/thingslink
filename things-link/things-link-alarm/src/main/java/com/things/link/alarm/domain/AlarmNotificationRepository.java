package com.things.link.alarm.domain;

import com.things.link.shared.page.CursorPage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 告警通知配置和投递意图的领域持久化端口。 */
public interface AlarmNotificationRepository {
    /**
     * @return 项目中的通知组页
     */
    CursorPage<AlarmNotificationGroup> pageGroups(UUID projectId, String cursor, int limit);

    /**
     * @return 项目可见通知组
     */
    Optional<AlarmNotificationGroup> findGroup(UUID projectId, UUID id);

    /**
     * @return 新建是否成功
     */
    boolean createGroup(AlarmNotificationGroup value);

    /**
     * @return CAS 更新是否成功
     */
    boolean updateGroup(AlarmNotificationGroup value);

    /**
     * @return 软删除 CAS 是否成功
     */
    boolean deleteGroup(UUID projectId, UUID id, int version);

    /**
     * @return 通知组有效收件人
     */
    List<AlarmNotificationRecipient> listRecipients(UUID projectId, UUID groupId);

    /**
     * @return 项目可见收件人
     */
    Optional<AlarmNotificationRecipient> findRecipient(UUID projectId, UUID id);

    /**
     * @return 新建是否成功
     */
    boolean createRecipient(AlarmNotificationRecipient value);

    /**
     * @return CAS 更新是否成功
     */
    boolean updateRecipient(AlarmNotificationRecipient value);

    /**
     * @return 软删除 CAS 是否成功
     */
    boolean deleteRecipient(UUID projectId, UUID id, int version);

    /**
     * @return 项目模板页
     */
    CursorPage<AlarmNotificationTemplate> pageTemplates(UUID projectId, String cursor, int limit);

    /**
     * @return 项目可见模板
     */
    Optional<AlarmNotificationTemplate> findTemplate(UUID projectId, UUID id);

    /**
     * @return 新建是否成功
     */
    boolean createTemplate(AlarmNotificationTemplate value);

    /**
     * @return CAS 更新是否成功
     */
    boolean updateTemplate(AlarmNotificationTemplate value);

    /**
     * @return 软删除 CAS 是否成功
     */
    boolean deleteTemplate(UUID projectId, UUID id, int version);

    /**
     * @return 规则路由绑定
     */
    List<AlarmNotificationBinding> listBindings(UUID projectId, UUID ruleId);

    /**
     * @return 项目可见绑定
     */
    Optional<AlarmNotificationBinding> findBinding(UUID projectId, UUID id);

    /**
     * @return 新建是否成功
     */
    boolean createBinding(AlarmNotificationBinding value);

    /**
     * @return CAS 更新是否成功
     */
    boolean updateBinding(AlarmNotificationBinding value);

    /**
     * @return 软删除 CAS 是否成功
     */
    boolean deleteBinding(UUID projectId, UUID id, int version);

    /**
     * @return 已按启用路由和同渠道收件人展开的投递上下文
     */
    List<DeliveryTarget> findDeliveryTargets(UUID projectId, UUID ruleId);

    /**
     * 查询有效 PUSH 路由。PUSH 的受众来自 {@link
     * com.things.link.alarm.application.AlarmPushAudiencePort}，不能伪造成配置收件人。
     *
     * @return 已按启用通知组、绑定和 PUSH 模板筛选的路由
     */
    List<PushDeliveryRoute> findPushDeliveryRoutes(UUID projectId, UUID ruleId);

    /**
     * @return 插入是否成功；唯一键吸收激活事件重放
     */
    boolean createDelivery(AlarmNotificationDelivery value);

    /** @return 当前项目投递事实；消费者仍须核对消息信封中的归属字段 */
    Optional<AlarmNotificationDelivery> findDelivery(UUID projectId, UUID deliveryId);

    /** @return Kafka 入口是否确认该事件/attempt 已由既有 QUEUED 投递事实持久接管 */
    boolean acceptQueuedDelivery(UUID projectId, UUID deliveryId, UUID eventId, int attemptNo);

    /** @return 是否按预期 attemptNo 从待处理状态原子进入 SENDING */
    boolean startDelivery(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            UUID dispatchLeaseToken,
            Instant startedAt,
            Instant recoveryAt);

    /** 跨项目、按租户领取 QUEUED 投递；领取本身不增加真实外部调用次数。 */
    DispatchClaim claimDispatches(int limit, Duration leaseDuration);

    /** @return 跨项目最老 QUEUED/SENDING 投递年龄，用于低基数积压告警 */
    default DispatchAges dispatchAges() {
        return new DispatchAges(Duration.ZERO, Duration.ZERO);
    }

    /** @return 未执行外部调用时是否按 token 释放并短暂延后投递租约 */
    boolean releaseDispatch(
            UUID projectId, UUID deliveryId, UUID leaseToken, Duration retryDelay);

    /** @return 是否把当前 SENDING 尝试推进为成功终态 */
    boolean markDeliverySucceeded(
            UUID projectId, UUID deliveryId, int attemptNo, String providerMessageId, Instant completedAt);

    /** @return 是否把当前 SENDING 尝试登记为下一次有限重试 */
    boolean markDeliveryRetry(
            UUID projectId,
            UUID deliveryId,
            int attemptNo,
            Instant nextAttemptAt,
            String errorCode,
            Instant updatedAt);

    /** @return 是否把当前 SENDING 尝试推进为死信终态 */
    boolean markDeliveryDeadLetter(
            UUID projectId, UUID deliveryId, int attemptNo, String errorCode, Instant completedAt);

    /** @return 是否把未触达厂商的 SENDING PUSH 推进为授权失效终态 */
    boolean markDeliverySkippedAuthorization(
            UUID projectId, UUID deliveryId, int attemptNo, Instant completedAt);

    /** 受控跨项目领取到期重试；调用者只能得到生成下一条 Outbox 所需的最小归属事实。 */
    RetryClaim claimRetries(int limit, Duration leaseDuration);

    /** @return 是否持有效租约把重试重新排队；必须与新 Outbox 位于同一事务 */
    boolean requeueClaimedRetry(
            UUID projectId,
            UUID deliveryId,
            UUID leaseToken,
            UUID outboxEventId,
            int nextAttemptNo,
            Instant updatedAt);

    /** ADR0069：有效重试token的冻结收束，保留当前attempt；false表示候选过期或已被接管。 */
    boolean stopClaimedRetryForProjectFreeze(UUID tenantId, UUID projectId, UUID deliveryId,
                                           UUID retryToken, int expectedAttemptNo, Instant now);

    /**
     * @return 项目投递意图页
     */
    CursorPage<AlarmNotificationDelivery> pageDeliveries(
            UUID projectId, UUID instanceId, String cursor, int limit);

    /** 生成投递时需要冻结的配置快照，不把内部 JDBC join 暴露给应用层。 */
    record DeliveryTarget(
            AlarmNotificationBinding binding,
            AlarmNotificationRecipient recipient,
            AlarmNotificationTemplate template) {}

    /** PUSH 路由只冻结绑定与模板；安装受众由 enduser 权威事实动态展开。 */
    record PushDeliveryRoute(
            AlarmNotificationBinding binding,
            AlarmNotificationTemplate template) {}

    /** 一次跨项目重试领取批次。 */
    record RetryClaim(UUID leaseToken, List<RetryCandidate> deliveries) {}

    /** 调度器恢复项目 RLS 和构造下一次最小消息所需的事实。 */
    record RetryCandidate(
            UUID id,
            UUID tenantId,
            UUID projectId,
            UUID instanceId,
            UUID alarmEventId,
            int nextAttemptNo) {}

    /** 一次跨项目通知 worker 领取批次。 */
    record DispatchClaim(UUID leaseToken, List<DispatchCandidate> deliveries) {}

    /** @param queued 最老可领取事实年龄 @param sending 最老在途事实年龄 */
    record DispatchAges(Duration queued, Duration sending) {}

    /** 构造原冻结 Kafka 信封所需的最小事实。 */
    record DispatchCandidate(
            UUID id,
            UUID tenantId,
            UUID projectId,
            UUID eventId,
            UUID instanceId,
            UUID alarmEventId,
            int attemptNo,
            Instant requestedAt,
            String traceId) {}
}
