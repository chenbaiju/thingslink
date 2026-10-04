package com.things.link.telemetry.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 时序点写入结果的低基数指标门面。
 *
 * <p>架构文档 13.1 要求时序写入失败持续一分钟即告警。指标按固定成功/失败结果聚合，
 * 不加入项目、设备、属性键或异常类，详细定位继续依赖带 traceId 的结构化日志。</p>
 */
@Component
public class TimeSeriesWriteMetrics {

    /** 时序写结果计数器名称；Prometheus 会追加 {@code _total}。 */
    public static final String WRITES = "thingslink.telemetry.timeseries.write";

    /** 固定结果到计数器的映射。 */
    private final Map<Result, Counter> writes = new EnumMap<>(Result.class);

    /**
     * 注册成功与失败两个固定标签组合。
     *
     * @param meterRegistry Micrometer 注册表
     */
    public TimeSeriesWriteMetrics(MeterRegistry meterRegistry) {
        for (Result result : Result.values()) {
            writes.put(result, Counter.builder(WRITES)
                    .description("TimescaleDB属性时序点写入结果")
                    .tag("result", result.tagValue)
                    .register(meterRegistry));
        }
    }

    /** 记录一个已由 JDBC 成功执行写入的属性点；事务最终提交仍由上层边界决定。 */
    public void recordSuccess() {
        writes.get(Result.SUCCESS).increment();
    }

    /** 记录一个写入失败且将由 Kafka 重投恢复的属性点。 */
    public void recordFailure() {
        writes.get(Result.FAILURE).increment();
    }

    /** Prometheus 允许使用的固定写入结果。 */
    private enum Result {
        /** 属性点已提交给 JDBC 写入。 */
        SUCCESS("success"),
        /** JDBC 写入抛出异常。 */
        FAILURE("failure");

        /** 低基数标签值。 */
        private final String tagValue;

        /**
         * @param tagValue Prometheus 标签值
         */
        Result(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
