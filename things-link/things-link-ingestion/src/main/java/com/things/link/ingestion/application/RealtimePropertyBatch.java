package com.things.link.ingestion.application;

import tools.jackson.databind.JsonNode;
import com.things.link.shared.message.ReportedRevision;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 事实事务提交后可供本机 WebSocket 扇出的属性增量。
 *
 * <p>该类型不是 Redis 消息实现，也不是完整影子快照；S4-4 的 Redis 监听器只需把反序列化后的
 * 同形数据交给 {@link RealtimeSubscriptionRegistry#fanout(RealtimePropertyBatch)}。断线与 Redis
 * 故障允许丢失本增量，客户端必须走 S4-2 批量当前值 REST 补拉。</p>
 *
 * @param projectId 项目隔离轴
 * @param deviceId 变更设备
 * @param occurredAt 设备采集时间；不作为接受顺序
 * @param shadowVersion 影子代次，仅供诊断与前端展示
 * @param thingModelVersionId 本批当前值的不可变物模型版本 ID
 * @param modelVersion 可读语义版本
 * @param propertyDataTypes 属性键到写入时数据类型的冻结映射
 * @param properties 已被 PostgreSQL 属性 CAS 接受的属性增量
 * @param reportedRevisions 逐属性实际接受序号；旧消息为空
 */
public record RealtimePropertyBatch(UUID projectId, UUID deviceId, Instant occurredAt, int shadowVersion,
                                    UUID thingModelVersionId, String modelVersion,
                                    Map<String, String> propertyDataTypes,
                                    Map<String, JsonNode> properties, Map<String, String> reportedRevisions) {

    /** 兼容旧内部调用，空序号不取得顺序资格。 */
    public RealtimePropertyBatch(UUID projectId, UUID deviceId, Instant occurredAt, int shadowVersion,
                                 UUID thingModelVersionId, String modelVersion,
                                 Map<String, String> propertyDataTypes, Map<String, JsonNode> properties) {
        this(projectId, deviceId, occurredAt, shadowVersion, thingModelVersionId, modelVersion,
                propertyDataTypes, properties, Map.of());
    }

    /** 冻结扇出边界，禁止把空更新或可变 Map 排进每会话队列。 */
    public RealtimePropertyBatch {
        if (projectId == null || deviceId == null || occurredAt == null || thingModelVersionId == null
                || modelVersion == null || propertyDataTypes == null || properties == null || properties.isEmpty()
                || !propertyDataTypes.keySet().equals(properties.keySet())) {
            throw new IllegalArgumentException("实时属性增量字段不能为空");
        }
        reportedRevisions = reportedRevisions == null ? Map.of() : Map.copyOf(reportedRevisions);
        if (!properties.keySet().containsAll(reportedRevisions.keySet())) {
            throw new IllegalArgumentException("实时属性接受序号无效");
        }
        reportedRevisions.values().forEach(ReportedRevision::require);
        propertyDataTypes = Map.copyOf(propertyDataTypes);
        properties = Map.copyOf(properties);
    }
}
