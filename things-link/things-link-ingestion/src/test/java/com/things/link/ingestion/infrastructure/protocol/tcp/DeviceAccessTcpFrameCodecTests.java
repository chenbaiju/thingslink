package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TCP 帧线格式：字节布局、类型表、长度上限与四类 FRAME_INVALID 分类。 */
class DeviceAccessTcpFrameCodecTests {

    /** 帧类型字节是跨进程契约：数值漂移会让设备与服务端互相误读。 */
    @Test
    void frameTypeCodesAreFrozen() {
        assertThat(DeviceAccessTcpFrameType.AUTH_REQUEST.code()).isEqualTo(0x01);
        assertThat(DeviceAccessTcpFrameType.AUTH_RESPONSE.code()).isEqualTo(0x02);
        assertThat(DeviceAccessTcpFrameType.HEARTBEAT.code()).isEqualTo(0x03);
        assertThat(DeviceAccessTcpFrameType.HEARTBEAT_ACK.code()).isEqualTo(0x04);
        assertThat(DeviceAccessTcpFrameType.UPLINK.code()).isEqualTo(0x10);
        assertThat(DeviceAccessTcpFrameType.DOWNLINK.code()).isEqualTo(0x11);
        assertThat(DeviceAccessTcpFrameType.REPLY.code()).isEqualTo(0x12);
        assertThat(DeviceAccessTcpFrameType.ACCEPTED.code()).isEqualTo(0x13);
        assertThat(DeviceAccessTcpFrameType.ERROR.code()).isEqualTo(0x7f);
    }

    /** 逐字节钉住帧头：magic、版本、类型、flags 与网络字节序长度。 */
    @Test
    void encodesFrozenHeaderLayout() {
        byte[] frame = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.AUTH_REQUEST, null);

