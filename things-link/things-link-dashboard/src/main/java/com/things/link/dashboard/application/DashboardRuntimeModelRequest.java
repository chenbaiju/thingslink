package com.things.link.dashboard.application;

import java.util.Objects;
import java.util.UUID;

/**
 * App运行数据请求携带的不可变模型声明。
 *
 * @param versionId 不可变物模型版本ID
 * @param digestAlgorithm 模型摘要算法
 * @param digest 模型摘要
 * @param profile 复合属性Profile
 */
public record DashboardRuntimeModelRequest(
        UUID versionId, String digestAlgorithm, String digest, String profile) {

    /** 拒绝缺失字段，具体闭集和值格式仍由HTTP解析器负责。 */
    public DashboardRuntimeModelRequest {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(profile, "profile");
    }
}
