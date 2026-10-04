package com.things.link.rule.api.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
/** 自动化节点参数保持JSON数值精度，进入原有节点用途白名单。 */
public record AutomationNodeRequest(@NotBlank String nodeType,
        @NotNull @JsonDeserialize(using=AutomationJsonDeserializer.class) JsonNode config) {}
