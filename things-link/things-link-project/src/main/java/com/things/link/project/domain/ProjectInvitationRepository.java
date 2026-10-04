package com.things.link.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 协作身份目录；按ID读取仅供有效码校验，不得将未确权行直接返回HTTP。 */
public interface ProjectInvitationRepository {
    Optional<ProjectInvitation> find(UUID id);
    Optional<ProjectInvitation> lock(UUID id, UUID projectId);
    void create(ProjectInvitation invitation);
    boolean replace(ProjectInvitation invitation, long expectedRevision);
    void expirePending(UUID projectId, String email, Instant now);
    List<ProjectInvitation> projectPage(UUID projectId, Instant before, UUID beforeId, int limit);
    List<ProjectInvitation> inboxPage(String verifiedEmail, Instant before, UUID beforeId, int limit);
    boolean recordDelivery(UUID id, long revision, ProjectInvitation.Delivery delivery);
}
