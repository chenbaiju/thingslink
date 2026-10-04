package com.things.link.alarm.api.dto.response;

/** @param markedCount 本次真正新增回执数；重复标记合法事件得到零仍然成功 */
public record AlarmInboxReadResponse(int markedCount) {
}
