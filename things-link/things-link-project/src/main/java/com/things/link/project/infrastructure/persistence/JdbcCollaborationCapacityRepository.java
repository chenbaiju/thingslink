package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.CollaborationCapacityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/** S14-R4d：按基础身份归属目录与保留项目成员关系聚合，不读取账号认证字段。 */
@Repository
public class JdbcCollaborationCapacityRepository implements CollaborationCapacityRepository {
    private final JdbcTemplate jdbc;

    /** @param jdbc 原业务事务连接 */
    public JdbcCollaborationCapacityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void lockCapacity(UUID tenantId, UUID accountId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(accountId, "accountId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("协作容量锁必须加入已有写事务");
        }
        String isolation = jdbc.queryForObject("SHOW transaction_isolation", String.class);
        if (!"read committed".equals(isolation) && !"read uncommitted".equals(isolation)) {
            throw new IllegalStateException("协作容量锁要求READ COMMITTED事务");
        }
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?::text,12043))",
                Integer.class, "account-external-projects-v1:" + accountId);
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?::text,12044))",
                Integer.class, "tenant-external-seats-v1:" + tenantId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Usage readUsage(UUID tenantId, UUID projectId, UUID accountId) {
        return jdbc.queryForObject("""
                WITH target AS (SELECT ?::uuid AS tenant, ?::uuid AS project, ?::uuid AS account),
                external AS (
                  SELECT m.project_id,m.account_id,p.tenant_id
                    FROM sys_project_member m JOIN sys_project p ON p.id=m.project_id
                    CROSS JOIN target scope
                   WHERE (p.tenant_id=scope.tenant OR m.account_id=scope.account)
                     AND NOT EXISTS (SELECT 1 FROM sys_tenant_member tm
                                      WHERE tm.tenant_id=p.tenant_id AND tm.account_id=m.account_id)
                )
                SELECT EXISTS(SELECT 1 FROM sys_project_member m WHERE m.project_id=t.project AND m.account_id=t.account),
                       EXISTS(SELECT 1 FROM sys_tenant_member tm WHERE tm.tenant_id=t.tenant AND tm.account_id=t.account),
                       EXISTS(SELECT 1 FROM external e WHERE e.tenant_id=t.tenant AND e.account_id=t.account),
                       (SELECT count(DISTINCT e.account_id) FROM external e WHERE e.tenant_id=t.tenant),
                       (SELECT count(*) FROM external e WHERE e.account_id=t.account)
                  FROM target t
                """, (rs, row) -> new Usage(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3),
                        rs.getLong(4), rs.getLong(5)), tenantId, projectId, accountId);
    }
}
