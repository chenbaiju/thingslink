package com.things.link.support.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR0070决策3：已确权领域在原事务中按完整身份读取单条原Outbox，校验历史交付信封。
 * 不提供全局扫描或按当前路由重建消息；当前PENDING引用的事件在收束前不得被清理。
 */
@Service
public class TransactionalOutboxReader {

    /** 复用业务原连接及其RLS，不能用独立事务或owner连接绕过隔离。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 已配置业务角色和事务路由的数据访问入口 */
    public TransactionalOutboxReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询不可变交付身份；不要求published_at，因为Kafka消费可能早于发布确认落库。
     * @param tenantId 已确权归属租户
     * @param projectId 已确权项目
     * @param eventId 当前业务事实引用的原事件
     * @return 同身份原事件；缺失由业务入口明确拒绝，不能伪造成功
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OutboxEvent> findByIdentity(UUID tenantId, UUID projectId, UUID eventId) {
        Objects.requireNonNull(tenantId, "Outbox租户不能为空");
        Objects.requireNonNull(projectId, "Outbox项目不能为空");
        Objects.requireNonNull(eventId, "Outbox事件不能为空");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("原Outbox身份读取必须加入业务事务");
        }
        // 即使调用方误配RLS范围，完整三元组也不能返回别的事件；查询不提升APP权限。
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, aggregate_type, aggregate_id, event_type,
                       destination_topic, partition_key, payload, trace_id, available_at
                  FROM sys_outbox_event
                 WHERE tenant_id=? AND project_id=? AND id=?
                """, (rs, row) -> {
                    // 仅构造契约拒绝属于事实损坏；JDBC取值/查询异常保持原类型，不误归永久消息错误。
                    UUID id = rs.getObject("id", UUID.class);
                    UUID ownerTenant = rs.getObject("tenant_id", UUID.class);
                    UUID project = rs.getObject("project_id", UUID.class);
                    String aggregateType = rs.getString("aggregate_type");
                    UUID aggregateId = rs.getObject("aggregate_id", UUID.class);
                    String eventType = rs.getString("event_type");
                    String topic = rs.getString("destination_topic");
                    String key = rs.getString("partition_key");
                    String payload = rs.getString("payload");
                    String trace = rs.getString("trace_id");
                    java.time.Instant available = rs.getTimestamp("available_at").toInstant();
                    try { return new OutboxEvent(id, ownerTenant, project, aggregateType, aggregateId,
                            eventType, topic, key, payload, trace, available); }
                    catch (IllegalArgumentException failure) { throw new CorruptedOutboxEventException(failure); }
                }, tenantId, projectId, eventId).stream().findFirst();
    }

    /**
     * ADR0071决策2：在没有eventId的旧配置线协议下，以完整不可变信封确认至少存在一个等价原Outbox。
     * JSON使用PostgreSQL语义比较，忽略对象字段顺序但保留数组顺序；查询仍受调用方项目RLS约束。
     *
     * @param tenantId 配置信封租户
     * @param projectId 配置信封项目
     * @param aggregateType 固定业务聚合类型
     * @param aggregateId 配置目标网关
     * @param eventType 固定配置事件类型
     * @param destinationTopic 固定Kafka主题
     * @param partitionKey 固定网关分区键
     * @param payloadJson 完整配置JSON对象
     * @return 是否存在完整元数据和载荷均等价的原事件
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean existsEquivalent(UUID tenantId, UUID projectId, String aggregateType, UUID aggregateId,
                                    String eventType, String destinationTopic, String partitionKey,
                                    String payloadJson) {
        Objects.requireNonNull(tenantId, "Outbox租户不能为空");
        Objects.requireNonNull(projectId, "Outbox项目不能为空");
        Objects.requireNonNull(aggregateId, "Outbox聚合不能为空");
        requireText(aggregateType, "Outbox聚合类型不能为空");
        requireText(eventType, "Outbox事件类型不能为空");
        requireText(destinationTopic, "Outbox目标主题不能为空");
        requireText(partitionKey, "Outbox分区键不能为空");
        requireText(payloadJson, "Outbox载荷不能为空");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("等价Outbox身份读取必须加入业务事务");
        }
        // 现有(project_id, aggregate_type, aggregate_id, created_at)索引先收敛单网关历史，再比较完整信封。
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM sys_outbox_event
                     WHERE tenant_id=? AND project_id=? AND aggregate_type=? AND aggregate_id=?
                       AND event_type=? AND destination_topic=? AND partition_key=?
                       AND payload::jsonb=?::jsonb
                )
                """, Boolean.class, tenantId, projectId, aggregateType, aggregateId,
                eventType, destinationTopic, partitionKey, payloadJson));
    }

    /** @param value 必填文本 @param message 缺失时的编程错误 */
    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
