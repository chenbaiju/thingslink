package com.things.link.alarm.api.dto.response;

/** @param unreadCount ADR0093限定0至100；100供界面显示99+，不表示精确总数 */
public record AlarmInboxCountResponse(int unreadCount) {
}
