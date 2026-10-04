package com.things.link.project.application;

import com.things.link.project.domain.QuotaMetric;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 部署级权益选择。原平台默认沿用商业套餐；独立非商业部署必须显式给出有限技术容量。
 * 此处不修改计量事实、认证、RLS、短窗口限流或消息背压。
 */
@Component
public final class DeploymentEntitlementPolicy {
    private static final String PREFIX = "things-link.deployment.noncommercial-capacity.";

    public enum Capacity {
        PROJECTS("projects"), DEVICES("devices"), END_USERS("end-users"),
        DASHBOARDS("dashboards"), EXTERNAL_SEATS("external-seats"),
        HISTORY_DAYS("history-days");

        private final String key;

        Capacity(String key) { this.key = key; }
    }

    private final boolean nonCommercial;
    private final Map<Capacity, Long> capacities;
    private final Map<QuotaMetric, Long> dailyLimits;

    private DeploymentEntitlementPolicy() {
        nonCommercial = false;
        capacities = Map.of();
        dailyLimits = Map.of();
    }

    static DeploymentEntitlementPolicy commercial() { return new DeploymentEntitlementPolicy(); }

    @Autowired
    public DeploymentEntitlementPolicy(Environment environment) {
        String mode = environment.getProperty("things-link.deployment.entitlement-mode", "COMMERCIAL");
        if (!"COMMERCIAL".equals(mode) && !"NONCOMMERCIAL".equals(mode)) {
            throw new IllegalStateException("未知部署权益模式：things-link.deployment.entitlement-mode");
        }
        nonCommercial = "NONCOMMERCIAL".equals(mode);
        capacities = new EnumMap<>(Capacity.class);
        dailyLimits = new EnumMap<>(QuotaMetric.class);
        if (!nonCommercial) return;
        for (Capacity capacity : Capacity.values()) {
            long limit = positive(environment, PREFIX + capacity.key);
            if (capacity == Capacity.HISTORY_DAYS && limit > 36_500) {
                throw new IllegalStateException("历史窗口技术容量不得超过 36500 天：" + PREFIX + capacity.key);
            }
            capacities.put(capacity, limit);
        }
        for (QuotaMetric metric : QuotaMetric.values()) {
            if (metric.dailyCounter()) {
                dailyLimits.put(metric, positive(environment,
                        PREFIX + "daily." + metric.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
            }
        }
    }

    public boolean nonCommercial() { return nonCommercial; }

    public long capacity(Capacity capacity) {
        if (!nonCommercial) throw new IllegalStateException("商业模式不得读取非商业技术容量");
        return capacities.get(capacity);
    }

    public long dailyLimit(QuotaMetric metric) {
        if (!nonCommercial || !metric.dailyCounter()) {
            throw new IllegalStateException("当前模式或指标不支持非商业日容量");
        }
        return dailyLimits.get(metric);
    }

    /** 向运行时公开自动化技术容量，避免跨模块引用 project.domain。 */
    public long automationExecutionDailyLimit() {
        return dailyLimit(QuotaMetric.AUTOMATION_EXECUTION);
    }

    private static long positive(Environment environment, String name) {
        String value = environment.getProperty(name);
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (RuntimeException ignored) {
            // 缺失、非整数与溢出都在同一稳定错误里拒绝启动，不回落到 FREE 档。
        }
        throw new IllegalStateException("非商业技术容量必须为正整数：" + name);
    }
}
