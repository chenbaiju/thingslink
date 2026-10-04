package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.ingestion.application.TenantConnectionLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 以 Redis ZSET 租约实现租户 WebSocket 跨实例并发连接上限。
 *
 * <p>ZSET score 是 Redis 服务端计算的租约到期毫秒；申请时先删除过期成员再原子检查数量。
 * 应用进程崩溃无法执行 release 时，旧成员仍会自动失效，不会永久吃掉租户名额。</p>
 */
@Component
public class RedisTenantConnectionLease implements TenantConnectionLease {

    /** 租约存活时间；三次心跳机会兼顾短暂抖动与崩溃后的快速回收。 */
    static final Duration LEASE_TTL = Duration.ofSeconds(30);
    /** Redis 键前缀；tenantId hash tag 让单租户脚本在 Cluster 中固定一个 slot。 */
    private static final String KEY_PREFIX = "quota:websocket:connections:";
    /** 当前进程随机实例标识，避免两个节点碰巧生成相同 WebSocket session ID。 */
    private final String instanceId;
    /** 当前实现的租约时长；生产固定三十秒，测试可缩短以验证崩溃回收。 */
    private final Duration leaseTtl;

    /**
     * 删除过期成员、检查上限并登记新租约必须在一个脚本内完成，避免两个实例同时越过上限。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local now_ms = (now[1] * 1000) + math.floor(now[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_ms)
            local limit = tonumber(ARGV[2])
            if limit ~= nil and redis.call('ZCARD', KEYS[1]) >= limit then
                return 0
            end
            redis.call('ZADD', KEYS[1], now_ms + tonumber(ARGV[1]), ARGV[3])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[1]) * 2)
            return 1
            """, Long.class);

    /**
     * 续期只允许更新仍存在且未过期的成员；租约丢失后必须重新走 acquire 才能检查上限。
     * 已有本地连接不会因此被踢，但也不能无条件 ZADD 复活并绕过其他实例已经占用的名额。
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local now_ms = (now[1] * 1000) + math.floor(now[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_ms)
            if redis.call('ZSCORE', KEYS[1], ARGV[2]) == false then
                return 0
            end
            redis.call('ZADD', KEYS[1], now_ms + tonumber(ARGV[1]), ARGV[2])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[1]) * 2)
            return 1
            """, Long.class);

    /** 释放后删除空键，避免历史租户 ID 长期占用 Redis keyspace。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local removed = redis.call('ZREM', KEYS[1], ARGV[1])
            if redis.call('ZCARD', KEYS[1]) == 0 then
                redis.call('DEL', KEYS[1])
            end
            return removed
            """, Long.class);

    /** Redis 原子脚本入口。 */
    private final StringRedisTemplate redis;
    /** 故障日志不携带租户或连接 ID，防止高频心跳制造敏感且高基数日志。 */
    private static final Logger log = LoggerFactory.getLogger(RedisTenantConnectionLease.class);

    /**
     * @param redis Redis 字符串与脚本入口
     */
    @Autowired
    public RedisTenantConnectionLease(StringRedisTemplate redis) {
        this(redis, UUID.randomUUID().toString(), LEASE_TTL);
    }

    /**
     * 显式实例标识入口仅供跨实例测试构造两个稳定节点。
     *
     * @param redis Redis 字符串与脚本入口
     * @param instanceId 当前应用实例唯一标识
     */
    RedisTenantConnectionLease(StringRedisTemplate redis, String instanceId) {
        this(redis, instanceId, LEASE_TTL);
    }

    /**
     * 可控 TTL 构造器只供真实 Redis 测试验证进程崩溃后的租约回收。
     *
     * @param redis Redis 字符串与脚本入口
     * @param instanceId 当前应用实例唯一标识
     * @param leaseTtl 正租约时长
     */
    RedisTenantConnectionLease(StringRedisTemplate redis, String instanceId, Duration leaseTtl) {
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("WebSocket 租约时长必须大于零");
        }
        this.redis = redis;
        this.instanceId = instanceId;
        this.leaseTtl = leaseTtl;
    }

    /** {@inheritDoc} */
    @Override
    public LeaseDecision acquire(ConnectionLease lease, Long connectionLimit) {
        // 显式不限由调用方保留本机物理上限即可，不在 Redis 制造无意义租约。
        if (connectionLimit == null) {
            return LeaseDecision.ACQUIRED;
        }
        if (connectionLimit != null && connectionLimit == 0L) {
            return LeaseDecision.REJECTED;
        }
        try {
            Long result = redis.execute(ACQUIRE_SCRIPT, List.of(key(lease.ownerTenantId())),
                    Long.toString(leaseTtl.toMillis()), connectionLimit.toString(), lease.member());
            if (result == null) {
                return LeaseDecision.UNAVAILABLE;
            }
            return result == 1L ? LeaseDecision.ACQUIRED : LeaseDecision.REJECTED;
        } catch (RuntimeException exception) {
            // 指标由上层按 fail_open 记录；连接风暴期间不能为每次申请打印 ERROR 堆栈。
            log.debug("租户 WebSocket 连接租约申请失败，本次回退本机有限上限", exception);
            return LeaseDecision.UNAVAILABLE;
        }
    }

    /** {@inheritDoc} */
    @Override
    public ConnectionLease create(UUID ownerTenantId, String connectionId) {
        return new ConnectionLease(ownerTenantId, member(connectionId));
    }

    /** {@inheritDoc} */
    @Override
    public RenewDecision renew(ConnectionLease lease) {
        try {
            Long result = redis.execute(RENEW_SCRIPT, List.of(key(lease.ownerTenantId())),
                    Long.toString(leaseTtl.toMillis()), lease.member());
            if (result == null) {
                return RenewDecision.UNAVAILABLE;
            }
            return result == 1L ? RenewDecision.RENEWED : RenewDecision.LOST;
        } catch (RuntimeException exception) {
            // 心跳按连接执行，故障详情只留 DEBUG；低基数 fail_open 指标承担平台告警。
            log.debug("租户 WebSocket 连接租约续期失败，保留已有连接并等待下次心跳", exception);
            return RenewDecision.UNAVAILABLE;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void release(ConnectionLease lease) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key(lease.ownerTenantId())), lease.member());
        } catch (RuntimeException exception) {
            log.warn("租户 WebSocket 连接租约释放失败，将由 TTL 自动回收");
        }
    }

    /** @param ownerTenantId 项目归属租户 ID @return Redis Cluster 单租户 ZSET 键 */
    private static String key(UUID ownerTenantId) {
        return KEY_PREFIX + "{" + ownerTenantId + "}";
    }

    /** @param connectionId 本机会话 ID @return 跨实例唯一 ZSET 成员 */
    private String member(String connectionId) {
        return instanceId + ':' + connectionId;
    }

}
