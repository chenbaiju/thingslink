package com.things.link.device.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 分享合同§2/4指定候选目录仓储，必须在SQL分页前完成全部范围过滤。 */
public interface DeviceRuntimeCatalogRepository {
    /**
     * @param projectId 已建立普通项目RLS的可信项目
     * @param modelVersionId 变量声明的精确模型
     * @param candidateIds 已确权的1..20个不同候选
     * @param beforeTime 可空游标时刻
     * @param beforeId 与时刻同时出现的游标身份
     * @param limit 1..51，额外一行用于上游判断下一页
     * @return 按createdAt/id降序排列的有限事实
     */
    List<Item> find(UUID projectId, UUID modelVersionId, List<UUID> candidateIds,
                    Instant beforeTime, UUID beforeId, int limit);

    /**
     * @param deviceId 设备身份
     * @param name 当前名称
     * @param deviceStatus 当前状态
     * @param currentModelVersionId 当前精确模型
     * @param createdAt 稳定分页时刻
     */
    record Item(UUID deviceId, String name, String deviceStatus,
                UUID currentModelVersionId, Instant createdAt) { }
}
