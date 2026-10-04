package com.things.link.device.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Modbus 点位配置下发与版本诊断的低基数指标。
 *
 * <p>只记录计数，不携带 gatewayId/projectId 等高基数字段；「版本不一致」由网关 ACK 落后于当前发布版本
 * 触发，用于观测「配置分发未收敛」的现场。</p>
 */
@Component
public class ModbusConfigMetrics {

    /** 网关已应用版本落后于当前发布版本的次数。 */
    public static final String MISMATCH = "thingslink.device.modbus.config_mismatch";

    /** 网关拒绝配置下发的次数。 */
    public static final String REJECTED = "thingslink.device.modbus.config_rejected";

    /** 版本不一致计数器。 */
    private final Counter mismatch;

    /** 拒绝计数器。 */
    private final Counter rejected;

    /**
     * @param meterRegistry Micrometer 注册表
     */
    public ModbusConfigMetrics(MeterRegistry meterRegistry) {
        this.mismatch = Counter.builder(MISMATCH)
                .description("网关已应用配置版本落后于当前发布版本的次数")
                .register(meterRegistry);
        this.rejected = Counter.builder(REJECTED)
                .description("网关拒绝配置下发的次数")
                .register(meterRegistry);
    }

    /** 记录一次版本不一致。 */
    public void recordMismatch() {
        mismatch.increment();
    }

    /** 记录一次配置拒绝。 */
    public void recordRejected() {
        rejected.increment();
    }
}
