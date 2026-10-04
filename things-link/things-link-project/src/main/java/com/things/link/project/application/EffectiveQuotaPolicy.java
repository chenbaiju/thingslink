package com.things.link.project.application;

import com.things.link.project.domain.plan.EffectivePlanQuota;

import java.util.UUID;

/**
 * 已解析到租户的运行时配额策略快照。
 *
 * <p>字段保留 {@code null} 的“不限”语义；调用方不得把它转换为 {@code 0}，否则会把套餐未设置的
 * 上限误判为禁用。该快照只供短窗口保护读取，日计费事实仍由 {@code sys_usage_counter_daily} 承担。
 *
 * @param tenantId 租户 ID
 * @param policyId 被绑定策略 ID
 * @param assignmentVersion 租户绑定版本，优先决定失效事件顺序
 * @param policyVersion 可复用策略模板版本
 * @param uplinkDeviceRefillPerSecond 单设备上行令牌桶每秒补充数
 * @param uplinkDeviceBurstCapacity 单设备上行令牌桶突发容量
 * @param uplinkTenantPerSecondLimit 租户共享上行每秒上限
 * @param uplinkTenantPerMinuteLimit 租户共享上行每分钟上限
 * @param restApiReadRatePerSecond 读 REST 单账号每秒上限
 * @param restApiWriteRatePerSecond 写 REST 单账号每秒上限
 * @param restApiReadRatePerMinute 读 REST 项目及租户每分钟上限
 * @param restApiWriteRatePerMinute 写 REST 项目及租户每分钟上限
 * @param websocketConnectionLimit 租户共享并发 WebSocket 连接上限
 * @param taskProjectDispatchPerSecond 单项目批量任务每秒设备命令派发上限
 * @param taskTenantDispatchPerSecond 租户共享批量任务每秒设备命令派发上限
 * @param ruleTenantConcurrencyLimit 租户共享规则执行并发上限
 * @param ruleTenantQueueCapacity 租户共享规则执行等待容量
 * @param ruleProjectQueueCapacity 单项目规则执行等待保护容量
 * @param dailySoftLimitBasisPoints UTC 日额度软限阈值基点
 * @param dailyDegradeBasisPoints UTC 日额度降级阈值基点
 * @param planQuota 该租户当前模板承载的产品修订版冻结配额（S14-1b）；
 *        只有 {@code sys_quota_policy.plan_template = true} 的模板才有此投影，
 *        既有 S7 运行时模板为 {@code null}。它表示「没有可售套餐模板」，不是「不限」，
 *        调用方不得据此放行未交付能力。
 */
