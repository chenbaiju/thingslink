package com.things.link.export.api.dto;

import com.things.link.export.application.ProjectExportDownload;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;

/**
 * 项目导出下载地址响应，不暴露对象键或存储身份。
 * @param url 五分钟有效的 bearer 预签名地址
 * @param expiresAt URL 截止时刻
 */
@Schema(description = "项目导出短时下载地址")
public record ProjectExportDownloadUrlResponse(
        @Schema(description = "五分钟有效的私有对象预签名URL") URI url,
        @Schema(description = "URL截止时刻") Instant expiresAt) {

    /** @param download 应用层签发结果 @return 仅含URL和截止时刻的HTTP响应 */
    public static ProjectExportDownloadUrlResponse from(ProjectExportDownload download) {
        return new ProjectExportDownloadUrlResponse(download.url(), download.expiresAt());
    }
}
