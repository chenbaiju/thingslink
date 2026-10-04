package com.things.link.device.api.dto.response;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 单设备的批量当前值响应。
 *
 * @param deviceId 设备 ID
 * @param values 已存在且被请求的属性值
 * @param occurredAt 与 values 同键的设备采集时间
 * @param version desired影子版本，不作为reported顺序
 * @param reportedRevisions 属性实际接受序号，正Long字符串；历史未知的属性缺键
 * @param thingModelVersionIds 属性写入时模型来源，未知缺键，不借当前设备绑定
 */
public record DeviceCurrentValuesResponse(UUID deviceId, Map<String, JsonNode> values,
                                          Map<String, Instant> occurredAt, int version,
                                          Map<String, String> reportedRevisions,
                                          Map<String, UUID> thingModelVersionIds) {
}
