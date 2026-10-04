package com.things.link.ingestion.application;

import com.things.link.dashboard.application.DashboardSharePrincipal;
import java.time.Instant;
import java.util.UUID;

/**
 * ADR0099/0101：三种身份显式隔离；分享不占用账号字段，真实项目来自能力凭据。
 * @param app 是否为独立App身份
 * @param subjectId 账号或终端用户；分享必须为空
 * @param tenantId 已验证真实租户
 * @param projectId 固定项目；尚未选项目的Console可空
 * @param projectGeneration 生命周期代次
 * @param expiresAt 绝对截止，不随租约续期延长
 * @param sharePrincipal 分享能力身份；Console/App必须为空
 */
public record DashboardRealtimePrincipal(boolean app, UUID subjectId, UUID tenantId, UUID projectId,
        long projectGeneration, Instant expiresAt, DashboardSharePrincipal sharePrincipal)
        implements java.security.Principal {
    /** 既有Console/App构造保持源兼容，不产生隐式分享身份。 */
    public DashboardRealtimePrincipal(boolean app, UUID subjectId, UUID tenantId, UUID projectId,
            long projectGeneration, Instant expiresAt) {
        this(app, subjectId, tenantId, projectId, projectGeneration, expiresAt, null);
    }

    /** @return 分享身份只有能力主体，不把shareId伪装成账号。 */
    public static DashboardRealtimePrincipal share(DashboardSharePrincipal principal) {
        return new DashboardRealtimePrincipal(false, null, principal.tenantId(), principal.projectId(),
                principal.projectGeneration(), principal.expiresAt(), principal);
    }

    /** @return 是否采用分享的失败关闭与预算策略。 */
    public boolean share() { return sharePrincipal != null; }

    /** @return 不含秘密的稳定主体名；分享带独立前缀，账号保持既有容器主体名。 */
    @Override
    public String getName() {
        return share() ? "share:" + sharePrincipal.shareId() : subjectId.toString();
    }

    /** 所有轴与分享能力必须一致，禁止通过可选字段拼装混合身份。 */
    public DashboardRealtimePrincipal {
        if (tenantId == null || expiresAt == null || projectGeneration < 0
                || ((app || sharePrincipal != null) && projectId == null)
                || (sharePrincipal == null ? subjectId == null : app || subjectId != null
                || !tenantId.equals(sharePrincipal.tenantId()) || !projectId.equals(sharePrincipal.projectId())
                || projectGeneration != sharePrincipal.projectGeneration()
                || !expiresAt.equals(sharePrincipal.expiresAt()))) {
            throw new IllegalArgumentException("实时身份不完整或混用");
        }
    }
}
