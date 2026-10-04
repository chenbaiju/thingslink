package com.things.link.export.application;

import java.net.URI;
import java.time.Instant;

/**
 * 一次成功签发的短时下载能力。
 * @param url 五分钟有效的完整预签名地址
 * @param expiresAt 本次能力的应用时钟截止时刻
 */
public record ProjectExportDownload(URI url, Instant expiresAt) {

    /** 下载能力响应不允许缺少URL或截止时刻。 */
    public ProjectExportDownload {
        if (url == null || expiresAt == null) {
            throw new IllegalArgumentException("项目导出下载能力不完整");
        }
    }
}
