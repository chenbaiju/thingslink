package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.ingestion.application.access.DeviceAccessPushPort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本实例活跃 TCP 会话注册表，兼作会话内下行推送实现（接入合同 §5.3）。
 *
 * <p>注册表按设备索引**当前代次**的写句柄：建立会话时注册、连接结束（含被接管）时按值条件移除。但本地条目
 * **不能单独作为代次依据**——另一个实例完成接管时，本实例的连接在自己的下一次读帧或心跳超时之前并不知情，
 * 这段时间里本地句柄仍是"活的"，直接写下去就会把下行投给一个数据库已判为关闭的会话。因此每次推送都先用
 * {@link DeviceAccessSessionPort#active} 复核「这次推送要用的行与归属实例仍是当前活跃会话」，再写帧。</p>
 *
 * <p>查找失败返回 {@link PushOutcome#DEFERRED_NOT_OWNER} 而不是回退到其他传输：把 TCP 命令交给 MQTT Broker 会让命令
 * 在没有订阅者的情况下被记为「已派发」，把「设备离线」伪装成「已投递」。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component
public class DeviceAccessTcpSessionRegistry implements DeviceAccessPushPort {

    /** 与生产一致的 JSON 映射器，用于把命令参数写成对象而不是字符串。 */
    private final ObjectMapper objectMapper;

    /** 设备 → 当前代次写句柄。 */
    private final ConcurrentHashMap<UUID, DeviceAccessTcpConnection> sessions = new ConcurrentHashMap<>();

    /** 会话事实端口：推送前复核代次与归属实例，跨实例接管后本地句柄立即失效。 */
    private final DeviceAccessSessionPort sessionPort;

    /** 本实例标识；只有会话事实仍归属本实例时才允许写帧。 */
    private final String ownerInstance;

    /**
     * @param objectMapper 统一 JSON 映射器
     * @param sessionPort 会话事实端口
     * @param runtime 本进程唯一身份
     */
    public DeviceAccessTcpSessionRegistry(ObjectMapper objectMapper, DeviceAccessSessionPort sessionPort,
                                          DeviceAccessTcpRuntime runtime) {
        this.objectMapper = objectMapper;
        this.sessionPort = sessionPort;
        this.ownerInstance = runtime.id();
    }

    /**
     * 注册一条刚完成认证的会话。
     *
     * @param connection 会话写句柄
     */
    void register(DeviceAccessTcpConnection connection) {
        // 同设备只保留最新句柄：旧句柄随后由它自己的收尾逻辑关闭，它的写入尝试会失败。
        sessions.compute(connection.deviceId(), (device, current) -> current != null
                && current.generation() > connection.generation() ? current : connection);
    }

    /**
     * 按值移除会话句柄；只移除仍指向自己的条目，避免被接管后误删新会话。
     *
     * @param connection 待移除的会话写句柄
     */
    void unregister(DeviceAccessTcpConnection connection) {
        sessions.remove(connection.deviceId(), connection);
        connection.close();
    }

    /**
     * @param deviceId 设备 ID
     * @return 本实例是否持有该设备的活跃会话
     */
    public boolean holdsSession(UUID deviceId) {
        DeviceAccessTcpConnection connection = sessions.get(deviceId);
        return connection != null && connection.isOpen();
    }

    /** @return 本实例当前持有的活跃会话数 */
    public int localSessionCount() {
        return sessions.size();
    }

    @Override
    public PushOutcome push(Push push) {
        DeviceAccessTcpConnection connection = sessions.get(push.deviceId());
        if (connection == null || !connection.isOpen()) {
            return PushOutcome.DEFERRED_NOT_OWNER;
        }
        if (!stillOwns(push, connection)) {
            // 只摘掉本地条目，**不**关闭句柄：读帧线程还要用它把 ERROR(AUTH_REQUIRED) 回给设备，
            // 让设备知道应当重新认证；直接关闭只会让设备看到一次无解释的断开。
            sessions.remove(push.deviceId(), connection);
            return PushOutcome.DEFERRED_NOT_OWNER;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", push.commandId().toString());
        payload.put("commandKey", push.commandKey());
        // input 必须是对象：冻结合同里它是 JSON 对象，写成字符串会让设备侧多解一层。
        payload.put("input", objectMapper.readTree(push.inputJson()));
        payload.put("attempt", push.attempt());
        if (push.expiresAt() != null) {
            payload.put("expiresAt", push.expiresAt().toString());
        }
        if (push.expiresAt() != null && !push.expiresAt().isAfter(java.time.Instant.now())) {
            return PushOutcome.DEFERRED_NOT_OWNER;
        }
        if (connection.writeFrame(DeviceAccessTcpFrameType.DOWNLINK, objectMapper.writeValueAsBytes(payload))) {
            return PushOutcome.DELIVERED;
        }
        return stillOwns(push, connection) ? PushOutcome.WRITE_FAILED : PushOutcome.DEFERRED_NOT_OWNER;
    }

    /**
     * 复核这条会话行仍由本实例持有（接入合同 §5.3「存在 CONNECTED 且代次匹配的会话时才可推送」）。
     *
     * @param push 待推送命令，提供租户／项目／设备
     * @param connection 本地句柄
     * @return 是否仍可写
     */
    private boolean stillOwns(Push push, DeviceAccessTcpConnection connection) {
        if (!push.tenantId().equals(connection.tenantId()) || !push.projectId().equals(connection.projectId())
                || !push.deviceId().equals(connection.deviceId())) return false;
        boolean configMatches = sessionPort.config(push.tenantId(), push.projectId(), push.deviceId())
                .filter(config -> config.enabled()
                        && config.protocol() == com.things.link.shared.message.TransportProtocol.TCP
                        && config.configVersion() == connection.configVersion()).isPresent();
        if (!configMatches) return false;
        return sessionPort.active(push.tenantId(), push.projectId(), push.deviceId())
                .filter(active -> active.rowId().equals(connection.rowId())
                        && active.protocol() == com.things.link.shared.message.TransportProtocol.TCP
                        && ownerInstance.equals(active.ownerInstance())
                        && active.generation() == connection.generation()
                        && active.configVersion() == connection.configVersion())
                .isPresent();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public boolean owns(Push push) {
        DeviceAccessTcpConnection connection = sessions.get(push.deviceId());
        return connection != null && connection.isOpen() && stillOwns(push, connection);
    }
}
