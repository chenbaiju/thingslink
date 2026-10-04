package com.things.link.support.cache;

/** 统一失效事件的有限操作集合。 */
public enum CacheInvalidationOperation {
    /** 事实变化后驱逐可重建投影。 */
    EVICT,
    /** 租户切换到另一控制面策略。 */
    ASSIGN,
    /** 共享控制面模板版本更新。 */
    UPDATE,
    /** 安全凭据轮换或撤销，必须立即停止使用旧成功结果。 */
    REVOKE
}
