package com.things.link.project.infrastructure.persistence;

import com.things.link.project.application.AccountDirectory;
import com.things.link.project.application.ProjectExportSource;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;

/** 使用本域 SQL 流式提供项目导出事实。 */
@Repository
public class JdbcProjectExportSource implements ProjectExportSource {

    /** 流式游标批次，避免成员异常增长时一次装入内存。 */
    private static final int FETCH_SIZE = 256;
    /** 本域 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** IAM 反转端口，只读取账号是否仍可认证。 */
    private final AccountDirectory accountDirectory;

    /**
     * 创建项目导出事实源。
     * @param jdbcTemplate JDBC 访问器
     * @param accountDirectory 账号状态目录
     */
    public JdbcProjectExportSource(JdbcTemplate jdbcTemplate, AccountDirectory accountDirectory) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountDirectory = accountDirectory;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectExportScope authorizeRequest(UUID accountId, UUID projectId) {
        requireWritableTransaction();
        // 锁前布尔预检避免陌生账号用锁等待时间枚举删除项目，锁后仍会完整复核。
        if (!retainedOwner(projectId, accountId)) {
            throw invisible();
        }
        ProjectExportScope scope = jdbcTemplate.query("""
                SELECT p.tenant_id, p.id, p.lifecycle_generation, p.deleted_at,
                       q.storage_bytes_limit IS NOT NULL AS storage_quota_configured
                  FROM sys_project p
                  JOIN sys_tenant t ON t.id = p.tenant_id
                  JOIN sys_quota_policy q ON q.id = t.quota_policy_id
                 WHERE p.id = ?
                   AND p.status = 'DELETING'
                   AND p.deleted_at IS NOT NULL
                   AND clock_timestamp() < p.deleted_at + interval '30 days'
                 FOR SHARE OF p
                """, (rs, row) -> new ProjectExportScope(
                rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getLong("lifecycle_generation"), rs.getTimestamp("deleted_at").toInstant(),
                rs.getBoolean("storage_quota_configured")), projectId)
                .stream().findFirst().orElseThrow(JdbcProjectExportSource::invisible);
        if (!retainedOwner(projectId, accountId) || !accountDirectory.isActive(accountId)) {
            throw invisible();
        }
        return scope;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectDownloadScope authorizeDownload(UUID accountId, UUID projectId) {
        requireWritableTransaction();
        if (accountId == null || projectId == null) {
            throw new IllegalArgumentException("项目导出下载身份不完整");
        }
        // 签名统一先锁project/member、再由export锁job；到期清理只锁job/cleanup，不会形成反向等待环。
        ProjectDownloadScope scope = jdbcTemplate.query("""
                SELECT p.tenant_id, p.id, p.lifecycle_generation
                  FROM sys_project p
                  JOIN sys_project_member m ON m.project_id = p.id
                 WHERE p.id = ?
                   AND ((p.status IN ('ACTIVE', 'ARCHIVED') AND p.deleted_at IS NULL)
                        OR (p.status = 'DELETING' AND p.deleted_at IS NOT NULL))
                   AND m.account_id = ? AND m.role = 'OWNER' AND m.status = 'ACTIVE'
                 FOR SHARE OF p, m
                """, (rs, row) -> new ProjectDownloadScope(
                rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getLong("lifecycle_generation")), projectId, accountId)
                .stream().findFirst().orElseThrow(JdbcProjectExportSource::invisible);
        if (!accountDirectory.isActive(accountId)) {
            throw invisible();
        }
        return scope;
    }

    /** {@inheritDoc} */
    @Override
    public ProjectExportProject lockSnapshot(UUID tenantId, UUID projectId, long generation) {
        requireRepeatableReadTransaction();
        return jdbcTemplate.query("""
                SELECT p.id, p.name, p.region, p.timezone, p.status, p.created_at, p.updated_at, p.deleted_at,
                       transaction_timestamp() AS snapshot_at
                  FROM sys_project p
                 WHERE p.tenant_id = ? AND p.id = ? AND p.lifecycle_generation = ?
                   AND p.status = 'DELETING' AND p.deleted_at IS NOT NULL
                   AND clock_timestamp() < p.deleted_at + interval '30 days'
                 FOR SHARE
                """, (rs, row) -> new ProjectExportProject(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("region"),
                rs.getString("timezone"), rs.getString("status"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("deleted_at")),
                instant(rs.getTimestamp("snapshot_at"))), tenantId, projectId, generation)
                .stream().findFirst().orElseThrow(JdbcProjectExportSource::invisible);
    }

    /** {@inheritDoc} */
    @Override
    public long streamMembers(UUID tenantId, UUID projectId, MemberSink sink) {
        long[] count = {0L};
        jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT m.id, m.account_id, m.role, m.status, m.created_at, m.updated_at
                      FROM sys_project_member m
                      JOIN sys_project p ON p.id = m.project_id
                     WHERE p.tenant_id = ? AND p.id = ?
                     ORDER BY m.id
                    """);
            statement.setObject(1, tenantId);
            statement.setObject(2, projectId);
            statement.setFetchSize(FETCH_SIZE);
            return statement;
        }, rs -> {
            sink.accept(new ProjectExportMember(
                    rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                    rs.getString("role"), rs.getString("status"), instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("updated_at"))));
            count[0]++;
        });
        return count[0];
    }

    /** 锁前与锁后共用相同不可枚举OWNER判断。 */
    private boolean retainedOwner(UUID projectId, UUID accountId) {
        Boolean matched = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM sys_project p
                    JOIN sys_project_member m ON m.project_id = p.id
                    WHERE p.id = ? AND p.status = 'DELETING' AND p.deleted_at IS NOT NULL
                      AND clock_timestamp() < p.deleted_at + interval '30 days'
                      AND m.account_id = ? AND m.role = 'OWNER' AND m.status = 'ACTIVE')
                """, Boolean.class, projectId, accountId);
        return Boolean.TRUE.equals(matched);
    }

    /** SHARE 锁必须加入调用方非只读事务，不能静默退化为查询快照。 */
    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目导出许可必须在非只读事务中执行");
        }
    }

    /** worker锁必须在同一REPEATABLE READ快照中覆盖全部领域读取。 */
    private static void requireRepeatableReadTransaction() {
        requireWritableTransaction();
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation == null || isolation != java.sql.Connection.TRANSACTION_REPEATABLE_READ) {
            throw new IllegalStateException("项目导出快照必须使用REPEATABLE READ事务");
        }
    }

    /** 所有资格失败沿50001隐藏项目存在性。 */
    private static BusinessException invisible() {
        return new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** nullable JDBC 时间到 UTC。 */
    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

}
