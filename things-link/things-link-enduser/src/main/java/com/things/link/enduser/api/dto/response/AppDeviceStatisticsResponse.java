package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.domain.AppDeviceStatistics;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前App账号的授权设备统计。
 * @param total 设备总数
 * @param online 当前在线数
 * @param active24h 最近24小时有效上报数
 * @param alarming 当前未解除告警设备数
 * @param asOf 数据库快照时刻
 */
@Schema(description = "App授权设备四统计；不含未绑定或已删除设备")
public record AppDeviceStatisticsResponse(
        @Schema(description = "授权设备总数") long total,
        @Schema(description = "当前ONLINE设备数") long online,
        @Schema(description = "接收时刻严格晚于asOf前24小时的首次有效CURRENT属性或事件设备数；历史未知不回填") long active24h,
        @Schema(description = "存在ACTIVE条件告警的设备数，确认状态不影响，按设备去重") long alarming,
        @Schema(description = "数据库统计时刻，RFC3339 UTC") String asOf) {
    /** @param value 同快照统计 @return HTTP响应 */
    public static AppDeviceStatisticsResponse from(AppDeviceStatistics value) {
        return new AppDeviceStatisticsResponse(value.total(), value.online(), value.active24h(),
                value.alarming(), value.asOf().toString());
    }
}
