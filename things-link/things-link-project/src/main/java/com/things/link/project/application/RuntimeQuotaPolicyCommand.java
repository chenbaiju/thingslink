package com.things.link.project.application;

/**
 * 可复用套餐模板的 S7-3 运行时阈值更新命令。
 *
 * <p>所有 {@code Long} 字段保留 {@code null=不限}、{@code 0=禁用}；两个令牌桶参数必须同时为 null、
 * 同时为 0，或满足 {@code burst >= refill > 0}，与数据库 CHECK 形成双层约束。
 *
 * @param uplinkDeviceRefillPerSecond 单设备上行每秒补充数
 * @param uplinkDeviceBurstCapacity 单设备上行突发容量
 * @param uplinkTenantPerSecondLimit 租户上行每秒上限
 * @param uplinkTenantPerMinuteLimit 租户上行每分钟上限
 * @param restApiReadRatePerSecond 读 REST 单账号每秒上限
 * @param restApiWriteRatePerSecond 写 REST 单账号每秒上限
 * @param restApiReadRatePerMinute 读 REST 项目与租户每分钟上限
 * @param restApiWriteRatePerMinute 写 REST 项目与租户每分钟上限
 * @param websocketConnectionLimit 租户并发 WebSocket 连接上限
 * @param taskProjectDispatchPerSecond 单项目批量任务每秒派发上限
 * @param taskTenantDispatchPerSecond 租户共享批量任务每秒派发上限
 * @param ruleTenantConcurrencyLimit 租户共享规则执行并发上限
 * @param ruleTenantQueueCapacity 租户共享规则执行等待容量
 * @param ruleProjectQueueCapacity 单项目规则执行等待保护容量
 * @param dailySoftLimitBasisPoints UTC 日额度软限阈值基点
 * @param dailyDegradeBasisPoints UTC 日额度降级阈值基点
 */
public record RuntimeQuotaPolicyCommand(
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

    /**
     * 在进入数据库前校验所有数值及令牌桶成对语义，避免把约束错误拖到事务末尾。
     */
    public RuntimeQuotaPolicyCommand {
        nonNegative(uplinkTenantPerSecondLimit, "租户上行每秒上限");
        nonNegative(uplinkTenantPerMinuteLimit, "租户上行每分钟上限");
        nonNegative(restApiReadRatePerSecond, "读 REST 每秒上限");
        nonNegative(restApiWriteRatePerSecond, "写 REST 每秒上限");
        nonNegative(restApiReadRatePerMinute, "读 REST 每分钟上限");
        nonNegative(restApiWriteRatePerMinute, "写 REST 每分钟上限");
        nonNegative(websocketConnectionLimit, "WebSocket 连接上限");
        nonNegative(taskProjectDispatchPerSecond, "单项目任务派发每秒上限");
        nonNegative(taskTenantDispatchPerSecond, "租户任务派发每秒上限");
        nonNegative(ruleTenantConcurrencyLimit, "租户规则执行并发上限");
        nonNegative(ruleTenantQueueCapacity, "租户规则执行等待容量");
        nonNegative(ruleProjectQueueCapacity, "项目规则执行等待容量");
        validateBucket(uplinkDeviceRefillPerSecond, uplinkDeviceBurstCapacity, "设备上行");
        if (dailySoftLimitBasisPoints < 0 || dailySoftLimitBasisPoints > 10000
                || dailyDegradeBasisPoints < 10000) {
            throw new IllegalArgumentException("UTC 日额度分级阈值不合法");
        }
    }

    /**
     * 兼容 S7-4 完整运行时更新调用点，并补上首期日额度分级基线。
     *
     * @param uplinkDeviceRefillPerSecond 单设备上行每秒补充数
     * @param uplinkDeviceBurstCapacity 单设备上行突发容量
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
    public RuntimeQuotaPolicyCommand(
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
        this(uplinkDeviceRefillPerSecond, uplinkDeviceBurstCapacity,
                uplinkTenantPerSecondLimit, uplinkTenantPerMinuteLimit,
                restApiReadRatePerSecond, restApiWriteRatePerSecond,
                restApiReadRatePerMinute, restApiWriteRatePerMinute,
                websocketConnectionLimit, taskProjectDispatchPerSecond, taskTenantDispatchPerSecond,
                4L, 100L, 20L,
                8000, 12000);
    }

    /**
     * 兼容 S7-3 的内部调用点，并为新任务维度写入 FREE 有限基线。
     */
    public RuntimeQuotaPolicyCommand(
            Long uplinkDeviceRefillPerSecond,
            Long uplinkDeviceBurstCapacity,
            Long uplinkTenantPerSecondLimit,
            Long uplinkTenantPerMinuteLimit,
            Long restApiReadRatePerSecond,
            Long restApiWriteRatePerSecond,
            Long restApiReadRatePerMinute,
            Long restApiWriteRatePerMinute,
            Long websocketConnectionLimit) {
        this(uplinkDeviceRefillPerSecond, uplinkDeviceBurstCapacity,
                uplinkTenantPerSecondLimit, uplinkTenantPerMinuteLimit,
                restApiReadRatePerSecond, restApiWriteRatePerSecond,
                restApiReadRatePerMinute, restApiWriteRatePerMinute,
                websocketConnectionLimit, 20L, 100L, 4L, 100L, 20L, 8000, 12000);
    }

    /** 校验单值的 null/0/正数语义。 */
    private static void nonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
    }

    /** 校验令牌桶成对语义。 */
    private static void validateBucket(Long refill, Long burst, String name) {
        nonNegative(refill, name + "每秒补充数");
        nonNegative(burst, name + "突发容量");
        if ((refill == null) != (burst == null)) {
            throw new IllegalArgumentException(name + "令牌桶必须成对配置");
        }
        if (refill != null && ((refill == 0 && burst != 0) || (refill > 0 && burst < refill))) {
            throw new IllegalArgumentException(name + "令牌桶容量必须为 0 或不小于每秒补充数");
        }
    }
}
