package com.things.link.iam.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 当前登录用户信息。
 *
 * <p>UUID 用字符串表示：JSON 没有 UUID 类型，明确成字符串可以避免各语言客户端
 * 的反序列化差异。
 *
 * @param accountId   账号 ID
 * @param email       邮箱
 * @param displayName 显示名
 * @param tenantId    当前租户 ID
 * @param projectRole 当前项目中的角色（OWNER / ADMIN / OPERATOR / VIEWER）；未选择项目时为 null
 * @param permissions 当前用户在当前项目下的权限点标识
 * @param currentProjectId 当前选中的项目 ID；未选择时为 null。
 *                         前端刷新页面后靠它恢复「当前在哪个项目」——
 *                         访问令牌只存在内存里，页面一刷新就没了，
 *                         但项目选择记在刷新令牌上，所以能还原
 */
@Schema(description = "当前登录用户")
public record CurrentUserResponse(
        @Schema(description = "账号 ID") String accountId,
        @Schema(description = "邮箱") String email,
        @Schema(description = "显示名") String displayName,
        @Schema(description = "当前租户 ID") String tenantId,
        @Schema(description = "当前项目角色；未选择项目时为 null", example = "OWNER") String projectRole,

        /*
         * 权限点集合。
         *
         * 下发它不是为了让前端做授权判断 —— 授权在服务端。它解决的是「同一个操作在
         * 多个页面出现」的场景：菜单里的 authList 只覆盖单个页面，而工具栏、右键菜单
         * 这类跨页面的入口需要一份全局清单，否则每处都得去翻自己所在路由的 meta。
         *
         * 无内容时是空数组而非 null，与 ApiError.details 同一个约定：客户端不必做
         * null 判断（架构文档 11.1）。
         */
        @Schema(description = "权限点标识集合", example = "[\"dashboard:read\"]")
        List<String> permissions,

        @Schema(description = "当前选中的项目 ID；未选择时为 null")
        String currentProjectId) {
}
