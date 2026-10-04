package com.things.link.support.tenant;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** G1-C3d nightly #15 暴露的物理连接生命周期与补建窗口回归。 */
class TenantDataSourceConfigurationTests {

    /** DATA 默认值必须在外部关闭前主动轮换，并给连接补建留下超过一秒的有界窗口。 */
    @Test
    void appliesDataPoolLifecycleAndRecoveryDefaults() {
        try (HikariDataSource pool = TenantDataSourceConfiguration.createPool(
                properties(), new MockEnvironment(), null, "data", "thingslink-data", 10, 2000L)) {
            assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
            assertThat(pool.getMinimumIdle()).isEqualTo(10);
            assertThat(pool.getConnectionTimeout()).isEqualTo(2000L);
            assertThat(pool.getValidationTimeout()).isEqualTo(250L);
            assertThat(pool.getMaxLifetime()).isEqualTo(600_000L);
            assertThat(pool.getKeepaliveTime()).isEqualTo(120_000L);
        }
    }

    /** 部署环境仍可下调生命周期与获取边界，但不能依赖改 Java 代码才能适配数据库代理。 */
    @Test
    void appliesLifecycleOverridesPerPhysicalPool() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("things-link.datasource.data.connection-timeout-ms", "2500")
                .withProperty("things-link.datasource.data.max-lifetime-ms", "480000")
                .withProperty("things-link.datasource.data.keepalive-time-ms", "60000");
        try (HikariDataSource pool = TenantDataSourceConfiguration.createPool(
                properties(), environment, null, "data", "thingslink-data", 10, 2000L)) {
            assertThat(pool.getConnectionTimeout()).isEqualTo(2500L);
            assertThat(pool.getMaxLifetime()).isEqualTo(480_000L);
            assertThat(pool.getKeepaliveTime()).isEqualTo(60_000L);
        }
    }

    /** L1 的等待窗口只覆盖短暂占满；仍保持六连接上限，默认500ms继续快速拒绝。 */
    @Test
    void l1ControlPoolWaitsForBriefSaturationWithoutIncreasingCapacity() throws Exception {
        assertControlPoolSaturation(new MockEnvironment(), false);
        assertControlPoolSaturation(new MockEnvironment()
                .withProperty("things-link.datasource.control.connection-timeout-ms", "2000"), true);
    }

    /** 使用真实 Hikari 借还连接与等待队列，JDBC替身仅排除外部数据库的时序噪声。 */
    private void assertControlPoolSaturation(MockEnvironment environment, boolean waitForRecovery) throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenAnswer(invocation -> {
            Connection connection = mock(Connection.class);
            when(connection.isValid(anyInt())).thenReturn(true);
            when(connection.getAutoCommit()).thenReturn(true);
            return connection;
        });
        when(source.getConnection(anyString(), anyString()))
                .thenAnswer(invocation -> source.getConnection());
        try (HikariDataSource pool = TenantDataSourceConfiguration.createPool(
                properties(), environment, null, "control", "l1-control-test", 6, 500L);
             var executor = Executors.newSingleThreadExecutor()) {
            pool.setDataSource(source);
            List<Connection> held = new ArrayList<>();
            try {
                for (int index = 0; index < 6; index++) held.add(pool.getConnection());
                assertThat(pool.getMaximumPoolSize()).isEqualTo(6);
                var started = new CountDownLatch(1);
                var waiter = executor.submit(() -> {
                    started.countDown();
                    return pool.getConnection();
                });
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                if (waitForRecovery) {
                    assertThatThrownBy(() ->
                            waiter.get(750, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    held.removeLast().close();
                    try (Connection recovered = waiter.get(1, TimeUnit.SECONDS)) {
                        assertThat(recovered.isClosed()).isFalse();
                    }
                } else {
                    assertThatThrownBy(() ->
                            waiter.get(2, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class)
                            .hasCauseInstanceOf(SQLTransientConnectionException.class);
                }
            } finally {
                for (Connection connection : held) connection.close();
            }
        }
    }

    /** @return 不发起真实连接、仅供校验 Hikari 配置的 JDBC 属性 */
    private static DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl("jdbc:postgresql://localhost:5432/not-used");
        properties.setUsername("not-used");
        properties.setPassword("not-used");
        properties.setDriverClassName("org.postgresql.Driver");
        return properties;
    }
}
