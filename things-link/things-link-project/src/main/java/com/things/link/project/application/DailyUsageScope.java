package com.things.link.project.application;

import java.util.UUID;

/**
 * 日用量贡献器收到的受信项目范围。
 *
 * <p>二元组由 project 权威表的受限领取函数派生；贡献器不得接受业务载荷自报的 tenantId。</p>
 *
 * @param tenantId 项目所有者租户 ID
 * @param projectId 项目 ID
 */
public record DailyUsageScope(UUID tenantId, UUID projectId) {

    /** 冻结完整二元组，避免缺失 RLS 轴时执行无范围查询。 */
    public DailyUsageScope {
        if (tenantId == null || projectId == null) {
            throw new IllegalArgumentException("日用量归并范围不能为空");
        }
    }
}
