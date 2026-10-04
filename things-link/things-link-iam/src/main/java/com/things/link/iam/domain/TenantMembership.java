package com.things.link.iam.domain;

import java.util.UUID;

/**
 * 账号在某个租户中的归属关系。
 *
 * <p>一个账号可以是多个租户的成员（架构文档第 9 节：集成商工程师同时服务
 * 几家客户），因此登录后需要选定「当前租户」。ADR 0012 已把授权边界收敛到
 * {@code sys_project_member.role}，本类型只证明账号仍属于该租户，不再承载任何角色语义。
 *
 * @param id        成员记录 ID
 * @param tenantId  租户 ID
 * @param accountId 账号 ID
 * @param status    成员状态
 */
public record TenantMembership(
        UUID id,
        UUID tenantId,
        UUID accountId,
        Status status) {

    /** 成员状态。与迁移中的 CHECK 约束保持一致。 */
    public enum Status {
        /** 正常。 */
        ACTIVE,
        /** 已邀请但尚未接受。此状态下不能登录进该租户。 */
        INVITED,
        /** 被停用。 */
        DISABLED
    }

    /**
     * 该成员身份当前是否可用于访问租户数据。
     *
     * @return 可用返回 true
     */
    public boolean isActive() {
        return status == Status.ACTIVE;
    }

}
