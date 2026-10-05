package com.things.link.shared.message;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 已提交设备属性增量的跨进程实时推送契约。
 *
 * <p>该消息只表达 PostgreSQL 影子 CAS 已接受的属性，不是遥测事实源，也不含历史点。
 * Redis Pub/Sub、Kafka 或浏览器连接丢失本消息后，客户端必须通过当前值 REST 接口补拉；
 * 这是 ADR 0016 的刻意取舍，不能把本类型演变成离线重放协议。</p>
 *
 * @param messageId 原始设备上行消息 ID，用于排障与客户端去重
 * @param tenantId 租户归属，仅供受信内部链路校验和观测，不作为 Redis 频道隔离轴
 * @param projectId 项目隔离轴，也是 Redis 项目频道的组成部分
 * @param deviceId 设备 ID，同时固定作为 Kafka 记录键 保持单设备顺序
 * @param thingModelVersionId 写入时不可变物模型版本 ID
 * @param modelVersion 可读语义版本
 * @param occurredAt 设备声明的属性采集时间
 * @param shadowVersion desired影子版本，不具备reported顺序资格
 * @param traceId 继承上行链路的追踪标识，异步线程必须显式恢复它
 * @param propertiesJson 属性键到单个 JSON 值文本的不可变映射
 * @param propertyDataTypes 属性键到 X-01 冻结数据类型的不可变映射
 * @param reportedRevisions 实际属性接受序号，旧信封缺失表示未知
 */
public record DeviceRealtimeUpdate(
        UUID messageId,
        UUID tenantId,
        UUID projectId,
        UUID deviceId,
        UUID thingModelVersionId,
        String modelVersion,
        Instant occurredAt,
        int shadowVersion,
        String traceId,
        Map<String, String> propertiesJson,
        Map<String, String> propertyDataTypes,
        Map<String, String> reportedRevisions) {

    /** 兼容旧消息构造，不能从desired版本伪造reported序号。 */
    public DeviceRealtimeUpdate(UUID messageId, UUID tenantId, UUID projectId, UUID deviceId,
                                UUID thingModelVersionId, String modelVersion, Instant occurredAt,
                                int shadowVersion, String traceId, Map<String, String> propertiesJson,
                                Map<String, String> propertyDataTypes) {
        this(messageId, tenantId, projectId, deviceId, thingModelVersionId, modelVersion,
                occurredAt, shadowVersion, traceId, propertiesJson, propertyDataTypes, Map.of());
    }

    /**
     * 冻结跨进程消息的最小完整性，避免坏消息悄悄被广播给不相关项目。
     */
    public DeviceRealtimeUpdate {
        Objects.requireNonNull(messageId, "messageId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(projectId, "projectId 不能为空");
        Objects.requireNonNull(deviceId, "deviceId 不能为空");
        Objects.requireNonNull(thingModelVersionId, "thingModelVersionId 不能为空");
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion 不能为空");
        }
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        if (shadowVersion < 0) {
            throw new IllegalArgumentException("shadowVersion 不能为负数");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId 不能为空");
        }
        if (propertiesJson == null || propertiesJson.isEmpty()
                || propertiesJson.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("实时属性增量不能为空");
        }
        if (propertyDataTypes == null || !propertyDataTypes.keySet().equals(propertiesJson.keySet())
                || propertyDataTypes.values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("实时属性类型必须与属性增量一一对应");
        }
        // Map.copyOf 防止 Kafka producer 与 Redis 发布器在异步期间观察到调用方继续修改的值。
        reportedRevisions = reportedRevisions == null ? Map.of() : Map.copyOf(reportedRevisions);
        if (!propertiesJson.keySet().containsAll(reportedRevisions.keySet()))
            throw new IllegalArgumentException("接受序号不得包含额外属性");
        reportedRevisions.values().forEach(ReportedRevision::require);
        propertiesJson = Map.copyOf(propertiesJson);
        propertyDataTypes = Map.copyOf(propertyDataTypes);
    }
}
