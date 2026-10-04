package com.things.link.support.kafka;

import com.things.link.support.trace.TraceContext;
import com.things.link.support.observability.KafkaConsumerProcessingMetrics;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 在 Kafka listener 执行前恢复 traceId，并在记录处理完成后清理 MDC。
 *
 * <p>消费者线程会处理不同项目、不同设备的多条记录；若不在 {@code afterRecord} 清理，
 * 下一条缺少 header 的消息会继承上一条 traceId，排障时得到错误因果链。</p>
 */
public final class KafkaTraceRecordInterceptor implements RecordInterceptor<Object, Object> {

    /** 逐记录低基数耗时与结果指标。 */
    private final KafkaConsumerProcessingMetrics metrics;
    /** consumer 线程一次只处理一条 record，用线程局部保存计时起点。 */
    private final ThreadLocal<Long> startedNanos = new ThreadLocal<>();
    /** listener 处理期间的数据面路由范围；必须与 trace 一样在 afterRecord 清除。 */
    private final ThreadLocal<DatabaseWorkloadContext.Scope> workloadScope = new ThreadLocal<>();

    /** 不加载 Spring 的单元测试入口。 */
    public KafkaTraceRecordInterceptor() {
        this(new KafkaConsumerProcessingMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    /** @param metrics Kafka 逐记录指标 */
    public KafkaTraceRecordInterceptor(KafkaConsumerProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 从最后一个同名 header 恢复安全 traceId。
     *
     * @param record 当前消息
     * @param consumer 当前消费者
     * @return 原记录
     */
    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                     Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(TraceContext.TRACE_ID_KEY);
        String incoming = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        TraceContext.set(TraceContext.resolve(incoming));
        try {
            workloadScope.set(DatabaseWorkloadContext.enter(DatabaseWorkload.DATA));
            startedNanos.set(metrics.start(record.topic()));
            return record;
        } catch (RuntimeException exception) {
            DatabaseWorkloadContext.Scope scope = workloadScope.get();
            workloadScope.remove();
            if (scope != null) {
                scope.close();
            }
            TraceContext.clear();
            throw exception;
        }
    }

    /** listener 正常返回时登记成功；offset 仍由 record ack 在其后提交。 */
    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        finish(record, "success");
    }

    /** listener 抛错时登记失败；重试/DLQ 仍由现有 DefaultErrorHandler 决定。 */
    @Override
    public void failure(
            ConsumerRecord<Object, Object> record,
            Exception exception,
            Consumer<Object, Object> consumer) {
        finish(record, "failure");
    }

    /**
     * 无论 listener 成功还是失败都清理当前线程 MDC。
     *
     * @param record 已处理记录
     * @param consumer 当前消费者
     */
    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        TraceContext.clear();
        startedNanos.remove();
        DatabaseWorkloadContext.Scope scope = workloadScope.get();
        workloadScope.remove();
        if (scope != null) {
            scope.close();
        }
    }

    /** 一个回调只完成一次计时；afterRecord 仅负责兜底清理。 */
    private void finish(ConsumerRecord<Object, Object> record, String result) {
        Long started = startedNanos.get();
        if (started != null) {
            metrics.finish(record.topic(), started, result);
            startedNanos.remove();
        }
    }
}
