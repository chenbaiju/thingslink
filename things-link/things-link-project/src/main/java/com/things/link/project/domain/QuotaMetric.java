package com.things.link.project.domain;

/**
 * 配额策略和日用量事实共用的冻结指标枚举。
 *
 * <p>编码与迁移 {@code sys_usage_counter_daily_metric_ck} 必须同步演进。字符串不直接散落在
 * 各业务模块，是为了避免 S7-3 接入事件时把同一个计量维度写成两种账单口径。
 */
public enum QuotaMetric {

    /** 设备存量；使用 {@code dev_device} 事实实时聚合，不进入日计数表。 */
    DEVICE_COUNT,
    /** UTC 日上行消息条数。 */
    UPLINK_MESSAGE,
    /** UTC 日下行消息条数。 */
    DOWNLINK_MESSAGE,
    /** UTC 日上行原始字节数。 */
    UPLINK_BYTES,
    /** UTC 日成功写入的时序点数。 */
    TIME_SERIES_POINT,
    /** UTC 日 REST API 调用次数。 */
    REST_API_CALL,
    /** 并发 WebSocket 连接数；使用运行时注册事实，不进入日计数表。 */
    WEBSOCKET_CONNECTION,
    /** UTC 日通知投递次数。 */
    NOTIFICATION_DELIVERY,
    /** UTC 日脚本执行次数；S8 前只冻结编码，不启用配额。 */
    SCRIPT_EXECUTION,
    /** UTC日自动化执行原事务预留次数，与脚本计量分开。 */
    AUTOMATION_EXECUTION,
    /** UTC 日脚本 CPU 毫秒；S8 前只冻结编码，不启用配额。 */
    SCRIPT_CPU_MILLIS,
    /** 当前对象存储字节数；S13 前只冻结编码，不启用配额。 */
    STORAGE_BYTES;

    /**
     * 判断指标是否属于 {@code sys_usage_counter_daily} 的 UTC 日窗口。
     *
     * @return 可从幂等业务事实按日绝对归并时为 {@code true}
     */
    public boolean dailyCounter() {
        return switch (this) {
            case UPLINK_MESSAGE, DOWNLINK_MESSAGE, UPLINK_BYTES, TIME_SERIES_POINT,
                    REST_API_CALL, NOTIFICATION_DELIVERY, SCRIPT_EXECUTION, SCRIPT_CPU_MILLIS, AUTOMATION_EXECUTION -> true;
            case DEVICE_COUNT, WEBSOCKET_CONNECTION, STORAGE_BYTES -> false;
        };
    }
}
