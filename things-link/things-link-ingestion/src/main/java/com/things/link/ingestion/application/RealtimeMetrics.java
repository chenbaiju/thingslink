package com.things.link.ingestion.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S4 实时增量通道的低基数运行指标。
 *
 * <p>项目、设备和会话标识都不能作为 Prometheus 标签：它们会随业务规模无限增长，导致
 * 监控系统先于业务链路耗尽。这里仅按有限的成功/失败结果计数，具体对象只允许出现在受控日志中。</p>
 */
@Component
public class RealtimeMetrics {

    /** Kafka 发送结果计数器名称。 */
    static final String KAFKA_PUBLISH = "thingslink.realtime.kafka.publish";
    /** Redis Pub/Sub 发布结果计数器名称。 */
    static final String REDIS_PUBLISH = "thingslink.realtime.redis.publish";
    /** 异步分发队列饱和时被主动丢弃的增量数。 */
    static final String DISPATCH_REJECTED = "thingslink.realtime.dispatch.rejected";
    /** 本实例当前 WebSocket 会话数；Gauge 不携带账号或项目等高基数标签。 */
    static final String WEBSOCKET_ACTIVE_SESSIONS = "thingslink.realtime.websocket.sessions.active";
    /** 本实例当前精确属性订阅数。 */
    static final String WEBSOCKET_ACTIVE_SUBSCRIPTIONS = "thingslink.realtime.websocket.subscriptions.active";
    /** 本实例所有会话尚未取出的控制帧和属性帧总数。 */
    static final String WEBSOCKET_QUEUE_DEPTH = "thingslink.realtime.websocket.queue.depth";
    /** 慢会话中被同 device + property 新事实覆盖的旧事实数。 */
    static final String WEBSOCKET_QUEUE_MERGED = "thingslink.realtime.websocket.queue.merged";
    /** 队列到达硬上限后无法接纳的帧数。 */
    static final String WEBSOCKET_QUEUE_DROPPED = "thingslink.realtime.websocket.queue.dropped";
    /** 底层 WebSocket 写入异常数。 */
    static final String WEBSOCKET_SEND_FAILURE = "thingslink.realtime.websocket.send.failure";
    /** 固定资源与租户套餐拒绝数；scope 只能取 {@link LimitScope} 的固定枚举值。 */
    static final String WEBSOCKET_LIMIT_REJECTED = "thingslink.realtime.websocket.limit.rejected";
    /** 租户共享连接租约判定；result 只能取 {@link QuotaDecision} 的固定枚举值。 */
    static final String WEBSOCKET_QUOTA_DECISIONS = "thingslink.realtime.websocket.quota.decisions";

    /** 已由 Kafka broker 确认的实时增量。 */
    private final Counter kafkaPublished;
    /** 无法交给 Kafka 的实时增量；允许丢失，客户端会 REST 补拉。 */
    private final Counter kafkaFailed;
    /** 已交给 Redis 的项目频道增量。 */
    private final Counter redisPublished;
    /** Redis 或序列化故障造成的在线增量丢失。 */
    private final Counter redisFailed;
    /** 有界异步队列拒绝的任务数。 */
    private final Counter dispatchRejected;
    /** 当前会话 Gauge 的并发安全承载值。 */
    private final AtomicInteger activeSessions = new AtomicInteger();
    /** 当前订阅 Gauge 的并发安全承载值。 */
    private final AtomicInteger activeSubscriptions = new AtomicInteger();
    /** 当前待发送队列 Gauge 的并发安全承载值。 */
    private final AtomicInteger outboundQueueDepth = new AtomicInteger();
    /** 同键覆盖计数器。 */
    private final Counter queueMerged;
    /** 队列拒绝计数器。 */
    private final Counter queueDropped;
    /** 底层发送失败计数器。 */
    private final Counter sendFailure;
    /** 各固定资源层级的拒绝计数器；禁止使用调用方字符串创建标签。 */
    private final Map<LimitScope, Counter> limitRejected = new EnumMap<>(LimitScope.class);
    /** 租户共享连接租约结果；不允许携带租户、项目或会话 ID。 */
    private final Map<QuotaDecision, Counter> quotaDecisions = new EnumMap<>(QuotaDecision.class);

