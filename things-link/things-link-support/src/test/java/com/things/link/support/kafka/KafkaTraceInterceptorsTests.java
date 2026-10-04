package com.things.link.support.kafka;

import com.things.link.support.trace.TraceContext;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Kafka traceId header 传播、输入清洗与消费线程清理。 */
class KafkaTraceInterceptorsTests {

    /** 每个用例后清理测试线程 MDC，防止失败用例污染后续断言。 */
    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    /** producer 必须覆盖旧 header，并写入当前请求 traceId。 */
    @Test
    void producerWritesCurrentTraceIdOnce() {
        TraceContext.set("0123456789abcdef0123456789abcdef");
        ProducerRecord<Object, Object> record = new ProducerRecord<>("topic", "key", "value");
        record.headers().add(TraceContext.TRACE_ID_KEY, "old".getBytes(StandardCharsets.UTF_8));

        new KafkaTraceProducerInterceptor().onSend(record);

        assertThat(record.headers().headers(TraceContext.TRACE_ID_KEY)).hasSize(1);
        assertThat(new String(record.headers().lastHeader(TraceContext.TRACE_ID_KEY).value(),
                StandardCharsets.UTF_8)).isEqualTo(TraceContext.current());
    }

    /** consumer 恢复安全 header，并在记录结束后清除复用线程。 */
    @Test
    void consumerRestoresAndClearsTraceId() {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("topic", 0, 0, "key", "value");
        record.headers().add(TraceContext.TRACE_ID_KEY,
                "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        KafkaTraceRecordInterceptor interceptor = new KafkaTraceRecordInterceptor();

        interceptor.intercept(record, null);
        assertThat(TraceContext.current()).isEqualTo("fedcba9876543210fedcba9876543210");
        assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);

        interceptor.afterRecord(record, null);
        assertThat(TraceContext.current()).isNull();
        assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
    }

    /** 恶意 header 不得进入 MDC；消费者应替换为安全的新 traceId。 */
    @Test
    void consumerReplacesUnsafeTraceId() {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("topic", 0, 0, "key", "value");
        record.headers().add(TraceContext.TRACE_ID_KEY, "valid\r\nforged".getBytes(StandardCharsets.UTF_8));

        KafkaTraceRecordInterceptor interceptor = new KafkaTraceRecordInterceptor();
        interceptor.intercept(record, null);

        assertThat(TraceContext.current()).matches("[0-9a-f]{32}");
        interceptor.afterRecord(record, null);
    }
}
