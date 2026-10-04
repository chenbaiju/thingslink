package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.domain.AppUserDashboardGrant;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Console可见的终端用户看板READ授权闭集响应。
 *
 * <p>内部授权行、租户、项目与Console操作者身份不属于管理合同；revision使用十进制字符串，避免
 * JavaScript数值精度改变后续CAS。稳定看板已软删时，读取服务仍只返回本历史关系字段。</p>
 *
 * @param appUserId 被授权终端用户ID
 * @param dashboardId 稳定看板ID
 * @param permission 固定READ，不从数据库或项目角色扩展
 * @param status ACTIVE或REVOKED
 * @param revision 当前授权CAS修订号的十进制字符串
 * @param createdAt 首次授予时刻
 * @param updatedAt 最近实际状态变化时刻
 * @param revokedAt 撤销时刻，ACTIVE时为空
 */
@Schema(description = "终端用户看板READ授权")
public record AppUserDashboardGrantResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID appUserId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "READ") String permission,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "REVOKED"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1") String revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(types = {"string", "null"}, format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED) Instant revokedAt) {

    /**
     * 从已经过管理服务可见性校验的领域事实投影HTTP闭集。
     *
     * @param grant 完整领域授权事实
     * @return 不泄露内部身份与操作者的管理响应
     */
    public static AppUserDashboardGrantResponse from(AppUserDashboardGrant grant) {
        Objects.requireNonNull(grant, "grant");
        return new AppUserDashboardGrantResponse(
                grant.appUserId(),
                grant.dashboardId(),
                "READ",
                grant.status().name(),
                Long.toString(grant.revision()),
                grant.createdAt(),
                grant.updatedAt(),
                grant.revokedAt());
    }
}
