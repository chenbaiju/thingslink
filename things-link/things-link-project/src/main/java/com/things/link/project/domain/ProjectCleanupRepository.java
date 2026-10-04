package com.things.link.project.domain;

import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupBatchResult;

import java.util.Optional;
import java.util.UUID;

/** ADR0076：只操作project事实的清理准入、围栏及退避仓储。 */
public interface ProjectCleanupRepository {

    /** @param token 新领取围栏 @return 一个到期项目或空，调用方必须提供真实写事务 */
    Optional<ProjectCleanupClaim> claimNext(UUID token);

    /** @param claim 全身份围栏 @return 是否持有到事务结束的当前项目锁 */
    boolean lockCurrent(ProjectCleanupClaim claim);

    /** @param claim 全身份围栏 @param failureCode 稳定失败码 @return 是否成功释放当前租约并退避 */
    boolean defer(ProjectCleanupClaim claim, String failureCode);

    /** @param claim 完整身份 @param nextStage 下一阶段 @param deletedRows 实删行数 @param blockedReason 等待分类 @return 围栏提交是否成功 */
    boolean completeBatch(ProjectCleanupClaim claim, String nextStage, int deletedRows, String blockedReason);

    /** @param claim PROJECT完整身份 @return 本轮最多500成员或空域完成 */
    ProjectCleanupBatchResult cleanMembers(ProjectCleanupClaim claim);

    /** @param claim 锁定的项目身份 @return 是否仍有任何状态的成员 */
    boolean hasMembers(ProjectCleanupClaim claim);

    /** @param claim FINALIZE完整身份 @return 实际时钟、无成员条件下是否封存成功 */
    boolean finalizeProject(ProjectCleanupClaim claim);
}
