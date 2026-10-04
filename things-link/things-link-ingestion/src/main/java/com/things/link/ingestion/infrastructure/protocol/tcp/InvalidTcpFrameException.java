package com.things.link.ingestion.infrastructure.protocol.tcp;

/**
 * TCP 帧不符合冻结线格式。
 *
 * <p>原因分类是给设备的稳定诊断：平台据此回 {@code ERROR(FRAME_INVALID)} 并关闭连接。分类只描述**线格式**问题，
 * 不描述业务语义——凭据、预算与命令找不到各有自己的错误码。</p>
 */
public class InvalidTcpFrameException extends RuntimeException {

    /** 线格式失败原因。 */
    public enum Reason {
        /** 魔数不是 {@code TC}：对端不是本协议，或帧边界已经错位。 */
        MAGIC_INVALID("魔数不是 TC"),
        /** 版本号不是 1：设备使用了平台尚未支持的帧版本。 */
        VERSION_UNSUPPORTED("帧版本不受支持"),
        /** 保留字段非 0：当前冻结要求必须为 0，非 0 说明对端按别的约定写帧。 */
        FLAGS_INVALID("保留字段必须为 0"),
        /** 声明长度非法：超过 65536。 */
        LENGTH_INVALID("帧长度非法"),
        /** 类型字节未知。 */
        TYPE_UNSUPPORTED("帧类型不受支持"),
        /** 字节不足：头部或载荷还没收全。 */
        INCOMPLETE("帧尚未接收完整");

        /** 面向日志与设备诊断的说明。 */
        private final String message;

        Reason(String message) {
            this.message = message;
        }

        /**
         * @return 稳定说明
         */
        public String message() {
            return message;
        }
    }

    /** 失败原因。 */
    private final Reason reason;

    /**
     * @param reason 线格式失败原因
     */
    public InvalidTcpFrameException(Reason reason) {
        super(reason.message());
        this.reason = reason;
    }

    /**
     * @return 线格式失败原因
     */
    public Reason reason() {
        return reason;
    }
}
