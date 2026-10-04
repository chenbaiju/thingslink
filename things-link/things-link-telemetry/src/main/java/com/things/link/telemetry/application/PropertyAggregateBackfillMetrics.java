package com.things.link.telemetry.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 属性聚合回补与 raw 对账结果的低基数指标。
 *
 * <p>D-042 禁止把项目、设备、属性或异常类放入标签；失败详情只进入限长日志和持久请求。</p>
 */
@Component
public class PropertyAggregateBackfillMetrics {

    /** 回补结果计数器名称；Prometheus 导出时追加 {@code _total}。 */
    public static final String BACKFILLS = "thingslink.telemetry.aggregate.backfill";

    /** 固定结果到计数器的映射。 */
    private final Map<Result, Counter> counters = new EnumMap<>(Result.class);

    /**
     * @param meterRegistry Micrometer 注册表
     */
    public PropertyAggregateBackfillMetrics(MeterRegistry meterRegistry) {
        for (Result result : Result.values()) {
            counters.put(result, Counter.builder(BACKFILLS)
                    .description("TimescaleDB属性连续聚合回补及raw对账结果")
                    .tag("result", result.tagValue)
                    .register(meterRegistry));
        }
    }

    /** 记录一个已刷新、对账一致且 revision 稳定的窗口。 */
    public void recordSuccess() {
        counters.get(Result.SUCCESS).increment();
    }

    /** 记录一个刷新失败或对账仍存在差异的窗口。 */
    public void recordFailure() {
        counters.get(Result.FAILURE).increment();
    }

    /** 固定的 Prometheus 结果标签。 */
    private enum Result {
        /** 窗口刷新和三层对账完成。 */
        SUCCESS("success"),
        /** 数据库刷新失败或 raw/聚合仍不一致。 */
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
