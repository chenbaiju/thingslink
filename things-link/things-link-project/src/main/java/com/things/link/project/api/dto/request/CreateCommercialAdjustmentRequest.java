package com.things.link.project.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 操作人只能来自认证主体；不定义客户端操作者字段，未知字段遵循ADR0042忽略。 */
public record CreateCommercialAdjustmentRequest(
        @NotBlank @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String dimensionCode,
        @NotBlank @JsonDeserialize(using=CommercialIntegerDeserializer.class)
        @Schema(type="string",pattern="[1-9][0-9]{0,18}",requiredMode=Schema.RequiredMode.REQUIRED) String amount,
        @NotNull Instant startsAt,@NotNull Instant endsAt,
        @NotBlank @Size(max=512) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,
        @NotBlank @Size(max=128) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String idempotencyKey,
        @NotBlank @JsonDeserialize(using=CommercialIntegerDeserializer.class)
        @Schema(type="string",pattern="0|[1-9][0-9]{0,18}",requiredMode=Schema.RequiredMode.REQUIRED) String expectedAssignmentVersion) {
}
