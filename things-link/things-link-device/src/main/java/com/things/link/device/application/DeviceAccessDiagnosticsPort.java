package com.things.link.device.application;

import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备接入连接诊断的读端口（接入合同 §7.2／§7.4）。
 *
 * <p>只回答「设备接入侧现在是什么状态、上一次为什么断开」，不含任何凭据、密钥或认证头：密文与明文都不经过
 * 本端口，因此调试面不可能把它们带出去。在线（存在活跃会话）与最近活动（{@code lastSeenAt}）是两类判据，
 * 分别返回、互不冒充——HTTP／CoAP 没有常连接，不能因为刚上报过就显示为在线。</p>
 *
 * <p>权限不在本端口判断：它只提供事实，读取入口沿用既有项目权限（§7.1 复用 {@code device:read}）。</p>
 */
public interface DeviceAccessDiagnosticsPort {

    /**
     * 一台设备的接入连接诊断快照。
     *
     * @param protocol 生效接入协议；没有配置行的存量设备为 MQTT
     * @param configVersion 生效配置代次；存量 MQTT 为 0
     * @param enabled 该协议当前是否允许接入
     * @param online 是否存在活跃会话（连接在线判据）
     * @param sessionRowId 活跃会话行 ID；离线时为空
     * @param sessionId 活跃会话标识；离线时为空
     * @param ownerInstance 活跃会话的归属接入实例；离线或历史行为空
     * @param generation 活跃会话代次；离线时为 0
     * @param connectedAt 活跃会话建立时刻；离线时为空
     * @param lastSeenAt 最近活动时刻（会话心跳）；从未活动时为空
 * @param lastActivityAt 最近一次认证通过的接入活动时刻（无会话协议的唯一活动判据）；从未活动时为空
     * @param lastDisconnectReason 最近一次断开原因；从未断开时为空
     * @param lastDisconnectedAt 最近一次断开时刻；从未断开时为空
     */
    record ConnectionDiagnostics(TransportProtocol protocol, long configVersion, boolean enabled, boolean online,
                                 UUID sessionRowId, String sessionId, String ownerInstance, long generation,
                                 Instant connectedAt, Instant lastSeenAt, Instant lastActivityAt,
                                 String lastDisconnectReason, Instant lastDisconnectedAt) {
    }

    /**
     * 读取设备的接入连接诊断。
     *
     * @param tenantId 设备归属租户，用于建立 RLS 范围
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 设备 ID
     * @return 诊断快照；设备在项目内不存在时为空
     */
    Optional<ConnectionDiagnostics> connection(UUID tenantId, UUID projectId, UUID deviceId);
}
