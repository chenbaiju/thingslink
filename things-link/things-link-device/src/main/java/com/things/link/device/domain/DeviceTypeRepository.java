package com.things.link.device.domain;

import com.things.link.shared.page.CursorPage;

import java.util.Optional;
import java.util.UUID;

/** 设备类型仓储端口；领域层不依赖 JDBC。 */
public interface DeviceTypeRepository {
    /** 保存新设备类型。 @param deviceType 新类型 */
    void create(DeviceType deviceType);
    /** 按项目和 ID 查找未删除类型；双条件用于配合 RLS 防止跨项目访问。 @param projectId 项目 ID @param id 类型 ID @return 类型 */
    Optional<DeviceType> findById(UUID projectId, UUID id);
    /** 按项目与类型标识查找未删除类型。 @param projectId 项目 ID @param typeKey 类型标识 @return 类型 */
    Optional<DeviceType> findByTypeKey(UUID projectId, String typeKey);
    /** 锁定设备类型聚合根，防止物模型写入与发布并发穿透。 @param projectId 项目 ID @param id 类型 ID @return 已锁定类型 */
    Optional<DeviceType> findByIdForUpdate(UUID projectId, UUID id);
    /**
     * ADR0057：以非阻塞共享锁读取可变类型，保护角色判断且避免设备锁之后等待类型写锁。
     *
     * @param projectId 已确权项目 ID
     * @param id 类型 ID
     * @return 未删除且已取得共享锁的类型；锁冲突必须向调用方传播并整体回滚
     */
    Optional<DeviceType> findByIdForShareNowait(UUID projectId, UUID id);
    /** @param projectId 项目 ID @param cursor 上一页游标 @param limit 单页上限 @return 一页未删除设备类型 */
    CursorPage<DeviceType> search(UUID projectId, String cursor, int limit);
    /** 更新草稿基础信息。 @param deviceType 更新后的领域对象 @return 是否命中未删除记录 */
    boolean update(DeviceType deviceType);
    /** 软删除草稿，保留审计与未来设备引用所需的稳定 ID。 @param projectId 项目 ID @param id 类型 ID @return 是否命中未删除记录 */
    boolean softDelete(UUID projectId, UUID id);
    /** 将草稿发布为已冻结的物模型版本。 @param projectId 项目 ID @param id 类型 ID @return 是否命中未删除草稿 */
    boolean publish(UUID projectId, UUID id);
    /** 按项目和产品标识查找已发布类型。 */
    Optional<DeviceType> findPublishedByProductKey(UUID projectId, String productKey);
    /** 轮换一型一密凭据；凭据变化不属于物模型版本修改。 */
    boolean updateProductCredential(UUID projectId, UUID id, String productKey, String productSecretHash);
}
