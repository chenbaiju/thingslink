package com.things.link.device.domain;

import com.things.link.shared.page.CursorPage;

import java.time.Instant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 设备实例仓储端口。 */
public interface DeviceRepository {
    /**
     * 单调登记首次成功CURRENT数据上报，未来接收时间钳至数据库时刻。
     * @param tenantId 可信设备租户
     * @param projectId 可信设备项目
     * @param deviceId 设备标识
     * @param receivedAt 平台接收时刻，不能传设备端occurredAt
     */
    void recordDataReport(UUID tenantId, UUID projectId, UUID deviceId, Instant receivedAt);

    /** 用既有组谓词批量判定同一设备；最多100个当前项目组，不展开组中全部设备。 */
    java.util.Set<UUID> matchingGroups(UUID projectId, UUID deviceId, java.util.List<DeviceGroup> groups);

    /** 原事务保护目标身份；空代表不存在，false代表已有软删除事实。 */
    Optional<Boolean> lockAutomationIdentity(UUID tenant, UUID project, UUID device);
    /** @param tenantId 项目所有者租户；同租户并发创建设备必须串行检查共享上限 */
    void lockTenantDeviceQuota(UUID tenantId);
    /** @param device 新设备 */ void create(Device device);
    /** @param projectId 项目 ID @param id 设备 ID @return 未删除设备 */
    Optional<Device> findById(UUID projectId, UUID id);
    /** 锁定未删除设备至事务结束；拓扑写必须先锁子设备再读关系，避免把旧网关事件写入新绑定。 */
    Optional<Device> findByIdForUpdate(UUID projectId, UUID id);
    /** 绑定期间保护网关不被排他软删；此锁必须先于子设备锁，普通在线状态更新仍可并发。 */
    Optional<Device> findByIdForKeyShare(UUID projectId, UUID id);
    /**
     * ADR0059：版本指针或任一转换事实已存在时，不得再把设备视为未版本化身份。
     * 调用方须在同一事务中先取得设备FOR UPDATE锁，避免首次绑定与类型变更并发穿透。
     *
     * @param projectId 已确权项目 ID
     * @param deviceId 已锁定设备 ID
     * @return 当前版本非空或存在绑定历史时为true；已有历史但空指针同样必须拒绝换型
     */
    boolean hasModelVersionBinding(UUID projectId, UUID deviceId);
    /** @param projectId 项目 ID @param deviceKey MQTT 设备标识 @return 未删除设备 */
    Optional<Device> findByDeviceKey(UUID projectId, String deviceKey);
    /**
     * 按固定白名单执行项目内组合筛选。
     *
     * @param query 组合筛选条件
     * @param group 可选且已校验归属的设备组
     * @return 一页设备
     */
    CursorPage<Device> search(DeviceSearchQuery query, DeviceGroup group);
    /**
     * 按显式设备 ID 白名单执行项目内键集分页。
     *
     * <p>App 数据面（S11-2b）先经 {@code app_user_device} 得到绑定设备 ID 集合，再按本方法取回
     * 设备事实；不接收客户端任意筛选表达式，只允许 ID 白名单 + 游标 + 数量。未删除设备按
     * {@code (created_at, id)} 倒序返回，与 {@link #search} 的键集约定一致。
     *
     * @param projectId 项目 ID
     * @param deviceIds 设备 ID 白名单
     * @param cursor    上一页游标；null 表示首页
     * @param limit     单页数量
     * @return 一页设备
     */
    CursorPage<Device> searchByIds(UUID projectId, java.util.Set<UUID> deviceIds, String cursor, int limit);
    /** @param device 更新后的领域对象 @return 是否命中 */
    boolean update(Device device);
    /**
     * ADR0059：明确换到已发布类型后，在一条SQL内首次绑定最新版本并追加一条INITIAL事实。
     * 调用方须持续持有设备FOR UPDATE及目标类型FOR SHARE NOWAIT锁，且已在同事务将设备改到目标类型。
     *
     * @param projectId 已确权项目 ID
     * @param deviceId 已锁定且尚无版本/历史的设备 ID
     * @param deviceTypeId 已锁定并确认为PUBLISHED的目标类型 ID
     * @return 恰好插入一条INITIAL时为true；缺版本或前置状态丢失时为false，调用方必须回滚此前类型及字段修改
     */
    boolean bindInitialModelVersion(UUID projectId, UUID deviceId, UUID deviceTypeId);
    /** 将设备的网关物化投影指向指定网关（可为空表示解绑）；由拓扑服务独占维护。 @return 是否命中 */
    boolean setGatewayId(UUID projectId, UUID deviceId, UUID gatewayId);
    /**
     * 投影设备统一可达性状态；由拓扑在线状态机或连接事件按设备类型互斥写。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param status 目标状态
     * @param lastOnlineAt 上线时的新最近在线时刻；非上线传 null 保持原值
     * @return 是否命中未删除设备
     */
    boolean setStatus(UUID projectId, UUID deviceId, Device.Status status, java.time.Instant lastOnlineAt);
    /** @param projectId 项目 ID @param id 设备 ID @return 是否命中 */
    boolean softDelete(UUID projectId, UUID id);
}
