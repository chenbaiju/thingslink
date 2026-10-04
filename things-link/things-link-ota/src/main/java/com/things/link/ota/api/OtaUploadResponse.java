package com.things.link.ota.api;

import com.things.link.ota.domain.OtaUploadSession;
import java.time.Instant;
import java.util.UUID;

/**
 * 上传会话公开白名单，不暴露内部租户、桶、对象键、版本或租约能力。
 * @param id 会话身份
 * @param projectId 项目身份
 * @param firmwareId 固件身份
 * @param status 上传状态，不代表固件已发布
 * @param revision 十进制修订
 * @param expectedLength 预期字节数
 * @param expectedSha256 预期摘要
 * @param failureCode 固定失败分类
 * @param createdAt 创建时间
 * @param expiresAt 等待到期时间
 * @param cancelRequestedAt 取消登记时间
 * @param cleanupCompletedAt 实际收束时间
 */
public record OtaUploadResponse(UUID id, UUID projectId, UUID firmwareId, String status, String revision,
        long expectedLength, String expectedSha256, String failureCode, Instant createdAt, Instant expiresAt,
        Instant cancelRequestedAt, Instant cleanupCompletedAt) {
    /** 必须显式映射，禁止直接序列化持久实体。 */
    public static OtaUploadResponse from(OtaUploadSession session) {
        return new OtaUploadResponse(session.id(), session.projectId(), session.firmwareId(), session.status(),
                Long.toString(session.revision()), session.expectedLength(), session.expectedSha256(),
                session.failureCode(), session.createdAt(), session.expiresAt(), session.cancelRequestedAt(),
                session.cleanupCompletedAt());
    }
}
