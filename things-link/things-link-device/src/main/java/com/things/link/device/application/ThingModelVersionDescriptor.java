package com.things.link.device.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 跨领域校验精确物模型版本引用所需的最小不可变描述。
 *
 * <p>该描述不包含设备类型管理事实、模型正文或设备授权。调用方必须使用可信projectId查询，并将空结果
 * 与错项目统一处理，不能把此只读描述当作访问设备数据的授权凭据。</p>
 *
 * @param versionId 不可变物模型版本ID
 * @param projectId 版本所属项目ID
 * @param digestAlgorithm PostgreSQL规范JSONB文本摘要算法
 * @param digest 规范模型快照摘要
 * @param profile 模型Schema Profile
 */
public record ThingModelVersionDescriptor(
        UUID versionId,
        UUID projectId,
        String digestAlgorithm,
        String digest,
        String profile) {

    /** 拒绝缺字段的跨领域投影；具体算法、摘要和Profile由消费方按自己的合同精确比较。 */
    public ThingModelVersionDescriptor {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(profile, "profile");
    }
}
