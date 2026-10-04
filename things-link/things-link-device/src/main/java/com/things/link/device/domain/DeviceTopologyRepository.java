package com.things.link.device.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 拓扑绑定仓储端口。 */
public interface DeviceTopologyRepository {
    /** @param projectId 项目 ID @param subDeviceId 子设备 ID @return 当前有效绑定（无则空） */
    Optional<DeviceTopology> findActiveBySubDevice(UUID projectId, UUID subDeviceId);
    /** @param projectId 项目 ID @param gatewayId 网关 ID @return 网关当前挂载的有效子设备绑定 */
    List<DeviceTopology> findActiveByGateway(UUID projectId, UUID gatewayId);
    /** @param projectId 项目 ID @return 项目内全部有效绑定，供控制台拓扑树一次拉取 */
    List<DeviceTopology> findActiveByProject(UUID projectId);
    /**
     * ADR0057：判断设备候选分类是否破坏其承担的任一有效拓扑角色，不修改或推断历史。
     *
     * @param projectId 已确权项目 ID
     * @param deviceId 设备 ID
     * @param candidateKind 候选分类；null 表示清空或失去有效类型，与任一有效角色均不兼容
     * @return 是否存在不兼容的有效角色；调用方负责设备与类型的并发保护
     */
    boolean hasIncompatibleRoleForDevice(UUID projectId, UUID deviceId, DeviceType.DeviceKind candidateKind);
    /**
     * ADR0057：按设备当前类型引用检查有效角色，类型锁内只读，不反向追加设备行锁。
     *
     * @param projectId 已确权项目 ID
     * @param deviceTypeId 正在修改或删除的类型 ID
     * @param candidateKind 候选分类；null 表示删除类型，与任一有效角色均不兼容
     * @return 是否存在不兼容的有效角色；已软删设备的残留有效关系也不得被忽略
     */
    boolean hasIncompatibleRoleForType(UUID projectId, UUID deviceTypeId, DeviceType.DeviceKind candidateKind);
    /**
     * ADR0057：在网关删除/离线级联取得子设备锁之前检查相关有效关系，拒绝存量分类漂移。
     *
     * @param projectId 已确权项目 ID
     * @param gatewayId 操作目标；同时检查其作为子设备的异常关系及直接子设备仍代理下级的异常关系
     * @return 相关有效关系是否包含缺失/已删除端点、缺失/已删除类型或错误网关/子设备分类
     */
    boolean hasInvalidRolesInGatewayComponent(UUID projectId, UUID gatewayId);
    /** 新建绑定。 @param topology 绑定 */
    void create(DeviceTopology topology);
    /** 关闭子设备当前有效绑定（换绑或解绑前先关闭旧记录）。 @return 是否命中有效绑定 */
    boolean closeActive(UUID projectId, UUID subDeviceId, UUID unboundBy);
    /**
     * CAS 更新子设备权威在线态；{@code receivedAt} 早于当前状态变更时间时拒绝，防旧事件覆盖新状态。
     *
     * @param projectId 项目 ID
     * @param subDeviceId 子设备 ID
     * @param status 目标在线态（ONLINE/OFFLINE）
     * @param receivedAt 平台可信接收时间
     * @return 是否命中有效绑定并更新成功
     */
    boolean updateOnlineStatus(UUID projectId, UUID subDeviceId, DeviceTopology.OnlineStatus status,
                               Instant receivedAt);
}
