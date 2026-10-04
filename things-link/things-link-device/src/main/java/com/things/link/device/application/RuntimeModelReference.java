package com.things.link.device.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 运行快照请求携带的不可变物模型身份。
 *
 * @param versionId 物模型版本ID
 * @param digestAlgorithm PostgreSQL规范文本摘要算法
 * @param digest 完整物模型快照摘要
 * @param profile 复合属性解释Profile
 */
public record RuntimeModelReference(
        UUID versionId,
        String digestAlgorithm,
        String digest,
        String profile) {

    /** 拒绝缺字段引用；格式和权威等值由运行服务统一校验。 */
    public RuntimeModelReference {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(profile, "profile");
    }
}
