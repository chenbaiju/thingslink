package com.things.link.bootstrap.telemetry.history;

import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5-2 消息日志保留策略的真实 TimescaleDB 验收。
 *
 * <p>开发路线图 S5 明确把“按时间分区并设保留期”列为容量硬门禁。这里只检查数据库真实行为，
 * 不用内存仓储或模拟调度器，避免策略虽然存在于迁移文本、实际却不能删除 chunk 的假阳性。
 */
@DisplayName("S5-2 消息日志保留策略")
class MessageLogRetentionTests extends AbstractIntegrationTest {
    /** 消息日志和幂等登记共同采用的保留窗口，来源于开发路线图 S3.5-2 与 S5。 */
    private static final int RETENTION_DAYS = 90;

    /**
     * 真实 retention 配置必须是 90 天，且相同的 drop_chunks 边界只删除过期 chunk。
     *
     * <p>测试通过迁移角色模拟 TimescaleDB 后台作业，因为应用角色受项目 RLS 约束且无权执行跨项目保留清理。
     * 当前记录必须保留，用来防止边界或表名写错导致全表删除。
     *
     * @throws Exception 数据库连接或 SQL 执行失败
     */
    @Test
    void retentionPolicyDropsExpiredChunkAndKeepsCurrentChunk() throws Exception {
        UUID expiredMessageId = UUID.randomUUID();
        UUID currentMessageId = UUID.randomUUID();
        Instant expiredAt = Instant.now().minus(RETENTION_DAYS + 10L, ChronoUnit.DAYS);
        Instant currentAt = Instant.now();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertRetentionConfiguration(connection);
            insertMessageLog(connection, expiredMessageId, expiredAt);
            insertMessageLog(connection, currentMessageId, currentAt);

            try (var statement = connection.createStatement()) {
                // 直接调用策略底层使用的 TimescaleDB API，验证真实 chunk 边界而不是只数一条 job 元数据。
                statement.execute("SELECT drop_chunks('ts_device_message_log', "
                        + "older_than => now() - INTERVAL '90 days')");
            }

            assertThat(countMessage(connection, expiredMessageId)).isZero();
            assertThat(countMessage(connection, currentMessageId)).isEqualTo(1);
        } finally {
            // 当前 chunk 不属于保留清理范围，显式移除测试行，避免共享 Testcontainers JVM 污染其他验收。
            deleteMessage(currentMessageId);
        }
    }

    /** 验证注册作业确实绑定目标 hypertable，并使用冻结的 90 天窗口。 */
    private static void assertRetentionConfiguration(java.sql.Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*)
                  FROM timescaledb_information.jobs
                 WHERE hypertable_schema = 'public'
                   AND hypertable_name = 'ts_device_message_log'
                   AND proc_name = 'policy_retention'
                   AND (config ->> 'drop_after')::interval = INTERVAL '90 days'
                """)) {
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }
        }
    }

    /** 写入指定时间的最小合法消息日志，使 TimescaleDB 为它建立真实时间 chunk。 */
    private static void insertMessageLog(java.sql.Connection connection, UUID messageId, Instant occurredAt)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ts_device_message_log
                    (id, project_id, device_id, message_id, tenant_id, protocol, direction,
                     raw_bytes, ts, received_at, trace_id)
                VALUES (?, ?, ?, ?, ?, 'MQTT', 'UP', 1, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, UUID.randomUUID());
            statement.setObject(4, messageId);
            statement.setObject(5, UUID.randomUUID());
            statement.setTimestamp(6, Timestamp.from(occurredAt));
            statement.setTimestamp(7, Timestamp.from(occurredAt));
            statement.setString(8, "s5-retention-" + messageId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /** 按业务消息 ID 统计保留后的测试记录。 */
    private static int countMessage(java.sql.Connection connection, UUID messageId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM ts_device_message_log WHERE message_id = ?")) {
            statement.setObject(1, messageId);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    /** 清理仍处于保留窗口内的测试记录；目标由单个随机 messageId 精确限定。 */
    private static void deleteMessage(UUID messageId) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM ts_device_message_log WHERE message_id = ?")) {
            statement.setObject(1, messageId);
            statement.executeUpdate();
        }
    }
}
