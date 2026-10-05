package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.telemetry.domain.PropertyAggregateBackfillRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.core.env.Environment;

import java.sql.Timestamp;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** JDBC 实现的属性连续聚合迟到窗口回补仓储。 */
@Repository
public class JdbcPropertyAggregateBackfillRepository implements PropertyAggregateBackfillRepository {

    /** 事务感知 JDBC 入口；刷新调用在扫描器非事务边界逐项提交。 */
    private final JdbcTemplate jdbcTemplate;
    /**
     * 连续聚合 owner 专用入口；只执行 Timescale 指定窗口刷新，不注册为 Bean，避免业务仓储取得迁移权限。
     */
    private final JdbcTemplate maintenanceJdbcTemplate;

    /**
     * @param jdbcTemplate 事务感知 JDBC 入口
     * @param environment 复用 Flyway owner 直连配置；Timescale 要求刷新者必须拥有连续聚合
     */
    public JdbcPropertyAggregateBackfillRepository(JdbcTemplate jdbcTemplate, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        String url = environment.getRequiredProperty("spring.flyway.url");
        String username = environment.getRequiredProperty("spring.flyway.user");
        String password = environment.getRequiredProperty("spring.flyway.password");
        this.maintenanceJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        // 历史窗口刷新可能触发压缩 chunk 解压，不能继承业务 JDBC 的五秒保护线；仍以五分钟硬上限收口。
        this.maintenanceJdbcTemplate.setQueryTimeout(300);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean request(UUID tenantId, UUID projectId, Instant occurredAt, Instant receivedAt) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT public.request_property_aggregate_backfill(?, ?, ?, ?)", Boolean.class,
                tenantId, projectId, Timestamp.from(occurredAt), Timestamp.from(receivedAt)));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<BackfillWindow> claimDue(int maximumRows) {
        return jdbcTemplate.query("""
                SELECT tenant_id, project_id, window_start, window_end, revision
                  FROM public.claim_due_property_aggregate_backfills(?)
                """, (resultSet, rowNumber) -> new BackfillWindow(
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getTimestamp("window_start").toInstant(),
                resultSet.getTimestamp("window_end").toInstant(),
                resultSet.getLong("revision")), maximumRows);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void refresh(BackfillWindow window) {
        if (!window.windowStart().isBefore(window.windowEnd())
                || window.windowEnd().isAfter(window.windowStart().plusSeconds(86400))) {
            throw new IllegalArgumentException("属性聚合回补窗口必须在一个 UTC 日内");
        }
        // 内部物化对象名由迁移的 owner-only 固定目录持有，生产 Java 不跨越安全视图硬编码内部名称。
        List<String> aggregates = maintenanceJdbcTemplate.queryForList(
                "SELECT aggregate_name::text FROM public.property_aggregate_backfill_targets() ORDER BY refresh_order",
                String.class);
        if (aggregates.size() != 3) {
            throw new IllegalStateException("属性聚合回补目标目录必须恰好包含三个层级");
        }
        // TimescaleDB 明确禁止从 PL/pgSQL 过程嵌套调用 refresh_continuous_aggregate，必须逐层直接调用。
        for (String aggregate : aggregates) {
            refreshLayer(aggregate, window);
        }
    }

    /**
     * ADR0082：每层独占维护连接，数据库五分钟截止与执行期源窗口校验共同保护保留边界。
     *
     * @param aggregate owner-only固定目录中的聚合，不接受请求参数表名
     * @param window 已检查宽度的窗口；年龄仍必须在CALL执行时按数据库实际时钟检查
     */
    private void refreshLayer(String aggregate, BackfillWindow window) {
        maintenanceJdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            // 同连接SET确保客户端取消线程停顿时数据库仍有截止；直连关闭即释放该会话配置。
            try (Statement settings = connection.createStatement()) {
                settings.setQueryTimeout(300);
                settings.execute("SET statement_timeout = '300000'");
            }
            // 参数函数只校验并返回起点，不嵌套CALL，保留Timescale刷新所需的非原子顶层过程。
            try (PreparedStatement refresh = connection.prepareStatement("""
                    CALL public.refresh_continuous_aggregate(?::regclass,
                        public.property_aggregate_safe_refresh_start(?::timestamptz, ?::timestamptz),
                        ?::timestamptz)
                    """)) {
                refresh.setQueryTimeout(300);
                refresh.setString(1, aggregate);
                refresh.setTimestamp(2, Timestamp.from(window.windowStart()));
                refresh.setTimestamp(3, Timestamp.from(window.windowEnd()));
                refresh.setTimestamp(4, Timestamp.from(window.windowEnd()));
                refresh.execute();
            }
            return null;
        });
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long mismatchCount(BackfillWindow window) {
        Long mismatches = jdbcTemplate.queryForObject(
                "SELECT public.property_aggregate_window_mismatch_count(?, ?, ?)", Long.class,
                window.projectId(), Timestamp.from(window.windowStart()), Timestamp.from(window.windowEnd()));
        if (mismatches == null) {
            throw new IllegalStateException("属性聚合窗口对账未返回结果");
        }
        return mismatches;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean complete(BackfillWindow window) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT public.complete_property_aggregate_backfill(?, ?, ?)", Boolean.class,
                window.projectId(), Timestamp.from(window.windowStart()), window.revision()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void fail(BackfillWindow window, String failureSummary) {
        jdbcTemplate.queryForObject("SELECT public.fail_property_aggregate_backfill(?, ?, ?)", Object.class,
                window.projectId(), Timestamp.from(window.windowStart()), failureSummary);
    }
}
