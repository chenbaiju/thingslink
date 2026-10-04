package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.NotificationChannel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * S6 告警状态机的低基数运行指标。
 *
 * <p>项目、设备、规则和实例标识都会随业务规模无界增长，绝不能进入 Prometheus 标签；具体对象 只通过受控结构化日志、事件事实和链路 ID 排查。指标标签仅使用本类固定枚举。
 */
@Component
public class AlarmMetrics {
    /** 成功写入的状态迁移事件数，event_type 是冻结的有限枚举。 */
    public static final String TRANSITION = "thingslink.alarm.transition";

    /** 已执行告警评估的结果数，result/reason 都是固定枚举。 */
    public static final String EVALUATION = "thingslink.alarm.evaluation";

    /** 告警激活展开的投递意图数；仅以固定渠道和结果聚合。 */
    public static final String NOTIFICATION_INTENT = "thingslink.alarm.notification.intent";

    /** 外部渠道尝试数；result/reason 都来自固定枚举。 */
    public static final String NOTIFICATION_DELIVERY = "thingslink.alarm.notification.delivery";

    /** 外部渠道尝试耗时；不按项目或收件人拆分。 */
    public static final String NOTIFICATION_DELIVERY_DURATION =
            "thingslink.alarm.notification.delivery.duration";

    /** 按迁移事件类型复用的计数器。 */
    private final Map<AlarmEvent.EventType, Counter> transitions =
            new EnumMap<>(AlarmEvent.EventType.class);

    /** 正常完成的评估计数器。 */
    private final Counter evaluationSuccess;

    /** 按固定失败原因复用的评估失败计数器。 */
    private final Map<EvaluationFailureReason, Counter> evaluationFailures =
            new EnumMap<>(EvaluationFailureReason.class);

    /** 按渠道和固定写入结果复用的投递意图计数器。 */
    private final Map<NotificationChannel, Map<NotificationIntentResult, Counter>>
            notificationIntents = new EnumMap<>(NotificationChannel.class);

    /** 按渠道与固定结果复用的投递计数器。 */
    private final Map<NotificationChannel, Map<NotificationDeliveryResult, Counter>> deliveries =
            new EnumMap<>(NotificationChannel.class);

    /** 按渠道复用的外部调用耗时。 */
    private final Map<NotificationChannel, Timer> deliveryDurations =
            new EnumMap<>(NotificationChannel.class);

    /**
     * Spring 装配入口；纯模块测试未加载 Actuator 时退到内存注册表，不让监控出口阻断业务测试。
     *
     * @param registryProvider 可选的应用统一指标注册表
     */
    @Autowired
    public AlarmMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 显式注册表入口，供单元测试验证指标名和有限标签集合。
     *
     * @param registry 指标注册表
     */
    public AlarmMetrics(MeterRegistry registry) {
        for (AlarmEvent.EventType type : AlarmEvent.EventType.values()) {
            transitions.put(
                    type,
                    Counter.builder(TRANSITION)
                            .description("成功写入告警状态迁移事件的次数")
                            .tag("event_type", type.name().toLowerCase(Locale.ROOT))
                            .register(registry));
        }
        evaluationSuccess =
                Counter.builder(EVALUATION)
                        .description("告警规则评估执行结果")
                        .tags("result", "success", "reason", "none")
                        .register(registry);
        for (EvaluationFailureReason reason : EvaluationFailureReason.values()) {
            evaluationFailures.put(
                    reason,
                    Counter.builder(EVALUATION)
                            .description("告警规则评估执行结果")
                            .tags("result", "failure", "reason", reason.tagValue)
                            .register(registry));
        }
        for (NotificationChannel channel : NotificationChannel.values()) {
            Map<NotificationIntentResult, Counter> byResult =
                    new EnumMap<>(NotificationIntentResult.class);
            for (NotificationIntentResult result : NotificationIntentResult.values()) {
                byResult.put(
                        result,
                        Counter.builder(NOTIFICATION_INTENT)
                                .description("告警激活展开通知投递意图的结果")
                                .tags(
                                        "channel",
                                        channel.name().toLowerCase(Locale.ROOT),
                                        "result",
                                        result.tagValue)
                                .register(registry));
            }
            notificationIntents.put(channel, byResult);
            Map<NotificationDeliveryResult, Counter> deliveryResults =
                    new EnumMap<>(NotificationDeliveryResult.class);
            for (NotificationDeliveryResult result : NotificationDeliveryResult.values()) {
                deliveryResults.put(
                        result,
                        Counter.builder(NOTIFICATION_DELIVERY)
                                .description("告警通知外部渠道尝试结果")
                                .tags(
                                        "channel", channel.name().toLowerCase(Locale.ROOT),
                                        "result", result.tagValue,
                                        "reason", result.reason)
                                .register(registry));
            }
            deliveries.put(channel, deliveryResults);
            deliveryDurations.put(
                    channel,
                    Timer.builder(NOTIFICATION_DELIVERY_DURATION)
                            .description("告警通知外部渠道调用耗时")
                            .tag("channel", channel.name().toLowerCase(Locale.ROOT))
                            .register(registry));
        }
    }

