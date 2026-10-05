package com.things.link.assistant.application;

import java.util.UUID;

/**
 * 启动时受控授权；调用方不能改变项目、环境、配置或固定样本。
 * @param id 冻结授权标识，重新启动不得改变它以重置次数
 * @param environmentId 授权绑定的环境标识
 * @param projectId 唯一允许调用的项目标识
 * @param configurationRevision 已启用项目模型配置的固定版本
 * @param manifestSha256 固定样本清单的摘要
 */
public record ProbeAuthorization(String id, String environmentId, UUID projectId,
        long configurationRevision, String manifestSha256) {
    /** 校验冻结授权的标识、正数版本及摘要格式，不创建或扩展授权范围。 */
    public ProbeAuthorization {
        if (id==null || !id.matches("[A-Za-z0-9_-]{1,64}") || environmentId==null
                || !environmentId.matches("[A-Za-z0-9_-]{1,64}") || projectId==null
                || configurationRevision<1 || manifestSha256==null || !manifestSha256.matches("[a-f0-9]{64}"))
            throw new IllegalStateException("INVALID_PROBE_AUTHORIZATION");
    }
    @Override public String toString() { return "ProbeAuthorization[REDACTED]"; }
}
