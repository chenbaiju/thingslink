package com.things.link.support.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 发布 CONTROL/DATA 两个物理连接池的主动数据库可用性。
 *
 * <p>G1-C4b-F2 不从某条业务 SQL 的失败位置推断整体停库，而由独立 {@code SELECT 1}
 * 探针更新本指标。标签只有冻结的池名，不得加入 SQL、异常类型、租户或项目。</p>
 */
@Component
public class DatabaseAvailabilityMetrics {

    /** Prometheus 导出名为 {@code thingslink_database_available}。 */
    static final String DATABASE_AVAILABLE = "thingslink.database.available";
    /** 未完成首次探测；告警只匹配零，避免应用启动期间误报。 */
    static final int UNKNOWN = -1;
    /** 两个冻结物理池的状态载体。 */
    private final Map<String, AtomicInteger> states = Map.of(
            "control", new AtomicInteger(UNKNOWN),
            "data", new AtomicInteger(UNKNOWN));

    /**
     * Spring 装配入口；不带 Actuator 的模块测试使用进程内注册表，不能阻断业务上下文。
     *
     * @param registries 可选的应用统一指标注册表
     */
    @Autowired
    public DatabaseAvailabilityMetrics(ObjectProvider<MeterRegistry> registries) {
        this(registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 注册两个固定 Gauge；值为 -1 未探测、0 不可用、1 可用。
     *
     * @param registry 应用统一指标注册表
     */
    public DatabaseAvailabilityMetrics(MeterRegistry registry) {
        states.forEach((pool, state) -> Gauge.builder(DATABASE_AVAILABLE, state, AtomicInteger::get)
                .description("物理数据库连接池主动 SELECT 1 可用性：-1 未探测、0 不可用、1 可用")
                .tag("pool", pool)
                .register(registry));
    }

    /**
     * 更新一次探测状态。
     *
     * @param pool 冻结池名 control 或 data
     * @param available 本次 SELECT 1 是否成功
     * @return 状态是否发生变化，用于只在转换边界记录日志
     */
    public boolean record(String pool, boolean available) {
        AtomicInteger state = states.get(pool);
        if (state == null) {
            throw new IllegalArgumentException("数据库可用性指标只接受 control/data 池");
        }
        int current = available ? 1 : 0;
        return state.getAndSet(current) != current;
    }

    /**
     * 返回当前机器状态，供组件测试核对，不向生产调用方暴露可变对象。
     *
     * @param pool 冻结池名
     * @return -1、0 或 1
     */
    int state(String pool) {
        AtomicInteger state = states.get(pool);
        if (state == null) {
            throw new IllegalArgumentException("数据库可用性指标只接受 control/data 池");
        }
        return state.get();
    }
}
