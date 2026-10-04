package com.things.link.rule.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.rule.domain.RuleDebugEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期限批清理已到保留截止时刻的规则调试事件。 */
@Component
@ConditionalOnProperty(
        prefix = "things-link.rule.debug-cleanup", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@DataPlaneDatabase
public class RuleDebugEventCleanupScheduler {

    /** 每轮最多删除 1000 行，积压由后续轮次追平，避免清理形成大事务。 */
    private static final int BATCH_SIZE = 1_000;

    /** 调试事件仓储。 */
    private final RuleDebugEventRepository repository;

    /** @param repository 调试事件仓储 */
    public RuleDebugEventCleanupScheduler(RuleDebugEventRepository repository) {
        this.repository = repository;
    }

    /** 多实例通过数据库 SKIP LOCKED 安全竞争，每分钟各清理一批到期事实。 */
    @Scheduled(
            initialDelayString = "${things-link.rule.debug-cleanup.initial-delay-millis:60000}",
            fixedDelayString = "${things-link.rule.debug-cleanup.fixed-delay-millis:60000}",
            scheduler = "maintenanceScheduler")
    public void cleanExpired() {
        repository.deleteExpired(BATCH_SIZE);
    }
}
