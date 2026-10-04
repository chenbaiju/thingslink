package com.things.link.rule.application.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S8-2B 规则队列的低基数观测门面。
 *
 * <p>所有标签都来自本类封闭枚举；tenantId、projectId、ruleId 和 messageId 只允许出现在受控日志与 trace，
 * 否则高基数时序会先于实际规则队列耗尽监控资源。指标在构造期预注册，既固定标签集合，也让零流量状态可见。</p>
 */
@Component
public class RuleQueueMetrics {

    /** 入队结果计数器名称。 */
    public static final String SUBMISSION = "thingslink.rule.queue.submission";
    /** 队列内待执行工作总量仪表名称。 */
    public static final String PENDING = "thingslink.rule.queue.pending";
    /** 当前活跃公平调度单元总量仪表名称。 */
    public static final String ACTIVE = "thingslink.rule.queue.active";
    /** 队列与执行阶段耗时名称。 */
    public static final String EXECUTION = "thingslink.rule.execution";
    /** 有限退避处理结果计数器名称。 */
    public static final String RETRY = "thingslink.rule.retry";
    /** 已得到 broker 确认的规则死信计数器名称。 */
    public static final String DEAD_LETTER = "thingslink.rule.dlq";

    /** tenant 与 project 两层的待执行快照。 */
    private final Map<QueueScope, AtomicInteger> pending = new EnumMap<>(QueueScope.class);
    /** tenant 与 project 两层的活跃快照。 */
    private final Map<QueueScope, AtomicInteger> active = new EnumMap<>(QueueScope.class);
    /** 固定入队阶段和结果的计数器。 */
    private final Map<SubmissionStage, Map<SubmissionResult, Counter>> submissions =
            new EnumMap<>(SubmissionStage.class);
    /** 固定执行阶段和结果的计时器。 */
    private final Map<ExecutionStage, Map<ExecutionResult, Timer>> executions =
            new EnumMap<>(ExecutionStage.class);
    /** 固定退避档位和结果的计数器。 */
    private final Map<RetryDelay, Map<RetryResult, Counter>> retries = new EnumMap<>(RetryDelay.class);
    /** 固定失败原因的已确认死信计数器。 */
    private final Map<RuleExecutionFailure, Counter> deadLetters = new EnumMap<>(RuleExecutionFailure.class);

    /**
     * Spring 装配入口；模块隔离测试未加载 Actuator 时回退进程内注册表。
     *
     * @param registryProvider 可选的应用指标注册表
     */
    @Autowired
    public RuleQueueMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 显式注册表构造器，供非 Spring 单测验证指标标签。
     *
     * @param registry Micrometer 指标注册表
     */
    public RuleQueueMetrics(MeterRegistry registry) {
        for (QueueScope scope : QueueScope.values()) {
            AtomicInteger pendingValue = new AtomicInteger();
            AtomicInteger activeValue = new AtomicInteger();
            pending.put(scope, pendingValue);
            active.put(scope, activeValue);
            Gauge.builder(PENDING, pendingValue, AtomicInteger::get)
                    .description("规则公平调度器内待执行工作总量")
                    .tag("scope", scope.tagValue)
                    .register(registry);
            Gauge.builder(ACTIVE, activeValue, AtomicInteger::get)
                    .description("规则公平调度器内活跃调度单元总量")
                    .tag("scope", scope.tagValue)
                    .register(registry);
        }
        for (SubmissionStage stage : SubmissionStage.values()) {
            Map<SubmissionResult, Counter> counters = new EnumMap<>(SubmissionResult.class);
            for (SubmissionResult result : SubmissionResult.values()) {
                counters.put(result, Counter.builder(SUBMISSION)
                        .description("规则队列提交结果")
                        .tags("stage", stage.tagValue, "result", result.tagValue)
                        .register(registry));
            }
            submissions.put(stage, counters);
        }
        for (ExecutionStage stage : ExecutionStage.values()) {
            Map<ExecutionResult, Timer> timers = new EnumMap<>(ExecutionResult.class);
            for (ExecutionResult result : ExecutionResult.values()) {
                timers.put(result, Timer.builder(EXECUTION)
                        .description("规则队列与执行阶段耗时")
                        .tags("stage", stage.tagValue, "result", result.tagValue)
                        .register(registry));
            }
            executions.put(stage, timers);
        }
        for (RetryDelay delay : RetryDelay.values()) {
            Map<RetryResult, Counter> counters = new EnumMap<>(RetryResult.class);
            for (RetryResult result : RetryResult.values()) {
                counters.put(result, Counter.builder(RETRY)
                        .description("规则有限退避处理结果")
                        .tags("delay", delay.tagValue, "result", result.tagValue)
                        .register(registry));
            }
            retries.put(delay, counters);
        }
        for (RuleExecutionFailure failure : RuleExecutionFailure.values()) {
            deadLetters.put(failure, Counter.builder(DEAD_LETTER)
                    .description("已确认写入规则死信主题的消息数")
                    .tag("reason", failure.name().toLowerCase())
                    .tag("result", "published")
                    .register(registry));
        }
    }

    /** @param stage 固定入队阶段 @param result 固定入队结果 */
    public void recordSubmission(SubmissionStage stage, SubmissionResult result) {
        submissions.get(stage).get(result).increment();
    }

    /** @param scope 调度层级 @param value 当前待执行总量，必须非负 */
    public void setPending(QueueScope scope, int value) {
        setGauge(pending, scope, value);
    }

