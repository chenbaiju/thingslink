package com.things.link.integration.domain;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
/** 只消费可信项目租约，在原事务有界清理本域凭据。 */
public interface IntegrationProjectCleanupRepository {
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
