package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 网关-子设备拓扑绑定（ADR 0031 / 0033）。
 *
 * <p>本聚合是「谁经谁接入」的权威事实：{@code dev_device.gateway_id} 只是它的物化投影，
 * 由拓扑服务同事务独占维护。一条记录即一次绑定，换绑通过「关闭旧记录 + 新建记录」表达，
 * 因此完整换绑轨迹都保留在 {@code unbound_at} 上，供审计与回放。</p>
 *
 * @param id 绑定记录 ID
 * @param tenantId 归属租户 ID
 * @param projectId 归属项目 ID，也是 RLS 隔离轴
 * @param gatewayDeviceId 代理接入的网关设备 ID
 * @param subDeviceId 被代理接入的子设备 ID
 * @param bindSource 绑定来源
 * @param onlineStatus 子设备权威在线态
 * @param lastOnlineAt 子设备最后一次可信上线时刻
 * @param statusChangedAt 在线态最后一次变化时刻
 * @param boundBy 执行绑定的控制台账号 ID，系统来源为空
 * @param boundAt 绑定生效时刻
 * @param unboundBy 执行解绑的控制台账号 ID，未解绑为空
 * @param unboundAt 解绑时刻，空表示当前有效绑定
 * @param version CAS 版本号，供在线态状态机防乱序覆盖
 * @param createdAt 记录创建时刻
 */
public record DeviceTopology(UUID id, UUID tenantId, UUID projectId, UUID gatewayDeviceId, UUID subDeviceId,
                             BindSource bindSource, OnlineStatus onlineStatus, Instant lastOnlineAt,
                             Instant statusChangedAt, UUID boundBy, Instant boundAt, UUID unboundBy,
                             Instant unboundAt, int version, Instant createdAt) {

    /** 绑定来源：控制面手动绑定、存量数据回填、网关上报（S10-2a）。 */
    public enum BindSource { /** 控制面手动绑定。 */ CONTROL_PLANE, /** 存量数据回填。 */ LEGACY_BACKFILL,
        /** 已认证网关经 {@code up/topo/add} 或 {@code up/sub/register} 上报。 */ GATEWAY_REPORTED }

    /** 子设备权威在线态，同步投影到 dev_device.status 的统一可达性状态面。 */
    public enum OnlineStatus { /** 尚未有可信状态。 */ UNKNOWN, /** 在线。 */ ONLINE, /** 离线。 */ OFFLINE }
}
