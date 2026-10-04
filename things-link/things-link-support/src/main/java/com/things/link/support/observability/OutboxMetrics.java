package com.things.link.support.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事务 Outbox 发布结果指标。
 *
 * <p>只按固定结果标签聚合，不能把 topic 以外的业务标识、项目或设备写进 Prometheus 标签；
 * 后三者会随客户量无上限增长，反而让指标系统先于业务链路失效。</p>
 */
@Component
public class OutboxMetrics {

    /** Outbox 事件发布结果计数，Prometheus 导出时自动追加 {@code _total}。 */
    public static final String DELIVERY = "thingslink.outbox.delivery";
    /** 固定 stripe 当前执行数。 */
    public static final String STRIPE_ACTIVE = "thingslink.outbox.stripe.active";
    /** 固定 stripe 有界队列深度。 */
    public static final String STRIPE_QUEUE = "thingslink.outbox.stripe.queue";
    /** stripe 容量竞争拒绝数。 */
    public static final String STRIPE_REJECTED = "thingslink.outbox.stripe.rejected";
    /** 等待 Kafka broker 回执耗时。 */
    public static final String PUBLISH_WAIT = "thingslink.outbox.publish.wait";
    /** Kafka 已确认但数据库租约已失效的次数。 */
    public static final String LEASE_EXPIRED = "thingslink.outbox.lease.expired";
    /** 最老未发布 lane head 年龄，暴露长期重试而不允许后继越序。 */
    public static final String OLDEST_HEAD_AGE = "thingslink.outbox.oldest.head.age";

    /** 指标注册表。 */
    private final MeterRegistry meterRegistry;
    /** 最近一轮数据库观测到的最老未发布年龄秒数。 */
    private final AtomicLong oldestHeadAgeSeconds = new AtomicLong();

    /**
     * Spring 装配入口；模块隔离测试未带 Actuator 时退到进程内注册表。
     *
     * @param registryProvider 可选的全局指标注册表
     */
    @Autowired
    public OutboxMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 显式注册表入口，便于验证低基数标签。
     *
     * @param meterRegistry 指标注册表
     */
    public OutboxMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder(OLDEST_HEAD_AGE, oldestHeadAgeSeconds, AtomicLong::get)
                .description("Outbox 最老未发布 lane head 年龄秒数")
                .register(meterRegistry);
    }

    /**
     * 记录 Kafka broker 已确认的投递。
     *
     * @param topic 已确认主题；主题由部署清单冻结，是有界标签
     */
    public void recordPublished(String topic) {
        record(topic, "published");
    }

    /**
     * 记录一次已登记下次重试时间的投递失败。
     *
     * @param topic 发布失败的目标主题
     */
    public void recordRetry(String topic) {
        record(topic, "retry");
    }

    /** 为四条固定 stripe 注册 active/queue Gauge；stripe 标签只有 0..3。 */
    public void registerStripes(List<ThreadPoolExecutor> executors) {
        for (int index = 0; index < executors.size(); index++) {
            ThreadPoolExecutor executor = executors.get(index);
            String stripe = Integer.toString(index);
            Gauge.builder(STRIPE_ACTIVE, executor, ThreadPoolExecutor::getActiveCount)
                    .description("Outbox 固定发布 stripe 当前执行数")
                    .tag("stripe", stripe)
                    .register(meterRegistry);
            Gauge.builder(STRIPE_QUEUE, executor, value -> value.getQueue().size())
                    .description("Outbox 固定发布 stripe 有界队列深度")
                    .tag("stripe", stripe)
                    .register(meterRegistry);
        }
    }

    /** @param stripe 固定 stripe 下标 */
    public void recordStripeRejected(int stripe) {
        Counter.builder(STRIPE_REJECTED)
                .description("Outbox stripe 容量竞争拒绝并回写重试的次数")
                .tag("stripe", Integer.toString(stripe))
                .register(meterRegistry)
                .increment();
    }

    /** @param topic 固定目标主题 @param elapsedNanos Kafka send 到 broker 回执耗时 */
    public void recordPublishWait(String topic, long elapsedNanos) {
        Timer.builder(PUBLISH_WAIT)
                .description("Outbox 等待 Kafka broker 回执耗时")
                .tag("topic", topic)
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(meterRegistry)
                .record(Duration.ofNanos(elapsedNanos));
    }

    /** @param topic 固定目标主题 */
    public void recordLeaseExpired(String topic) {
        Counter.builder(LEASE_EXPIRED)
                .description("Outbox Kafka 已确认但数据库租约已失效次数")
                .tag("topic", topic)
                .register(meterRegistry)
                .increment();
    }

    /** @param age 数据库当前最老未发布事件年龄 */
    public void recordOldestHeadAge(Duration age) {
        oldestHeadAgeSeconds.set(Math.max(0L, age == null ? 0L : age.toSeconds()));
    }

    /**
     * 用稳定标签登记结果。
     *
     * @param topic Kafka 主题
     * @param result 固定结果枚举
     */
    private void record(String topic, String result) {
        Counter.builder(DELIVERY)
                .description("事务 Outbox 事件投递结果")
                .tags("topic", topic, "result", result)
                .register(meterRegistry)
                .increment();
    }
}
