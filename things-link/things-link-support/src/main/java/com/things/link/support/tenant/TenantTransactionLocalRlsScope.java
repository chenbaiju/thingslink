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
 * 在当前 Spring 事务绑定的物理连接上建立只含租户轴的事务局部 RLS 范围。
 *
 * <p>S12-2a1e / D-098：租户级后台任务没有项目身份，不能伪造项目轴，也不能依赖连接借出后才写入的
 * ThreadLocal。本组件只接受调用方已经从受限领取或权威持久事实取得的租户 ID，并在同一事务连接上
 * 执行 {@code set_config(..., true)}；它不解析外部身份、不读取线程上下文，也不授予跨租户能力。</p>
 *
 * <p>首次调用可覆盖两轴都空、合法 tenant-only 或两轴都合法的 session 基线，并原子写入可信租户、
 * 清空项目轴；project-only 或非法基线失败关闭。事务内完成授权登记后只允许同租户 tenant-only
 * 重入，避免与双轴组件交错切域。</p>
 */
@Component
public class TenantTransactionLocalRlsScope {

    /** 同时读取租户与项目轴，不能只看 tenant 后遗漏已有项目范围。 */
    private static final String READ_SCOPE_SQL = """
            SELECT current_setting('app.tenant_id', true),
                   current_setting('app.project_id', true)
            """;

    /** 单条语句建立租户轴并清空项目轴，避免完整 session 基线留下旧项目授权。 */
    private static final String APPLY_SCOPE_SQL = """
            SELECT set_config('app.tenant_id', ?, true),
                   set_config('app.project_id', '', true)
            """;

    /** 所有范围读取与设置都经事务感知访问器取得当前事务绑定连接。 */
    private final JdbcTemplate jdbcTemplate;

    /** 与 JDBC 访问器完全相同的数据源身份，用于排除借错连接。 */
    private final DataSource dataSource;

