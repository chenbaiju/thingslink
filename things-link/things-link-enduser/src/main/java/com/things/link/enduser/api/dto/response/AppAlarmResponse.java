package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.domain.AppAlarm;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * App授权告警的公开标量字段。
 * @param id 告警事故标识
 * @param deviceId 来源设备标识
 * @param deviceName 当前设备名称
 * @param deviceKey 设备标识
 * @param alarmType 告警类型文本
 * @param severity 严重程度
 * @param conditionState 条件状态：ACTIVE或CLEARED
 * @param ackState 确认状态，与解除状态不同
 * @param firstConditionAt 首次异常时间，排序和时间筛选依据
 * @param activatedAt 实际触发时间，未知为空
 * @param clearedAt 解除时间，可空
 * @param acknowledgedAt 确认时间，可空
 * @param lastReceivedAt 最近相关数据接收时间
 */
@Schema(description = "App公共告警")
public record AppAlarmResponse(
        @Schema(description = "告警事故标识") String id,
        @Schema(description = "来源设备标识") String deviceId,
        @Schema(description = "当前设备名称") String deviceName,
        @Schema(description = "设备标识") String deviceKey,
        @Schema(description = "告警类型文本") String alarmType,
        @Schema(description = "严重程度") String severity,
        @Schema(description = "条件状态：ACTIVE或CLEARED") String conditionState,
        @Schema(description = "确认状态，与解除状态不同") String ackState,
        @Schema(description = "首次异常时间，排序和时间筛选依据") String firstConditionAt,
        @Schema(description = "实际触发时间，未知为空", types = {"string", "null"}, format = "date-time") String activatedAt,
        @Schema(description = "解除时间，可空", types = {"string", "null"}, format = "date-time") String clearedAt,
        @Schema(description = "确认时间，可空", types = {"string", "null"}, format = "date-time") String acknowledgedAt,
        @Schema(description = "最近相关数据接收时间") String lastReceivedAt) {
    /** @param alarm 已授权事故 @return 公开响应，不包含规则正文或人员信息 */
    public static AppAlarmResponse from(AppAlarm alarm) {
        return new AppAlarmResponse(alarm.id().toString(),alarm.deviceId().toString(),alarm.deviceName(),
                alarm.deviceKey(),alarm.alarmType(),alarm.severity(),alarm.conditionState(),alarm.ackState(),
                time(alarm.firstConditionAt()),time(alarm.activatedAt()),time(alarm.clearedAt()),
                time(alarm.acknowledgedAt()),time(alarm.lastReceivedAt()));
    }
    /** 空历史时间保留为空，不补造其他时间。 */
    private static String time(Instant value) { return value==null ? null : value.toString(); }
}
