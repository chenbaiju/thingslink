package com.things.link.project.api.dto.response;

import com.things.link.project.application.ProjectMemberView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 项目成员。
 *
 * <p><b>不返回 tenantId。</b>成员来自哪个租户是别人的组织信息，与本项目的协作无关
 * （与 {@link ProjectResponse} 同一个理由）。
 *
 * @param accountId   账号 ID。改角色与移除成员都用它定位
 * @param email       邮箱
 * @param displayName 显示名
 * @param role        项目内角色
 * @param joinedAt    加入时刻（RFC3339 UTC）
 */
@Schema(description = "项目成员")
public record ProjectMemberResponse(
        @Schema(description = "账号 ID") String accountId,
        @Schema(description = "邮箱", example = "teammate@example.com") String email,
        @Schema(description = "显示名", example = "李四") String displayName,
        @Schema(description = "项目内角色", example = "OPERATOR") String role,
        @Schema(description = "加入时刻") String joinedAt) {

    /**
     * 由用例视图转换。
     *
     * @param view 成员视图
     * @return HTTP 响应
     */
    public static ProjectMemberResponse from(ProjectMemberView view) {
        return new ProjectMemberResponse(
                view.accountId().toString(),
                view.email(),
                view.displayName(),
                view.role().name(),
                // 时间一律 RFC3339 UTC，格式化交给前端（开发手册 2.2）
                view.joinedAt().toString());
    }

}
