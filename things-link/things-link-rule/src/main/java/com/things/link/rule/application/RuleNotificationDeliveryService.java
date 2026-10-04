package com.things.link.rule.application;

import com.things.link.shared.message.RuleNotificationDeliveryRequest;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.things.link.support.resilience.NotificationExternalGuard;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 在规则 Outbox 消费边界执行真实邮件/Webhook 投递，并维护有界重试与死信状态机。
 *
 * <p>S9-3 沿用 S6-3 的三次尝试、1 分钟/5 分钟退避和「通知失败不改规则执行事实」语义。
 * 首次 Kafka 重投由事实主键吸收；后续到期尝试由数据库租约扫描领取，外部 I/O 始终发生在短 CAS 之后。</p>
 */
@Service
public class RuleNotificationDeliveryService {

    /** ADR0069：最多三次发送处理尝试，包含最后授权拒绝，不等同供应商调用次数。 */
    static final int MAX_ATTEMPTS = 3;

    /** 首次可恢复失败等待一分钟，避免瞬时故障形成重试风暴。 */
    private static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(1);

    /** 第二次可恢复失败等待五分钟，第三次仍失败直接死信。 */
    private static final Duration SECOND_RETRY_DELAY = Duration.ofMinutes(5);

    /** 发送进程崩溃后允许其他实例接管；正常慢请求由适配器十秒超时约束。 */
    private static final Duration RECOVERY_LEASE = Duration.ofSeconds(30);

    /** 投递状态机仓储。 */
    private final RuleNotificationDeliveryStore store;

    /** 只按 EMAIL/WEBHOOK 固定渠道分派，禁止租户输入决定实现类。 */
    private final Map<String, RuleNotificationSender> senders;

    /** 可测试 UTC 时钟。 */
    private final Clock clock;
    /** 渠道 bulkhead 与按 provider/origin 隔离的有界断路器。 */
    private final NotificationExternalGuard externalGuard;

