package com.things.link.assistant.infrastructure.persistence;
import com.things.link.assistant.domain.AssistantProjectCleanupRepository;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupBatchResult;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
/** 数据库再次验证同一租约；APP无普通DELETE权限。 */
@Repository
public class JdbcAssistantProjectCleanupRepository implements AssistantProjectCleanupRepository {
    private final JdbcTemplate jdbc;
    public JdbcAssistantProjectCleanupRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim c) {
        return jdbc.queryForObject("SELECT * FROM public.assistant_project_cleanup_batch(?,?,?,?)",
            (r,n)->new ProjectCleanupBatchResult(r.getInt("deleted_rows"),r.getBoolean("complete"),r.getString("blocked_reason")),
            c.tenantId(),c.projectId(),c.generation(),c.leaseToken());
    }
}
