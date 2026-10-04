package com.things.link.ingestion.application.access.coap;

/**
 * 设备面 CoAP 响应（接入合同 §3.4／§3.5）。
 *
 * <p>只表达**冻结语义**：响应码与可选载荷。传输层（Scandium DTLS／Californium 端点）负责把它编码成 CoAP 报文，
 * 因此本层可以完全脱离 DTLS 单独验证——响应码选错是契约缺陷，不是传输问题。</p>
 *
 * @param code 冻结的 CoAP 响应码
 * @param body 响应载荷；空体用空串
 */
public record DeviceAccessCoapResponse(CoapCode code, String body) {

    /** 空响应体。 */
    public static final String EMPTY_BODY = "";

    /** 冻结的 CoAP 响应码（语义与 §3.5 的三协议映射一致）。 */
    public enum CoapCode {

        /** 2.04 Changed：属性上报与命令回复的成功响应。 */
        CHANGED(2, 4),
        /** 2.05 Content：命令领取的成功响应。 */
        CONTENT(2, 5),
        /** 4.00 Bad Request：字段／类型／长度／JSON 非法。 */
        BAD_REQUEST(4, 0),
        /** 4.01 Unauthorized：缺少凭据或凭据不匹配。 */
        UNAUTHORIZED(4, 1),
        /** 4.03 Forbidden：设备被禁用／项目不可写。 */
        FORBIDDEN(4, 3),
        /** 4.04 Not Found：资源不存在，或回复引用的命令不存在。 */
        NOT_FOUND(4, 4),
        /** 4.09 Conflict：同 messageId 不同载荷。 */
        IDEMPOTENCY_CONFLICT(4, 9),
        /** 4.13 Request Entity Too Large：超出 64 KiB。 */
        REQUEST_ENTITY_TOO_LARGE(4, 13),
        /** 4.15 Unsupported Content-Format：非 application/json。 */
        UNSUPPORTED_CONTENT_FORMAT(4, 15),
        /** 4.29 Too Many Requests：触发 §6 预算。 */
        TOO_MANY_REQUESTS(4, 29),
        /** 5.01 Not Implemented：资源已冻结但对应业务尚未接线（绝不伪造 2.04）。 */
        NOT_IMPLEMENTED(5, 1),
        /** 5.03 Service Unavailable：持久交接不可用。 */
        SERVICE_UNAVAILABLE(5, 3);

        /** 响应码类别（2／4／5）。 */
        private final int codeClass;

        /** 响应码明细。 */
        private final int detail;

        CoapCode(int codeClass, int detail) {
            this.codeClass = codeClass;
            this.detail = detail;
        }

        /** @return 响应码类别 */
        public int codeClass() {
            return codeClass;
        }

        /** @return 响应码明细 */
        public int detail() {
            return detail;
        }

        /** @return RFC 7252 文本形式，例如 {@code 4.01} */
        public String text() {
            return codeClass + "." + (detail < 10 ? "0" + detail : String.valueOf(detail));
        }
    }

    /**
     * @param code 响应码
     * @return 无载荷响应
     */
    public static DeviceAccessCoapResponse of(CoapCode code) {
        return new DeviceAccessCoapResponse(code, EMPTY_BODY);
    }
}
