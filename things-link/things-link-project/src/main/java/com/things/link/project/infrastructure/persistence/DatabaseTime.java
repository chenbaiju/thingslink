package com.things.link.project.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 以数据库时钟作为窗口判定的唯一时间基准（S14-6b）。
 *
 * <h2>为什么不能各用各的时钟</h2>
 * 资源包与人工调整的 {@code starts_at/ends_at} 由数据库写入（{@code now()}），强制面（设备准入与
 * 日额度决策的 SQL 函数）也用数据库 {@code now()} 判定窗口，而只读投影若用 JVM 的
 * {@code Instant.now()}，两个时钟就可能在**窗口边界**上给出相反结论：集成验收里已复现
 * 「刚支付成功的包在 Java 投影里还没开始（{@code starts_at} 比 JVM 时间晚几毫秒）」，于是
 * 控制台显示 100 台而强制面已按 102 台放行——正是「显示与强制必须同源」要禁止的分叉。
 *
 * <p>{@code now()} 在 PostgreSQL 里是**事务开始时刻**，因此同一次读取与同事务内的加数函数
 * （{@code tenant_resource_package_addon(..., now())}）用的是同一个值，判定天然一致。
 *
 * <p>本类只读一次时间，不做缓存：缓存会把「同一事务同源」变成「同一个连接的历史值」，
 * 反而重新引入第二个时间基准。
 */
final class DatabaseTime {

    /** 工具类不可实例化。 */
    private DatabaseTime() {
    }

    /**
     * 读取当前数据库时刻（事务开始时刻）。
     *
     * @param jdbcTemplate 当前请求的 JDBC 访问器
     * @return 数据库 {@code now()}
     */
    static Instant now(JdbcTemplate jdbcTemplate) {
        Timestamp value = jdbcTemplate.queryForObject("SELECT now()", Timestamp.class);
        return value == null ? Instant.now() : value.toInstant();
    }
}
