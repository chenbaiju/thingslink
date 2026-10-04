package com.things.link.support.tenant;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * 在当前 Spring 事务绑定的物理连接上建立完整的事务局部 RLS 范围。
 *
 * <p>S12-2a1a / D-098：无 HTTP 入口不能依赖稍后写入的 ThreadLocal，因为
 * {@link TenantAwareDataSource} 只在借出连接时读取线程范围。本组件改为在调用方已经建立的真实事务内，
 * 通过事务绑定的 {@link JdbcTemplate} 对同一连接执行 {@code set_config(..., true)}。</p>
 *
 * <p>传入的租户与项目必须来自调用方已经核验的权威事实，不能直接来自请求字段。组件不解析身份、
 * 不读取 ThreadLocal，也不提供跨租户查询能力；首次调用接受空、合法 tenant-only 或完整双轴基线，
 * 但拒绝 project-only 与非法范围；完成授权记录后拒绝任何范围切换，避免一次事务进入两个隔离域。</p>
 */
@Component
public class TransactionLocalRlsScope {

    /** 一次读取两轴当前值，保证分类来自同一连接和同一数据库快照。 */
    private static final String READ_SCOPE_SQL = """
            SELECT current_setting('app.tenant_id', true),
                   current_setting('app.project_id', true)
            """;

    /** 一条语句同时建立两轴事务局部值，任一设置失败时不会留下半个范围。 */
    private static final String APPLY_SCOPE_SQL = """
            SELECT set_config('app.tenant_id', ?, true),
                   set_config('app.project_id', ?, true)
            """;

    /** 所有读取与设置都经事务感知访问器取得当前事务绑定连接。 */
    private final JdbcTemplate jdbcTemplate;

    /** 与 JDBC 访问器完全相同的数据源身份，用于核对回调连接确实属于当前事务。 */
    private final DataSource dataSource;

