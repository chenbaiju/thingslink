package com.things.link.export.domain;

import java.util.UUID;

/**
 * 已到期成功对象的持久清理领取身份。
 * @param exportId 导出任务 ID
 * @param cleanupId 被采用上传的清理事实 ID
 * @param objectKey 私有对象键
 * @param leaseToken 当前两分钟清理租约令牌
 */
public record ProjectExportExpiryClaim(UUID exportId, UUID cleanupId, String objectKey,
                                       UUID leaseToken) {

    /** 到期清理必须同时携带任务、cleanup、对象和token身份。 */
    public ProjectExportExpiryClaim {
        if (exportId == null || cleanupId == null || objectKey == null || objectKey.isBlank()
                || leaseToken == null) {
            throw new IllegalArgumentException("项目导出到期清理身份不完整");
        }
    }
}
