package com.things.link.export.domain;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;

/** ADR0077：导出域保留对象清理身份直到上传预算后再次确认，随后显式分批删元数据。 */
public interface ProjectExportPurgeRepository {

    /** @param claim 已持项目锁和RLS的范围 @return 等待、单表最多500行删除或完整空域 */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
