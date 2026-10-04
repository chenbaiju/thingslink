package com.things.link.rule.api.dto.request;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.util.List;
/** 自动化Revise请求：拒绝额外顶层字段，身份只由服务器生成。 */
public record ReviseAutomationRequest(@NotBlank @Size(max=128) String name,@Size(max=512) String description,
        @jakarta.validation.constraints.Min(1) long expectedVersion, @NotBlank String triggerType,@NotNull JsonNode triggerConfig,
        List<@Valid AutomationNodeRequest> conditions,@NotEmpty List<@Valid AutomationNodeRequest> actions) {
    @JsonAnySetter public void unknown(String field,JsonNode value) {throw new IllegalArgumentException("未知自动化请求字段");}
}
