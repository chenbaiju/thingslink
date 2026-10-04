package com.things.link.rule.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 场景条件节点 HTTP 写入契约；S9-4 只允许纯计算白名单节点，非法类型在应用服务 fail-closed。
 *
 * @param nodeType 条件节点稳定类型
 * @param config 条件节点配置对象
 */
public record SceneConditionRequest(
        @NotBlank @Size(max = 64) String nodeType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) JsonNode config) {
}
