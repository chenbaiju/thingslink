package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceReportedPropertiesCommitted;
import com.things.link.ingestion.infrastructure.RealtimeDispatchExecutorConfiguration;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import com.things.link.support.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 将已提交影子增量异步交给单组实时 Kafka 消费者。
 *
 * <p>这里刻意不等待 broker future：实时通道不是事实源，等待会让原始遥测 Kafka 消费线程被
 * 下游网络反压。进程在提交后、发送前崩溃可能漏掉增量，客户端重连后通过 REST 补拉正是 ADR 0016
 * 规定的恢复路径。</p>
 */
@Component
public class RealtimeKafkaPublisher {

    /** 实时增量主题；设备 ID key 保障同设备更新在消费组内有序。 */
    public static final String REALTIME_TOPIC = "tc.device.realtime";

    /** 只记录链路标识，禁止把属性值写到日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeKafkaPublisher.class);

    /** Kafka 生产入口。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;
    /** 实时链路低基数指标。 */
    private final RealtimeMetrics metrics;

    /**
     * @param kafkaTemplate 继承统一 追踪生产者拦截器 的 Kafka 模板
     * @param metrics 实时链路指标
     */
    public RealtimeKafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate, RealtimeMetrics metrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    /**
     * 仅在属性事实事务提交后异步发送实时消息。
     *
     * @param event CAS 已接受属性的不可变事件
     */
    @Async(RealtimeDispatchExecutorConfiguration.REALTIME_DISPATCH_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(DeviceReportedPropertiesCommitted event) {
        DeviceRealtimeUpdate update = event.update();
        // @Async 不继承 ThreadLocal；这里显式恢复，使统一 Kafka interceptor 仍写入原上行 traceId。
        TraceContext.set(update.traceId());
        try {
            kafkaTemplate.send(REALTIME_TOPIC, update.deviceId().toString(), update)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            metrics.recordKafkaPublished();
                        } else {
                            metrics.recordKafkaFailure();
                            LOGGER.warn("实时增量 Kafka 发布失败: messageId={} deviceId={}",
                                    update.messageId(), update.deviceId(), failure);
                        }
                    });
        } catch (RuntimeException failure) {
            // 例如 producer 已关闭或本地缓冲区满；提交已完成，绝不能重新抛回遥测链路。
            metrics.recordKafkaFailure();
            LOGGER.warn("实时增量 Kafka 提交失败: messageId={} deviceId={}",
                    update.messageId(), update.deviceId(), failure);
        } finally {
            TraceContext.clear();
        }
    }
}
