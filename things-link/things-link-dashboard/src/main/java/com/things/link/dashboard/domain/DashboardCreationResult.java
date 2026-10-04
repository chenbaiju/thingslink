package com.things.link.dashboard.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次看板创建幂等请求与服务端生成身份之间的持久映射。
 *
 * <p>S12-1d4b只保存调用身份、键摘要、请求摘要和看板ID，不保存原始幂等键、请求正文或响应正文。
 * 公共完成墓碑无法保存新生成响应时，该映射仍能恢复首次创建的稳定看板身份。</p>
 *
 * @param tenantId 项目所有者租户ID
 * @param projectId 创建目标项目ID
 * @param accountId 发起创建的Console账号ID
 * @param idempotencyKeyDigest 原始幂等键的域分离SHA-256小写十六进制摘要
 * @param requestDigest 管理名称与content原文字节的长度分帧SHA-256小写十六进制摘要
 * @param dashboardId 首次请求生成的稳定看板ID
 */
public record DashboardCreationResult(
        UUID tenantId,
        UUID projectId,
        UUID accountId,
        String idempotencyKeyDigest,
        String requestDigest,
        UUID dashboardId) {

    /** 创建后即冻结全部身份与摘要，避免仓储调用时才暴露不完整映射。 */
    public DashboardCreationResult {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(idempotencyKeyDigest, "idempotencyKeyDigest");
        Objects.requireNonNull(requestDigest, "requestDigest");
        Objects.requireNonNull(dashboardId, "dashboardId");
        if (!idempotencyKeyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("idempotencyKeyDigest必须是SHA-256小写十六进制");
        }
        if (!requestDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestDigest必须是SHA-256小写十六进制");
        }
    }
}
