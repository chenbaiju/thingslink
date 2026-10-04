package com.things.link.export.domain;

import java.util.UUID;

/**
 * 一条待删孤儿对象的领取身份。
 * @param cleanupId 清理事实 ID
 * @param objectKey 私有对象键
 * @param leaseToken 清理租约令牌
 */
public record ProjectExportCleanupClaim(UUID cleanupId, String objectKey, UUID leaseToken) {
}