    /**
     * Spring 装配入口；隔离测试未加载 Actuator 时使用进程内注册表，不让可观测性缺席阻断业务测试。
     *
     * @param registryProvider 可选的统一指标注册表
     */
    @Autowired
    public RealtimeMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 显式注册表入口，供单元测试验证指标语义。
     *
     * @param registry 指标注册表
     */
    public RealtimeMetrics(MeterRegistry registry) {
        kafkaPublished = counter(registry, KAFKA_PUBLISH, "success", "Kafka 已确认的实时增量数");
        kafkaFailed = counter(registry, KAFKA_PUBLISH, "failure", "Kafka 发布失败的实时增量数");
        redisPublished = counter(registry, REDIS_PUBLISH, "success", "Redis 已接受的实时增量数");
        redisFailed = counter(registry, REDIS_PUBLISH, "failure", "Redis 发布失败的实时增量数");
        dispatchRejected = Counter.builder(DISPATCH_REJECTED)
                .description("实时增量异步分发队列拒绝数")
                .register(registry);
        Gauge.builder(WEBSOCKET_ACTIVE_SESSIONS, activeSessions, AtomicInteger::get)
                .description("本实例当前 WebSocket 会话数")
                .register(registry);
        Gauge.builder(WEBSOCKET_ACTIVE_SUBSCRIPTIONS, activeSubscriptions, AtomicInteger::get)
                .description("本实例当前精确属性订阅数")
                .register(registry);
        Gauge.builder(WEBSOCKET_QUEUE_DEPTH, outboundQueueDepth, AtomicInteger::get)
                .description("本实例 WebSocket 待发送帧总数")
                .register(registry);
        queueMerged = Counter.builder(WEBSOCKET_QUEUE_MERGED)
                .description("WebSocket 队列按同一属性合并的旧事实数")
                .register(registry);
        queueDropped = Counter.builder(WEBSOCKET_QUEUE_DROPPED)
                .description("WebSocket 队列达到硬上限后拒绝的帧数")
                .register(registry);
        sendFailure = Counter.builder(WEBSOCKET_SEND_FAILURE)
                .description("WebSocket 底层文本帧发送失败数")
                .register(registry);
        for (LimitScope scope : LimitScope.values()) {
            limitRejected.put(scope, Counter.builder(WEBSOCKET_LIMIT_REJECTED)
                    .description("WebSocket 固定资源配额拒绝数")
                    .tag("scope", scope.tagValue)
                    .register(registry));
        }
        for (QuotaDecision decision : QuotaDecision.values()) {
            quotaDecisions.put(decision, Counter.builder(WEBSOCKET_QUOTA_DECISIONS)
                    .description("租户共享 WebSocket 连接租约判定数")
                    .tag("result", decision.tagValue)
                    .register(registry));
        }
    }

    /** 记录 Kafka broker 已确认。 */
    public void recordKafkaPublished() { kafkaPublished.increment(); }

    /** 记录 Kafka 异步发送或立即提交失败。 */
    public void recordKafkaFailure() { kafkaFailed.increment(); }

    /** 记录 Redis broker 已接受 publish 命令；本机无订阅者仍是正常成功。 */
    public void recordRedisPublished() { redisPublished.increment(); }

    /** 记录 Redis 连接或 JSON 序列化失败。 */
    public void recordRedisFailure() { redisFailed.increment(); }

    /** 记录有界 executor 饱和。 */
    public void recordDispatchRejected() { dispatchRejected.increment(); }

    /** 记录会话登记成功；只在配额检查与索引写入都成功后调用。 */
    public void sessionOpened() { activeSessions.incrementAndGet(); }

    /** 记录会话索引被真实删除；重复关闭不会重复递减。 */
    public void sessionClosed() { decreaseBy(activeSessions, 1); }

    /**
     * 记录整体替换订阅产生的净变化。
     *
     * @param delta 新订阅数减旧订阅数，可为负数
     */
    public void subscriptionsChanged(int delta) { changeBy(activeSubscriptions, delta); }

