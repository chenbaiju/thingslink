package com.things.link.ingestion.infrastructure.websocket;

import com.things.link.dashboard.application.sharing.DashboardShareProtectionErrors;
import com.things.link.ingestion.application.DashboardShareConnectionLease;
import com.things.link.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Redis权威时间与单键原子脚本保证每share跨实例最多两条租约；无匿名fail-open。 */
@Component
public class RedisDashboardShareConnectionLease implements DashboardShareConnectionLease {
    /** 5秒续期配合15秒崩溃兜底；本机10秒授权新鲜度仍独立强制关闭。 */
    private static final Duration TTL = Duration.ofSeconds(15);
    /** 实例前缀避免跨JVM碰撞。 */
    private final String instanceId;
    /** 实际生产TTL或测试短TTL。 */
    private final Duration ttl;
    /** 独立于业务事实的Redis保护端口。 */
    private final StringRedisTemplate redis;
    /** 删除已过期成员和两连接检查必须同一原子操作。 */
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local t=redis.call('TIME')
            local now=t[1]*1000+math.floor(t[2]/1000)
            redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
            if redis.call('ZCARD',KEYS[1])>=2 then return 0 end
            redis.call('ZADD',KEYS[1],now+tonumber(ARGV[1]),ARGV[2])
            redis.call('PEXPIRE',KEYS[1],tonumber(ARGV[1])*2)
            return 1
            """, Long.class);
    /** 丢失租约不得无条件复活，避免其他实例已填满时越过两连接。 */
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            local t=redis.call('TIME')
            local now=t[1]*1000+math.floor(t[2]/1000)
            redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
            if not redis.call('ZSCORE',KEYS[1],ARGV[2]) then return 0 end
            redis.call('ZADD',KEYS[1],now+tonumber(ARGV[1]),ARGV[2])
            redis.call('PEXPIRE',KEYS[1],tonumber(ARGV[1])*2)
            return 1
            """, Long.class);
    /** 最后连接离开即删除空键。 */
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local removed=redis.call('ZREM',KEYS[1],ARGV[1])
            if redis.call('ZCARD',KEYS[1])==0 then redis.call('DEL',KEYS[1]) end
            return removed
            """, Long.class);
    /** 生产实例ID不来自请求或凭据。 */
    @Autowired
    public RedisDashboardShareConnectionLease(StringRedisTemplate redis) {
        this(redis, UUID.randomUUID().toString(), TTL);
    }
    /** 明确构造器用于真实Redis双节点与崩溃TTL验证，不引入生产可调保护上限。 */
    public RedisDashboardShareConnectionLease(StringRedisTemplate redis, String instanceId, Duration ttl) {
        if (instanceId == null || instanceId.isBlank() || ttl == null || ttl.toMillis() <= 0)
            throw new IllegalArgumentException("分享租约配置无效");
        this.redis = redis; this.instanceId = instanceId; this.ttl = ttl;
    }
    /** 每次握手生成独立成员，容器尚未生成会话ID也不会复用上一条租约。 */
    @Override public Lease acquire(UUID shareId, String connectionId) {
        if (shareId == null || connectionId == null || connectionId.isBlank()) throw new IllegalArgumentException("分享租约身份无效");
        Lease lease = new Lease(shareId, instanceId + ":" + connectionId + ":" + UUID.randomUUID());
        long result = execute(ACQUIRE, lease);
        if (result == 0) throw DashboardShareProtectionErrors.rateLimited();
        if (result != 1) throw unavailable(null);
        return lease;
    }
    /** 无法确认续期时调用方必须关闭，不将Redis异常转为继续发送。 */
    @Override public boolean renew(Lease lease) { return execute(RENEW, lease) == 1; }
    /** 失败不打印底层异常或连接身份，统一WS_CLOSE事件负责脱敏诊断。 */
    @Override public void release(Lease lease) {
        if (lease == null) return;
        try { redis.execute(RELEASE, List.of(key(lease.shareId())), lease.member()); }
        catch (RuntimeException ignored) { /* Redis TTL保守回收，清理不得覆盖首次关闭原因。 */ }
    }
    /** 所有未知返回与Redis故障保留首因并拒绝匿名连接。 */
    private long execute(DefaultRedisScript<Long> script, Lease lease) {
        try {
            Long result = redis.execute(script, List.of(key(lease.shareId())), Long.toString(ttl.toMillis()), lease.member());
            if (result == null) throw unavailable(null);
            return result;
        } catch (BusinessException failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(failure); }
    }
    /** 只有已认证shareId能生成键，hash tag保持单键脚本的Redis Cluster槽位一致。 */
    private static String key(UUID shareId) { return "share:ws:connections:{" + shareId + "}"; }
    /** 固定安全错误不回显异常文字。 */
    private static BusinessException unavailable(Throwable cause) {
        return DashboardShareProtectionErrors.unavailable(cause);
    }
}
