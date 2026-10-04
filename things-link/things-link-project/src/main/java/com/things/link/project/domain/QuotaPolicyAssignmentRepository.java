package com.things.link.project.domain;

import com.things.link.project.application.QuotaPolicyChanged;

import java.util.Optional;
import java.util.UUID;

/**
 * 租户配额策略绑定的 CAS 写入端口。
 *
 * <p>assignmentVersion 是绑定自身的顺序轴，不能拿可复用策略的 version 代替；否则切换到较旧模板会被
 * 错误拒绝或让缓存回滚。
 */
public interface QuotaPolicyAssignmentRepository {

    /**
     * 仅在调用方持有当前绑定版本时切换策略，并返回需要广播的最新版本事件。
     *
     * @param tenantId 租户 ID
     * @param policyId 目标策略 ID
     * @param expectedAssignmentVersion 调用方读取时看到的绑定版本
     * @return CAS 成功时的新失效事件；版本不匹配、租户或策略不存在时为空
     */
    Optional<QuotaPolicyChanged> assign(UUID tenantId, UUID policyId, long expectedAssignmentVersion);
}
