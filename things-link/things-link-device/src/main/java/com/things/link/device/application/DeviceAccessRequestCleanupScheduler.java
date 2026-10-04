package com.things.link.device.application;

import com.things.link.device.domain.DeviceAccessRequestRepository;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 周期限量清理保留期外的接入受理事实，使幂等窗口有界。
 *
 * <p>保留期与接入合同 §6 冻结的 7 天一致：短于设备可能的重试窗口会让重放被当成新尝试而重复落库，
 * 长于必要则只是白占容量。清理走 {@code SECURITY DEFINER} 数据库函数限量分批执行，跨项目但不看内容，
 * 因此不需要也不接受调用方提供项目范围。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "things-link.device.access-request-cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@DataPlaneDatabase
public class DeviceAccessRequestCleanupScheduler {

    /** 单轮最多删除 1000 行，持续高流量下也不会形成大事务。 */
    private static final int BATCH_SIZE = 1_000;

    /**
     * 幂等事实保留期，与接入合同 §6 冻结值一致。
     *
     * <p>公开常量的理由：验证保留语义的真库用例必须与生产删除阈值引用同一个值，否则改了一处而测试
     * 自己再写一个「7 天」，只会得到一条永远通过的假证据。</p>
     */
    public static final Duration RETENTION = Duration.ofDays(7);

    /** 受理事实仓储。 */
    private final DeviceAccessRequestRepository repository;

    /**
     * @param repository 受理事实仓储
     */
    public DeviceAccessRequestCleanupScheduler(DeviceAccessRequestRepository repository) {
        this.repository = repository;
    }

    /**
     * 每分钟清理一批；积压由后续轮次追平，多实例并发执行由数据库删除语义安全收敛。
     */
    @Scheduled(
            initialDelayString = "${things-link.device.access-request-cleanup.initial-delay-millis:60000}",
            fixedDelayString = "${things-link.device.access-request-cleanup.fixed-delay-millis:60000}",
            scheduler = "maintenanceScheduler")
    public void cleanExpired() {
        repository.deleteAcceptedBefore(Instant.now().minus(RETENTION), BATCH_SIZE);
    }
}
