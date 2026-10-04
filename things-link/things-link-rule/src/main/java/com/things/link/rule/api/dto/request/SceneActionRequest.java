package com.things.link.rule.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 场景动作节点 HTTP 写入契约；动作节点与规则动作共用同一组冻结副作用。
 *
 * @param nodeType 动作节点稳定类型
 * @param config 动作节点配置对象
 */
public record SceneActionRequest(
        @NotBlank @Size(max = 64) String nodeType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) JsonNode config) {
}
