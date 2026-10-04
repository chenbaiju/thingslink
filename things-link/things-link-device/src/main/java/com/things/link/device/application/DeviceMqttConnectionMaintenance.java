package com.things.link.device.application;

import com.things.link.device.domain.DeviceMqttConnectionMaintenanceRepository;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 原连接账本维护独立于公开Webhook开关；失败保留事实，由调度器记录并在下轮重试。 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(prefix = "things-link.device.mqtt-connection-maintenance", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DeviceMqttConnectionMaintenance {
    /** 固定有界维护端口。 */
    private final DeviceMqttConnectionMaintenanceRepository repository;
    /** 装配领域持久维护入口。 */
    public DeviceMqttConnectionMaintenance(DeviceMqttConnectionMaintenanceRepository repository) { this.repository = repository; }
    /** 默认每60秒一轮，每轮最多500条，不用应用时钟决定排序下界。 */
    @Scheduled(initialDelayString = "${things-link.device.mqtt-connection-maintenance.initial-delay-millis:60000}",
            fixedDelayString = "${things-link.device.mqtt-connection-maintenance.fixed-delay-millis:60000}",
            scheduler = "maintenanceScheduler")
    public void tick() { repository.cleanExpired(); }
}
