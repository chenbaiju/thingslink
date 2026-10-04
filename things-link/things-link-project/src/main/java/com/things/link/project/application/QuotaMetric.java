package com.things.link.project.application;

/**
 * 跨模块共享的配额指标契约。
 *
 * <p>编码与 project 内部领域指标及 {@code sys_usage_counter_daily} 检查约束保持同名；
 * 业务模块只能依赖本 application 契约，project 服务在边界映射到内部领域模型。</p>
 */
public enum QuotaMetric {

    /** 设备存量。 */
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
    /** 并发 WebSocket 连接数。 */
    WEBSOCKET_CONNECTION,
    /** UTC 日通知投递次数。 */
    NOTIFICATION_DELIVERY,
    /** UTC 日脚本执行次数。 */
    SCRIPT_EXECUTION,
    /** UTC日自动化执行原事务预留次数，与脚本计量分开。 */
    AUTOMATION_EXECUTION,
    /** UTC 日脚本 CPU 毫秒。 */
    SCRIPT_CPU_MILLIS,
    /** 当前对象存储字节数。 */
    STORAGE_BYTES;

    /**
     * 判断指标是否属于 UTC 日绝对用量事实。
     *
     * @return 业务模块可按固定 UTC 日窗口重算时为 {@code true}
     */
    public boolean dailyCounter() {
        return switch (this) {
            case UPLINK_MESSAGE, DOWNLINK_MESSAGE, UPLINK_BYTES, TIME_SERIES_POINT,
                    REST_API_CALL, NOTIFICATION_DELIVERY, SCRIPT_EXECUTION, SCRIPT_CPU_MILLIS, AUTOMATION_EXECUTION -> true;
            case DEVICE_COUNT, WEBSOCKET_CONNECTION, STORAGE_BYTES -> false;
        };
    }
}
