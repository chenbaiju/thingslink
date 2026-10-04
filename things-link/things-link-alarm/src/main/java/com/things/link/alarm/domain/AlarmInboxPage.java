package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.List;

/**
 * ADR0093：固定三十天浏览窗口的通知页，不承诺并发事务的完整快照。
 * @param items 当前页全部事件，包含已读事件
 * @param nextCursor 下一页不透明位置，不是阅读水位
 * @param hasMore 是否存在后页
 * @param windowStart 固定窗口起点，含边界
 * @param windowEnd 初页数据库实际时刻，含边界
 */
public record AlarmInboxPage(List<AlarmInboxItem> items, String nextCursor, boolean hasMore,
                             Instant windowStart, Instant windowEnd) {
    /** 固化列表，防止响应生成期间调用方修改页面内容。 */
    public AlarmInboxPage {
        items = List.copyOf(items);
    }
}
