package com.things.link.telemetry.api.dto.response;

import com.things.link.telemetry.application.OverviewSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 项目概要 HTTP 响应。 */
public record OverviewResponse(Instant generatedAt, WindowResponse window, DeviceSummaryResponse devices,
                               MessageSummaryResponse messages24h, AvailabilityRateResponse alarmRate,
                               AlarmSeverityDeviceCountsResponse alarmSeverityDeviceCounts) {
    /** @param snapshot 应用概要 @return HTTP 响应 */
    public static OverviewResponse from(OverviewSnapshot snapshot) {
        return new OverviewResponse(snapshot.generatedAt(),
                new WindowResponse(snapshot.window().from(), snapshot.window().to()),
                new DeviceSummaryResponse(snapshot.devices().total(), snapshot.devices().online(),
                        snapshot.devices().onlineRate(), snapshot.devices().active24h(),
                        snapshot.devices().active24hRate()),
                new MessageSummaryResponse(snapshot.messages24h().count(), snapshot.messages24h().bytes()),
                new AvailabilityRateResponse(snapshot.alarmRate().available(), snapshot.alarmRate().value()),
                new AlarmSeverityDeviceCountsResponse(snapshot.alarmSeverityDeviceCounts().normal(),
                        snapshot.alarmSeverityDeviceCounts().critical(), snapshot.alarmSeverityDeviceCounts().major(),
                        snapshot.alarmSeverityDeviceCounts().minor(), snapshot.alarmSeverityDeviceCounts().warning(),
                        snapshot.alarmSeverityDeviceCounts().info()));
    }

    /** @param from 窗口起点（包含） @param to 窗口终点（不包含） */
    public record WindowResponse(Instant from, Instant to) {
    }

    /** @param total 总数 @param online 在线数 @param onlineRate 在线率
     * @param active24h 24 小时活跃数 @param active24hRate 24 小时活跃率 */
    public record DeviceSummaryResponse(long total, long online, double onlineRate,
                                        long active24h, double active24hRate) {
    }

    /** @param count 24 小时消息数 @param bytes 24 小时原始报文字节数 */
    public record MessageSummaryResponse(long count, long bytes) {
    }

    /** @param available 是否已有事实源 @param value 比率；不可用时为 null */
    public record AvailabilityRateResponse(boolean available,
                                           @Schema(types = {"number", "null"}, format = "double",
                                                   description = "指标不可用时为 null")
                                           Double value) {
    }
    /** 每台有效设备恰好属于一个类别，按最高ACTIVE严重度归类。 */
    public record AlarmSeverityDeviceCountsResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("NORMAL") long normal,
            @com.fasterxml.jackson.annotation.JsonProperty("CRITICAL") long critical,
            @com.fasterxml.jackson.annotation.JsonProperty("MAJOR") long major,
            @com.fasterxml.jackson.annotation.JsonProperty("MINOR") long minor,
            @com.fasterxml.jackson.annotation.JsonProperty("WARNING") long warning,
            @com.fasterxml.jackson.annotation.JsonProperty("INFO") long info) { }

}
