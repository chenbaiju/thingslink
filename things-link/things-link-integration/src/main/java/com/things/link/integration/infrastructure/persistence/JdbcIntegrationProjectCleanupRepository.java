package com.things.link.integration.infrastructure.persistence;
import com.things.link.integration.domain.IntegrationProjectCleanupRepository;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
/** 固定数据库清理端口复验租约与代次，不给普通应用DELETE权限。 */
@Repository
public class JdbcIntegrationProjectCleanupRepository implements IntegrationProjectCleanupRepository {
    private final JdbcTemplate jdbc;
    public JdbcIntegrationProjectCleanupRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return jdbc.queryForObject("SELECT * FROM public.integ_project_cleanup_batch(?,?,?,?)",
            (r,n)->new ProjectCleanupBatchResult(r.getInt("deleted_rows"),r.getBoolean("complete"),r.getString("blocked_reason")),
            claim.tenantId(),claim.projectId(),claim.generation(),claim.leaseToken());
    }
}
