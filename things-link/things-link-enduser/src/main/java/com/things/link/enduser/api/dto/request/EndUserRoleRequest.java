package com.things.link.enduser.api.dto.request;

import com.things.link.enduser.domain.EndUserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 终端用户项目角色请求（分配角色与改角色共用）。
 *
 * <p>直接用枚举而不是 String：非法值在绑定阶段就被 Jackson 拒绝（映射为请求体格式错误），
 * 服务层不必再解析一次。控制台角色是下拉框选的，选不出非法值。
 *
 * @param role 终端用户项目角色
 */
@Schema(description = "终端用户项目角色请求")
public record EndUserRoleRequest(

        @Schema(description = "终端用户项目角色", example = "MAINTAINER",
                allowableValues = {"APP_ADMIN", "MAINTAINER", "OPERATOR", "OBSERVER"})
        @NotNull(message = "角色不能为空")
        EndUserRole role) {
}
