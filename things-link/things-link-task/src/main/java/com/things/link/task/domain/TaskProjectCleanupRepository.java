package com.things.link.task.domain;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;

/** S12-P0-5h3：目标先于执行，执行和调度先于任务定义，不隐藏父级联删除。 */
public interface TaskProjectCleanupRepository {

    /** @param claim 已在同物理事务锁定且配置RLS的项目 @return 单表有界删除或明确阻塞/空域 */
    ProjectCleanupBatchResult clean(ProjectCleanupClaim claim);
}
