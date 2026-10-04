package com.things.link.ota.api;

import com.things.link.ota.application.OtaReleaseDownloadIssuer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** 临时bearer地址不进入日志或幂等响应存储；不表示设备升级资格。
 * @param firmwareId 本次管理下载固件
 * @param downloadUrl 固定版本的短时效下载地址
 * @param expiresAt 从调用开始计算的保守到期时间
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"firmwareId", "downloadUrl", "expiresAt"})
public record OtaReleaseDownloadResponse(UUID firmwareId, URI downloadUrl, Instant expiresAt) {
    /** 避免默认record字符串将临时bearer查询串带入诊断。 */
    @Override public String toString() {
        return "OtaReleaseDownloadResponse[firmwareId=" + firmwareId + ", expiresAt=" + expiresAt + "]";
    }
    /** 只返回短地址与到期，不投影内部版本身份。 */
    public static OtaReleaseDownloadResponse from(OtaReleaseDownloadIssuer.Download download) {
        return new OtaReleaseDownloadResponse(download.firmwareId(), download.downloadUrl(), download.expiresAt());
    }
}
