package com.things.link.project.application;

/**
 * 跨模块写入口可依赖的统一配额等级。
 *
 * <p>状态按 {@link Enum#ordinal()} 从低到高排列，但调用方应显式判断允许的等级，
 * 避免未来新增状态时被隐式放行或拒绝。</p>
 */
public enum QuotaStatus {
    /** 未接近限额。 */
    NORMAL,
    /** 达到软限，继续服务并记录告警。 */
    SOFT_LIMIT,
    /** 达到硬限，由写入口拒绝新增事实。 */
    HARD_LIMIT,
    /** 达到降级水位，保留核心事实并停止高成本副作用。 */
    DEGRADED
}
