package com.things.link.dashboard.application;

import com.things.link.device.application.RuntimeDeviceCatalogItem;
import java.util.List;

/**
 * 分享能力及过滤绑定的一页有限结果，不复用App或Console游标。
 * @param items 已过滤且有界的条目
 * @param nextCursor 下一页完整性保护游标
 * @param hasMore 是否还有下一页
 */
public record DashboardShareCatalogPage(List<RuntimeDeviceCatalogItem> items, String nextCursor, boolean hasMore) {
    /** 页状态必须与游标存在性一致，调用方不得在编码前改变条目。 */
    public DashboardShareCatalogPage {
        items = List.copyOf(items);
        if (hasMore != (nextCursor != null)) throw new IllegalArgumentException("分享下一页状态不一致");
    }
}
