package com.things.link.support.idempotency;

import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期限批清理超过 24 小时重试窗口的幂等记录。 */
@Component
@ConditionalOnProperty(
        prefix = "things-link.idempotency.cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@DataPlaneDatabase
public class IdempotencyCleanupScheduler {

    /** 每分钟最多删除 1000 行，持续流量下也不会形成大事务或长时间持锁。 */
    private static final int BATCH_SIZE = 1_000;

    /** PostgreSQL 事实存储。 */
    private final IdempotencyStore store;

    /** @param store 幂等事实存储 */
    public IdempotencyCleanupScheduler(IdempotencyStore store) {
        this.store = store;
    }

    /** 延迟一分钟启动并每分钟清一批；过期记录多时由后续轮次逐步追平。 */
    @Scheduled(
            initialDelayString = "${things-link.idempotency.cleanup.initial-delay-millis:60000}",
            fixedDelayString = "${things-link.idempotency.cleanup.fixed-delay-millis:60000}",
            scheduler = "maintenanceScheduler")
    public void cleanExpired() {
        store.deleteExpired(BATCH_SIZE);
    }
}
