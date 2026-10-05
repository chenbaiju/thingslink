package com.things.link.support.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL 事务 Outbox 实现。
 *
 * <p>业务写入保持在调用方事务中；领取、确认和失败登记刻意使用独立短事务。这样 Kafka 网络等待
 * 不会占住 PostgreSQL 行锁，而租约已提交后即使发布进程崩溃也会自然过期并被后续轮询接管。</p>
 */
@Repository
public class JdbcTransactionalOutboxRepository implements TransactionalOutboxRepository {

    /** 数据库访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 JDBC Outbox 仓储。
     *
     * @param jdbcTemplate 数据库访问入口
     */
    public JdbcTransactionalOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxEvent event) {
        jdbcTemplate.update("""
                INSERT INTO sys_outbox_event
                    (id, tenant_id, project_id, aggregate_type, aggregate_id, event_type, destination_topic,
                     partition_key, payload, trace_id, available_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.tenantId(), event.projectId(), event.aggregateType(), event.aggregateId(),
                event.eventType(), event.destinationTopic(), event.partitionKey(), event.payload(), event.traceId(),
                Timestamp.from(event.availableAt()));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxClaim claimReady(int maximumEvents, Duration leaseDuration) {
        validateClaimArguments(maximumEvents, leaseDuration);
        UUID leaseToken = UUID.randomUUID();
        List<OutboxEvent> events = jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, aggregate_type, aggregate_id, event_type, destination_topic, partition_key,
                       payload, trace_id, available_at
                  FROM claim_sys_outbox_events(?, ?, ?)
                """, this::mapEvent, leaseToken, maximumEvents, Math.toIntExact(leaseDuration.toSeconds()));
        return new OutboxClaim(leaseToken, events);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Duration oldestUnpublishedAge(Instant now) {
        Long seconds = jdbcTemplate.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (?::timestamptz - min(created_at)))::bigint, 0)
                  FROM sys_outbox_event
                 WHERE published_at IS NULL
                """, Long.class, Timestamp.from(now));
        return Duration.ofSeconds(Math.max(0L, seconds == null ? 0L : seconds));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(UUID eventId, UUID leaseToken) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT mark_sys_outbox_event_published(?, ?)", Boolean.class, eventId, leaseToken));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(UUID eventId, UUID leaseToken, Instant nextAvailableAt, String failureMessage) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT mark_sys_outbox_event_retry(?, ?, ?, ?)", Boolean.class,
                eventId, leaseToken, Timestamp.from(nextAvailableAt), failureMessage));
    }

    /**
     * 把受控领取函数返回的行还原为可直接交给 KafkaTemplate 的事件。
     *
     * @param resultSet 数据库结果集
     * @param rowNum 当前行序号
     * @return Outbox 事件
     * @throws SQLException 驱动读取失败时由 JDBC 向上报告
     */
    private OutboxEvent mapEvent(ResultSet resultSet, int rowNum) throws SQLException {
        return new OutboxEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("destination_topic"),
                resultSet.getString("partition_key"),
                resultSet.getString("payload"),
                resultSet.getString("trace_id"),
                resultSet.getTimestamp("available_at").toInstant());
    }

    /**
     * 尽早校验参数；迁移函数保留同一限制，防止绕过 Java 调用时扩张跨项目领取范围。
     *
     * @param maximumEvents 单轮最大数
     * @param leaseDuration 租约时长
     */
    private static void validateClaimArguments(int maximumEvents, Duration leaseDuration) {
        if (maximumEvents < 1 || maximumEvents > 8) {
            throw new IllegalArgumentException("Outbox 单轮领取数量必须在 1 到 8 之间");
        }
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0 || leaseDuration.toSeconds() < 1) {
            throw new IllegalArgumentException("Outbox 租约必须在 1 秒到 5 分钟之间");
        }
    }
}
