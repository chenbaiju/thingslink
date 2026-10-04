package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.domain.AlarmInboxPage;

import java.time.Instant;
import java.util.List;

/**
 * ADR0093：带固定窗口的浏览页；游标不表示已读水位或完整数据库快照。
 * @param items 全部窗口事件页 @param nextCursor 后页位置 @param hasMore 是否有后页
 * @param windowStart 三十天窗口起点 @param windowEnd 初页数据库实际时刻
 */
public record AlarmInboxPageResponse(List<AlarmInboxItemResponse> items, String nextCursor, boolean hasMore,
                                     Instant windowStart, Instant windowEnd) {
    /** @param value 已授权窗口页 @return 仅含公开字段的HTTP响应 */
    public static AlarmInboxPageResponse from(AlarmInboxPage value) {
        return new AlarmInboxPageResponse(value.items().stream().map(AlarmInboxItemResponse::from).toList(),
                value.nextCursor(), value.hasMore(), value.windowStart(), value.windowEnd());
    }
}
