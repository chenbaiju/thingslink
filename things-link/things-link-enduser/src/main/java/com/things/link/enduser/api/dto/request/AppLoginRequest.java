package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * App 登录请求体。
 *
 * <p>本类型是 HTTP 契约的一部分，<b>禁止被其他模块引用</b>（架构文档 10.4 规则 4）。
 *
 * <p>以 {@code projectKey} 而非 tenantId 定位租户（ADR 0036）：租户由 projectKey 从 RLS
 * 豁免的 {@code sys_project} 解析，客户端提交的 tenantId 是不可信的。
 *
 * @param projectKey 项目 MQTT 标识，全局唯一
 * @param username   租户内用户名，服务端规范化为 trim + 小写
 * @param password   明文口令
 */
@Schema(description = "App 登录请求")
public record AppLoginRequest(

        @Schema(description = "项目 MQTT 标识（全局唯一）", example = "proj-abc123")
        @NotBlank(message = "项目标识不能为空")
        @Size(max = 64, message = "项目标识过长")
        String projectKey,

        @Schema(description = "租户内用户名", example = "alice")
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名过长")
        String username,

        // 只校验非空与长度上限，不校验口令复杂度 —— 登录校验的是「这个口令对不对」，
        // 不是「够不够强」。上限是防御性的：bcrypt 对超长输入的计算成本随长度增长
        @Schema(description = "口令")
        @NotBlank(message = "口令不能为空")
        @Size(max = 128, message = "口令长度不能超过 128")
        String password) {

    /**
     * 覆盖默认实现，避免口令随日志泄露。
     */
    @Override
    public String toString() {
        return "AppLoginRequest[projectKey=%s, username=%s, password=***]"
                .formatted(projectKey, username);
    }

}
