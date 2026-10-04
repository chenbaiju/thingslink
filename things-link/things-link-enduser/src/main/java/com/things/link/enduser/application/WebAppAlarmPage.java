package com.things.link.enduser.application;

import com.things.link.alarm.application.AlarmDeviceQueryItem;

import java.util.List;

/**
 * App告警实例的一页公开编排结果。
 *
 * @param items 过滤后告警事实
 * @param nextCursor 下一页签名游标；末页为空
 * @param hasMore 是否仍有下一页
 */
public record WebAppAlarmPage(List<AlarmDeviceQueryItem> items, String nextCursor, boolean hasMore) {

    /** 冻结告警事实并保持游标状态一致。 */
    public WebAppAlarmPage {
        items = List.copyOf(items);
        if (hasMore != (nextCursor != null)) throw new IllegalArgumentException("告警下一页状态不一致");
    }
}
