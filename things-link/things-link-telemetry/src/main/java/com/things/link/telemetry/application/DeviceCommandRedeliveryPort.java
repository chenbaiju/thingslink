package com.things.link.telemetry.application;

import java.util.UUID;

/**
 * 设备重连时的待投命令加速端口（接入合同 §5.3）。
 *
 * <p>命令因设备离线而未投递时保持待发并写退避；设备重连是「现在可以投了」的权威信号，因此把该设备这些命令的
 * 下次派发时刻提前到现在。本端口**不**推进命令状态、不创建尝试、不直接交付：它只缩短退避，交付仍由既有重投
 * 状态机与下行链路完成，因此协议层不会引入第二套命令语义。</p>
 *
 * <p>只提前、不推后；尝试已耗尽的命令不会被改动（它们按既有时限语义终态）。</p>
 */
public interface DeviceCommandRedeliveryPort {

    /**
     * 把该设备因离线而未投递、且仍在退避中的命令提前到当前时刻。
     *
     * @param tenantId 权威租户
     * @param projectId 项目 ID，同时是 RLS 范围
     * @param deviceId 刚建立会话的设备（承载连接的设备）
     * @return 被提前的命令条数；没有待投命令时为 0
     */
    int accelerateOfflinePending(UUID tenantId, UUID projectId, UUID deviceId);
}
