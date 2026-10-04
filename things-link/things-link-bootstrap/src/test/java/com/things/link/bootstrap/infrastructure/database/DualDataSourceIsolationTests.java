package com.things.link.bootstrap.infrastructure.database;

import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.support.tenant.TenantAwareDataSource;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** G1-C3c 双物理池、单 RLS 外包装与故障域隔离的真实 PostgreSQL 验收。 */
@TestPropertySource(properties = {
        "things-link.datasource.control.maximum-pool-size=6",
        "things-link.datasource.control.minimum-idle=6",
        "things-link.datasource.data.maximum-pool-size=10",
        "things-link.datasource.data.minimum-idle=10"
})
class DualDataSourceIsolationTests extends AbstractIntegrationTest {
    /** 生产唯一应用数据源。 */ @Autowired private DataSource dataSource;
    /** 用于验证业务容器不能注入物理池。 */ @Autowired private ApplicationContext context;
    /** 验证 D-048 全局 JDBC 最后保险，不发起慢 SQL。 */ @Autowired private JdbcTemplate jdbcTemplate;

    /** 防止项目上下文污染共享测试线程。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** 容器只暴露一层 TenantAwareDataSource；两个 HikariPool 不得成为可注入 Bean。 */
    @Test
    void exposesOnlyOneApplicationDataSource() {
        assertThat(context.getBeansOfType(DataSource.class)).containsOnlyKeys("dataSource");
        assertThat(dataSource).isInstanceOf(TenantAwareDataSource.class);
    }

    /** 全局只设 5 秒查询超时，不用 maxRows/fetchSize 静默截断业务结果。 */
    @Test
    void jdbcTemplateUsesTimeoutWithoutGlobalRowTruncation() {
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(5);
        assertThat(jdbcTemplate.getMaxRows()).isEqualTo(-1);
        assertThat(jdbcTemplate.getFetchSize()).isEqualTo(-1);
    }

    /** CONTROL 与 DATA 都在真实借出连接上写入当前 RLS，并在同物理连接复用前清除旧项目。 */
    @Test
    void bothRoutesApplyAndReplaceRlsScope() throws Exception {
        assertSamePhysicalConnectionReplacesScope(DatabaseWorkload.CONTROL, 6);
        assertSamePhysicalConnectionReplacesScope(DatabaseWorkload.DATA, 10);
    }

