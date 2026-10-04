package com.things.link.ota.api;

import com.things.link.ota.domain.OtaPublication;
import java.time.Instant;
import java.util.UUID;

/**
 * 发布尝试状态白名单，202及PREPARED不代表已READY或设备具备安装资格。
 * @param id 尝试身份
 * @param firmwareId 固件身份
 * @param uploadSessionId 精确上传身份
 * @param status 持久处理状态
 * @param revision 十进制修订字符串
 * @param failureCode 固定失败分类
 * @param createdAt 创建时间
 * @param updatedAt 最近状态变更
 */
public record OtaPublicationResponse(UUID id, UUID firmwareId, UUID uploadSessionId, String status,
        String revision, String failureCode, Instant createdAt, Instant updatedAt) {
    /** 不序列化签名、manifest、对象路径或内部scope。 */
    public static OtaPublicationResponse from(OtaPublication value) {
        return new OtaPublicationResponse(value.id(), value.firmwareId(), value.uploadSessionId(), value.status(),
                Long.toString(value.revision()), value.failureCode(), value.createdAt(), value.updatedAt());
    }
}
