package com.things.link.device.infrastructure.persistence;

import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.device.domain.DeviceProjectCleanupRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ADR0078/0088：调用本域固定SQL端口，不直接读写project表或扩张历史表权限。 */
@Repository
public class JdbcDeviceProjectCleanupRepository implements DeviceProjectCleanupRepository {

    /** 与project批次共享原物理事务的连接入口。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 当前领域事务连接 */
    public JdbcDeviceProjectCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        return jdbc.queryForObject("SELECT * FROM public.dev_project_cleanup_batch(?,?,?,?)",
                (result, row) -> new ProjectCleanupBatchResult(result.getInt("deleted_rows"),
                        result.getBoolean("complete"), result.getString("blocked_reason")),
                claim.tenantId(), claim.projectId(), claim.generation(), claim.leaseToken());
    }
}
