package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.project.application.DailyUsageContributor;
import com.things.link.project.application.DailyUsageScope;
import com.things.link.project.application.DailyUsageValue;
import com.things.link.project.application.QuotaMetric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 从 telemetry 自有幂等事实重算消息、字节、命令与时序点 UTC 日用量。
 *
 * <p>上行条数使用消费 inbox，避免消息日志保留策略或重复投递改变计数；原始字节使用同事务消息日志；
 * 下行使用同项目幂等命令事实而不是网络重试 attempt；时序点直接对应成功写入的点事实。</p>
 */
@Component
public class TelemetryDailyUsageContributor implements DailyUsageContributor {

    /** 已绑定 project 事务级 RLS 的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate 已绑定 project 事务级 RLS 的 JDBC 访问器
     */
    public TelemetryDailyUsageContributor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<DailyUsageValue> calculate(DailyUsageScope scope, LocalDate usageDate) {
        Instant from = usageDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = usageDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        UsageRow row = jdbcTemplate.queryForObject("""
                SELECT
                    (SELECT count(*)
                       FROM sys_inbox_message inbox
                      WHERE inbox.project_id = ?
                        AND inbox.received_at >= ? AND inbox.received_at < ?) AS uplink_messages,
                    (SELECT coalesce(sum(log.raw_bytes), 0)
                       FROM ts_device_message_log log
                      WHERE log.project_id = ? AND log.direction = 'UP'
                        AND log.received_at >= ? AND log.received_at < ?) AS uplink_bytes,
                    (SELECT count(*)
                       FROM ts_device_command command_fact
                      WHERE command_fact.project_id = ?
                        AND command_fact.accepted_at >= ? AND command_fact.accepted_at < ?) AS downlink_messages,
                    (SELECT count(*)
                       FROM ts_property_point point
                      WHERE point.project_id = ?
                        AND point.ts >= ? AND point.ts < ?) AS time_series_points
                """, (resultSet, rowNumber) -> new UsageRow(
                        resultSet.getLong("uplink_messages"),
                        resultSet.getLong("uplink_bytes"),
                        resultSet.getLong("downlink_messages"),
                        resultSet.getLong("time_series_points")),
                scope.projectId(), Timestamp.from(from), Timestamp.from(to),
                scope.projectId(), Timestamp.from(from), Timestamp.from(to),
                scope.projectId(), Timestamp.from(from), Timestamp.from(to),
                scope.projectId(), Timestamp.from(from), Timestamp.from(to));
        if (row == null) {
            throw new IllegalStateException("telemetry 日用量查询未返回聚合行");
        }
        return List.of(
                new DailyUsageValue(QuotaMetric.UPLINK_MESSAGE, row.uplinkMessages()),
                new DailyUsageValue(QuotaMetric.UPLINK_BYTES, row.uplinkBytes()),
                new DailyUsageValue(QuotaMetric.DOWNLINK_MESSAGE, row.downlinkMessages()),
                new DailyUsageValue(QuotaMetric.TIME_SERIES_POINT, row.timeSeriesPoints()));
    }

    /**
     * telemetry 四个已交付日指标的一次数据库聚合结果。
     *
     * @param uplinkMessages 平台 inbox 幂等上行条数
     * @param uplinkBytes 解码前原始上行字节数
     * @param downlinkMessages 幂等逻辑下行命令条数
     * @param timeSeriesPoints 已成功落库的时序点数
     */
    private record UsageRow(long uplinkMessages, long uplinkBytes,
                            long downlinkMessages, long timeSeriesPoints) {
    }
}
