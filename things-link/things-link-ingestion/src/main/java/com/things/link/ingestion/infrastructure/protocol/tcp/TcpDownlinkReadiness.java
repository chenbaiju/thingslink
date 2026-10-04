package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 全部主题分区已分配且位置已初始化才允许接入；集合按本进程的并发consumer合并。 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component
public class TcpDownlinkReadiness {
    /** 固定监听器ID，同时用于停止事件识别。 */
    public static final String LISTENER_ID = "tcpCommandDownlink";
    /** 消费线程在重平衡回调中提交的已初始化分区。 */
    private final Map<Consumer<?, ?>, Set<TopicPartition>> assigned = new HashMap<>();
    /** 分配回调早于Fetch授权检查；必须等同代次首次成功poll后才能开放接入。 */
    private final Set<Consumer<?, ?>> polled = new HashSet<>();
    /** 最新metadata的完整主题分区。 */
    private Set<TopicPartition> expected = Set.of();
    /** 各并发消费者最近一次可观测延迟，未知必须保留NaN。 */
    private final Map<Consumer<?, ?>, Double> lag = new HashMap<>();
    /** TCP启动等待上界。 */
    private final Duration timeout;
    /** 并发消费者多于主题分区时不能先开放监听、再等 ApplicationRunner 拒绝。 */
    private final int concurrency;

    /** 配置非法必须在任何监听开放前失败。 */
    public TcpDownlinkReadiness(@Value("${things-link.access.tcp.consumer-ready-timeout-seconds:30}") int seconds,
                               @Value("${things-link.kafka.concurrency.tcp-downlink:1}") int concurrency,
                               MeterRegistry meters) {
        if (seconds < 1 || seconds > 300 || concurrency < 1 || concurrency > 16) {
            throw new IllegalArgumentException("TCP广播就绪超时须1..300秒，并发须1..16");
        }
        timeout = Duration.ofSeconds(seconds);
        this.concurrency = concurrency;
        Gauge.builder("device.access.tcp.consumer.ready", this, value -> value.ready() ? 1 : 0).register(meters);
        Gauge.builder("device.access.tcp.consumer.lag", this, TcpDownlinkReadiness::lag).register(meters);
    }

    /** 在Kafka所属线程上初始化位置，不能从其他线程访问consumer。 */
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        Set<TopicPartition> metadata = new HashSet<>();
        consumer.partitionsFor(DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, timeout)
                .forEach(info -> metadata.add(new TopicPartition(info.topic(), info.partition())));
        Set<TopicPartition> initialized = new HashSet<>(consumer.assignment());
        for (TopicPartition partition : initialized) consumer.position(partition, timeout);
        synchronized (this) {
            expected = Set.copyOf(metadata);
            assigned.put(consumer, Set.copyOf(initialized));
            polled.remove(consumer);
            notifyAll();
        }
    }

    /** 撤销提交前即关闭接入资格，不能等待旧分区处理完才标红。 */
    public synchronized void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        assigned.remove(consumer);
        polled.remove(consumer);
        lag.remove(consumer);
    }
    /** 丢失归属时同样失去接入资格。 */
    public synchronized void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        assigned.remove(consumer);
        polled.remove(consumer);
        lag.remove(consumer);
    }
    /** @return 本进程广播是否拥有主题全部分区的已初始化位置 */
    public synchronized boolean ready() {
        Set<TopicPartition> union = new HashSet<>();
        assigned.forEach((consumer, partitions) -> { if (polled.contains(consumer)) union.addAll(partitions); });
        return expected.size() >= concurrency && union.equals(expected);
    }
    /** 成功poll后的记录/空闲回调采样位置并确认本代次消费资格；不从观测线程访问consumer。 */
    public void sampleLag(Consumer<?, ?> consumer) {
        double maximum = 0;
        for (TopicPartition partition : consumer.assignment()) {
            var value = consumer.currentLag(partition);
            if (value.isEmpty()) { maximum = Double.NaN; break; }
            maximum = Math.max(maximum, value.getAsLong());
        }
        synchronized (this) {
            lag.put(consumer, maximum);
            if (assigned.containsKey(consumer)) polled.add(consumer);
            notifyAll();
        }
    }
    /** @return 最慢分区延迟；未就绪或尚无观测时NaN，不能把未知报成零 */
    private synchronized double lag() {
        return ready() && !lag.isEmpty() ? lag.values().stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN) : Double.NaN;
    }
    /** 空闲时也更新延迟，故障恢复后不保留过期积压值。 */
    @EventListener public void idle(org.springframework.kafka.event.ListenerContainerIdleEvent event) {
        if (event.getListenerId().startsWith(LISTENER_ID) && event.getConsumer() != null) sampleLag(event.getConsumer());
    }
    /** SmartLifecycle在Kafka容器启动之后调用；超时拒绝TCP启动。 */
    public synchronized void awaitReady() {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!ready()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("TCP广播未在期限内就绪，拒绝开启TCP监听");
            try { wait(Math.max(1, Math.min(100, Duration.ofNanos(remaining).toMillis()))); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TCP广播就绪等待被中断", exception);
            }
        }
    }
    /** 致命认证/线程停止不应继续以旧分配声明就绪。 */
    @EventListener public synchronized void stopped(ConsumerStoppedEvent event) {
        if (event.getSource() instanceof MessageListenerContainer container
                && container.getListenerId().startsWith(LISTENER_ID)) { assigned.clear(); polled.clear(); lag.clear(); }
    }
}
