package com.things.link.bootstrap.fixture;

import com.things.link.bootstrap.assistant.AbstractAssistantIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgConnection;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG证明owner SQL复用、事务回滚与JUnit关闭职责，不替代业务或RLS断言。 */
class FixtureOwnerJdbcPoolTests extends AbstractAssistantIntegrationTest {
    private static FixtureOwnerJdbcPool pool() {
        return new FixtureOwnerJdbcPool(()->new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }

    @Test void repeatedSqlReusesPhysicalConnectionAndOriginalOwnerIdentity() {
        try(var fixture=pool()) {
            var jdbc=fixture.jdbc();var pids=new HashSet<Integer>();
            for(int i=0;i<250;i++) pids.add(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
            assertThat(pids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT current_user",String.class)).isEqualTo(POSTGRES.getUsername());
            assertThat(fixture.jdbc()).isSameAs(jdbc);
            assertThat(((HikariDataSource)jdbc.getDataSource()).getMaximumPoolSize()).isEqualTo(4);
            assertThat(((HikariDataSource)jdbc.getDataSource()).getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @Test void callerTransactionRollsBackAndReturnedConnectionResetsAutoCommit() throws SQLException {
        try(var fixture=pool()) {
            var jdbc=fixture.jdbc();
            jdbc.execute("CREATE TEMP TABLE owner_pool_transaction(value integer)");
            var transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            assertThatThrownBy(()->transaction.executeWithoutResult(status->{
                jdbc.update("INSERT INTO owner_pool_transaction VALUES (1)");
                throw new IllegalStateException("synthetic rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("synthetic rollback");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_pool_transaction",Integer.class)).isZero();
            try(var connection=jdbc.getDataSource().getConnection()) {
                assertThat(connection.getAutoCommit()).isTrue();
            }
        }
    }

    @Test void junitCallbackClosesPhysicalConnectionAndCannotReopenPool() throws SQLException {
        var opens=new AtomicInteger();
        var fixture=new FixtureOwnerJdbcPool(()->{
            opens.incrementAndGet();
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        });
        assertThat(opens).hasValue(0);
        try {
            var source=(HikariDataSource)fixture.jdbc().getDataSource();
            PgConnection physical;
            try(var connection=source.getConnection()) {physical=connection.unwrap(PgConnection.class);}
            assertThat(physical.isClosed()).isFalse();
            fixture.afterAll(null);
            assertThat(source.isClosed()).isTrue();
            assertThat(physical.isClosed()).isTrue();
            assertThatThrownBy(fixture::jdbc).isInstanceOf(IllegalStateException.class);
            assertThat(opens).hasValue(1);
        } finally {fixture.close();}
    }
}
