package com.things.link.device.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备单属性当前值投影。
 *
 * <p>该投影同时用于 PostgreSQL 事实源和 Redis 热副本，reportedRevision是PG属性接受顺序；
 * shadowVersion仅为desired版本，不能用于reported排序。</p>
 *
 * @param deviceId 设备 ID
 * @param propertyKey 物模型属性键
 * @param value 当前 JSON 值
 * @param occurredAt 当前值的设备采集时间
 * @param shadowVersion desired影子版本
 * @param reportedRevision 可空属性接受序号，历史未知不得伪填
 * @param thingModelVersionId 可空属性写入时物模型来源
 */
public record DeviceCurrentValue(UUID deviceId, String propertyKey, JsonNode value,
                                 Instant occurredAt, int shadowVersion, String reportedRevision, UUID thingModelVersionId) {
    /** 验证已知序号，未知历史保持null。 */
    public DeviceCurrentValue {
        if (reportedRevision != null) com.things.link.shared.message.ReportedRevision.require(reportedRevision);
    }
    /** 旧投影只表达未知接受顺序及来源。 */
    public DeviceCurrentValue(UUID deviceId, String propertyKey, JsonNode value, Instant occurredAt, int shadowVersion) {
        this(deviceId, propertyKey, value, occurredAt, shadowVersion, null, null);
    }
}
