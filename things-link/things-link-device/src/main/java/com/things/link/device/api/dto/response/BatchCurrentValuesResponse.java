package com.things.link.device.api.dto.response;

import java.util.List;

/**
 * 批量当前值响应。
 *
 * @param items 按请求设备首次出现顺序返回的当前值；尚未上报的设备保留为空项
 */
public record BatchCurrentValuesResponse(List<DeviceCurrentValuesResponse> items) {
}
