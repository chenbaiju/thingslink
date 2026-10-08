package com.things.link.project.infrastructure.persistence;

import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.domain.ProjectCleanupRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** ADR0076/0078：范围只来自锁定的public权威项目行；限定schema防止D-138临时表伪造。 */
@Repository
public class JdbcProjectCleanupRepository implements ProjectCleanupRepository {

    /** 必须使用当前事务连接，不能把准入和首次审计分成两个提交。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 当前事务感知的project数据库访问器 */
    public JdbcProjectCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<ProjectCleanupClaim> claimNext(UUID token) {
        // 与恢复和导出共享固定七百二十小时边界，不按会话时区的自然日提前或延迟清理。
        return jdbc.query("""
                WITH candidate AS MATERIALIZED (
                    SELECT id, status AS previous_status FROM public.sys_project
                     WHERE (status = 'DELETING' AND deleted_at IS NOT NULL
                            AND clock_timestamp() >= deleted_at + interval '720 hours')
                        OR (status = 'PURGING' AND cleanup_next_attempt_at <= clock_timestamp()
                            AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= clock_timestamp()))
                     ORDER BY COALESCE(cleanup_next_attempt_at, deleted_at), id
                     FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE public.sys_project p
                   SET status = 'PURGING', cleanup_stage = COALESCE(p.cleanup_stage, 'WAIT_EXPORT'),
                       cleanup_started_at = COALESCE(p.cleanup_started_at, clock_timestamp()),
                       cleanup_next_attempt_at = clock_timestamp(), cleanup_lease_token = ?,
                       cleanup_lease_until = clock_timestamp() + interval '2 minutes', updated_at = clock_timestamp()
                  FROM candidate c WHERE p.id = c.id
                   AND ((p.status = 'DELETING' AND p.deleted_at IS NOT NULL
                         AND clock_timestamp() >= p.deleted_at + interval '720 hours')
                        OR (p.status = 'PURGING' AND p.cleanup_next_attempt_at <= clock_timestamp()
                            AND (p.cleanup_lease_until IS NULL OR p.cleanup_lease_until <= clock_timestamp())))
                RETURNING p.tenant_id, p.id, p.lifecycle_generation, p.cleanup_stage,
                          p.cleanup_lease_token, p.cleanup_lease_until, c.previous_status = 'DELETING' AS admitted
                """, (rs, row) -> new ProjectCleanupClaim(rs.getObject("tenant_id", UUID.class),
                rs.getObject("id", UUID.class), rs.getLong("lifecycle_generation"), rs.getString("cleanup_stage"),
                rs.getObject("cleanup_lease_token", UUID.class), rs.getTimestamp("cleanup_lease_until").toInstant(),
                rs.getBoolean("admitted")), token).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean lockCurrent(ProjectCleanupClaim claim) {
        // 先锁身份行，再用独立RC语句按数据库实际时钟复核；等待期间租约可能已过期或被接管。
        boolean found = !jdbc.query("""
                SELECT id FROM public.sys_project WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), claim.tenantId(), claim.projectId()).isEmpty();
        if (!found) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.sys_project WHERE tenant_id = ? AND id = ?
                    AND lifecycle_generation = ? AND status = 'PURGING' AND cleanup_stage = ?
                    AND cleanup_lease_token = ? AND cleanup_lease_until > clock_timestamp())
                """, Boolean.class, claim.tenantId(), claim.projectId(), claim.generation(), claim.stage(), claim.leaseToken()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean defer(ProjectCleanupClaim claim, String failureCode) {
        return jdbc.update("""
                UPDATE public.sys_project SET cleanup_lease_token = NULL, cleanup_lease_until = NULL,
                    cleanup_next_attempt_at = clock_timestamp() + interval '30 seconds',
                    cleanup_failure_code = ?, updated_at = clock_timestamp()
                 WHERE tenant_id = ? AND id = ? AND lifecycle_generation = ? AND status = 'PURGING'
                   AND cleanup_stage = ? AND cleanup_lease_token = ? AND cleanup_lease_until > clock_timestamp()
                """, failureCode, claim.tenantId(), claim.projectId(), claim.generation(), claim.stage(), claim.leaseToken()) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean completeBatch(ProjectCleanupClaim claim, String nextStage, int deletedRows, String blockedReason) {
        return jdbc.update("""
                UPDATE public.sys_project SET cleanup_stage = ?, cleanup_rows = cleanup_rows + ?,
                    cleanup_batches = cleanup_batches + CASE WHEN ? > 0 THEN 1 ELSE 0 END,
                    cleanup_failure_code = ?, cleanup_lease_token = NULL, cleanup_lease_until = NULL,
                    cleanup_next_attempt_at = clock_timestamp() + CASE WHEN ?::text IS NULL
                        THEN interval '0 seconds' ELSE interval '30 seconds' END,
                    updated_at = clock_timestamp()
                 WHERE tenant_id = ? AND id = ? AND lifecycle_generation = ? AND status = 'PURGING'
                   AND cleanup_stage = ? AND cleanup_lease_token = ? AND cleanup_lease_until > clock_timestamp()
                """, nextStage, deletedRows, deletedRows, blockedReason, blockedReason,
                claim.tenantId(), claim.projectId(), claim.generation(), claim.stage(), claim.leaseToken()) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ProjectCleanupBatchResult cleanMembers(ProjectCleanupClaim claim) {
        return jdbc.queryForObject("SELECT * FROM public.project_member_cleanup_batch(?,?,?,?)",
                (rs, row) -> new ProjectCleanupBatchResult(rs.getInt("deleted_rows"),rs.getBoolean("complete"),rs.getString("blocked_reason")),
                claim.tenantId(),claim.projectId(),claim.generation(),claim.leaseToken());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean hasMembers(ProjectCleanupClaim claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.sys_project_member WHERE project_id=?)",
                Boolean.class, claim.projectId()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean finalizeProject(ProjectCleanupClaim claim) {
        return jdbc.update("""
                UPDATE public.sys_project SET status='PURGED',cleanup_stage='DONE',name='已清理项目',timezone='UTC',
                    cleanup_completed_at=clock_timestamp(),updated_at=clock_timestamp(),cleanup_next_attempt_at=NULL,
                    cleanup_lease_token=NULL,cleanup_lease_until=NULL,cleanup_failure_code=NULL
                 WHERE tenant_id=? AND id=? AND lifecycle_generation=? AND status='PURGING' AND cleanup_stage='FINALIZE'
                   AND cleanup_lease_token=? AND cleanup_lease_until>clock_timestamp()
                   AND NOT EXISTS(SELECT 1 FROM public.sys_project_member WHERE project_id=?)
                """, claim.tenantId(),claim.projectId(),claim.generation(),claim.leaseToken(),claim.projectId()) == 1;
    }
}
