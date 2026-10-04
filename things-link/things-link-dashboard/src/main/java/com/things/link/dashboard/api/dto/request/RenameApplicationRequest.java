package com.things.link.dashboard.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 修改Console应用目录管理名称的封闭请求。
 *
 * @param managementName 新管理名称，由应用服务执行Title语义校验
 */
public record RenameApplicationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Console目录管理名称")
        String managementName) {
}
