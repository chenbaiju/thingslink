package com.things.link.device.application;

import com.things.link.support.tenant.DataPlaneDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Modbus 平台驱动轮询的定时扫描器。
 *
 * <p>到期扫描与超时扫描分离：前者领取到期 IDLE 轮询并发送读请求，后者领取租约过期的在途请求做有限重试。
 * 两者通过 {@code FOR UPDATE SKIP LOCKED} 排除已锁定的轮询行；到期领取另持D-117网关事务锁。
 * 每个poll的状态与Outbox使用独立事务；当前poll失败只回滚自身并停止本轮扫描，此前已提交poll保持成功。</p>
 */
@Component
@DataPlaneDatabase
public class ModbusPollScheduler {

    /** 不打印寄存器原始值。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ModbusPollScheduler.class);

    /** 轮询引擎。 */
    private final ModbusPollService service;

    /** @param service 轮询引擎 */
    public ModbusPollScheduler(ModbusPollService service) {
        this.service = service;
    }

    /** 领取到期轮询并发送读请求。 */
    @Scheduled(fixedDelayString = "${things-link.device.modbus-scan-millis:500}",
            scheduler = "modbusLifecycleScheduler")
    public void scanDue() {
        try {
            service.scanDue(100);
        } catch (RuntimeException exception) {
            LOGGER.error("Modbus 到期轮询扫描失败", exception);
        }
    }

    /** 领取租约过期的在途请求做有限重试或释放。 */
    @Scheduled(fixedDelayString = "${things-link.device.modbus-timeout-millis:1000}",
            scheduler = "modbusLifecycleScheduler")
    public void scanTimeout() {
        try {
            service.scanTimeout(100);
        } catch (RuntimeException exception) {
            LOGGER.error("Modbus 超时扫描失败", exception);
        }
    }
}
