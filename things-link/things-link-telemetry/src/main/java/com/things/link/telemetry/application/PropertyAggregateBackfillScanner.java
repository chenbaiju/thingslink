package com.things.link.telemetry.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.telemetry.domain.PropertyAggregateBackfillRepository;
import com.things.link.telemetry.domain.PropertyAggregateBackfillRepository.BackfillWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 领取持久迟到窗口，按依赖顺序刷新连续聚合并在 raw 对账后完成。 */
@Component
@DataPlaneDatabase
public class PropertyAggregateBackfillScanner {

    /** 日志只保留窗口身份与异常类型，不输出属性值或完整异常消息。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PropertyAggregateBackfillScanner.class);
    /** 持久回补端口。 */
    private final PropertyAggregateBackfillRepository repository;
    /** 固定结果指标。 */
    private final PropertyAggregateBackfillMetrics metrics;

    /**
     * @param repository 持久回补端口
     * @param metrics 固定结果指标
     */
    public PropertyAggregateBackfillScanner(PropertyAggregateBackfillRepository repository,
                                            PropertyAggregateBackfillMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /** 每十秒领取最多四个单日窗口；重型 Timescale 维护不占满 maintenanceScheduler 两条线程。 */
    @Scheduled(
            fixedDelayString = "${things-link.telemetry.aggregate-backfill.scan-millis:10000}",
            initialDelayString = "${things-link.telemetry.aggregate-backfill.initial-delay-millis:10000}",
            scheduler = "maintenanceScheduler")
    public void scan() {
        for (BackfillWindow window : repository.claimDue(4)) {
            process(window);
        }
    }

    /**
     * 单窗口失败不阻断同批其他窗口；失败请求持久保留并有界退避。
     *
     * @param window 已领取窗口
     */
    void process(BackfillWindow window) {
        try {
            repository.refresh(window);
            long mismatches = repository.mismatchCount(window);
            if (mismatches != 0) {
                throw new IllegalStateException("属性聚合回补对账仍存在差异桶: " + mismatches);
            }
            if (repository.complete(window)) {
                metrics.recordSuccess();
            }
        } catch (RuntimeException exception) {
            metrics.recordFailure();
            String failureType = exception.getClass().getSimpleName();
            try {
                repository.fail(window, failureType);
            } catch (RuntimeException releaseException) {
                exception.addSuppressed(releaseException);
            }
            LOGGER.error("属性聚合回补失败 projectId={} windowStart={} errorType={}",
                    window.projectId(), window.windowStart(), failureType, exception);
        }
    }
}