    /**
     * 记录一条已真正写入的告警状态迁移事件。
     *
     * @param type 迁移事件类型，来自数据库受约束的固定枚举
     */
    public void recordTransition(AlarmEvent.EventType type) {
        transitions.get(type).increment();
    }

    /** 记录一次无异常完成的告警评估。 */
    public void recordEvaluationSuccess() {
        evaluationSuccess.increment();
    }

    /**
     * 记录一次评估异常；原因由枚举边界冻结，禁止传入异常文本或业务标识。
     *
     * @param reason 固定失败原因
     */
    public void recordEvaluationFailure(EvaluationFailureReason reason) {
        evaluationFailures.get(reason).increment();
    }

    /** 记录投递意图创建或由唯一键吸收的重放；不允许调用方自定义标签。 */
    public void recordNotificationIntent(
            NotificationChannel channel, NotificationIntentResult result) {
        notificationIntents.get(channel).get(result).increment();
    }

    /** 记录一次渠道尝试及耗时；调用方不能提供任意异常文本标签。 */
    public void recordNotificationDelivery(
            NotificationChannel channel, NotificationDeliveryResult result, long elapsedNanos) {
        deliveries.get(channel).get(result).increment();
        deliveryDurations.get(channel).record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /** 外部投递结果的固定分类。 */
    public enum NotificationDeliveryResult {
        /** 供应商确认接收。 */
        SUCCEEDED("succeeded", "none"),
        /** 可恢复错误已登记下一次尝试。 */
        RETRY_SCHEDULED("failed", "retryable"),
        /** 永久错误或尝试耗尽进入死信。 */
        DEAD_LETTER("failed", "terminal"),
        /** 发送前授权已失效，未触达外部厂商。 */
        SKIPPED_AUTHORIZATION("skipped", "authorization");

        private final String tagValue;
        private final String reason;

        NotificationDeliveryResult(String tagValue, String reason) {
            this.tagValue = tagValue;
            this.reason = reason;
        }
    }

    /** 评估失败的有限分类，用于告警聚合且不暴露高基数异常文本。 */
    public enum EvaluationFailureReason {
        /** 调用方没有遵守已验证数值属性端口的输入契约。 */
        INVALID_INPUT("invalid_input"),
        /** PostgreSQL/RLS 等持久化层失败。 */
        PERSISTENCE("persistence"),
        /** 未被预期分类的应用异常。 */
        UNEXPECTED("unexpected");

        /** Prometheus 的固定低基数标签值。 */
        private final String tagValue;

        /**
         * @param tagValue 固定低基数标签值
         */
        EvaluationFailureReason(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** delivery 唯一键写入的有限结果，禁止把 deliveryId/异常信息变成指标标签。 */
    public enum NotificationIntentResult {
        /** 已创建一条新的投递意图和对应 Outbox。 */
        CREATED("created"),
        /** Kafka 重放或并发竞争命中唯一键，未新增事实。 */
        DEDUPLICATED("deduplicated"),
        /** 模板渲染结果非法，已写 TEMPLATE_INVALID 终态意图（不进入外部投递）。 */
        TEMPLATE_INVALID("template_invalid");

        /** Prometheus 的固定低基数标签值。 */
        private final String tagValue;

        /**
         * @param tagValue 固定标签值
         */
        NotificationIntentResult(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
