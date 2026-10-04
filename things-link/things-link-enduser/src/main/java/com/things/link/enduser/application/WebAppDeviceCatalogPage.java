package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppRuntimeDeviceCatalogItem;

import java.util.List;

/**
 * 模型过滤App设备目录的一页。
 *
 * @param items 已授权且仍绑定精确模型的目录项
 * @param nextCursor 下一页签名游标；末页为空
 * @param hasMore 是否仍有下一页
 */
public record WebAppDeviceCatalogPage(
        List<AppRuntimeDeviceCatalogItem> items, String nextCursor, boolean hasMore) {

    /** 冻结目录项，且末页不得携带游标。 */
    public WebAppDeviceCatalogPage {
        items = List.copyOf(items);
        if (hasMore != (nextCursor != null)) throw new IllegalArgumentException("目录下一页状态不一致");
    }
}
