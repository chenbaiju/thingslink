package com.things.link.telemetry.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 跨项目领取到期命令，再逐条进入独立业务事务执行重试或最终超时。 */
@Component
@DataPlaneDatabase
public class DeviceCommandTimeoutScanner {
    /** 单条失败不应中断同批其他项目。 */ private static final Logger LOGGER = LoggerFactory.getLogger(DeviceCommandTimeoutScanner.class);
    /** 命令仓储只通过受控函数返回最小 ID 投影。 */ private final DeviceCommandRepository repository;
    /** 独立应用服务 Bean 保证每条命令经过 Spring 事务代理。 */ private final DeviceCommandService service;
    /** 聚合指标不含租户或命令标识。 */ private final DeviceCommandMetrics metrics;
    /** 创建扫描器。 */
    public DeviceCommandTimeoutScanner(DeviceCommandRepository repository, DeviceCommandService service, DeviceCommandMetrics metrics) {
        this.repository = repository; this.service = service; this.metrics = metrics;
    }
    /**
     * 每秒扫描最多 100 条；数据库短租约允许崩溃实例在 30 秒后被接管。
     *
     * <p>初始延迟可在集成测试中单独拉长，避免后台扫描器抢走测试刚准备好的到期事实；生产默认仍在启动一秒后开始，
     * 不能用关闭整个 Spring 调度器的方式规避竞争，否则 Outbox 等无关调度也会被一并掩盖。</p>
     */
    @Scheduled(
            fixedDelayString = "${things-link.command.timeout-scan-millis:1000}",
            initialDelayString = "${things-link.command.timeout-scan-initial-delay-millis:1000}",
            scheduler = "commandLifecycleScheduler")
    public void scan() {
        metrics.observeOldestPendingAge(repository.oldestPendingAgeSeconds());
        for (DeviceCommandRepository.DueCommand due : repository.claimDue(100)) {
            try { service.processDue(due); }
            catch (RuntimeException exception) {
                LOGGER.error("命令超时处理失败，等待租约过期重试 commandId={} projectId={}",
                        due.commandId(), due.projectId(), exception);
            }
        }
    }
}
