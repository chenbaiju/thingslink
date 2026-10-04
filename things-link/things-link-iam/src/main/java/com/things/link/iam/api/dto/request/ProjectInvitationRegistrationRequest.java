package com.things.link.iam.api.dto.request;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
/** 邀请注册仍需随后验证邮箱；输入邮箱不能替换邀请绑定邮箱。 */
public record ProjectInvitationRegistrationRequest(@NotNull UUID invitationId,
        @Schema(minLength=43,maxLength=43) @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String code,
        @NotBlank @Email @Size(max=255) String email,
        @NotBlank @Size(min=10,max=128) String password,
        @Size(max=64) String displayName) {
    @Override public String toString() { return "ProjectInvitationRegistrationRequest[id=" + invitationId + ", secrets=***]"; }
}
