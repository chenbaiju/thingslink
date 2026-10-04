package com.things.link.project.api.dto.request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
/** 接受必须经正文提交，不支持GET消费或URL查询码。 */
public record AcceptProjectInvitationRequest(
        @Schema(description="邀请校验码", minLength=43, maxLength=43) @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String code) {
    @Override public String toString() { return "AcceptProjectInvitationRequest[redacted]"; }
}
