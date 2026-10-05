package com.things.link.assistant.domain;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupBatchResult;
/** 自有表清理端口。 */
public interface AssistantProjectCleanupRepository { ProjectCleanupBatchResult clean(ProjectCleanupClaim claim); }
