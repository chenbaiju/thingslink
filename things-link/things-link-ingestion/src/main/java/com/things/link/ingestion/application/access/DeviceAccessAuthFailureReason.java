package com.things.link.ingestion.application.access;

/**
 * 接入面认证失败的稳定分类（接入合同 §3.5 的跨协议映射）。
 *
 * <p>只使用已经冻结的错误码，不为实现方便新增码：凭据问题一律 {@code AUTH_FAILED}（401），设备在本平面不可用
 * 一律 {@code DEVICE_DISABLED}（403），项目已冻结（归档只读）一律 {@code PROJECT_UNAVAILABLE}（403）。删除的
 * 项目在凭据解析阶段就查不到设备，因此不会走到第三类。</p>
 */
public enum DeviceAccessAuthFailureReason {

    /** 凭据缺失或与设备不匹配。 */
    AUTH_FAILED(401, "AUTH_FAILED", "设备凭据无效"),

    /** 凭据有效但设备未开通该接入平面或配置被关闭。 */
    PLANE_NOT_ENABLED(403, "DEVICE_DISABLED", "设备未开通该接入平面"),

    /** 凭据与平面都有效，但项目已冻结（归档只读），设备写入必须拒绝。 */
    PROJECT_UNAVAILABLE(403, "PROJECT_UNAVAILABLE", "项目当前不接受设备写入");

    /** 对应的 HTTP 状态码。 */
    private final int httpStatus;

    /** 冻结的错误码。 */
    private final String errorCode;

    /** 面向设备的说明（不含任何凭据片段）。 */
    private final String message;

    DeviceAccessAuthFailureReason(int httpStatus, String errorCode, String message) {
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * @return HTTP 状态码
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * @return 冻结的错误码
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * @return 面向设备的说明
     */
    public String message() {
        return message;
    }
}
