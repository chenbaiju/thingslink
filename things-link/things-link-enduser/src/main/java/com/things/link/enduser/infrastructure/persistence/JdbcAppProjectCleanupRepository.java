package com.things.link.enduser.infrastructure.persistence;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.enduser.domain.AppProjectCleanupRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ADR0078/0081：调用本域固定SQL端口，不直接读写project表或扩张历史表权限。 */
@Repository
public class JdbcAppProjectCleanupRepository implements AppProjectCleanupRepository {

    /** 与project批次共享原物理事务的连接入口。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 当前领域事务连接 */
    public JdbcAppProjectCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return jdbc.queryForObject("SELECT * FROM public.app_project_cleanup_batch(?,?,?,?)",
                (result, row) -> new ProjectCleanupBatchResult(result.getInt("deleted_rows"),
                        result.getBoolean("complete"), result.getString("blocked_reason")),
                claim.tenantId(), claim.projectId(), claim.generation(), claim.leaseToken());
    }
}
