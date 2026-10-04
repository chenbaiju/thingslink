package com.things.link.ingestion.application.access;

import java.util.UUID;

/**
 * 表示设备业务请求超出接入预算。
 *
 * <p>接入合同 §6.1 冻结的预算按「租户 + 设备」共享：设备每秒/突发桶与租户每秒/分钟窗口由既有 S7 限流器
 * 统一扣减，因此换协议、换端点或改轮询节奏都不能绕过同一份额度。预算拒绝是**暂时**错误：设备按
 * {@code Retry-After} 退避后重试即可，协议层据此映射 {@code RATE_LIMITED}／{@code BUDGET_EXCEEDED}
 * （HTTP 429／CoAP 4.29／TCP ERROR），而不是当成协议错误。</p>
 */
public class DeviceAccessRateLimitedException extends RuntimeException {

    /** 触发拒绝的设备。 */
    private final UUID deviceId;

    /**
     * 创建预算拒绝异常。
     *
     * @param deviceId 触发拒绝的设备
     */
    public DeviceAccessRateLimitedException(UUID deviceId) {
        super("设备业务请求超出接入预算 deviceId=" + deviceId);
        this.deviceId = deviceId;
    }

    /**
     * 返回触发拒绝的设备。
     *
     * @return 设备 ID
     */
    public UUID deviceId() {
        return deviceId;
    }
}
