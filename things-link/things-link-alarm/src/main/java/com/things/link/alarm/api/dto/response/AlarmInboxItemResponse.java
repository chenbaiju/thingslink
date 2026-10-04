package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmInboxItem;
import com.things.link.alarm.domain.AlarmRule;

import java.time.Instant;
import java.util.UUID;

/**
 * Console个人通知投影，不暴露内部租户身份或原始报文。
 * @param eventId ACTIVATED事件身份 @param instanceId 当前事故身份 @param receivedAt 事件接收时间
 * @param severity 当前实例严重程度 @param alarmType 当前实例告警类型 @param read 当前账号是否已读
 */
public record AlarmInboxItemResponse(UUID eventId, UUID instanceId, Instant receivedAt,
                                     AlarmRule.Severity severity, String alarmType, boolean read) {
    /** @param value 已授权的个人投影 @return HTTP展示对象 */
    public static AlarmInboxItemResponse from(AlarmInboxItem value) {
        return new AlarmInboxItemResponse(value.eventId(), value.instanceId(), value.receivedAt(),
                value.severity(), value.alarmType(), value.read());
    }
}
