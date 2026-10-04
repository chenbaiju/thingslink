package com.things.link.support.tenant;

import com.things.link.support.observability.DatabaseAvailabilityMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** G1-C4b-F2 主动数据库可用性探针的真实 JDBC 调用边界测试。 */
class DatabaseAvailabilityProbeTests {

    /** 两个物理池必须独立探测；连接失败记零，成功 SELECT 1 记一并设置语句超时。 */
    @Test
    void probesBothPhysicalPoolsWithoutBusinessQueries() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DatabaseAvailabilityMetrics metrics = new DatabaseAvailabilityMetrics(registry);
        JdbcFixture control = successfulDataSource();
        DataSource data = mock(DataSource.class);
        when(data.getConnection()).thenThrow(new SQLException("database unavailable"));

        new DatabaseAvailabilityProbe(control.dataSource(), data, metrics).probeAll();

        assertThat(registry.get("thingslink.database.available")
                .tag("pool", "control").gauge().value()).isEqualTo(1D);
        assertThat(registry.get("thingslink.database.available")
                .tag("pool", "data").gauge().value()).isZero();
        verify(control.statement()).setQueryTimeout(1);
        verify(data).getConnection();
    }

    /** 数据库恢复后的下一轮 SELECT 1 必须把零恢复为一，使 Prometheus 自动 resolved。 */
    @Test
    void recordsRecoveryOnNextSuccessfulProbe() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DatabaseAvailabilityMetrics metrics = new DatabaseAvailabilityMetrics(registry);
        DataSource recovering = mock(DataSource.class);
        JdbcFixture recovered = successfulDataSource();
        when(recovering.getConnection())
                .thenThrow(new SQLException("database unavailable"))
                .thenReturn(recovered.connection());
        DatabaseAvailabilityProbe probe = new DatabaseAvailabilityProbe(
                recovering, successfulDataSource().dataSource(), metrics);

        probe.probeAll();
        assertThat(registry.get("thingslink.database.available")
                .tag("pool", "control").gauge().value()).isZero();
        probe.probeAll();
        assertThat(registry.get("thingslink.database.available")
                .tag("pool", "control").gauge().value()).isEqualTo(1D);
    }

    /** @return 完成一次 SELECT 1 的 DataSource 替身 */
    private static JdbcFixture successfulDataSource() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
        return new JdbcFixture(dataSource, connection, statement);
    }

    /** @param dataSource 数据源替身 @param connection 连接替身 @param statement SELECT 1 语句替身 */
    private record JdbcFixture(DataSource dataSource, Connection connection, PreparedStatement statement) {
    }
}
