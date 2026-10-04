package com.things.link.support.tenant;

/**
 * 数据库连接池故障域。
 *
 * <p>G1-C3c / D-048 只区分控制面与数据面，不按读写拆池：控制面同样有写事务，数据面同样有重查询。</p>
 */
public enum DatabaseWorkload {
    /** HTTP、认证与未分类入口的默认故障域。 */
    CONTROL,
    /** Kafka、Outbox 与后台 worker 的故障域。 */
    DATA
}
