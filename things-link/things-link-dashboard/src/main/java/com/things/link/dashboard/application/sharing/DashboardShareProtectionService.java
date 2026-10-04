package com.things.link.dashboard.application.sharing;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** ADR0101匿名验证与响应的跨实例滑动保护；Redis故障不能沿用Console本机降级。 */
@Service
@EnableConfigurationProperties(DashboardShareRuntimeProperties.class)
public class DashboardShareProtectionService {
    /** 所有原子多键操作同Redis Cluster槽，未知凭据不会进入键名。 */
    private static final String PREFIX = "things-link:{dashboard-share}:";
    /** 双窗口计数原子检查全部身份后才写入，不因部分拒绝留下不一致扣款。 */
    private static final DefaultRedisScript<Long> REQUEST = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1]*1000 + math.floor(time[2]/1000)
            for i,key in ipairs(KEYS) do
              redis.call('ZREMRANGEBYSCORE',key,'-inf',now-60000)
              if redis.call('ZCARD',key) >= tonumber(ARGV[2*i]) then return 0 end
              local second = tonumber(ARGV[2*i+1])
              if second > 0 and redis.call('ZCOUNT',key,'('..(now-1000),'+inf') >= second then return 0 end
            end
            for _,key in ipairs(KEYS) do
              redis.call('ZADD',key,now,ARGV[1])
              redis.call('PEXPIRE',key,65000)
            end
            return 1
            """, Long.class);
    /** 每个身份的索引和金额同原子脚本维护；保留过期请求只会保守占额直到清理或TTL。 */
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1]*1000 + math.floor(time[2]/1000)
            local amount = tonumber(ARGV[2])
            for i=1,3 do
              local index = KEYS[2*i-1]
              local values = KEYS[2*i]
              local expired = redis.call('ZRANGEBYSCORE',index,'-inf',now-60000)
              for _,id in ipairs(expired) do
                local old = tonumber(redis.call('HGET',values,id) or '0')
                redis.call('HINCRBY',values,'_total',-old)
                redis.call('HDEL',values,id)
              end
              redis.call('ZREMRANGEBYSCORE',index,'-inf',now-60000)
              if tonumber(redis.call('HGET',values,'_total') or '0')+amount > tonumber(ARGV[i+2]) then return 0 end
            end
            for i=1,3 do
              redis.call('ZADD',KEYS[2*i-1],now,ARGV[1])
              redis.call('HSET',KEYS[2*i],ARGV[1],amount)
              redis.call('HINCRBY',KEYS[2*i],'_total',amount)
              redis.call('PEXPIRE',KEYS[2*i-1],65000)
              redis.call('PEXPIRE',KEYS[2*i],65000)
            end
            return 1
            """, Long.class);
    /** 只退款该次还存在的预留；已过窗的旧请求不会扣减新窗口，也不延长TTL。 */
    private static final DefaultRedisScript<Long> SETTLE = new DefaultRedisScript<>("""
            local actual = tonumber(ARGV[2])
            local time = redis.call('TIME')
            local now = time[1]*1000 + math.floor(time[2]/1000)
            -- 发送前必须三轴预留仍有效；不能在旧预留已过期后无额度发送。
            if actual > 0 then
              for i=1,3 do
                local score = redis.call('ZSCORE',KEYS[2*i-1],ARGV[1])
                if not score or tonumber(score) <= now-60000 then return -1 end
                local old = redis.call('HGET',KEYS[2*i],ARGV[1])
                if not old or tonumber(old) < actual then return -1 end
              end
            end
            for i=1,3 do
              local old = redis.call('HGET',KEYS[2*i],ARGV[1])
              if old then
                local unused = tonumber(old)-actual
                if unused < 0 then return -1 end
                redis.call('HINCRBY',KEYS[2*i],'_total',-unused)
                if actual == 0 then
                  redis.call('HDEL',KEYS[2*i],ARGV[1])
                  redis.call('ZREM',KEYS[2*i-1],ARGV[1])
                else
                  redis.call('HSET',KEYS[2*i],ARGV[1],actual)
                  -- 已发送正文按发送时刻计滑动窗，不从较早的序列化开始时间提前释放。
                  redis.call('ZADD',KEYS[2*i-1],now,ARGV[1])
                  redis.call('PEXPIRE',KEYS[2*i-1],65000)
                  redis.call('PEXPIRE',KEYS[2*i],65000)
                end
              end
            end
            return 1
            """, Long.class);
    /** 单实例匿名验证最多16个在途，不为未知身份开辟持久计数。 */
    private final Semaphore inFlight = new Semaphore(16);
    /** 使用独立脚本且Redis权威时间，不根据应用节点时钟判窗。 */
    private final StringRedisTemplate redis;
    /** 关闭匿名入口时不读取Redis或任何capability事实。 */
    private final DashboardShareRuntimeProperties properties;

    /** 装配共享保护，无可随意扩大的请求参数。 */
    public DashboardShareProtectionService(StringRedisTemplate redis, DashboardShareRuntimeProperties properties) {
        this.redis = redis; this.properties = properties;
    }

    /** 首先占固定在途，再扣真实remoteAddr哈希来源桶；调用方必须在finally归还许可。 */
    public SourcePermit acquireSource(String remoteAddr) {
        requireEnabled();
        if (!inFlight.tryAcquire()) throw limited();
        SourcePermit permit = new SourcePermit(inFlight);
        try {
            int bucket = sourceBucket(remoteAddr);
            requireAccepted(execute(REQUEST, List.of(PREFIX + "source:" + bucket),
                    UUID.randomUUID().toString(), "120", "4"));
            return permit;
        } catch (RuntimeException failed) {
            permit.close();
            throw failed;
        }
    }

    /** 只能传入已经定位并完整授权的真实内部ID，三轴任一超限整次拒绝。 */
    public void requireKnown(UUID shareId, UUID projectId, UUID tenantId) {
        requireEnabled();
        if (shareId == null || projectId == null || tenantId == null) throw unavailable();
        requireAccepted(execute(REQUEST, List.of(PREFIX + "request:share:" + shareId,
                PREFIX + "request:project:" + projectId, PREFIX + "request:tenant:" + tenantId),
                UUID.randomUUID().toString(), "120", "4", "1200", "0", "6000", "0"));
    }

    /** 编码前按端点上限预留三轴额度；本片调用方仅开放16KiB context与768KiB schema。 */
    public Reservation reserve(UUID shareId, UUID projectId, UUID tenantId, long responseLimit) {
        requireEnabled();
        if (shareId == null || projectId == null || tenantId == null) throw unavailable();
        if (responseLimit <= 0 || responseLimit > 4L * 1024 * 1024) throw new IllegalArgumentException("分享单次响应上限非法");
        String id = UUID.randomUUID().toString();
        List<String> keys = new ArrayList<>();
        for (String identity : List.of("share:" + shareId, "project:" + projectId, "tenant:" + tenantId)) {
            keys.add(PREFIX + "bytes:index:" + identity); keys.add(PREFIX + "bytes:values:" + identity);
        }
        requireAccepted(execute(RESERVE, keys, id, Long.toString(responseLimit),
                Long.toString(64L * 1024 * 1024), Long.toString(256L * 1024 * 1024), Long.toString(1024L * 1024 * 1024)));
        return new Reservation(this, List.copyOf(keys), id, responseLimit);
    }

    /** 单桶编号0..1023，散列原文与IP不成为Redis动态键、诊断事件或指标标签。 */
    static int sourceBucket(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank() || remoteAddr.length() > 128) throw unavailable();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(remoteAddr.getBytes(StandardCharsets.US_ASCII));
            return ((digest[0] & 0xff) << 8 | digest[1] & 0xff) & 1023;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("缺少SHA-256", impossible); }
    }

    /** 将Redis连接/执行故障统一为503，不带底层异常原文或键名。 */
    private Long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        try {
            Long result = redis.execute(script, keys, (Object[]) args);
            if (result == null) throw unavailable();
            return result;
        } catch (BusinessException known) { throw known; }
        catch (RuntimeException failure) {
            BusinessException unavailable = unavailable();
            unavailable.initCause(failure);
            throw unavailable;
        }
    }

    /** 只接受脚本明确成功，额度不足429，脚本不可能状态503。 */
    private static void requireAccepted(long result) {
        if (result == 0) throw limited();
        if (result != 1) throw unavailable();
    }
    /** 未显式启用的分享读取失败关闭。 */
    private void requireEnabled() { if (!properties.enabled()) throw unavailable(); }
    /** 固定限流分类，Retry-After由HTTP过滤器统一设置。 */
    private static BusinessException limited() { return new BusinessException(DashboardErrorCode.SHARE_RATE_LIMITED); }
    /** 固定依赖故障，不能把Redis不可用伪装成capability不存在。 */
    private static BusinessException unavailable() { return new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE); }

    /** 实例验证槽所有权，重复close不会扩容Semaphore。 */
    public static final class SourcePermit implements AutoCloseable {
        /** 原固定信号量。 */ private final Semaphore semaphore;
        /** 幂等归还标志。 */ private final AtomicBoolean released = new AtomicBoolean();
        /** 仅成功占槽的服务可以构造。 */ private SourcePermit(Semaphore semaphore) { this.semaphore = semaphore; }
        /** 认证失败、限流、成功响应均必须归还。 */
        @Override public void close() { if (released.compareAndSet(false, true)) semaphore.release(); }
    }

    /** 只允许一次结算的跨实例响应预留，已发送字节绝不退款。 */
    public static final class Reservation implements AutoCloseable {
        /** 原保护服务。 */ private final DashboardShareProtectionService owner;
        /** 仅包含已经授权内部ID的键。 */ private final List<String> keys;
        /** 本次随机预留身份，不来自token。 */ private final String id;
        /** 端点响应最大值。 */ private final long reserved;
        /** 结算互斥，不允许close把已收费响应再退款。 */ private boolean completed;
        /** 只由成功原子预留创建。 */
        private Reservation(DashboardShareProtectionService owner, List<String> keys, String id, long reserved) {
            this.owner = owner; this.keys = keys; this.id = id; this.reserved = reserved;
        }
        /** 缓冲编码完成但尚未输出网络时调用；超限不截断成看似完整的正文。 */
        public synchronized void finish(long actualBytes) {
            if (completed) throw new IllegalStateException("分享响应已经结算");
            if (actualBytes < 0 || actualBytes > reserved) throw limited();
            requireAccepted(owner.execute(SETTLE, keys, id, Long.toString(actualBytes)));
            completed = true;
        }
        /** 未发送失败全退；Redis故障保守保留到TTL，不能覆盖原业务异常。 */
        @Override public synchronized void close() {
            if (!completed) {
                completed = true;
                try { owner.execute(SETTLE, keys, id, "0"); }
                catch (BusinessException unavailable) { /* 原预留随窗口过期，故障不假报退款成功。 */ }
            }
        }
    }
}
