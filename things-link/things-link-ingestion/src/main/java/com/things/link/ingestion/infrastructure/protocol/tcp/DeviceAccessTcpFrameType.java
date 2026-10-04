package com.things.link.ingestion.infrastructure.protocol.tcp;

/**
 * TCP 设备接入的帧类型（接入合同 §3.3 冻结取值）。
 *
 * <p>数值是跨进程线上契约：设备端 SDK 按这些字节写帧，服务端按同一张表读帧。因此这里显式声明数值而不是
 * 依赖枚举顺序，并在测试里逐个钉住——顺序一变就会静默错位，症状是「认证帧被当成心跳」这类难以定位的故障。</p>
 */
public enum DeviceAccessTcpFrameType {

    /** 设备到平台的认证请求，载荷为 {@code {projectKey, deviceKey, secret}}。 */
    AUTH_REQUEST(0x01),

    /** 平台到设备的认证响应，载荷为 {@code {status, serverTime, heartbeatIntervalMillis, maxFrameBytes}}。 */
    AUTH_RESPONSE(0x02),

    /** 心跳，双方均可发送，空载荷。 */
    HEARTBEAT(0x03),

    /** 心跳应答，空载荷。 */
    HEARTBEAT_ACK(0x04),

    /** 属性上报，载荷与 MQTT 业务载荷同源。 */
    UPLINK(0x10),

    /** 命令推送，载荷为 {@code {commandId, commandKey, input, attempt, expiresAt?}}。 */
    DOWNLINK(0x11),

    /** 命令业务回复。 */
    REPLY(0x12),

    /** 显式受理应答，关联原请求类型与消息标识；命令回复另含命令标识。 */
    ACCEPTED(0x13),

    /** 平台错误帧，载荷为 {@code {errorCode, message?}}；连接是否关闭由阶段和错误类别决定（ADR 0142）。 */
    ERROR(0x7f);

    /** 帧类型字节。 */
    private final int code;

    DeviceAccessTcpFrameType(int code) {
        this.code = code;
    }

    /**
     * @return 冻结的帧类型字节
     */
    public int code() {
        return code;
    }

    /**
     * 按字节解析帧类型。
     *
     * @param code 帧类型字节
     * @return 对应类型
     * @throws InvalidTcpFrameException 未知类型字节
     */
    public static DeviceAccessTcpFrameType fromCode(int code) {
        for (DeviceAccessTcpFrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.TYPE_UNSUPPORTED);
    }
}