    /** CONTROL/DATA 都必须实际执行同一项目 RLS，不能只证明会话变量写进去了。 */
    @Test
    void bothRoutesEnforceProjectVisibility() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        TenantContext.set(new TenantScope(tenantId, projectA, UUID.randomUUID()));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO project_http_probe (id, tenant_id, project_id, note)
                     VALUES (?, ?, ?, 'dual-pool')
                     """)) {
            statement.setObject(1, rowId);
            statement.setObject(2, tenantId);
            statement.setObject(3, projectA);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            TenantContext.set(new TenantScope(tenantId, projectA, UUID.randomUUID()));
            assertThat(visibleProbeRows(workload, rowId)).isEqualTo(1);
            TenantContext.set(new TenantScope(tenantId, projectB, UUID.randomUUID()));
            assertThat(visibleProbeRows(workload, rowId)).isZero();
            TenantContext.clear();
            assertThat(visibleProbeRows(workload, rowId)).isZero();
        }
    }

    /** DATA 的十条连接全部占用时，CONTROL 仍从自己的六连接池立即取得连接。 */
    @Test
    void exhaustedDataPoolDoesNotStarveControlPool() throws Exception {
        List<Connection> held = borrow(DatabaseWorkload.DATA, 10);
        try {
            long started = System.nanoTime();
            try (Connection ignored = dataSource.getConnection()) {
                assertThat(elapsedMillis(started)).isLessThan(500L);
            }
        } finally {
            closeAll(held);
        }
    }

    /** CONTROL 的六条连接全部占用时，DATA 仍可独立取得连接。 */
    @Test
    void exhaustedControlPoolDoesNotStarveDataPool() throws Exception {
        List<Connection> held = borrow(DatabaseWorkload.CONTROL, 6);
        try {
            long started = System.nanoTime();
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA);
                 Connection connection = dataSource.getConnection()) {
                assertThat(connection.isValid(1)).isTrue();
                assertThat(elapsedMillis(started)).isLessThan(1000L);
            }
        } finally {
            closeAll(held);
        }
    }

    /**
     * 占住池内其余连接，只释放一个候选连接后重借，确定性证明同一物理会话不会残留上一项目。
     *
     * @param workload 被测路由
     * @param capacity 该路由冻结的物理池容量
     */
    private void assertSamePhysicalConnectionReplacesScope(DatabaseWorkload workload, int capacity) throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();
        TenantContext.set(new TenantScope(tenantA, projectA, UUID.randomUUID()));
        List<Connection> held = borrow(workload, capacity);
        Connection candidate = held.removeLast();
        try {
            ConnectionSnapshot first = snapshot(candidate);
            assertThat(first.tenantId()).isEqualTo(tenantA.toString());
            assertThat(first.projectId()).isEqualTo(projectA.toString());
            candidate.close();

            UUID tenantB = UUID.randomUUID();
            UUID projectB = UUID.randomUUID();
            TenantContext.set(new TenantScope(tenantB, projectB, UUID.randomUUID()));
            try (Connection secondConnection = borrowOne(workload)) {
                ConnectionSnapshot second = snapshot(secondConnection);
                assertThat(second.backendPid()).isEqualTo(first.backendPid());
                assertThat(second.tenantId()).isEqualTo(tenantB.toString());
                assertThat(second.projectId()).isEqualTo(projectB.toString());
            }

            TenantContext.clear();
            try (Connection clearedConnection = borrowOne(workload)) {
                ConnectionSnapshot cleared = snapshot(clearedConnection);
                assertThat(cleared.backendPid()).isEqualTo(first.backendPid());
                assertThat(cleared.tenantId()).isNull();
                assertThat(cleared.projectId()).isNull();
            }
        } finally {
            if (!candidate.isClosed()) candidate.close();
            closeAll(held);
        }
    }

    /** @return 已借连接所指向的物理数据库会话与 RLS 快照 */
    private static ConnectionSnapshot snapshot(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("""
                     SELECT pg_backend_pid(), nullif(current_setting('app.tenant_id', true), ''),
                            nullif(current_setting('app.project_id', true), '')
                     """);
             var result = statement.executeQuery()) {
            result.next();
            return new ConnectionSnapshot(result.getInt(1), result.getString(2), result.getString(3));
        }
    }

    /** @return 从指定路由借出一条连接 */
    private Connection borrowOne(DatabaseWorkload workload) throws Exception {
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
            return dataSource.getConnection();
        }
    }

    /** @return 指定路由与当前项目上下文可见的探针行数 */
    private int visibleProbeRows(DatabaseWorkload workload, UUID rowId) throws Exception {
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload);
             Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM project_http_probe WHERE id = ?")) {
            statement.setObject(1, rowId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** 借满一个物理池；返回的连接由调用方统一关闭。 */
    private List<Connection> borrow(DatabaseWorkload workload, int count) throws Exception {
        List<Connection> connections = new ArrayList<>(count);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
            for (int index = 0; index < count; index++) {
                connections.add(dataSource.getConnection());
            }
        } catch (Exception exception) {
            closeAll(connections);
            throw exception;
        }
        return connections;
    }

    /** 逐条归还测试连接，避免一个 close 失败遮蔽后续连接。 */
    private static void closeAll(List<Connection> connections) throws Exception {
        Exception failure = null;
        for (Connection connection : connections) {
            try {
                connection.close();
            } catch (Exception exception) {
                if (failure == null) failure = exception;
            }
        }
        if (failure != null) throw failure;
    }

    /** @return 从起点到当前的毫秒数 */
    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    /** 数据库会话快照。 */
    private record ConnectionSnapshot(int backendPid, String tenantId, String projectId) { }
}
