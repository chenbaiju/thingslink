package com.things.link.assistant.application;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
/** 唯一 HTTP 出参，不包含秘密及其可复用标识。 */
public record ModelConfigurationView(
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean configured,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean enabled,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="0|[1-9][0-9]{0,18}") String revision,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED,types={"string","null"},format="date-time") Instant updatedAt) {}