    /** ADR0069：原start、不可变身份复核和项目许可共同处于此短事务。 */
    private final TransactionTemplate transactions;
    /** 项目许可只持到发送准入提交，不跨外部网络调用。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /**
     * 装配固定渠道发送器，并在启动期拒绝重复渠道。
     *
     * @param store 投递状态机仓储
     * @param senders 真实邮件与 Webhook 发送适配器
     * @param clock UTC 时钟
     * @param externalGuard 仅在短事务内取得的本地渠道资格
     * @param transactions 无ambient事务时使用的独立短事务
     * @param lifecycleAccessService 原事务内项目持续许可
     */
    public RuleNotificationDeliveryService(
            RuleNotificationDeliveryStore store,
            List<RuleNotificationSender> senders,
            Clock clock,
            NotificationExternalGuard externalGuard,
            TransactionTemplate transactions,
            ProjectLifecycleAccessService lifecycleAccessService) {
        this.store = Objects.requireNonNull(store, "store");
        this.senders = new HashMap<>();
        for (RuleNotificationSender sender : senders) {
            String channel = normalize(sender.channel());
            if (this.senders.put(channel, sender) != null) {
                throw new IllegalStateException("同一规则通知渠道只能装配一个发送器");
            }
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.externalGuard = Objects.requireNonNull(externalGuard, "externalGuard");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.lifecycleAccessService = Objects.requireNonNull(lifecycleAccessService, "lifecycleAccessService");
    }

    /**
     * 消费首次 Kafka 请求；事件 ID 已存在或已进入终态时直接返回，不再次触达外部系统。
     *
     * @param request 反序列化后的规则通知投递请求
     */
    public void accept(RuleNotificationDeliveryRequest request) {
        validate(request);
        RuleNotificationDeliveryStore.Acceptance acceptance = Objects.requireNonNull(
                store.accept(request), "通知接管裁决不能为空");
        if (acceptance == RuleNotificationDeliveryStore.Acceptance.REJECTED) {
            throw new IllegalArgumentException("规则通知请求与持久接管事实冲突");
        }
    }

    /** 数据库 worker 已持有 dispatch 租约后，才允许把 attempt 增加并执行真实外部调用。 */
    public void deliverClaimed(
            RuleNotificationDeliveryRequest request, java.util.UUID dispatchToken) {
        validate(request);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("规则通知发送入口不能加入调用方事务");
        }
        TenantScope previous = TenantContext.current().orElse(null);
        TenantContext.set(new TenantScope(request.tenantId(), request.projectId(), request.tenantId()));
        NotificationExternalGuard.Guard[] acquired = new NotificationExternalGuard.Guard[1];
        try {
            Admission admission;
            try {
                admission = transactions.execute(status -> {
                    Instant startedAt = clock.instant();
                    if (!store.startClaimed(request.projectId(), request.eventId(), request.attemptNo(),
                            dispatchToken, startedAt, startedAt.plus(RECOVERY_LEASE))) {
                        return Admission.NOOP;
                    }
                    // start已证明旧事实存在；再核完整请求，禁止accept在伪造token失败后建立新通知。
                    if (store.accept(request) != RuleNotificationDeliveryStore.Acceptance.IDEMPOTENT_REPLAY) {
                        throw new IllegalArgumentException("规则通知请求与持久发送事实冲突");
                    }
                    if (!lifecycleAccessService.lockActiveForWrite(request.tenantId(), request.projectId())) {
                        if (!store.markDeadLetter(request.projectId(), request.eventId(), request.attemptNo(),
                                "PROJECT_FROZEN", startedAt)) {
                            throw new IllegalStateException("规则通知冻结终态CAS失败");
                        }
                        return Admission.NOOP;
                    }
                    acquired[0] = externalGuard.tryAcquire(request.channel(), request.recipient());
                    if (acquired[0] == null) {
                        // 无外发资格不能消耗attempt；退出事务后再按原token退避释放领取。
                        status.setRollbackOnly();
                        return Admission.DEFER;
                    }
                    return Admission.SEND;
                });
            } catch (RuntimeException exception) {
                // SQL或提交失败不证明渠道故障，取消尚未执行的half-open资格且不重置历史失败。
                if (acquired[0] != null) {
                    acquired[0].abortBeforeSend();
                    acquired[0].close();
                }
                throw exception;
            }
            if (admission == Admission.DEFER) {
                store.releaseDispatch(request.projectId(), request.eventId(), dispatchToken, Duration.ofSeconds(1));
                return;
            }
            if (admission != Admission.SEND) return;
            try (NotificationExternalGuard.Guard guard = acquired[0]) {
                send(new RuleNotificationDelivery(request.eventId(), request.tenantId(), request.projectId(),
                        request.channel(), request.recipient(), request.subject(), request.body(), request.traceId(),
                        request.attemptNo(), MAX_ATTEMPTS), guard);
            }
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }

    /** 仅内存准入结果，不新增通知持久枚举或公开消息字段。 */
    private enum Admission {
        /** 旧token、已终态或本轮冻结收束均无外发。 */ NOOP,
        /** 本地渠道无容量，事务回滚后退避释放旧token。 */ DEFER,
        /** 真实短事务已提交且本地guard归本轮独占。 */ SEND
    }

    /** 外部发送成功或失败都只推进投递事实，不抛回 Kafka 触发无界业务重试。 */
    private void send(
            RuleNotificationDelivery delivery, NotificationExternalGuard.Guard guard) {
        RuleNotificationSender sender = senders.get(normalize(delivery.channel()));
        try {
            if (sender == null) {
                throw new RuleNotificationSendException(
                        RuleNotificationSendException.Reason.INVALID_CHANNEL, false, null);
            }
            String providerMessageId = sender.send(delivery);
            store.markDelivered(
                    delivery.projectId(), delivery.id(), delivery.attemptNo(),
                    providerMessageId, clock.instant());
            guard.success();
        } catch (RuleNotificationSendException exception) {
            if (exception.retryable()) {
                guard.retryableFailure();
            } else {
                guard.ignoredFailure();
            }
            finishFailure(delivery, exception);
        } catch (RuntimeException exception) {
            guard.retryableFailure();
            finishFailure(delivery, new RuleNotificationSendException(
                    RuleNotificationSendException.Reason.INTERNAL_ERROR, true, exception));
        }
    }

    /** 可恢复错误只在剩余尝试内排队；永久错误和第三次失败直接落 DEAD_LETTER。 */
    private void finishFailure(
            RuleNotificationDelivery delivery,
            RuleNotificationSendException failure) {
        Instant now = clock.instant();
        if (failure.retryable() && delivery.attemptNo() < delivery.maxAttempts()) {
            Duration delay = delivery.attemptNo() == 1 ? FIRST_RETRY_DELAY : SECOND_RETRY_DELAY;
            store.markRetry(
                    delivery.projectId(), delivery.id(), delivery.attemptNo(),
                    now.plus(delay), failure.reason().name(), now);
            return;
        }
        store.markDeadLetter(
                delivery.projectId(), delivery.id(), delivery.attemptNo(),
                failure.reason().name(), now);
    }

    /** 请求信封必须完整；来源必须是互斥的规则组或场景组，目标格式由渠道适配器做 fail-closed 校验。 */
    private static void validate(RuleNotificationDeliveryRequest request) {
        Objects.requireNonNull(request, "规则通知请求不能为空");
        if (request.eventId() == null || request.tenantId() == null || request.projectId() == null
                || request.deviceId() == null || !request.validSource()
                || blank(request.channel()) || blank(request.recipient())
                || request.subject() == null || request.body() == null || blank(request.traceId())
                || request.attemptNo() < 1 || request.attemptNo() > MAX_ATTEMPTS
                || request.enqueuedAt() == null) {
            throw new IllegalArgumentException("规则通知请求信封不完整");
        }
    }

    /** @return 大小写不敏感的固定渠道键 */
    private static String normalize(String channel) {
        return channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
    }

    /** @return 字符串是否缺失或全为空白 */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
