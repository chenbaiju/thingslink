package com.things.link.support.cache;

/**
 * 允许进入统一缓存事件与低基数指标的资源类型。
 *
 * <p>业务 ID 只放在事件的 {@code resourceId} 中供定向驱逐，绝不能成为指标标签。</p>
 */
public enum CacheResource {
    /** 项目概要派生快照。 */
    PROJECT_OVERVIEW(CacheClass.DERIVED),
    /** 动态设备组成员派生结果。 */
    DYNAMIC_DEVICE_GROUP(CacheClass.DERIVED),
    /** 属性当前值热副本。 */
    CURRENT_VALUE(CacheClass.DERIVED),
    /** 租户有效配额策略。 */
    QUOTA_POLICY(CacheClass.CONTROL),
    /** EMQX 设备成功认证短缓存。 */
    DEVICE_CREDENTIAL(CacheClass.SECURITY);

    /** 资源所属的缓存安全等级。 */
    private final CacheClass cacheClass;

    /** @param cacheClass 架构文档 8.5 的缓存安全等级 */
    CacheResource(CacheClass cacheClass) {
        this.cacheClass = cacheClass;
    }

    /** @return 固定缓存安全等级 */
    public CacheClass cacheClass() {
        return cacheClass;
    }
}
