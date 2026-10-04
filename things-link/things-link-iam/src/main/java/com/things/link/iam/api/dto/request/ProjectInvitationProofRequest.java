package com.things.link.iam.api.dto.request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
/** 公开预览的能力证明；不接受只传邮箱或项目的匿名查询。 */
public record ProjectInvitationProofRequest(@NotNull UUID invitationId,
        @Schema(minLength=43,maxLength=43) @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String code) {
    @Override public String toString() { return "ProjectInvitationProofRequest[id=" + invitationId + ", code=***]"; }
}
