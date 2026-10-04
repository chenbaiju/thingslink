package com.things.link.project.application;

import com.things.link.shared.authz.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 最小邀请投影；code仅向已验证收件人返回，管理响应为null。 */
public record ProjectInvitationView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String projectName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetEmail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProjectRole role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String deliveryChannel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String deliveryStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(description="仅向已验证目标收件人返回；管理/预览响应为空") String code) {
    @Override public String toString() { return "ProjectInvitationView[id=" + id + ", status=" + status + ", code=***]"; }
}
