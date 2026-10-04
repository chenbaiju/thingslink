package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 预置终端用户账号请求。
 *
 * <p>预置只创建租户级登录身份（{@code app_user}），<b>不分配项目角色</b>——角色分配是
 * 独立用例，因为「创建账号」与「给账号项目角色」是两个可分别撤销的动作。
 *
 * @param username    租户内用户名，服务端规范化为 trim + 小写
 * @param password    初始口令，最短长度由应用服务校验（口令策略独立演进）
 * @param displayName 显示名称，可空
 */
@Schema(description = "预置终端用户请求")
public record ProvisionEndUserRequest(

        @Schema(description = "租户内用户名", example = "alice")
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名过长")
        String username,

        @Schema(description = "初始口令", example = "correct-horse-battery-staple")
        @NotBlank(message = "口令不能为空")
        String password,

        @Schema(description = "显示名称，可空", example = "张三")
        @Size(max = 128, message = "显示名称过长")
        String displayName) {
}
