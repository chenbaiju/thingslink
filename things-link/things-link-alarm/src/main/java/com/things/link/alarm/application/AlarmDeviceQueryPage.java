package com.things.link.alarm.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 内部键集查询结果；HTTP编排以可信身份和规范过滤签名锚点，不直接把本对象序列化。
 * @param items 过滤后的最多limit项
 * @param nextUpdatedAt 有后页时的最后更新时间锚点，否则空
 * @param nextId 有后页时的最后ID锚点，否则空
 * @param hasMore 是否存在第limit+1个过滤后候选
 */
public record AlarmDeviceQueryPage(List<AlarmDeviceQueryItem> items, Instant nextUpdatedAt, UUID nextId, boolean hasMore) {
    /** 固定本次页观察，不承诺跨页事务快照。 */
    public AlarmDeviceQueryPage {
        items = List.copyOf(items);
    }
}
