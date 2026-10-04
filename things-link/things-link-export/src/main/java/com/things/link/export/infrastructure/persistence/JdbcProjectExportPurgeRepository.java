package com.things.link.export.infrastructure.persistence;

import com.things.link.export.domain.ProjectExportPurgeRepository;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ADR0077：不把租约过期、首次DELETED或SDK取消请求当作上传已安全收束。 */
@Repository
public class JdbcProjectExportPurgeRepository implements ProjectExportPurgeRepository {

    /** 与项目围栏及进度共用物理事务。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 导出域事务访问器 */
    public JdbcProjectExportPurgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.sys_project_export_job
                  WHERE tenant_id=? AND project_id=? AND status IN ('QUEUED','RUNNING','SUCCEEDED'))
                """, Boolean.class, claim.tenantId(), claim.projectId()))) {
            return ProjectCleanupBatchResult.blocked("EXPORT_NOT_TERMINAL");
        }

        // 登记发生在尝试开始之后；登记+15分钟是ADR0075总预算的保守上界。
        // 首次删除过早时保留原对象键再排队，随后仍由真实对象worker删除并token-fenced确认。
        int requeued = jdbc.update("""
                WITH candidate AS (SELECT id FROM public.sys_project_export_upload_cleanup
                    WHERE tenant_id=? AND project_id=? AND status='DELETED'
                      AND (deleted_at IS NULL OR deleted_at < created_at + interval '15 minutes')
                    ORDER BY id LIMIT ? FOR UPDATE)
                UPDATE public.sys_project_export_upload_cleanup c SET status='PENDING', deleted_at=NULL,
                    next_attempt_at=GREATEST(clock_timestamp(), c.created_at + interval '15 minutes'),
                    updated_at=clock_timestamp()
                  FROM candidate x WHERE c.id=x.id
                """, claim.tenantId(), claim.projectId(), ProjectCleanupBatchResult.LIMIT);
        if (requeued > 0) {
            return ProjectCleanupBatchResult.blocked("EXPORT_UPLOAD_QUIESCENCE");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.sys_project_export_upload_cleanup
                  WHERE tenant_id=? AND project_id=? AND (status <> 'DELETED' OR deleted_at IS NULL))
                """, Boolean.class, claim.tenantId(), claim.projectId()))) {
            return ProjectCleanupBatchResult.blocked("EXPORT_OBJECT_PENDING");
        }

        // 先清空全部子事实，再在后续批次删除job；不存在父行级联吞掉任意多子行的路径。
        int deleted = jdbc.update("""
                DELETE FROM public.sys_project_export_upload_cleanup WHERE id IN (
                    SELECT id FROM public.sys_project_export_upload_cleanup WHERE tenant_id=? AND project_id=?
                    ORDER BY id LIMIT ?)
                """, claim.tenantId(), claim.projectId(), ProjectCleanupBatchResult.LIMIT);
        if (deleted > 0) {
            return ProjectCleanupBatchResult.deleted(deleted);
        }
        deleted = jdbc.update("""
                DELETE FROM public.sys_project_export_job WHERE id IN (
                    SELECT id FROM public.sys_project_export_job WHERE tenant_id=? AND project_id=?
                      AND status IN ('FAILED','EXPIRED') ORDER BY id LIMIT ?)
                """, claim.tenantId(), claim.projectId(), ProjectCleanupBatchResult.LIMIT);
        return deleted > 0 ? ProjectCleanupBatchResult.deleted(deleted) : ProjectCleanupBatchResult.done();
    }
}
