package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.domain.DashboardCatalogEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Console看板目录响应。
 *
 * <p>发布修订号使用十进制字符串，避免JavaScript数值精度上限破坏后续CAS。
 * 租户、项目、操作者与软删除时刻属于服务端持久事实，不进入此HTTP合同。</p>
 *
 * @param id 看板内部ID
 * @param managementName Console目录管理名称
 * @param publicationRevision 发布状态CAS修订号的十进制字符串
 * @param currentVersionId 当前发布版本；尚未发布或已撤回时为空
 * @param createdAt 创建时刻
 * @param updatedAt 最近更新时刻
 */
public record DashboardCatalogResponse(
        UUID id,
        String managementName,
        String publicationRevision,
        @Schema(types = {"string", "null"}, format = "uuid",
                description = "当前发布版本；尚未发布或已撤回时为null")
        UUID currentVersionId,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 将项目范围内的可见目录事实投影为最小HTTP响应。
     *
     * @param entry 看板目录事实
     * @return 不包含服务端内部字段的响应
     */
    public static DashboardCatalogResponse from(DashboardCatalogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return new DashboardCatalogResponse(
                entry.id(),
                entry.managementName(),
                Long.toString(entry.publicationRevision()),
                entry.currentVersionId(),
                entry.createdAt(),
                entry.updatedAt());
    }
}
