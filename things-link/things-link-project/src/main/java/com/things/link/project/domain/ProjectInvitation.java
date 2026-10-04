package com.things.link.project.domain;

import com.things.link.shared.authz.ProjectRole;
import java.time.Instant;
import java.util.UUID;

/** 不保存明文校验码的邀请事实；邮箱、项目、角色在重发时保持绑定。 */
public record ProjectInvitation(UUID id, UUID tenantId, UUID projectId, UUID inviterAccountId,
        String targetEmail, ProjectRole role, Status status, long revision, UUID codeNonce,
        Instant expiresAt, Channel deliveryChannel, Delivery deliveryStatus, UUID acceptedAccountId,
        Instant acceptedAt, Instant createdAt, Instant updatedAt) {
    public enum Status { PENDING, ACCEPTED, REVOKED, EXPIRED }
    public enum Channel { INBOX, EMAIL }
    public enum Delivery { QUEUED, SENT, FAILED, INBOX }
    public boolean pendingAt(Instant now) { return status == Status.PENDING && now.isBefore(expiresAt); }
}
