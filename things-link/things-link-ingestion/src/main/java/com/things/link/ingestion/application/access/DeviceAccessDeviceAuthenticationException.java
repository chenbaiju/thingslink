package com.things.link.ingestion.application.access;

/**
 * 设备接入面认证失败：携带稳定分类供协议层映射响应。
 *
 * <p>异常里不放凭据、不放请求头内容，也不区分「项目不存在」「设备不存在」「密钥错误」，避免公开端点被当作
 * 设备枚举器。</p>
 */
public class DeviceAccessDeviceAuthenticationException extends RuntimeException {

    /** 稳定失败分类。 */
    private final DeviceAccessAuthFailureReason reason;

    /**
     * @param reason 稳定失败分类
     */
    public DeviceAccessDeviceAuthenticationException(DeviceAccessAuthFailureReason reason) {
        super(reason.message());
        this.reason = reason;
    }

    /**
     * @return 稳定失败分类
     */
    public DeviceAccessAuthFailureReason reason() {
        return reason;
    }
}
