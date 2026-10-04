package com.things.link.project.api.dto.request;

import com.things.link.shared.authz.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 修改成员角色请求。
 *
 * @param role 新角色。<b>不能是 OWNER</b>（返回 50012），也不能用于修改 OWNER 本人
 *             （返回 50013）—— 转让所有权是独立操作，混进「改个角色」里，
 *             一次误点就能把项目交出去
 */
@Schema(description = "修改成员角色请求")
public record UpdateMemberRoleRequest(

        @Schema(description = "新角色", example = "VIEWER",
                allowableValues = {"ADMIN", "OPERATOR", "VIEWER"})
        @NotNull(message = "角色不能为空")
        ProjectRole role) {
}
