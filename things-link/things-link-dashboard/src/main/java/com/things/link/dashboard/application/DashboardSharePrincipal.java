package com.things.link.dashboard.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** ADR0101内部capability身份，不借Console/App主体，不包含原secret也不作为公开响应DTO。 */
public final class DashboardSharePrincipal implements java.security.Principal {
    /** 能力选择器。 */
    private final UUID shareId;
    /** 可信租户。 */
    private final UUID tenantId;
    /** 可信项目。 */
    private final UUID projectId;
    /** 固定看板。 */
    private final UUID dashboardId;
    /** 冻结精确版本。 */
    private final UUID dashboardVersionId;
    /** 签发时生命周期代次。 */
    private final long projectGeneration;
    /** 数据库到期时刻。 */
    private final Instant expiresAt;
    /** 签发时来源限制。 */
    private final String refererPolicy;
    /** 仅供每请求匹配的凭据摘要，禁止序列化或日志。 */
    @JsonIgnore
    private final String secretHash;
    /** 建立不可变身份；每次正文读取仍必须回库复验，不能把该值当跨请求缓存。 */
    public DashboardSharePrincipal(UUID shareId, UUID tenantId, UUID projectId, UUID dashboardId, UUID dashboardVersionId, long projectGeneration, Instant expiresAt, String refererPolicy, String secretHash) {
        this.shareId = Objects.requireNonNull(shareId, "shareId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.dashboardId = Objects.requireNonNull(dashboardId, "dashboardId");
        this.dashboardVersionId = Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
        this.projectGeneration = projectGeneration;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.refererPolicy = Objects.requireNonNull(refererPolicy, "refererPolicy");
        this.secretHash = Objects.requireNonNull(secretHash, "secretHash");
        if (projectGeneration < 0 || !secretHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("分享身份不完整");
    }
    /** @return 能力选择器 */
    public UUID shareId() { return shareId; }
    /** @return 可信租户 */
    public UUID tenantId() { return tenantId; }
    /** @return 可信项目 */
    public UUID projectId() { return projectId; }
    /** @return 固定看板 */
    public UUID dashboardId() { return dashboardId; }
    /** @return 冻结精确版本 */
    public UUID dashboardVersionId() { return dashboardVersionId; }
    /** @return 签发时生命周期代次 */
    public long projectGeneration() { return projectGeneration; }
    /** @return 数据库到期时刻 */
    public Instant expiresAt() { return expiresAt; }
    /** @return 签发时来源限制 */
    public String refererPolicy() { return refererPolicy; }
    /** @return 仅供每请求匹配的凭据摘要，禁止序列化或日志 */
    @JsonIgnore
    public String secretHash() { return secretHash; }
    /** @return 仅内部share身份，不映射任何账号 */
    @Override public String getName() { return shareId.toString(); }
    /** 日志误用也不输出hash或scope。 */
    @Override public String toString() { return "DashboardSharePrincipal[shareId=" + shareId + "]"; }
}
