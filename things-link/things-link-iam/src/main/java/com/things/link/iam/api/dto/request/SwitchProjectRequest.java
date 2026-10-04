package com.things.link.iam.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 切换当前项目请求。
 *
 * @param projectId 目标项目 ID。传 {@code null} 表示<b>退出项目上下文</b> ——
 *                  回到「还没选项目」的状态，此时受项目 RLS 保护的数据一行也读不到
 */
@Schema(description = "切换当前项目请求")
public record SwitchProjectRequest(
        @Schema(description = "目标项目 ID；null 表示退出项目上下文")
        String projectId) {
}
