package com.things.link.device.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 成功设备实际使用的不可变物模型运行投影。
 *
 * @param versionId 精确版本ID
 * @param digestAlgorithm 完整快照摘要算法
 * @param digest 完整快照摘要
 * @param profile 复合属性Profile
 * @param properties 请求键并集的属性描述
 */
public record RuntimeModelDescription(
        UUID versionId,
        String digestAlgorithm,
        String digest,
        String profile,
        List<RuntimePropertyDescription> properties) {

    /** 冻结模型投影；属性顺序由请求首次出现顺序确定。 */
    public RuntimeModelDescription {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(profile, "profile");
        properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
    }
}
