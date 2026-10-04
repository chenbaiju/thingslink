package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.HandoffDisposition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** ADR 0046 durable handoff 的低基数结果、连接与积压年龄指标。 */
@Component
public class BrokerHandoffMetrics {

    /** 固定 ACK 结果计数器。 */
    public static final String HANDOFF_RESULTS = "thingslink.ingress.handoff";
    /** 暂时故障导致保留未确认消息的计数器。 */
    public static final String TRANSIENT_RETRIES = "thingslink.ingress.handoff.transient.retry";
    /** 固定 clientId 当前是否连接。 */
    public static final String CONNECTED = "thingslink.ingress.handoff.connected";
    /** 最近一次 CONNACK 是否恢复既有会话。 */
    public static final String SESSION_PRESENT = "thingslink.ingress.handoff.session.present";
    /** 当前实例最老同步处理中消息的秒龄；Broker 离线积压由 EMQX 指标补充。 */
    public static final String OLDEST_UNACKED_SECONDS = "thingslink.ingress.handoff.unacked.oldest.seconds";
    /** 连接丢失后的重连调度次数。 */
    public static final String RECONNECTS = "thingslink.ingress.handoff.reconnect";
    /** 固定可确认结果的计数器。 */
    private final Map<HandoffDisposition, Counter> results = new EnumMap<>(HandoffDisposition.class);
    /** 暂时故障计数器。 */
    private final Counter transientRetries;
    /** 重连调度计数器。 */
    private final Counter reconnects;
    /** 只有这四种正常分派结果允许生成 ACK 结果时序。 */
    private static final Set<HandoffDisposition> ACK_DISPOSITIONS = EnumSet.of(
            HandoffDisposition.ACCEPTED, HandoffDisposition.DUPLICATE,
            HandoffDisposition.PERMANENT_REJECT, HandoffDisposition.QUARANTINED);
    /** 连接状态 Gauge。 */
    private final AtomicInteger connected = new AtomicInteger();
    /** 会话恢复状态 Gauge。 */
    private final AtomicInteger sessionPresent = new AtomicInteger();
    /** 当前处理消息开始时间；单线程冻结使一个原子值足够。 */
    private final AtomicLong deliveryStartedAtMillis = new AtomicLong();
    /** 计算消息秒龄的时钟。 */
    private final Clock clock;

    /** @param provider 可选统一注册表；隔离测试缺少 Actuator 时使用进程内注册表 */
    @Autowired
    public BrokerHandoffMetrics(ObjectProvider<MeterRegistry> provider) {
        this(provider.getIfAvailable(SimpleMeterRegistry::new), Clock.systemUTC());
    }

    /** @param registry 指标注册表 @param clock 可测试时钟 */
    BrokerHandoffMetrics(MeterRegistry registry, Clock clock) {
        this.clock = clock;
        for (HandoffDisposition disposition : ACK_DISPOSITIONS) {
            results.put(disposition, Counter.builder(HANDOFF_RESULTS)
                    .description("Broker durable handoff 已获持久事实的固定结果")
                    .tag("result", tag(disposition))
                    .register(registry));
        }
        transientRetries = Counter.builder(TRANSIENT_RETRIES)
                .description("依赖故障或提交结果未知而未 ACK 的 Broker handoff 数")
                .register(registry);
        reconnects = Counter.builder(RECONNECTS)
                .description("固定 ingress owner 的重连调度次数")
                .register(registry);
        Gauge.builder(CONNECTED, connected, AtomicInteger::get)
                .description("固定 Broker ingress clientId 当前连接状态")
                .register(registry);
        Gauge.builder(SESSION_PRESENT, sessionPresent, AtomicInteger::get)
                .description("最近 CONNACK 是否恢复既有 durable session")
                .register(registry);
        Gauge.builder(OLDEST_UNACKED_SECONDS, this, BrokerHandoffMetrics::oldestUnackedSeconds)
                .description("本实例当前同步处理中最老 MQTT 消息秒龄")
                .register(registry);
    }

    /** @param disposition 已获持久事实且准备 manual ACK 的固定结果 */
    public void record(HandoffDisposition disposition) {
        Counter counter = results.get(disposition);
        if (counter == null) throw new IllegalArgumentException("暂时故障不得作为 MQTT ACK 结果记录");
        counter.increment();
    }

    /** 标记一次同步处理开始；单活单线程不允许被后续消息覆盖。 */
    public void deliveryStarted() {
        deliveryStartedAtMillis.compareAndSet(0L, clock.millis());
    }

    /** 处理完成或转为 Broker 重放后清空本实例处理秒龄。 */
    public void deliveryFinished() {
        deliveryStartedAtMillis.set(0L);
    }

    /** 暂时故障不 ACK，并留下固定计数。 */
    public void transientRetry() {
        transientRetries.increment();
    }

    /** @param present CONNACK 的 session-present 位 */
    public void connected(boolean present) {
        connected.set(1);
        sessionPresent.set(present ? 1 : 0);
    }

    /** 连接丢失后清零连接状态；保留最近 session-present 供故障诊断。 */
    public void disconnected() {
        connected.set(0);
    }

    /** 记录一次有界退避重连调度。 */
    public void reconnectScheduled() {
        reconnects.increment();
    }

    /** 计算当前同步处理消息年龄；没有处理中消息时为零。 */
    private double oldestUnackedSeconds() {
        long started = deliveryStartedAtMillis.get();
        return started == 0L ? 0D : Math.max(0D, (clock.millis() - started) / 1000D);
    }

    /** 将正常 ACK 分类锁定到 ADR 0046 的四个低基数标签；暂时故障必须走独立重试计数。 */
    private static String tag(HandoffDisposition disposition) {
        return switch (disposition) {
            case ACCEPTED -> "accepted";
            case DUPLICATE -> "duplicate";
            case PERMANENT_REJECT -> "permanent_reject";
            case QUARANTINED -> "quarantined";
            case TRANSIENT_RETRY -> throw new IllegalArgumentException("暂时故障不得作为 MQTT ACK 结果记录");
        };
    }
}
