package com.things.link.project.domain;

import java.util.UUID;

/**
 * 后台归并器从受限领取函数得到的项目计量范围。
 *
 * <p>tenant/project 二元组由 project 权威表派生，贡献器不得接受控制台或设备载荷自报的
 * tenantId，否则会把业务事实计入错误租户。</p>
 *
 * @param tenantId 项目所有者租户 ID
 * @param projectId 项目 ID
 */
public record DailyUsageScope(UUID tenantId, UUID projectId) {

    /** 冻结后台范围的完整性，避免缺失 RLS 轴时执行无范围查询。 */
    public DailyUsageScope {
        if (tenantId == null || projectId == null) {
            throw new IllegalArgumentException("日用量归并范围不能为空");
        }
    }
}