    /**
     * 创建 tenant-only 事务范围组件。
     *
     * @param jdbcTemplate 事务感知 JDBC 访问器
     */
    public TenantTransactionLocalRlsScope(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JDBC访问器不能为空");
        this.dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "JDBC数据源不能为空");
    }

    /**
     * 在当前事务连接上建立或确认唯一租户范围。
     *
     * <p>无真实事务或事务同步时在借连接前失败。首次调用接受两轴都空、合法 tenant-only 或完整双轴
     * session 基线，并以可信 tenantId 原子替换为 tenant-only；project-only/非法基线、同事务异租户
     * 及与双轴模式交错均失败。</p>
     *
     * @param tenantId 已从权威事实取得的租户 ID
     * @throws IllegalArgumentException 可信租户身份为空
     * @throws IllegalStateException 缺少真实事务、连接未绑定、首次基线为project-only/非法，或登记后范围/模式冲突
     */
    public void establish(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("可信租户RLS范围不得为空");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("事务局部租户RLS范围必须在启用事务同步的真实Spring事务内建立");
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            Object connectionResource = requireTransactionBoundConnection(connection);
            CurrentScope current = readCurrentScope(connection);
            TransactionLocalRlsScopeCoordinator.EstablishedScope established =
                    TransactionLocalRlsScopeCoordinator.current(connectionResource);
            if (established == null) {
                // 空、合法tenant-only或完整双轴session基线可覆盖；project-only/非法基线不能静默修复。
                if (!current.acceptableInitialBaseline()) {
                    throw new IllegalStateException("当前事务已存在残缺或非法RLS范围，拒绝建立租户隔离域");
                }
                applyScope(connection, tenantId);
                TransactionLocalRlsScopeCoordinator.bind(connectionResource,
                        TransactionLocalRlsScopeCoordinator.EstablishedScope.tenantOnly(tenantId));
                return null;
            }
            if (!established.matchesTenantOnly(tenantId) || !current.matchesTenantOnly(tenantId)) {
                throw new IllegalStateException("当前事务已经建立另一租户RLS范围，禁止切换隔离域");
            }
            return null;
        });
    }

    /**
     * 核对回调物理连接确实来自本组件数据源的事务资源，并排除 auto-commit 伪事务。
     *
     * @return 当前事务绑定的连接资源，用作本事务范围标记的稳定身份
     */
    private Object requireTransactionBoundConnection(Connection connection) throws SQLException {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder)
                || !DataSourceUtils.isConnectionTransactional(holder.getConnection(), dataSource)
                || physicalConnection(holder.getConnection()) != physicalConnection(connection)) {
            throw new IllegalStateException("租户RLS组件取得的连接没有绑定当前Spring事务");
        }
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("租户RLS范围连接未绑定当前Spring事务");
        }
        return resource;
    }

    /** 解开 Spring 与租户连接代理，用身份比较确认回调没有借到另一条物理连接。 */
    private static Connection physicalConnection(Connection connection) throws SQLException {
        Connection target = DataSourceUtils.getTargetConnection(connection);
        return target.isWrapperFor(Connection.class) ? target.unwrap(Connection.class) : target;
    }

    /** 在回调收到的同一连接读取两轴，不经第二次路由或借用。 */
    private static CurrentScope readCurrentScope(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(READ_SCOPE_SQL);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("数据库未返回当前租户RLS范围");
            }
            return CurrentScope.from(result.getString(1), result.getString(2));
        }
    }

    /** 在读取范围的同一连接建立事务局部租户轴。 */
    private static void applyScope(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPLY_SCOPE_SQL)) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
    }

    /** 当前范围形态；首次接受 EMPTY/TENANT_ONLY/COMPLETE，建立后只接受同租户 TENANT_ONLY。 */
    private enum ScopeState {
        /** 两轴都为空。 */ EMPTY,
        /** 两轴都是合法 UUID。 */ COMPLETE,
        /** 只有合法租户轴；既可作首次session基线，也用于建立后的重入检查。 */ TENANT_ONLY,
        /** 只有项目轴，属于残缺范围。 */ PROJECT_ONLY,
        /** 任一非空轴无法解析为 UUID。 */ INVALID
    }

    /**
     * 当前两轴范围分类。
     *
     * @param tenantId 可解析的租户 ID
     * @param projectId 可解析的项目 ID
     * @param state 当前范围形态
     */
    private record CurrentScope(UUID tenantId, UUID projectId, ScopeState state) {

        /** 同时解析两轴，保留空、完整、tenant-only、project-only 与非法的精确区别。 */
        private static CurrentScope from(String tenantText, String projectText) {
            boolean tenantEmpty = tenantText == null || tenantText.isEmpty();
            boolean projectEmpty = projectText == null || projectText.isEmpty();
            if (tenantEmpty && projectEmpty) {
                return new CurrentScope(null, null, ScopeState.EMPTY);
            }
            UUID tenantId = parse(tenantText);
            UUID projectId = parse(projectText);
            if (!tenantEmpty && projectEmpty) {
                return tenantId == null
                        ? new CurrentScope(null, null, ScopeState.INVALID)
                        : new CurrentScope(tenantId, null, ScopeState.TENANT_ONLY);
            }
            if (tenantEmpty) {
                return projectId == null
                        ? new CurrentScope(null, null, ScopeState.INVALID)
                        : new CurrentScope(null, projectId, ScopeState.PROJECT_ONLY);
            }
            return tenantId == null || projectId == null
                    ? new CurrentScope(null, null, ScopeState.INVALID)
                    : new CurrentScope(tenantId, projectId, ScopeState.COMPLETE);
        }

        /** @return 空、合法tenant-only或完整双轴基线，允许首次可信调用原子覆盖 */
        private boolean acceptableInitialBaseline() {
            return state == ScopeState.EMPTY || state == ScopeState.TENANT_ONLY || state == ScopeState.COMPLETE;
        }

        /** @return 建立后当前状态是否保持同租户且项目轴为空 */
        private boolean matchesTenantOnly(UUID expectedTenantId) {
            return state == ScopeState.TENANT_ONLY && expectedTenantId.equals(tenantId);
        }

        /** 空文本不参与 UUID 解析，非空非法值统一返回 null 供状态分类。 */
        private static UUID parse(String text) {
            if (text == null || text.isEmpty()) {
                return null;
            }
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException invalidSetting) {
                return null;
            }
        }
    }

}
