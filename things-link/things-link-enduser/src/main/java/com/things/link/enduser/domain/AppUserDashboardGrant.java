package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行访问冻结§4：用户对稳定看板的READ事实，不代表设备权限或某个历史版本的直接入口。
 * @param id 授权行稳定身份，撤销重授不更换
 * @param tenantId 项目权威归属租户
 * @param projectId 授权项目
 * @param appUserId 租户级用户
 * @param dashboardId 稳定看板身份
 * @param status 当前显式授权状态
 * @param revision 从1开始的管理CAS代次
 * @param createdAt 首次授予的数据库时刻
 * @param updatedAt 最近实际变化时刻
 * @param revokedAt 撤销时刻，仅REVOKED有值
 * @param createdBy 首次Console操作者
 * @param updatedBy 最近实际变化的Console操作者
 * @param revokedBy 当前撤销的Console操作者，仅REVOKED有值
 */
public record AppUserDashboardGrant(UUID id, UUID tenantId, UUID projectId, UUID appUserId,
                                    UUID dashboardId, Status status, long revision,
                                    Instant createdAt, Instant updatedAt, Instant revokedAt,
                                    UUID createdBy, UUID updatedBy, UUID revokedBy) {
    /** 持久损坏不能投影成有效授权；状态与撤销元数据必须成对。 */
    public AppUserDashboardGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(appUserId, "appUserId");
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(updatedBy, "updatedBy");
        if (revision < 1 || (status == Status.ACTIVE && (revokedAt != null || revokedBy != null))
                || (status == Status.REVOKED && (revokedAt == null || revokedBy == null))) {
            throw new IllegalArgumentException("看板授权持久状态不一致");
        }
    }

    /** 显式授权状态；不能替代用户、项目角色与资源生命周期的逐次复核。 */
    public enum Status {
        /** 显式授予；实际读取还须角色、项目与资源均有效。 */
        ACTIVE,
        /** 显式撤销；重授必须在同一行推进revision。 */
        REVOKED
    }
}