    /**
     * 记录队列加入、取出或清理产生的净变化。
     *
     * @param delta 新深度减旧深度，可为负数
     */
    public void queueDepthChanged(int delta) { changeBy(outboundQueueDepth, delta); }

    /** 记录同一设备属性的旧待发事实被最新事实覆盖。 */
    public void recordQueueMerged() { queueMerged.increment(); }

    /** 记录控制帧或属性帧因有界队列饱和而未被接纳。 */
    public void recordQueueDropped() { queueDropped.increment(); }

    /** 记录底层 WebSocket 文本发送异常。 */
    public void recordSendFailure() { sendFailure.increment(); }

    /**
     * 记录固定层级资源配额拒绝；枚举边界防止账号、项目等高基数值进入标签。
     *
     * @param scope 被触发的固定资源层级
     */
    public void recordLimitRejected(LimitScope scope) { limitRejected.get(scope).increment(); }

    /**
     * 记录租户共享连接租约结果；枚举防止业务 ID 意外进入 Prometheus 标签。
     *
     * @param decision 固定租约结果
     */
    public void recordQuotaDecision(QuotaDecision decision) { quotaDecisions.get(decision).increment(); }

    /**
     * 创建只含固定结果标签的计数器。
     *
     * @param registry 指标注册表
     * @param name 指标名称
     * @param result 固定结果标签
     * @param description 指标含义
     * @return 计数器
     */
    private static Counter counter(MeterRegistry registry, String name, String result, String description) {
        return Counter.builder(name).description(description).tag("result", result).register(registry);
    }

    /** 原子调整 Gauge，并用零作为防御下界，避免关闭竞态污染监控。 */
    private static void changeBy(AtomicInteger gauge, int delta) {
        gauge.updateAndGet(current -> Math.max(0, current + delta));
    }

    /** 对关闭/取出路径提供语义明确的递减入口。 */
    private static void decreaseBy(AtomicInteger gauge, int delta) {
        changeBy(gauge, -delta);
    }

    /**
     * WebSocket 配额固定层级。
     *
     * <p>标签值是代码冻结的有限集合，不能扩展为连接 ID、账号 ID 或项目 ID。</p>
     */
    public enum LimitScope {
        /** 本实例总连接数。 */
        GLOBAL_CONNECTION("global_connection"),
        /** 单账号连接数。 */
        ACCOUNT_CONNECTION("account_connection"),
        /** 单项目连接数。 */
        PROJECT_CONNECTION("project_connection"),
        /** 项目所有者租户的套餐共享连接数。 */
        TENANT_CONNECTION("tenant_connection"),
        /** 单会话精确属性订阅数。 */
        SESSION_SUBSCRIPTION("session_subscription"),
        /** 单账号全部会话的精确属性订阅数。 */
        ACCOUNT_SUBSCRIPTION("account_subscription"),
        /** 单项目全部会话的精确属性订阅数。 */
        PROJECT_SUBSCRIPTION("project_subscription");

        /** Prometheus 的固定低基数标签值。 */
        private final String tagValue;

        /** @param tagValue 固定低基数标签值 */
        LimitScope(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /**
     * 租户共享连接租约结果。
     *
     * <p>{@code fail_open} 仅表示 Redis 即时计数故障后回退本机有限硬上限；不表示鉴权或
     * 策略读取失败被放行。</p>
     */
    public enum QuotaDecision {
        /** Redis 已登记租约。 */
        ACQUIRED("acquired"),
        /** 租户套餐上限拒绝新连接。 */
        REJECTED("rejected"),
        /** Redis 故障，保留本机固定资源上限后放行或续期失败但不踢旧连接。 */
        FAIL_OPEN("fail_open"),
        /** 心跳发现租约已经丢失；保留本地连接但禁止无条件复活占位。 */
        LEASE_LOST("lease_lost"),
        /** 权威策略显式不限，仅保留本机物理安全上限且不占 Redis 租约。 */
        UNLIMITED("unlimited"),
        /** 项目 owner tenant 无法解析时不猜归属，仅使用本机有限安全默认。 */
        SAFE_DEFAULT("safe_default");

        /** Prometheus 固定低基数标签值。 */
        private final String tagValue;

        /** @param tagValue 固定低基数标签值 */
        QuotaDecision(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
