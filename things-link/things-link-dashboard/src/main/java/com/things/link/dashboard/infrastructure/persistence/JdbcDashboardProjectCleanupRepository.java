package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.domain.DashboardProjectCleanupRepository;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ADR0078/0096：调用dashboard域固定SECURITY DEFINER批次端口，不直接读写project表。
 * SQL函数负责再次校验项目、代次、DASHBOARD阶段与租约，Java层只映射有限结果。
 */
@Repository
public class JdbcDashboardProjectCleanupRepository implements DashboardProjectCleanupRepository {

    /** 与project批次共享调用方已经开启的物理事务连接。 */
    private final JdbcTemplate jdbc;

    /**
     * 创建看板域JDBC清理适配器。
     *
     * @param jdbc 当前领域事务的数据库入口
     */
    public JdbcDashboardProjectCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return jdbc.queryForObject("SELECT * FROM public.dashboard_project_cleanup_batch(?,?,?,?)",
                (result, row) -> new ProjectCleanupBatchResult(result.getInt("deleted_rows"),
                        result.getBoolean("complete"), result.getString("blocked_reason")),
                claim.tenantId(), claim.projectId(), claim.generation(), claim.leaseToken());
    }
}
