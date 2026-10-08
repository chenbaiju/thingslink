package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.AppUserReference;
import com.things.link.enduser.application.EndUserLookupService;
import com.things.link.enduser.domain.AppUserAssignment;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 终端用户响应。
 *
 * <p>一个 DTO 表达两种视图：列表/分配角色后带 {@code role} 系列字段；仅预置账号、尚未
 * 分配角色时这些字段为空。两个 {@code status} 并列表述 —— {@code status} 是租户级登录状态
 * （项目管理员不得修改），{@code roleStatus} 是项目级停用/恢复状态。
 *
 * @param id          终端用户 ID
 * @param username    租户内用户名
 * @param displayName 显示名称
 * @param status      租户级登录状态
 * @param role        项目角色；未分配时为空
 * @param roleStatus  项目级角色状态；未分配时为空
 * @param assignedAt  角色分配时刻；未分配时为空
 */
@Schema(description = "终端用户")
public record EndUserResponse(
        @Schema(description = "终端用户 ID") String id,
        @Schema(description = "租户内用户名", example = "alice") String username,
        @Schema(description = "显示名称；未设置时为空", example = "张三", types = {"string", "null"}) String displayName,
        @Schema(description = "租户级登录状态", example = "ACTIVE") String status,
        @Schema(description = "项目角色；未分配时为空", example = "OPERATOR", types = {"string", "null"}) String role,
        @Schema(description = "项目级角色状态；未分配时为空", example = "ACTIVE", types = {"string", "null"}) String roleStatus,
        @Schema(description = "角色分配时刻；未分配时为空", types = {"string", "null"}, format = "date-time") String assignedAt) {

    /** 由角色赋值投影转换（列表、已分配角色）。 */
    public static EndUserResponse from(AppUserAssignment assignment) {
        return new EndUserResponse(
                assignment.appUserId().toString(),
                assignment.username(),
                assignment.displayName(),
                assignment.userStatus().name(),
                assignment.role().name(),
                assignment.roleStatus().name(),
                assignment.assignedAt().toString());
    }

    /** 由预置账号投影转换（尚未分配角色）。 */
    public static EndUserResponse from(AppUserReference reference) {
        return new EndUserResponse(
                reference.id().toString(),
                reference.username(),
                reference.displayName(),
                reference.status().name(),
                null,
                null,
                null);
    }
    /**
     * 查找投影仅合并本项目角色，不带出其他项目赋值。
     * @param result 非敏感身份与可空角色
     * @return 与列表一致的响应，未分配时角色字段为空
     */
    public static EndUserResponse from(EndUserLookupService.Result result) {
        var user = result.user();
        var role = result.assignment();
        return new EndUserResponse(user.id().toString(), user.username(), user.displayName(), user.status().name(),
                role == null ? null : role.role().name(), role == null ? null : role.status().name(),
                role == null ? null : role.createdAt().toString());
    }

}
