package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.domain.DashboardGrantTargetRepository;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用PostgreSQL共享行锁稳定终端用户授权目标目录。
 *
 * <p>S12-2a3a2 的SQL只读取目录主键并以{@code FOR SHARE}阻塞包括{@code deleted_at}在内的
 * 非键更新；共享锁允许不同用户的授权事务并发锁定同一看板。普通表RLS继续生效，本适配器不调用
 * SECURITY DEFINER函数，也不建立或切换事务范围。</p>
 */
@Repository
public class JdbcDashboardGrantTargetRepository implements DashboardGrantTargetRepository {

    /** 完整三轴身份与软删状态是唯一资格，不把发布指针或草稿存在性混入授权目标判断。 */
    private static final String LOCK_FOR_GRANT_SQL = """
            SELECT id
              FROM public.dash_dashboard
             WHERE tenant_id = ?
               AND project_id = ?
               AND id = ?
               AND deleted_at IS NULL
             FOR SHARE
            """;

    /** 使用调用方事务绑定的原连接执行边界检查与共享锁SQL。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建看板授权目录锁适配器。
     *
     * @param jdbcTemplate 事务感知JDBC入口
     */
    public JdbcDashboardGrantTargetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockForGrant(UUID tenantId, UUID projectId, UUID dashboardId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        requireGrantTransaction();
        List<UUID> locked = jdbcTemplate.query(
                LOCK_FOR_GRANT_SQL,
                (result, row) -> result.getObject("id", UUID.class),
                tenantId, projectId, dashboardId);
        if (locked.size() > 1) {
            throw new IllegalStateException("看板授权目录锁返回了重复身份");
        }
        return !locked.isEmpty();
    }

    /**
     * 拒绝会提前释放共享锁或不提供锁后新快照的事务环境。
     *
     * <p>Spring事务标记防止其他事务管理器制造假阳性；原物理连接还必须关闭自动提交、保持可写，
     * 并使用PostgreSQL按语句刷新快照的READ COMMITTED/RU隔离级。</p>
     */
    private void requireGrantTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("看板授权目录锁要求已有非只读事务");
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            int isolation = connection.getTransactionIsolation();
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || (isolation != Connection.TRANSACTION_READ_COMMITTED
                    && isolation != Connection.TRANSACTION_READ_UNCOMMITTED)) {
                throw new IllegalStateException("看板授权目录锁要求原READ COMMITTED可写连接");
            }
            return null;
        });
    }
}
