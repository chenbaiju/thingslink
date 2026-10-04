package com.things.link.device.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 设备组、静态成员和标签的持久化端口。 */
public interface DeviceGroupRepository {
    /** @param group 新设备组 */
    void createGroup(DeviceGroup group);
    /** @param projectId 项目 ID @return 项目内设备组 */
    List<DeviceGroup> findGroups(UUID projectId);
    /** 有界读取当前有效组，最多100个ID；不存在或已删除项不返回。 */
    List<DeviceGroup> findGroupsByIds(UUID projectId, java.util.Set<UUID> groupIds);
    /** @param projectId 项目 ID @param groupId 组 ID @return 项目内有效设备组 */
    Optional<DeviceGroup> findGroup(UUID projectId, UUID groupId);
    /** @param group 更新后的组基础信息 @return 是否命中 */
    boolean updateGroup(DeviceGroup group);
    /** @param projectId 项目 ID @param groupId 组 ID @return 是否软删除 */
    boolean softDeleteGroup(UUID projectId, UUID groupId);
    /** @param projectId 项目 ID @param groupId 静态组 ID @param deviceId 设备 ID */
    void addMember(UUID projectId, UUID groupId, UUID deviceId);
    /** 原子替换静态组全部成员。 @param projectId 项目 ID @param groupId 组 ID @param deviceIds 新成员 */
    void replaceMembers(UUID projectId, UUID groupId, List<UUID> deviceIds);
    /** @param tag 要写入或更新的设备标签 */
    void upsertTag(DeviceTag tag);
    /** @param projectId 项目 ID @param deviceId 设备 ID @return 项目设备标签 */
    List<DeviceTag> findTags(UUID projectId, UUID deviceId);
    /** @param projectId 项目 ID @param deviceId 设备 ID @param key 标签键 @return 是否删除 */
    boolean deleteTag(UUID projectId, UUID deviceId, String key);
}
