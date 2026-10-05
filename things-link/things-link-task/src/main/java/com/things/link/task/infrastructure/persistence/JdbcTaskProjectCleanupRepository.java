package com.things.link.task.infrastructure.persistence;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.task.domain.TaskProjectCleanupRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** S12-P0-5h3：逐表稳定键集删除，先锁执行再跨RLS复核子行，阻止隐藏级联或并发新增引用。 */
@Repository
public class JdbcTaskProjectCleanupRepository implements TaskProjectCleanupRepository {

    /** 必须沿用project批次的当前事务连接。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 任务域事务访问器 */
    public JdbcTaskProjectCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        int deleted = jdbc.update("""
                DELETE FROM public.task_target WHERE (execution_id, device_id) IN (
                    SELECT execution_id, device_id FROM public.task_target WHERE tenant_id=? AND project_id=?
                    ORDER BY execution_id, device_id LIMIT ?)
                """, claim.tenantId(), claim.projectId(), ProjectCleanupBatchResult.LIMIT);
        if (deleted > 0) {
            return ProjectCleanupBatchResult.deleted(deleted);
        }

        // FOR UPDATE与子行外键的KEY SHARE冲突，检查之后不能再插入指向这些执行的新目标。
        List<UUID> executions = jdbc.query("""
                SELECT id FROM public.task_execution WHERE tenant_id=? AND project_id=?
                ORDER BY id LIMIT ? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), claim.tenantId(), claim.projectId(), ProjectCleanupBatchResult.LIMIT);
        if (!executions.isEmpty()) {
            // UUID数组作为绑定参数；不拼SQL标识符或请求文本。独立RC语句看见等待锁期间提交的子行。
            String ids = executions.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT public.task_cleanup_targets_remain(?::uuid[])", Boolean.class, ids))) {
                return ProjectCleanupBatchResult.blocked("TASK_TARGET_REMAINS");
            }
            deleted = jdbc.update("DELETE FROM public.task_execution WHERE tenant_id=? AND project_id=? AND id=ANY(?::uuid[])",
                    claim.tenantId(), claim.projectId(), ids);
            return ProjectCleanupBatchResult.deleted(deleted);
        }
        deleted = jdbc.update("""
                DELETE FROM public.task_schedule WHERE id IN (SELECT id FROM public.task_schedule WHERE tenant_id=? AND project_id=?
                    ORDER BY id LIMIT ?)
                """, claim.tenantId(), claim.projectId(), ProjectCleanupBatchResult.LIMIT);
        if (deleted > 0) {
            return ProjectCleanupBatchResult.deleted(deleted);
        }
        deleted = jdbc.update("""
                DELETE FROM public.task_job WHERE id IN (SELECT id FROM public.task_job WHERE tenant_id=? AND project_id=?
                    ORDER BY id LIMIT ?)
                """, claim.tenantId(), claim.projectId(), ProjectCleanupBatchResult.LIMIT);
        return deleted > 0 ? ProjectCleanupBatchResult.deleted(deleted) : ProjectCleanupBatchResult.done();
    }
}