public record EffectiveQuotaPolicy(
        UUID tenantId,
        UUID policyId,
        long assignmentVersion,
        long policyVersion,
        Long uplinkDeviceRefillPerSecond,
        Long uplinkDeviceBurstCapacity,
        Long uplinkTenantPerSecondLimit,
        Long uplinkTenantPerMinuteLimit,
        Long restApiReadRatePerSecond,
        Long restApiWriteRatePerSecond,
        Long restApiReadRatePerMinute,
        Long restApiWriteRatePerMinute,
        Long websocketConnectionLimit,
        Long taskProjectDispatchPerSecond,
        Long taskTenantDispatchPerSecond,
        Long ruleTenantConcurrencyLimit,
        Long ruleTenantQueueCapacity,
        Long ruleProjectQueueCapacity,
        int dailySoftLimitBasisPoints,
        int dailyDegradeBasisPoints,
        EffectivePlanQuota planQuota) {

    /** 冷启动安全默认的虚拟策略 ID，不能与 PostgreSQL 中的 UUIDv7 业务 ID 混用。 */
    private static final UUID SAFE_DEFAULT_POLICY_ID = new UUID(0L, 1L);

    /**
     * 兼容 S7 既有完整构造点（不含 S14-1b 产品配额投影）。
     *
     * <p>既有数据面与测试只关心 S7 的短窗口阈值；{@code planQuota} 为 {@code null} 表示
     * 「没有绑定可售套餐模板」，不是「不限」。
     *
     * @param tenantId 租户 ID
     * @param policyId 策略 ID
     * @param assignmentVersion 绑定版本
     * @param policyVersion 策略版本
     * @param uplinkDeviceRefillPerSecond 设备上行每秒补充数
     * @param uplinkDeviceBurstCapacity 设备上行突发容量
     * @param uplinkTenantPerSecondLimit 租户上行每秒上限
     * @param uplinkTenantPerMinuteLimit 租户上行每分钟上限
     * @param restApiReadRatePerSecond 读 REST 每秒上限
     * @param restApiWriteRatePerSecond 写 REST 每秒上限
     * @param restApiReadRatePerMinute 读 REST 每分钟上限
     * @param restApiWriteRatePerMinute 写 REST 每分钟上限
     * @param websocketConnectionLimit WebSocket 连接上限
     * @param taskProjectDispatchPerSecond 项目任务派发上限
     * @param taskTenantDispatchPerSecond 租户任务派发上限
     * @param ruleTenantConcurrencyLimit 租户规则并发上限
     * @param ruleTenantQueueCapacity 租户规则等待容量
     * @param ruleProjectQueueCapacity 项目规则等待容量
     * @param dailySoftLimitBasisPoints 日额度软限基点
     * @param dailyDegradeBasisPoints 日额度降级基点
     */
    public EffectiveQuotaPolicy(
            UUID tenantId,
            UUID policyId,
            long assignmentVersion,
            long policyVersion,
            Long uplinkDeviceRefillPerSecond,
            Long uplinkDeviceBurstCapacity,
            Long uplinkTenantPerSecondLimit,
            Long uplinkTenantPerMinuteLimit,
            Long restApiReadRatePerSecond,
            Long restApiWriteRatePerSecond,
            Long restApiReadRatePerMinute,
            Long restApiWriteRatePerMinute,
            Long websocketConnectionLimit,
            Long taskProjectDispatchPerSecond,
            Long taskTenantDispatchPerSecond,
            Long ruleTenantConcurrencyLimit,
            Long ruleTenantQueueCapacity,
            Long ruleProjectQueueCapacity,
            int dailySoftLimitBasisPoints,
            int dailyDegradeBasisPoints) {
        this(tenantId, policyId, assignmentVersion, policyVersion,
                uplinkDeviceRefillPerSecond, uplinkDeviceBurstCapacity,
                uplinkTenantPerSecondLimit, uplinkTenantPerMinuteLimit,
                restApiReadRatePerSecond, restApiWriteRatePerSecond,
                restApiReadRatePerMinute, restApiWriteRatePerMinute,
                websocketConnectionLimit, taskProjectDispatchPerSecond, taskTenantDispatchPerSecond,
                ruleTenantConcurrencyLimit, ruleTenantQueueCapacity, ruleProjectQueueCapacity,
                dailySoftLimitBasisPoints, dailyDegradeBasisPoints, null);
    }

    /**
     * 把产品修订版冻结配额附加到同一份快照上。
     *
     * <p>JDBC 读取会把 {@code sys_quota_policy.plan_template} 行的冻结值补进来；不得在内存中
     * 用代码默认伪造套餐值。
     *
     * @param quota 已解析的冻结配额投影
     * @return 携带冻结配额的新快照
     */
    public EffectiveQuotaPolicy withPlanQuota(EffectivePlanQuota quota) {
        return new EffectiveQuotaPolicy(tenantId, policyId, assignmentVersion, policyVersion,
                uplinkDeviceRefillPerSecond, uplinkDeviceBurstCapacity,
                uplinkTenantPerSecondLimit, uplinkTenantPerMinuteLimit,
                restApiReadRatePerSecond, restApiWriteRatePerSecond,
                restApiReadRatePerMinute, restApiWriteRatePerMinute,
                websocketConnectionLimit, taskProjectDispatchPerSecond, taskTenantDispatchPerSecond,
                ruleTenantConcurrencyLimit, ruleTenantQueueCapacity, ruleProjectQueueCapacity,
                dailySoftLimitBasisPoints, dailyDegradeBasisPoints, quota);
    }

    /**
     * 兼容 S7-4 完整运行时策略构造点，并补上 S7-5 的 80%/120% 日额度分级基线。
     *
     * @param tenantId 租户 ID
     * @param policyId 策略 ID
     * @param assignmentVersion 绑定版本
     * @param policyVersion 策略版本
     * @param uplinkDeviceRefillPerSecond 设备上行每秒补充数
     * @param uplinkDeviceBurstCapacity 设备上行突发容量
     * @param uplinkTenantPerSecondLimit 租户上行每秒上限
     * @param uplinkTenantPerMinuteLimit 租户上行每分钟上限
     * @param restApiReadRatePerSecond 读 REST 每秒上限
     * @param restApiWriteRatePerSecond 写 REST 每秒上限
     * @param restApiReadRatePerMinute 读 REST 每分钟上限
     * @param restApiWriteRatePerMinute 写 REST 每分钟上限
     * @param websocketConnectionLimit WebSocket 连接上限
     * @param taskProjectDispatchPerSecond 项目任务派发上限
     * @param taskTenantDispatchPerSecond 租户任务派发上限
     */
    public EffectiveQuotaPolicy(
            UUID tenantId,
            UUID policyId,
            long assignmentVersion,
            long policyVersion,
            Long uplinkDeviceRefillPerSecond,
            Long uplinkDeviceBurstCapacity,
            Long uplinkTenantPerSecondLimit,
            Long uplinkTenantPerMinuteLimit,
            Long restApiReadRatePerSecond,
            Long restApiWriteRatePerSecond,
            Long restApiReadRatePerMinute,
            Long restApiWriteRatePerMinute,
            Long websocketConnectionLimit,
            Long taskProjectDispatchPerSecond,
            Long taskTenantDispatchPerSecond) {
        this(tenantId, policyId, assignmentVersion, policyVersion,
                uplinkDeviceRefillPerSecond, uplinkDeviceBurstCapacity,
                uplinkTenantPerSecondLimit, uplinkTenantPerMinuteLimit,
                restApiReadRatePerSecond, restApiWriteRatePerSecond,
                restApiReadRatePerMinute, restApiWriteRatePerMinute,
                websocketConnectionLimit, taskProjectDispatchPerSecond, taskTenantDispatchPerSecond,
                4L, 100L, 20L,
                8000, 12000);
    }

    /**
     * 兼容 S7-3 期间只关心上行、REST 与 WebSocket 的内部构造点，并补上 S7-4 有限任务基线。
     *
     * <p>保留该构造器可避免其他数据面测试为了无关任务字段反复铺陈参数；生产 JDBC 映射始终使用完整构造器，
     * 不会用代码默认掩盖数据库里的 {@code null=不限}。</p>
     */
    public EffectiveQuotaPolicy(
            UUID tenantId,
            UUID policyId,
            long assignmentVersion,
            long policyVersion,
            Long uplinkDeviceRefillPerSecond,
            Long uplinkDeviceBurstCapacity,
            Long uplinkTenantPerSecondLimit,
            Long uplinkTenantPerMinuteLimit,
            Long restApiReadRatePerSecond,
            Long restApiWriteRatePerSecond,
            Long restApiReadRatePerMinute,
            Long restApiWriteRatePerMinute,
            Long websocketConnectionLimit) {
        this(tenantId, policyId, assignmentVersion, policyVersion,
                uplinkDeviceRefillPerSecond, uplinkDeviceBurstCapacity,
                uplinkTenantPerSecondLimit, uplinkTenantPerMinuteLimit,
                restApiReadRatePerSecond, restApiWriteRatePerSecond,
                restApiReadRatePerMinute, restApiWriteRatePerMinute,
                websocketConnectionLimit, 20L, 100L, 4L, 100L, 20L, 8000, 12000);
    }

    /**
     * 返回无法读取权威策略时使用的有限安全默认。
     *
     * <p>这里故意不是“不限”：Redis 或数据库短暂不可用时，保护面必须仍有上界；待数据库恢复后缓存会回源
     * 到真实套餐。D-181：REST分钟速率、WebSocket及规则并发按product-revision-1的FREE保护值；
     * 其余未售卖的保护旋钮保留S7有限基线。此默认不构造租户套餐投影或商业权益。
     *
     * @param tenantId 可信调用方已经确定的租户 ID
     * @return 有限的代码安全默认策略
     */
    public static EffectiveQuotaPolicy safeDefault(UUID tenantId) {
        var floor = com.things.link.project.domain.plan.ProductRevision1.quotaTemplates().get("FREE");
        return new EffectiveQuotaPolicy(tenantId, SAFE_DEFAULT_POLICY_ID, 0L, 0L,
                10L, 20L, 1_000L, 60_000L, 20L, 10L, floor.restApiRatePerMinute(),
                floor.restApiRatePerMinute(), floor.websocketConnectionLimit(),
                20L, 100L, floor.scriptRuleConcurrency(), 100L, 20L, 8000, 12000);
    }
}
