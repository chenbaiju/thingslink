package com.things.link.bootstrap.ota;

import static org.assertj.core.api.Assertions.assertThat;

import com.things.link.ota.infrastructure.persistence.JdbcOtaDeviceReportRepository;
import com.things.link.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 普通数据库角色读取实时时钟，与事务起点和应用进程时钟分离。 */
class OtaDeviceReportClockIntegrationTests extends AbstractIntegrationTest {
    /** 在同一事务中等待数据库短暂推进后，仓储必须返回新的数据库时间。 */
    @Test void readsDatabaseWallClockInsideExistingTransaction() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(5);
        transaction.executeWithoutResult(status -> {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            var started = jdbc.queryForObject("SELECT transaction_timestamp()", Timestamp.class).toInstant();
            jdbc.execute("SELECT pg_sleep(0.01)");
            var before = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
            var actual = new JdbcOtaDeviceReportRepository(jdbc).currentTime();
            var after = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
            assertThat(actual).isAfter(started).isBetween(before, after);
            assertThat(actual.getNano() % 1000).isZero();
        });
    }
}
