package com.things.link.task.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** 任务批量下行的项目与租户共享 Redis 双令牌桶。 */
@Component
public class TaskDispatchRateLimiter {
    /**
     * 单作用域令牌桶脚本。
     *
     * <p>项目与租户键使用不同 Redis 集群哈希标签，不能放进同一 Lua；否则集群模式会以
     * CROSSSLOT 拒绝。两级按项目、租户顺序保守扣减，后一级拒绝时允许前一级少量多消耗。</p>
     */
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local rate = tonumber(ARGV[1])
            local values = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
            local tokens = tonumber(values[1]) or rate
            local updated = tonumber(values[2]) or now
            tokens = math.min(rate, tokens + math.max(0, now - updated) * rate / 1000)
            if tokens < 1 then return 0 end
            redis.call('HSET', KEYS[1], 'tokens', tokens - 1, 'updated', now)
            redis.call('PEXPIRE', KEYS[1], 120000)
            return 1
            """, Long.class);
    /** Redis 客户端。 */ private final StringRedisTemplate redis;
    /** @param redis Redis 客户端 */ public TaskDispatchRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    /**
     * 尝试获取一次批量下行配额。
     *
     * <p>Redis 不可用时返回 false 而非放行；执行记录租约到期后会退避重试，避免调度器绕过套餐上限。</p>
     */
    public boolean tryAcquire(UUID tenantId, UUID projectId, Long tenantPerSecond, Long projectPerSecond) {
        if (Long.valueOf(0L).equals(projectPerSecond) || Long.valueOf(0L).equals(tenantPerSecond)) {
            return false;
        }
        try {
            if (projectPerSecond != null && !acquire("tc:task:rate:{project:" + projectId + "}", projectPerSecond)) {
                return false;
            }
            return tenantPerSecond == null
                    || acquire("tc:task:rate:{tenant:" + tenantId + "}", tenantPerSecond);
        } catch (RuntimeException exception) { return false; }
    }

    /** @param key 单作用域且带 hash tag 的 Redis 键 @param rate 每秒补充量 @return 是否取得令牌 */
    private boolean acquire(String key, long rate) {
        Long result = redis.execute(SCRIPT, List.of(key), Long.toString(rate));
        return Long.valueOf(1L).equals(result);
    }
}
