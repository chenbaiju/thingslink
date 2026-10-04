package com.things.link.support.kafka;

import com.things.link.support.trace.TraceContext;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 把当前线程 traceId 写入每条 Kafka 消息的 header。
 *
 * <p>使用 Kafka 原生 interceptor 而不是要求每个 producer 手工加 header；漏掉一次就会让
 * HTTP/EMQX 到消费者之间的日志链断开，而且业务功能与测试可能仍然正常。</p>
 */
public final class KafkaTraceProducerInterceptor implements ProducerInterceptor<Object, Object> {

    /**
     * 写入或覆盖 traceId header。
     *
     * @param record 待发送记录
     * @return 带稳定 traceId 的原记录
     */
    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        String traceId = TraceContext.resolve(TraceContext.current());
        Headers headers = record.headers();
        // 重试或上游重复添加时只保留最后一个值，避免消费者选择规则产生歧义。
        headers.remove(TraceContext.TRACE_ID_KEY);
        headers.add(TraceContext.TRACE_ID_KEY, traceId.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /**
     * 发送确认不改变 trace 上下文；失败处理由 producer future 与后续重试策略负责。
     *
     * @param metadata broker 返回的元数据
     * @param exception 可选发送异常
     */
    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // 无状态 interceptor，无需处理确认回调。
    }

    /**
     * 接收 producer 配置；本实现没有自定义配置项。
     *
     * @param configs producer 配置
     */
    @Override
    public void configure(Map<String, ?> configs) {
        // 无配置项。
    }

    /** 关闭无状态 interceptor。 */
    @Override
    public void close() {
        // 无资源需要释放。
    }
}
