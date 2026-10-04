package com.things.link.ingestion.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Semaphore;

/** EMQX 四类固定操作的低基数 bulkhead 与断路器观测。 */
@Component
public class EmqxDispatchMetrics {

    /** 当前占用槽位。 */
    static final String BULKHEAD_ACTIVE = "thingslink.emqx.bulkhead.active";
    /** 无可用槽位的快速拒绝。 */
    static final String BULKHEAD_REJECTED = "thingslink.emqx.bulkhead.rejected";
    /** 命令断路器快速拒绝次数。 */
    static final String CIRCUIT_OPEN = "thingslink.emqx.circuit.open";

    /** 应用指标注册表。 */
    private final MeterRegistry registry;

    /** @param provider 模块测试不带 Actuator 时使用进程内注册表 */
    @Autowired
    public EmqxDispatchMetrics(ObjectProvider<MeterRegistry> provider) {
        this(provider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** @param registry 显式注册表，供单元测试使用 */
    EmqxDispatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 每个固定操作注册一个 active Gauge，禁止设备或租户进入标签。 */
    void registerBulkheads(Map<String, Semaphore> bulkheads, Map<String, Integer> capacities) {
        bulkheads.forEach((operation, semaphore) -> Gauge.builder(
                        BULKHEAD_ACTIVE,
                        semaphore,
                        value -> capacities.get(operation) - value.availablePermits())
                .description("EMQX 固定操作当前占用并发槽")
                .tag("operation", operation)
                .register(registry));
    }

    /** @param operation 固定操作名 */
    void recordBulkheadRejected(String operation) {
        counter(BULKHEAD_REJECTED, operation).increment();
    }

    /** 命令断路器 OPEN/half-open 在途时的快速拒绝。 */
    void recordCircuitOpen() {
        counter(CIRCUIT_OPEN, "COMMAND").increment();
    }

    /** @return 固定 operation 标签计数器 */
    private Counter counter(String name, String operation) {
        return Counter.builder(name)
                .tag("operation", operation)
                .register(registry);
    }
}
