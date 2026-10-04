package com.things.link.ingestion.infrastructure.protocol.tcp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 从阻塞输入流按帧读取（接入合同 §3.3）。
 *
 * <p>阻塞读比非阻塞解码简单得多，而 TCP 接入首阶段每设备只有一个长连接（§6），因此按帧顺序读取即可满足
 * 语义；真正需要事件循环与背压时再评估分帧框架，届时复用同一编解码。</p>
 *
 * <p>半包与粘包在这里处理：头部 10 字节一定读满，再按声明长度读满载荷；读满即为一帧，剩余字节留在流里
 * 由下一次调用继续，因此调用方不需要自己维护缓冲。</p>
 */
public class DeviceAccessTcpFrameReader {

    /** 数据输入流。 */
    private final InputStream input;

    /**
     * @param input 已建立的连接输入流（TLS 握手已完成）
     */
    public DeviceAccessTcpFrameReader(InputStream input) {
        this.input = input;
    }

    /**
     * 读取一帧。
     *
     * @return 解码结果；对端在帧边界正常关闭时为空
     * @throws IOException 读取失败或对端在帧中途关闭
     * @throws InvalidTcpFrameException 帧不符合冻结线格式
     */
    public DeviceAccessTcpFrameCodec.DecodedFrame readFrame() throws IOException {
        byte[] header = new byte[DeviceAccessTcpFrameCodec.HEADER_BYTES];
        int firstByte = input.read();
        if (firstByte < 0) {
            // 帧边界上的 EOF 是正常的对端关闭，不是协议错误。
            return null;
        }
        header[0] = (byte) firstByte;
        readFully(header, 1, header.length - 1);
        int payloadLength = declaredLength(header);
        byte[] frame = new byte[DeviceAccessTcpFrameCodec.HEADER_BYTES + payloadLength];
        System.arraycopy(header, 0, frame, 0, header.length);
        readFully(frame, header.length, payloadLength);
        return DeviceAccessTcpFrameCodec.decode(frame);
    }

    /** 只解析长度字段，用于按声明长度读满载荷；合法性仍由编解码统一判定。 */
    private static int declaredLength(byte[] header) throws InvalidTcpFrameException {
        long length = ((long) (header[6] & 0xFF) << 24) | ((header[7] & 0xFF) << 16)
                | ((header[8] & 0xFF) << 8) | (header[9] & 0xFF);
        if (length > DeviceAccessTcpFrameCodec.MAX_PAYLOAD_BYTES) {
            throw new InvalidTcpFrameException(InvalidTcpFrameException.Reason.LENGTH_INVALID);
        }
        return (int) length;
    }

    /** 读满指定字节数；中途 EOF 视为线格式不完整而不是正常关闭。 */
    private void readFully(byte[] target, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int current = input.read(target, offset + read, length - read);
            if (current < 0) {
                throw new EOFException("帧尚未接收完整，对端已关闭连接");
            }
            read += current;
        }
    }
}