    /** @param scope 调度层级 @param value 当前活跃总量，必须非负 */
    public void setActive(QueueScope scope, int value) {
        setGauge(active, scope, value);
    }

    /** @param stage 固定执行阶段 @param result 固定执行结果 @param duration 已测得耗时 */
    public void recordExecution(ExecutionStage stage, ExecutionResult result, Duration duration) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("执行耗时必须为非负值");
        }
        executions.get(stage).get(result).record(duration);
    }

    /** @param delay 已冻结的退避档位 @param result 固定退避处理结果 */
    public void recordRetry(Duration delay, RetryResult result) {
        retries.get(RetryDelay.from(delay)).get(result).increment();
    }

    /**
     * 仅在死信 producer 得到 broker 确认后调用。
     *
     * @param failure 封闭失败原因
     */
    public void recordDeadLetterPublished(RuleExecutionFailure failure) {
        deadLetters.get(failure).increment();
    }

    /** 安全设置仪表值，防止调用方把计数器减到负数掩盖队列错误。 */
    private static void setGauge(Map<QueueScope, AtomicInteger> gauges, QueueScope scope, int value) {
        if (scope == null || value < 0) {
            throw new IllegalArgumentException("队列仪表范围必须非空且非负");
        }
        gauges.get(scope).set(value);
    }

    /** tenant 外层与 project 内层的固定调度范围标签。 */
    public enum QueueScope {
        /** owner tenant 外层公平调度。 */
        TENANT("tenant"),
        /** project 内层公平调度。 */
        PROJECT("project");

        /** Prometheus 固定标签值。 */
        private final String tagValue;

        /** @param tagValue Prometheus 固定标签值 */
        QueueScope(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** 入队处理的固定阶段。 */
    public enum SubmissionStage {
        /** 配额与容量门禁阶段。 */
        ADMISSION("admission"),
        /** 已通过门禁后交给调度器的阶段。 */
        DISPATCH("dispatch");

        /** Prometheus 固定标签值。 */
        private final String tagValue;

        /** @param tagValue Prometheus 固定标签值 */
        SubmissionStage(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** 入队处理的固定结果。 */
    public enum SubmissionResult {
        /** 已被有界队列接受。 */
        ACCEPTED("accepted"),
        /** owner tenant 的共享容量耗尽。 */
        TENANT_FULL("tenant_full"),
        /** project 的保护容量耗尽。 */
        PROJECT_FULL("project_full"),
        /** 调度器正在关闭。 */
        CLOSED("closed"),
        /** 有效配额拒绝本次工作。 */
        QUOTA_REJECTED("quota_rejected");

        /** Prometheus 固定标签值。 */
        private final String tagValue;

        /** @param tagValue Prometheus 固定标签值 */
        SubmissionResult(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** 规则耗时的固定阶段。 */
    public enum ExecutionStage {
        /** 进入队列到取得执行槽位的等待时间。 */
        QUEUE("queue"),
        /** 实际处理器执行时间。 */
        ENGINE("engine");

        /** Prometheus 固定标签值。 */
        private final String tagValue;

        /** @param tagValue Prometheus 固定标签值 */
        ExecutionStage(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** 规则耗时的固定结果。 */
    public enum ExecutionResult {
        /** 规则成功完成。 */
        SUCCESS("success"),
        /** 不可重试的确定性失败。 */
        PERMANENT_FAILURE("permanent_failure"),
        /** 已安排有限退避重试。 */
        RETRY_SCHEDULED("retry_scheduled"),
        /** 已转入规则死信。 */
        DEAD_LETTER("dead_letter"),
        /** 本次由已存在回执吸收的重放。 */
        REPLAYED("replayed");

        /** Prometheus 固定标签值。 */
        private final String tagValue;

        /** @param tagValue Prometheus 固定标签值 */
        ExecutionResult(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** retry Topic 的固定延迟档位。 */
    public enum RetryDelay {
        /** 第一次失败的一分钟退避。 */
        ONE_MINUTE(RuleRetryPolicy.FIRST_RETRY_DELAY, "one_minute"),
        /** 第二次失败的五分钟退避。 */
        FIVE_MINUTES(RuleRetryPolicy.SECOND_RETRY_DELAY, "five_minutes");

        /** 策略固定的实际延迟。 */
        private final Duration duration;
        /** Prometheus 固定标签值。 */
        private final String tagValue;

        /** @param duration 策略固定延迟 @param tagValue Prometheus 固定标签值 */
        RetryDelay(Duration duration, String tagValue) {
            this.duration = duration;
            this.tagValue = tagValue;
        }

        /** @param duration 已决定的退避时间 @return 对应的固定指标档位 */
        private static RetryDelay from(Duration duration) {
            for (RetryDelay value : values()) {
                if (value.duration.equals(duration)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("仅允许 S8-2B 冻结的规则退避档位");
        }
    }

    /** retry Topic 处理的固定结果。 */
    public enum RetryResult {
        /** 已成功登记下次重试。 */
        SCHEDULED("scheduled"),
        /** 已达到最大次数，不再安排重试。 */
        EXHAUSTED("exhausted"),
        /** retry Topic 已得到 broker 确认。 */
        PUBLISHED("published");

        /** Prometheus 固定标签值。 */
        private final String tagValue;

        /** @param tagValue Prometheus 固定标签值 */
        RetryResult(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
