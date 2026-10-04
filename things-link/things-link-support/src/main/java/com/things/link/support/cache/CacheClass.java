package com.things.link.support.cache;

/**
 * 架构文档 8.5 冻结的缓存安全等级。
 *
 * <p>枚举值会进入 Redis 失效事件与 Prometheus 标签，禁止改成租户或资源动态值。</p>
 */
public enum CacheClass {
    /** PostgreSQL 事实的可丢失投影，故障时回源事实。 */
    DERIVED,
    /** 带版本和 last-known-good 的控制面策略。 */
    CONTROL,
    /** 凭据与权限等不可 fail-open 的安全判断。 */
    SECURITY
}
