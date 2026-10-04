package com.things.link.device.application;

import com.things.link.device.domain.DeviceCurrentValue;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * App 数据面可见的设备单属性当前值投影。
 *
 * <p>从 domain {@link DeviceCurrentValue} 映射而来，只保留 App 客户端关心的字段（影子版本等
 * 内部事实不下放）。value 保留 {@link JsonNode} 由统一序列化器输出，避免手工拼 JSON。
 *
 * @param deviceId    设备 ID
 * @param propertyKey 物模型属性键
 * @param value       当前 JSON 值
 * @param occurredAt  设备采集时间
 * @param reportedRevision 属性接受序号；未知为null
 * @param thingModelVersionId 写入时模型来源；未知为null
 */
public record AppCurrentValue(UUID deviceId, String propertyKey, JsonNode value, Instant occurredAt,
                              String reportedRevision, UUID thingModelVersionId) {

    /** 旧夹具及历史调用只能表达未知，不伪造接受序号或当前模型来源。 */
    public AppCurrentValue(UUID deviceId, String propertyKey, JsonNode value, Instant occurredAt) {
        this(deviceId, propertyKey, value, occurredAt, null, null);
    }

    /** @param value 领域当前值 @return App 数据面投影 */
    public static AppCurrentValue from(DeviceCurrentValue value) {
        return new AppCurrentValue(value.deviceId(), value.propertyKey(), value.value(), value.occurredAt(),
                value.reportedRevision(), value.thingModelVersionId());
    }
}
