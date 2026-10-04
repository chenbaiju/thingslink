package com.things.link.support.tenant;

import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 借出连接时把当前租户与当前项目写入会话变量，归还时清除。
 *
 * <p>这是 PostgreSQL 行级安全策略的输入。迁移里的 {@code app_current_tenant()} 与
 * {@code app_current_project()} 读的就是这两个变量。没有这一步，所有策略都会因为
 * 读不到值而拦下全部数据（fail-closed）。
 *
 * <h2>项目才是主要的隔离轴</h2>
 * 架构校准后（ADR 0012），业务表的策略建在 {@code project_id} 上：数据的归属边界
 * 是项目，而协作者可能来自其他租户。{@code app.tenant_id} 仍然写入，供少数真正
 * 按租户隔离的表（账单、配额用量）使用 —— 那类数据不属于任何项目。
 *
 * <h2>为什么必须在归还时清除</h2>
 * 连接池会复用连接。不清除的话，下一个借到这条连接的请求会继承上一个请求的租户 ——
 * 与 {@code TenantContext} 那个 ThreadLocal 完全同构的问题，而且更隐蔽：
 * 应用层的租户上下文是对的，只有数据库会话里残留着旧值。
 *
 * <h2>为什么用 set_config 而不是拼 SET 语句</h2>
 * {@code set_config} 可以走预编译参数，避免把值拼进 SQL 文本。租户 ID 是 UUID
 * 类型、注入风险本就极低，但让「值永远走参数」成为无例外的习惯，比逐处判断
 * 「这个值安不安全」可靠。
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    /** 会话变量名。与迁移中的 {@code app_current_tenant()} 对应，改名要同时改迁移。 */
    private static final String TENANT_SETTING = "app.tenant_id";

    /** 会话变量名。与迁移中的 {@code app_current_project()} 对应。 */
    private static final String PROJECT_SETTING = "app.project_id";

    /** {@code false} 表示设置在整个会话内有效，而不是仅当前事务。 */
    private static final String SET_CONFIG_SQL = "SELECT set_config(?, ?, false)";

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    /**
     * 设置租户并包装连接，使其在关闭时清除设置。
     *
     * @param connection 池中借出的原始连接
     * @return 包装后的连接
     * @throws SQLException 设置会话变量失败
     */
    private Connection prepare(Connection connection) throws SQLException {
        // 读 RlsScopeContext 而非 TenantContext：App 请求的行为主体是 app_user_id，
        // 不经过 TenantScope（它要求 accountId），但同样需要租户/项目去驱动 RLS。
        // 控制台请求经 TenantContext.set 镜像进来，两类请求在此汇合（见 RlsScopeContext）。
        UUID tenantId = RlsScopeContext.current().map(RlsScope::tenantId).orElse(null);
        UUID projectId = RlsScopeContext.current().map(RlsScope::projectId).orElse(null);
        applyScope(connection, tenantId, projectId);
        return wrapForCleanup(connection);
    }

    /**
     * 写入两个会话变量。
     *
     * @param connection 连接
     * @param tenantId   当前租户；为 null 时写入空串
     * @param projectId  当前项目；为 null 时写入空串。
     *                   两个取值函数都会把空串转成 NULL，从而拦下所有受策略保护的行 ——
     *                   「没有上下文」的正确表现是什么都看不到，不是什么都能看到
     */
    private static void applyScope(Connection connection, UUID tenantId, UUID projectId)
            throws SQLException {
        setConfig(connection, TENANT_SETTING, tenantId);
        setConfig(connection, PROJECT_SETTING, projectId);
    }

    /**
     * 写入单个会话变量。
     *
     * @param connection 连接
     * @param name       变量名
     * @param value      取值；null 写入空串
     */
    private static void setConfig(Connection connection, String name, UUID value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_CONFIG_SQL)) {
            statement.setString(1, name);
            statement.setString(2, value == null ? "" : value.toString());
            statement.execute();
        }
    }

    /**
     * 包装连接：拦截 {@code close()}，先清除会话变量再归还到池。
     *
     * <p>用动态代理而不是继承 {@code Connection}：JDBC 的 Connection 接口方法很多，
     * 手工委托每一个既冗长又会在 JDBC 版本升级时漏掉新方法。
     *
     * @param connection 原始连接
     * @return 代理连接
     */
    private static Connection wrapForCleanup(Connection connection) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("close".equals(method.getName())) {
                clearScopeQuietly(connection);
            }
            return method.invoke(connection, args);
        };
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }

    /**
     * 清除会话变量，失败时不抛异常。
     *
     * <p>清除发生在 {@code close()} 期间。此时连接可能已经因为网络中断或超时而不可用，
     * 抛异常会掩盖掉业务代码真正的失败原因。而这条连接既然已经坏了，也不会被复用，
     * 残留的会话变量随之消失。
     */
    private static void clearScopeQuietly(Connection connection) {
        try {
            if (!connection.isClosed()) {
                applyScope(connection, null, null);
            }
        } catch (SQLException ignored) {
            // 见方法注释：此处吞掉异常是刻意的
        }
    }

}
