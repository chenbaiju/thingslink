package com.things.link.bootstrap.fixture;

import com.things.link.bootstrap.assistant.AbstractAssistantIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实数据库验证造数连接上界、释放职责与调用方事务保持。 */
class WebAppRuntimeFixtureConnectionTests extends AbstractAssistantIntegrationTest {
    private static DriverManagerDataSource ownerSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
    }

    @Test void runtimeSeedAcquiresOneConnectionAndReleasesIt() throws SQLException {
        var source=new CountingSource();
        var fixture=WebAppRuntimeFixture.seed(new JdbcTemplate(source));
        assertThat(source.opens).hasValue(1);
        assertThat(source.last.isClosed()).isTrue();
        assertThat(new JdbcTemplate(ownerSource()).queryForObject(
                "SELECT count(*) FROM sys_project WHERE id=?",Integer.class,fixture.projectId())).isEqualTo(1);
    }

    @Test void dataSeedIncludingNestedRuntimeAcquiresOneConnectionAndReleasesIt() throws SQLException {
        var source=new CountingSource();
        var fixture=WebAppDataRuntimeFixture.seed(new JdbcTemplate(source));
        assertThat(source.opens).hasValue(1);
        assertThat(source.last.isClosed()).isTrue();
        assertThat(new JdbcTemplate(ownerSource()).queryForObject(
                "SELECT count(*) FROM dev_device WHERE project_id=?",Integer.class,fixture.runtime().projectId())).isEqualTo(2);
    }

    @Test void seedKeepsCallerConnectionAndRollbackOwnership() throws SQLException {
        try(var connection=ownerSource().getConnection()) {
            connection.setAutoCommit(false);
            var fixture=WebAppDataRuntimeFixture.seed(new JdbcTemplate(new SingleConnectionDataSource(connection,true)));
            assertThat(connection.isClosed()).isFalse();
            assertThat(connection.getAutoCommit()).isFalse();
            var independent=new JdbcTemplate(ownerSource());
            assertThat(independent.queryForObject("SELECT count(*) FROM sys_tenant WHERE id=?",
                    Integer.class,fixture.runtime().tenantId())).isZero();
            connection.rollback();
            assertThat(independent.queryForObject("SELECT count(*) FROM sys_tenant WHERE id=?",
                    Integer.class,fixture.runtime().tenantId())).isZero();
        }
    }

    /** 只计数真实物理建连，不替代SQL、连接或数据库行为。 */
    private static final class CountingSource extends AbstractDataSource {
        private final DriverManagerDataSource delegate=ownerSource();
        private final AtomicInteger opens=new AtomicInteger();
        private Connection last;
        @Override public Connection getConnection() throws SQLException {
            opens.incrementAndGet();last=delegate.getConnection();return last;
        }
        @Override public Connection getConnection(String username,String password) throws SQLException {
            opens.incrementAndGet();last=delegate.getConnection(username,password);return last;
        }
    }
}
