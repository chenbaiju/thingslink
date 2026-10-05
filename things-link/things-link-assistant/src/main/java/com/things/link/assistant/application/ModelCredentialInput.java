package com.things.link.assistant.application;
import io.swagger.v3.oas.annotations.media.Schema;
/** 敏感输入默认打印脱敏，不作为持久事实或审计内容。 */
@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record ModelCredentialInput(
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="0|[1-9][0-9]{0,18}") String expectedRevision,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minLength=1,maxLength=4096,accessMode=Schema.AccessMode.WRITE_ONLY) String apiKey) {
    @Override public String toString() { return "ModelCredentialInput[REDACTED]"; }
}
