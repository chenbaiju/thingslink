package com.things.link.device.api.dto.response;

import com.things.link.device.application.DeviceAccessDiagnosticsPort.ConnectionDiagnostics;
import com.things.link.shared.message.TransportProtocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 接入连接诊断响应（接入合同 §7.2 的连接诊断字段）。
 *
 * <p>字段集是冻结的：协议、生效接入配置版本、会话代次、最近认证与活动时刻、当前状态、断开或拒绝原因。
 * **归属实例只给脱敏短哈希**：实例标识属于平台内部拓扑信息，调试面只需要"是不是同一实例承载"，
 * 不需要也不应泄露实例名（§7.2）。本响应里没有任何凭据字段——诊断数据源自会话与配置事实，凭据从不进入。</p>
 *
 * @param protocol 生效接入协议
 * @param configVersion 生效接入配置版本
 * @param enabled 该接入平面是否启用
 * @param online 连接在线（会话 `CONNECTED`）
 * @param state 冻结口径的状态：{@code CONNECTED}／{@code LAST_ACTIVITY}／{@code OFFLINE}
 * @param generation 当前会话代次；无活跃会话时为 0
 * @param connectedAt 会话建立时刻；无活跃会话时为空
 * @param lastSeenAt 最近心跳时刻；无活跃会话或从未心跳时为空
 * @param lastAuthenticatedAt 最近认证时刻（取自最后一次认证通过的接入活动）
 * @param lastActivityAt 最近活动时刻（非连接型协议的在线判据）
 * @param lastDisconnectReason 最近断开或拒绝原因；从未断开时为空
 * @param lastDisconnectedAt 最近断开时刻；从未断开时为空
 * @param ownerInstanceHash 归属实例标识的脱敏短哈希；无活跃会话时为空
 */
public record DeviceAccessDiagnosticsResponse(TransportProtocol protocol, long configVersion, boolean enabled,
                                              boolean online, String state, long generation, Instant connectedAt,
                                              Instant lastSeenAt, Instant lastAuthenticatedAt,
                                              Instant lastActivityAt, String lastDisconnectReason,
                                              Instant lastDisconnectedAt, String ownerInstanceHash) {

    /** 归属实例短哈希的十六进制长度：足够区分实例，又不泄露标识本身。 */
    private static final int OWNER_HASH_LENGTH = 12;

    /**
     * 由诊断快照构造响应。
     *
     * @param diagnostics 诊断快照
     * @return 脱敏后的响应
     */
    public static DeviceAccessDiagnosticsResponse from(ConnectionDiagnostics diagnostics) {
        return new DeviceAccessDiagnosticsResponse(diagnostics.protocol(), diagnostics.configVersion(),
                diagnostics.enabled(), diagnostics.online(), state(diagnostics), diagnostics.generation(),
                // 最近认证时刻取自生效配置上的「最后一次认证通过的接入活动」：连接型协议还会另有
                // connectedAt／lastSeenAt 给出会话级细节，非连接型（HTTP／CoAP）只有这一条事实。
                diagnostics.connectedAt(), diagnostics.lastSeenAt(), diagnostics.lastActivityAt(),
                diagnostics.lastActivityAt(), diagnostics.lastDisconnectReason(),
                diagnostics.lastDisconnectedAt(), shortHash(diagnostics.ownerInstance()));
    }

    /**
     * 冻结的两类在线判据分列（§6.2）：连接在线优先，其次最近活动，最后离线。
     *
     * @param diagnostics 诊断快照
     * @return 连接诊断依据：已连接、最近活跃或离线，对应 {@code CONNECTED}／{@code LAST_ACTIVITY}／{@code OFFLINE}
     */
    private static String state(ConnectionDiagnostics diagnostics) {
        if (diagnostics.online()) {
            return "CONNECTED";
        }
        Instant activity = diagnostics.lastActivityAt();
        if (activity != null && activity.isAfter(Instant.now().minusSeconds(300))) {
            return "LAST_ACTIVITY";
        }
        return "OFFLINE";
    }

    /**
     * 归属实例标识的脱敏短哈希；为空时返回空。
     *
     * @param ownerInstance 归属实例标识
     * @return 短哈希或空
     */
    private static String shortHash(String ownerInstance) {
        if (ownerInstance == null || ownerInstance.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ownerInstance.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, OWNER_HASH_LENGTH);
        } catch (Exception exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
        }
    }
}
