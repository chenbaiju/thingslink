package com.things.link.ingestion.infrastructure.protocol.tcp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 一条已认证 TCP 会话的写句柄：把「写一帧」收敛到唯一一处并串行化。
 *
 * <p>同一条连接上有两个写者——承载该连接的读帧线程（心跳应答、错误帧）与推送命令的下行线程。若两者直接写
 * 同一个 {@link OutputStream}，两条帧的字节会交错，设备侧看到的是一帧无法解析的垃圾。因此帧的编码与写入只在
 * 本类内部进行，并由同一把锁串行化；调用方只关心「写成功还是连接已断」。</p>
 *
 * <p>句柄同时携带设备、会话行与代次：注册表按设备索引活跃句柄，接管发生时旧句柄被替换并关闭，因此推送只能
 * 落在当前代次的会话上。</p>
 */
final class DeviceAccessTcpConnection {

    /** 认证时的租户与项目快照。 */
    private final UUID tenantId, projectId;
    /** 认证时的配置代次。 */
    private final long configVersion;

    /** 承载连接的设备。 */
    private final UUID deviceId;

    /** 会话行 ID，用于诊断与重放对账。 */
    private final UUID rowId;

    /** 会话代次；接管递增，旧代次的句柄不再被注册表引用。 */
    private final long generation;

    /** 连接输出流；只允许在本类的锁内写入。 */
    private final OutputStream output;

    /** 写锁：串行化同一连接上的所有帧写入。 */
    private final Object writeLock = new Object();

    /** 是否仍可写；关闭后写入一律失败，不再尝试。 */
    private boolean open = true;

    /**
     * @param deviceId 承载连接的设备
     * @param rowId 会话行 ID
     * @param generation 会话代次
     * @param output 连接输出流
     */
    DeviceAccessTcpConnection(UUID tenantId, UUID projectId, UUID deviceId, UUID rowId,
                              long generation, long configVersion, OutputStream output) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.configVersion = configVersion;
        this.deviceId = deviceId;
        this.rowId = rowId;
        this.generation = generation;
        this.output = output;
    }

    /** @return 认证租户 */ UUID tenantId() { return tenantId; }
    /** @return 认证项目 */ UUID projectId() { return projectId; }
    /** @return 认证配置代次 */ long configVersion() { return configVersion; }

    /** @return 承载连接的设备 */
    UUID deviceId() {
        return deviceId;
    }

    /** @return 会话行 ID */
    UUID rowId() {
        return rowId;
    }

    /** @return 会话代次 */
    long generation() {
        return generation;
    }

    /**
     * 写一帧。
     *
     * @param type 帧类型
     * @param payload 载荷字节；空载荷传空数组
     * @return 是否写入成功；false 表示连接已关闭或已断，调用方不得据此认为设备已收到
     */
    boolean writeFrame(DeviceAccessTcpFrameType type, byte[] payload) {
        synchronized (writeLock) {
            if (!open) {
                return false;
            }
            try {
                output.write(DeviceAccessTcpFrameCodec.encode(type, payload));
                output.flush();
                return true;
            } catch (IOException exception) {
                return false;
            }
        }
    }

    /** 标记句柄关闭；此后所有写入直接失败，不再触碰套接字。 */
    void close() {
        synchronized (writeLock) {
            open = false;
        }
    }

    /** @return 句柄是否仍可写 */
    boolean isOpen() {
        synchronized (writeLock) {
            return open;
        }
    }
}
