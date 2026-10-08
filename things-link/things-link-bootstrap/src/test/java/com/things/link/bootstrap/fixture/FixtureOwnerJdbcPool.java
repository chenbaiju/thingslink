package com.things.link.bootstrap.fixture;

import com.zaxxer.hikari.HikariDataSource;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 类级owner夹具连接池，只用于造数和观察；JUnit负责类结束时关闭，不改变应用权限。 */
public final class FixtureOwnerJdbcPool implements AfterAllCallback, AutoCloseable {
    private final Supplier<DataSource> source;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private boolean closed;

    /** 延迟建立连接，保留原测试的真实容器地址和owner身份。 */
    public FixtureOwnerJdbcPool(Supplier<DataSource> source) {
        this.source=source;
    }

    /** 同一类复用有限连接；JdbcTemplate和事务管理器仍负责借还与事务边界。 */
    public synchronized JdbcTemplate jdbc() {
        if(closed) throw new IllegalStateException("Fixture owner pool is closed");
        if(jdbc==null) {
            pool=new HikariDataSource();
            pool.setDataSource(source.get());
            pool.setMaximumPoolSize(4);
            pool.setMinimumIdle(0);
            pool.setAutoCommit(true);
            jdbc=new JdbcTemplate(pool);
        }
        return jdbc;
    }

    /** 也覆盖测试失败路径；仅关闭本类创建的池，不停止共享数据库。 */
    @Override public void afterAll(ExtensionContext context) {
        close();
    }

    /** 幂等关闭，结束后禁止隐式重新建池。 */
    @Override public synchronized void close() {
        closed=true;
        if(pool!=null) pool.close();
    }
}
