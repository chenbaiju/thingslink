package com.things.link.device.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Modbus 平台驱动轮询的低基数指标。
 *
 * <p>只记录成功/失败计数，不携带网关或子设备 ID；失败涵盖超时耗尽与网关返回 ERROR。</p>
 */
@Component
public class ModbusPollMetrics {

    /** 轮询成功次数。 */
    public static final String SUCCESS = "thingslink.device.modbus.poll_success";

    /** 轮询失败（超时耗尽或网关返回 ERROR）次数。 */
    public static final String FAILED = "thingslink.device.modbus.poll_failed";

    /** 成功计数器。 */
    private final Counter success;

    /** 失败计数器。 */
    private final Counter failed;

    /**
     * @param meterRegistry Micrometer 注册表
     */
    public ModbusPollMetrics(MeterRegistry meterRegistry) {
        this.success = Counter.builder(SUCCESS)
                .description("Modbus 平台轮询成功的次数")
                .register(meterRegistry);
        this.failed = Counter.builder(FAILED)
                .description("Modbus 平台轮询失败（超时耗尽或网关返回 ERROR）的次数")
                .register(meterRegistry);
    }

    /** 记录一次轮询成功。 */
    public void recordSuccess() {
        success.increment();
    }

    /** 记录一次轮询失败。 */
    public void recordFailed() {
        failed.increment();
    }
}
