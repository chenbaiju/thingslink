package com.things.link.project.application;
import java.util.UUID;
/** 事务提交后仅按邀请身份和修订投递，不把码或邮箱放进事件日志。 */
public record ProjectInvitationDispatchRequested(UUID invitationId, long revision) { }
