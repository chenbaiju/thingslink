package com.things.link.ingestion.infrastructure.protocol.tcp;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * TCP 接入的 10 字节定长帧头编解码（接入合同 §3.3）。
 *
 * <p>这里刻意只做纯字节编解码、不依赖任何网络框架：线格式是跨进程契约，应当能被独立验证；把编解码绑在框架上
 * 会让「帧是否正确」变成必须起服务器才能回答的问题。后续 TLS 接入增量复用同一实现，不另写一份。</p>
 *
 * <p>帧头固定为：magic {@code 0x54 0x43}（"TC"）／version {@code 0x01}／type 1 字节／flags 2 字节（必须为 0）／
 * length 4 字节（网络字节序）。载荷上限 65536 字节，与 §3.3 一致。</p>
 */
public final class DeviceAccessTcpFrameCodec {

    /** 魔数第一个字节：{@code 'T'}。 */
    public static final int MAGIC_FIRST = 0x54;

    /** 魔数第二个字节：{@code 'C'}。 */
    public static final int MAGIC_SECOND = 0x43;

    /** 冻结的帧版本。 */
    public static final int VERSION = 0x01;

    /** 帧头字节数。 */
    public static final int HEADER_BYTES = 10;

    /** 载荷长度上限：65536 字节（接入合同 §3.3）。 */
    public static final int MAX_PAYLOAD_BYTES = 65_536;

    /** 工具类不允许实例化。 */
    private DeviceAccessTcpFrameCodec() {
    }

    /**
     * 编码一帧。
     *
     * @param type 帧类型
     * @param payload 载荷字节；空载荷传 null 或空数组
     * @return 线上字节
     * @throws InvalidTcpFrameException 载荷超过上限
     */
    public static byte[] encode(DeviceAccessTcpFrameType type, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        if (body.length > MAX_PAYLOAD_BYTES) {
            throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.LENGTH_INVALID);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(HEADER_BYTES + body.length);
        buffer.write(MAGIC_FIRST);
        buffer.write(MAGIC_SECOND);
        buffer.write(VERSION);
        buffer.write(type.code());
        // flags 固定 0：保留字段被占用意味着对端按别的约定写帧，平台不能猜。
        buffer.write(0);
        buffer.write(0);
        buffer.write((body.length >>> 24) & 0xFF);
        buffer.write((body.length >>> 16) & 0xFF);
        buffer.write((body.length >>> 8) & 0xFF);
        buffer.write(body.length & 0xFF);
        buffer.writeBytes(body);
        return buffer.toByteArray();
    }

    /**
     * 解码一帧。
     *
     * @param bytes 自帧头起至少 {@link #HEADER_BYTES} 字节的输入
     * @return 帧类型、载荷与整帧字节数
     * @throws InvalidTcpFrameException 魔数／版本／保留字段／长度／类型非法，或字节尚未收全
     */
    public static DecodedFrame decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_BYTES) {
            throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.INCOMPLETE);
        }
        if ((bytes[0] & 0xFF) != MAGIC_FIRST || (bytes[1] & 0xFF) != MAGIC_SECOND) {
            throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.MAGIC_INVALID);
        }
        if ((bytes[2] & 0xFF) != VERSION) {
            throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.VERSION_UNSUPPORTED);
        }
        if (bytes[4] != 0 || bytes[5] != 0) {
            throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.FLAGS_INVALID);
        }
        long length = ((long) (bytes[6] & 0xFF) << 24) | ((bytes[7] & 0xFF) << 16)
                | ((bytes[8] & 0xFF) << 8) | (bytes[9] & 0xFF);
        if (length > MAX_PAYLOAD_BYTES) {
            throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.LENGTH_INVALID);
        }
        DeviceAccessTcpFrameType type = DeviceAccessTcpFrameType.fromCode(bytes[3] & 0xFF);
        if (bytes.length < HEADER_BYTES + length) {
            throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.INCOMPLETE);
        }
        return new DecodedFrame(type, Arrays.copyOfRange(bytes, HEADER_BYTES, HEADER_BYTES + (int) length),
                (int) (HEADER_BYTES + length));
    }

    /**
     * 解码结果。
     *
     * @param type 帧类型
     * @param payload 载荷副本
     * @param frameBytes 本帧总字节数（头 + 载荷），供调用方按帧切分接收缓冲区
     */
    public record DecodedFrame(DeviceAccessTcpFrameType type, byte[] payload, int frameBytes) {
    }
}
