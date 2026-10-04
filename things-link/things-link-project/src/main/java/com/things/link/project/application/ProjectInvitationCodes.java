package com.things.link.project.application;

import com.things.link.project.domain.ProjectInvitation;

/** 固定用途的邀请校验码，不兼作邮箱证明、会话令牌或分页游标。 */
public interface ProjectInvitationCodes {
    String issue(ProjectInvitation invitation);
    boolean matches(ProjectInvitation invitation, String code);
}
