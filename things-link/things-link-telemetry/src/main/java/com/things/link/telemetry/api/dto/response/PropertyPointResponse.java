package com.things.link.telemetry.api.dto.response;

import com.things.link.telemetry.domain.PropertyPoint;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * 单个设备属性历史点。
 *
 * @param deviceId 设备 ID
 * @param propertyKey 属性标识符
 * @param ts 设备端采集时刻
 * @param value 已按物模型恢复类型的属性值
 * @param dataType 写入时数据类型
 * @param thingModelVersionId 写入时物模型版本 ID
 * @param modelVersion 可读版本号或 LEGACY_UNVERSIONED
 * @param quality 数据质量标记
 */
public record PropertyPointResponse(UUID deviceId, String propertyKey, Instant ts,
                                    Object value, String dataType, UUID thingModelVersionId,
                                    String modelVersion, short quality) {
    /**
     * 把多值列领域对象转换为 API 的单值表示。
     *
     * @param point 属性点
     * @return API 响应
     */
    public static PropertyPointResponse from(PropertyPoint point, ObjectMapper objectMapper) {
        Object value = point.valueDouble() != null ? point.valueDouble()
                : point.valueText() != null ? point.valueText()
                : point.valueBool() != null ? point.valueBool() : objectMapper.readTree(point.valueJson());
        return new PropertyPointResponse(point.deviceId(), point.propertyKey(), point.ts(), value, point.dataType(),
                point.thingModelVersionId(), point.modelVersion(), point.quality());
    }
}