        assertThat(frame).hasSize(DeviceAccessTcpFrameCodec.HEADER_BYTES);
        assertThat(frame[0] & 0xFF).as("magic 首字节为 'T'").isEqualTo(0x54);
        assertThat(frame[1] & 0xFF).as("magic 次字节为 'C'").isEqualTo(0x43);
        assertThat(frame[2] & 0xFF).as("版本固定 1").isEqualTo(0x01);
        assertThat(frame[3] & 0xFF).as("类型字节").isEqualTo(0x01);
        assertThat(frame[4]).as("flags 高位必须为 0").isZero();
        assertThat(frame[5]).as("flags 低位必须为 0").isZero();
        assertThat(frame[6]).isZero();
        assertThat(frame[7]).isZero();
        assertThat(frame[8]).isZero();
        assertThat(frame[9]).as("空载荷长度为 0").isZero();
    }

    /** 编解码往返：类型与载荷逐字保留，帧字节数可用于切分接收缓冲。 */
    @Test
    void roundTripsPayloadAndFrameSize() {
        byte[] payload = "{\"projectKey\":\"p\",\"deviceKey\":\"d\"}".getBytes(StandardCharsets.UTF_8);

        byte[] frame = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.AUTH_REQUEST, payload);
        DeviceAccessTcpFrameCodec.DecodedFrame decoded = DeviceAccessTcpFrameCodec.decode(frame);

        assertThat(decoded.type()).isEqualTo(DeviceAccessTcpFrameType.AUTH_REQUEST);
        assertThat(decoded.payload()).isEqualTo(payload);
        assertThat(decoded.frameBytes()).isEqualTo(frame.length);
        assertThat(frame[6] & 0xFF).isZero();
        assertThat(frame[7] & 0xFF).isZero();
        assertThat(frame[8] & 0xFF).isEqualTo((payload.length >>> 8) & 0xFF);
        assertThat(frame[9] & 0xFF).isEqualTo(payload.length & 0xFF);
    }

    /** 粘包场景：输入是「一帧半」时只解码第一帧，并给出该帧字节数。 */
    @Test
    void decodesFirstFrameFromConcatenatedStream() {
        byte[] first = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.HEARTBEAT, null);
        byte[] second = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.UPLINK,
                "{\"messageId\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        byte[] stream = new byte[first.length + second.length];
        System.arraycopy(first, 0, stream, 0, first.length);
        System.arraycopy(second, 0, stream, first.length, second.length);

        DeviceAccessTcpFrameCodec.DecodedFrame decoded = DeviceAccessTcpFrameCodec.decode(stream);

        assertThat(decoded.type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT);
        assertThat(decoded.frameBytes()).isEqualTo(first.length);
    }

    /** 四类线格式错误各自可诊断，供平台回 ERROR(FRAME_INVALID) 并在日志里分清原因。 */
    @Test
    void reportsStableFrameInvalidReasons() {
        byte[] valid = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.HEARTBEAT, null);

        byte[] wrongMagic = valid.clone();
        wrongMagic[0] = 0x00;
        assertThatThrownBy(() -> DeviceAccessTcpFrameCodec.decode(wrongMagic))
                .isInstanceOf(InvalidTcpFrameException.class)
                .hasMessageContaining("魔数")
                .satisfies(exception -> assertThat(((InvalidTcpFrameException) exception).reason())
                        .isEqualTo(InvalidTcpFrameException.Reason.MAGIC_INVALID));

        byte[] wrongVersion = valid.clone();
        wrongVersion[2] = 0x02;
        assertThatThrownBy(() -> DeviceAccessTcpFrameCodec.decode(wrongVersion))
                .satisfies(exception -> assertThat(((InvalidTcpFrameException) exception).reason())
                        .isEqualTo(InvalidTcpFrameException.Reason.VERSION_UNSUPPORTED));

        byte[] nonZeroFlags = valid.clone();
        nonZeroFlags[5] = 0x01;
        assertThatThrownBy(() -> DeviceAccessTcpFrameCodec.decode(nonZeroFlags))
                .satisfies(exception -> assertThat(((InvalidTcpFrameException) exception).reason())
                        .isEqualTo(InvalidTcpFrameException.Reason.FLAGS_INVALID));

        byte[] unknownType = valid.clone();
        unknownType[3] = 0x55;
        assertThatThrownBy(() -> DeviceAccessTcpFrameCodec.decode(unknownType))
                .satisfies(exception -> assertThat(((InvalidTcpFrameException) exception).reason())
                        .isEqualTo(InvalidTcpFrameException.Reason.TYPE_UNSUPPORTED));
    }

    /** 声明长度超上限、载荷未收全与头部未收全都必须被识别为线格式失败而不是静默截断。 */
    @Test
    void rejectsOversizedAndIncompleteFrames() {
        byte[] oversized = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.UPLINK, null);
        oversized[6] = 0x00;
        oversized[7] = 0x02;
        oversized[8] = 0x00;
        oversized[9] = 0x00;
        assertThatThrownBy(() -> DeviceAccessTcpFrameCodec.decode(oversized))
                .satisfies(exception -> assertThat(((InvalidTcpFrameException) exception).reason())
                        .isEqualTo(InvalidTcpFrameException.Reason.LENGTH_INVALID));

        byte[] declaredButMissingPayload = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.UPLINK, null);
        declaredButMissingPayload[9] = 0x10;
        assertThatThrownBy(() -> DeviceAccessTcpFrameCodec.decode(declaredButMissingPayload))
                .satisfies(exception -> assertThat(((InvalidTcpFrameException) exception).reason())
                        .isEqualTo(InvalidTcpFrameException.Reason.INCOMPLETE));

        assertThatThrownBy(() -> DeviceAccessTcpFrameCodec.decode(new byte[] {0x54, 0x43, 0x01}))
                .satisfies(exception -> assertThat(((InvalidTcpFrameException) exception).reason())
                        .isEqualTo(InvalidTcpFrameException.Reason.INCOMPLETE));
    }

    /** 上限边界自身是合法帧：等于上限应通过，超过一个字节必须被拒。 */
    @Test
    void enforcesPayloadBoundaryInclusively() {
        byte[] atLimit = new byte[DeviceAccessTcpFrameCodec.MAX_PAYLOAD_BYTES];
        byte[] frame = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.UPLINK, atLimit);

        assertThat(DeviceAccessTcpFrameCodec.decode(frame).payload()).hasSize(DeviceAccessTcpFrameCodec.MAX_PAYLOAD_BYTES);
        assertThatThrownBy(() -> DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.UPLINK,
                new byte[DeviceAccessTcpFrameCodec.MAX_PAYLOAD_BYTES + 1]))
                .isInstanceOf(InvalidTcpFrameException.class)
                .hasMessageContaining("帧长度非法");
    }

    /** 拆包：输入每次只给一个字节时仍必须还原出完整帧（真实网络的 TCP 分段没有边界保证）。 */
    @Test
    void readsFrameSplitAcrossSingleByteChunks() throws Exception {
        byte[] payload = "{\"projectKey\":\"p\"}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.AUTH_REQUEST, payload);
        DeviceAccessTcpFrameReader reader = new DeviceAccessTcpFrameReader(new OneByteAtATimeStream(frame));

        DeviceAccessTcpFrameCodec.DecodedFrame decoded = reader.readFrame();

        assertThat(decoded.type()).isEqualTo(DeviceAccessTcpFrameType.AUTH_REQUEST);
        assertThat(decoded.payload()).isEqualTo(payload);
    }

    /** 合包：一次写入包含两帧时，连续两次读取必须按顺序各得其一。 */
    @Test
    void readsCoalescedFramesInOrder() throws Exception {
        byte[] first = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.HEARTBEAT, null);
        byte[] second = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.REPLY,
                "{\"commandId\":\"c\"}".getBytes(StandardCharsets.UTF_8));
        byte[] stream = new byte[first.length + second.length];
        System.arraycopy(first, 0, stream, 0, first.length);
        System.arraycopy(second, 0, stream, first.length, second.length);
        DeviceAccessTcpFrameReader reader = new DeviceAccessTcpFrameReader(new java.io.ByteArrayInputStream(stream));

        assertThat(reader.readFrame().type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT);
        assertThat(reader.readFrame().type()).isEqualTo(DeviceAccessTcpFrameType.REPLY);
        assertThat(reader.readFrame()).as("两帧读完后是干净的帧边界").isNull();
    }

    /** 帧边界上的 EOF 是正常关闭；帧中途的 EOF 必须报线格式不完整而不是静默截断。 */
    @Test
    void distinguishesCleanCloseFromTruncatedFrame() throws Exception {
        byte[] headerOnly = DeviceAccessTcpFrameCodec.encode(DeviceAccessTcpFrameType.UPLINK, null);
        headerOnly[9] = 0x04;
        DeviceAccessTcpFrameReader truncated = new DeviceAccessTcpFrameReader(
                new java.io.ByteArrayInputStream(headerOnly));
        assertThatThrownBy(truncated::readFrame).isInstanceOf(java.io.EOFException.class);

        DeviceAccessTcpFrameReader closed = new DeviceAccessTcpFrameReader(
                new java.io.ByteArrayInputStream(new byte[0]));
        assertThat(closed.readFrame()).isNull();
    }

    /** 每次只返回一个字节的输入流，用于验证拆包。 */
    private static final class OneByteAtATimeStream extends java.io.InputStream {

        /** 源字节。 */
        private final byte[] source;

        /** 当前下标。 */
        private int index;

        /**
         * @param source 源字节
         */
        private OneByteAtATimeStream(byte[] source) {
            this.source = source;
        }

        @Override
        public int read() {
            return index < source.length ? source[index++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            int value = read();
            if (value < 0) {
                return -1;
            }
            target[offset] = (byte) value;
            return 1;
        }
    }
}