    /**
     * 创建事务局部 RLS 范围组件。
     *
     * @param jdbcTemplate 事务感知 JDBC 访问器
     */
    public TransactionLocalRlsScope(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JDBC访问器不能为空");
        this.dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "JDBC数据源不能为空");
    }

    /**
     * 在当前事务连接上建立或确认完整租户/项目范围。
     *
     * <p>本组件在当前事务首次调用时允许用可信二元组覆盖空范围或完整的连接会话基线，并记录该事务
     * 已授权的二元组；后续仅允许相同值。合法 tenant-only 基线可由权威项目二元组补全，project-only
     * 或非法值始终拒绝。无真实 Spring 事务时在借连接前失败，不能让 {@code SET LOCAL} 在
     * auto-commit 中只对自身语句短暂生效。</p>
     *
     * @param tenantId 已从权威事实取得的租户ID
     * @param projectId 已从同一权威事实取得的项目ID
     * @throws IllegalArgumentException 任一可信身份字段为空
     * @throws IllegalStateException 缺少真实事务、连接未绑定或事务内范围残缺/冲突
     */
    public void establish(UUID tenantId, UUID projectId) {
        requireTrustedIdentity(tenantId, projectId);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("事务局部RLS范围必须在启用事务同步的真实Spring事务内建立");
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            Object connectionResource = requireTransactionBoundConnection(connection);
            CurrentScope current = readCurrentScope(connection);
            TransactionLocalRlsScopeCoordinator.EstablishedScope established =
                    TransactionLocalRlsScopeCoordinator.current(connectionResource);
            if (established == null) {
                // 首次可信调用可补全合法tenant基线或覆盖完整session基线，project-only/非法值失败关闭。
                if (!current.valid()) {
                    throw new IllegalStateException("当前事务已存在残缺或非法的RLS范围，拒绝建立隔离域");
                }
                applyScope(connection, tenantId, projectId);
                TransactionLocalRlsScopeCoordinator.bind(connectionResource,
                        TransactionLocalRlsScopeCoordinator.EstablishedScope.tenantAndProject(tenantId, projectId));
                return null;
            }
            if (!established.matchesTenantAndProject(tenantId, projectId)
                    || !current.matches(tenantId, projectId)) {
                throw new IllegalStateException("当前事务已经建立另一RLS范围，禁止切换隔离域");
            }
            return null;
        });
    }

    /** 缺失任一轴都不是可降级状态，且必须在执行任何SQL之前拒绝。 */
    private static void requireTrustedIdentity(UUID tenantId, UUID projectId) {
        if (tenantId == null || projectId == null) {
            throw new IllegalArgumentException("可信RLS范围必须同时提供租户与项目身份");
        }
    }

    /**
     * 核对回调物理连接确实来自本组件数据源的事务资源，并排除auto-commit伪事务。
     *
     * @return 当前事务绑定的连接资源，用作本事务范围标记的稳定身份
     */
    private Object requireTransactionBoundConnection(Connection connection) throws SQLException {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder)
                || !DataSourceUtils.isConnectionTransactional(holder.getConnection(), dataSource)
                || physicalConnection(holder.getConnection()) != physicalConnection(connection)) {
            throw new IllegalStateException("RLS组件取得的连接没有绑定当前Spring事务");
        }
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("RLS范围连接未绑定当前Spring事务");
        }
        return resource;
    }

    /** 解开Spring与租户连接代理，用身份比较确认回调没有借到另一条物理连接。 */
    private static Connection physicalConnection(Connection connection) throws SQLException {
        Connection target = DataSourceUtils.getTargetConnection(connection);
        return target.isWrapperFor(Connection.class) ? target.unwrap(Connection.class) : target;
    }

    /** 在回调收到的同一连接读取两轴，不经第二次数据源路由或借用。 */
    private static CurrentScope readCurrentScope(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(READ_SCOPE_SQL);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("数据库未返回当前RLS范围");
            }
            return CurrentScope.from(result.getString(1), result.getString(2));
        }
    }

    /** 在读取范围的同一连接用单条参数化语句建立事务局部两轴。 */
    private static void applyScope(Connection connection, UUID tenantId, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPLY_SCOPE_SQL)) {
            statement.setString(1, tenantId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    /**
     * 当前连接上的双轴候选；合法tenant-only可由首次调用补全，project-only或非法值视为残缺。
     *
     * @param tenantId 当前租户；空表示未建立，非UUID表示残缺
     * @param projectId 当前项目；空表示未建立，非UUID表示残缺
     * @param valid 两轴均空、仅租户合法或两轴均为合法 UUID
     */
    private record CurrentScope(UUID tenantId, UUID projectId, boolean valid) {

        /** 把数据库文本收敛为不可被无效GUC冒充的范围状态。 */
        private static CurrentScope from(String tenantText, String projectText) {
            boolean tenantEmpty = tenantText == null || tenantText.isEmpty();
            boolean projectEmpty = projectText == null || projectText.isEmpty();
            if (tenantEmpty && projectEmpty) {
                return new CurrentScope(null, null, true);
            }
            if (tenantEmpty) {
                return new CurrentScope(null, null, false);
            }
            if (projectEmpty) {
                try {
                    return new CurrentScope(UUID.fromString(tenantText), null, true);
                } catch (IllegalArgumentException invalidSetting) {
                    return new CurrentScope(null, null, false);
                }
            }
            try {
                return new CurrentScope(UUID.fromString(tenantText), UUID.fromString(projectText), true);
            } catch (IllegalArgumentException invalidSetting) {
                return new CurrentScope(null, null, false);
            }
        }

        /** @return 当前两轴均合法并与本次可信身份完全一致 */
        private boolean matches(UUID expectedTenantId, UUID expectedProjectId) {
            return valid && expectedTenantId.equals(tenantId) && expectedProjectId.equals(projectId);
        }
    }

}
