package com.things.link.shared.message;

/**
 * 平台下行派发失败的稳定分类。
 *
 * <p>该码只进入命令事实（{@code ts_device_command_attempt.error_code}）与低基数指标标签，
 * 不作为 HTTP API 错误响应，因此不登记 {@code ERROR_CODES.md}；权威口径见架构文档命令状态机一节。
 * 它表达「平台未能把报文交给承载该设备的传输」（Broker 或设备会话），与设备侧回复失败（如
 * {@code DEVICE_REJECTED}）无关。</p>
 */
public enum DeviceCommandDispatchFailure {
    /** 截止前未取得交付回执，不能推断设备未执行。 */ DISPATCH_PENDING_TIMEOUT("DISPATCH_PENDING_TIMEOUT"),
    /** 通用派发失败（未能细分原因）。 */ DISPATCH_FAILED("DISPATCH_FAILED"),
    /** 缺少 EMQX 下行发布 API 凭据，fail-closed。 */ DISPATCH_CREDENTIALS_MISSING("DISPATCH_CREDENTIALS_MISSING"),
    /** EMQX HTTP 连接或读取超时。 */ DISPATCH_TIMEOUT("DISPATCH_TIMEOUT"),
    /** EMQX HTTP 连接建立失败（拒绝/不可达/解析失败）。 */ DISPATCH_CONNECTION_FAILED("DISPATCH_CONNECTION_FAILED"),
    /** 独立操作 bulkhead 已满，未向 EMQX 发起请求。 */ DISPATCH_BULKHEAD_REJECTED("DISPATCH_BULKHEAD_REJECTED"),
    /** 命令故障域断路器已打开，未向 EMQX 发起请求。 */ DISPATCH_CIRCUIT_OPEN("DISPATCH_CIRCUIT_OPEN"),
    /** EMQX HTTP 返回除 429 外的 4xx 永久拒绝。 */ DISPATCH_HTTP_CLIENT_ERROR("DISPATCH_HTTP_CLIENT_ERROR"),
    /** EMQX HTTP 返回 429，属于可重试供应商过载。 */ DISPATCH_HTTP_RATE_LIMITED("DISPATCH_HTTP_RATE_LIMITED"),
    /** EMQX HTTP 返回 5xx，属于可重试供应商故障。 */ DISPATCH_HTTP_SERVER_ERROR("DISPATCH_HTTP_SERVER_ERROR"),
    /** EMQX HTTP 返回 3xx 或其他非成功状态。 */ DISPATCH_HTTP_REJECTED("DISPATCH_HTTP_REJECTED"),
    /** 设备按会话内推送接入，但本实例没有它的活跃会话（离线或归属其他实例）：命令保持待发并退避重投。 */
    DISPATCH_DEVICE_OFFLINE("DISPATCH_DEVICE_OFFLINE");

    /** 入库与指标标签共用的稳定字符串码。 */
    private final String code;

    /**
     * @param code 稳定字符串码，不得与设备侧失败码混用
     */
    DeviceCommandDispatchFailure(String code) {
        this.code = code;
    }

    /** @return 稳定字符串码 */
    public String code() {
        return code;
    }
}
