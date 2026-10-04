package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备面 TCP 的连接预算指标（接入合同 §6「另设握手、连接……预算」）。
 *
 * <p>连接数必须在**接受连接时**就计入：慢握手（slowloris）占用的正是这个额度，若只在认证成功后计数，
 * 攻击者可以用大量半开连接把额度耗尽而指标上一条都不显示。因此这里的计数含尚未完成认证的连接。</p>
 *
 * <p>与 {@code BrokerHandoffMetrics} 同一口径：指标名固定、基数低（只有两个），无注册表时退回进程内注册表，
 * 不让隔离测试因缺少 Actuator 而失败。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component
public class DeviceAccessTcpMetrics {

    /** 当前本实例已接受的 TCP 连接数（含握手中、未认证的连接）。 */
    public static final String ACTIVE_CONNECTIONS = "thingslink.access.tcp.connections.active";

    /** 因超出连接预算被拒绝的连接数。 */
    public static final String REJECTED_CONNECTIONS = "thingslink.access.tcp.connections.rejected";

    /** 当前连接数；由服务端在接受／结束连接时维护。 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /** 超预算拒绝计数器。 */
    private final Counter rejectedConnections;

    /**
     * @param provider 可选统一注册表；隔离测试缺少 Actuator 时使用进程内注册表
     */
    @Autowired
    public DeviceAccessTcpMetrics(ObjectProvider<MeterRegistry> provider) {
        MeterRegistry registry = provider.getIfAvailable(SimpleMeterRegistry::new);
        Gauge.builder(ACTIVE_CONNECTIONS, activeConnections, AtomicInteger::get).register(registry);
        this.rejectedConnections = registry.counter(REJECTED_CONNECTIONS);
    }

    /** @return 计入后的当前连接数 */
    int enterConnection() {
        return activeConnections.incrementAndGet();
    }

    /** 结束一条连接；与服务端 {@code enterConnection} 一一对应。 */
    void leaveConnection() {
        activeConnections.decrementAndGet();
    }

    /** 记录一次因超出连接预算被拒绝的连接。 */
    void recordRejectedConnection() {
        rejectedConnections.increment();
    }

    /** @return 当前本实例已接受的连接数 */
    public int activeConnections() {
        return activeConnections.get();
    }
}
