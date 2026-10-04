package com.things.link.project.application.plan;

import com.things.link.project.application.QuotaPolicyTemplateChanged;
import com.things.link.project.domain.plan.EffectiveEntitlement;
import com.things.link.project.domain.plan.EffectiveEntitlementProvider;
import com.things.link.project.domain.plan.EffectiveEntitlementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带 TTL 与模板版本失效的功能权益提供者（S14-1b）。
 *
 * <p>失效事件复用 S7 的统一协议：{@link QuotaPolicyTemplateChanged} 携带的
 * {@code policyId}/{@code policyVersion} 正是产品修订版 {@code quota_policy_id} 指向的模板行版本，
 * 因此本缓存不需要第二套版本方案，也不发布自己的 Redis 事件。缓存只优化读取，不保存订单或订阅事实；
 * 修订版模板不可变，事件丢失由 30 秒 TTL 回源覆盖。
 */
@Service
public class CachedEffectiveEntitlementProvider implements EffectiveEntitlementProvider {

    /** TTL 是漏掉 Redis Pub/Sub 事件时的收敛上界，不应替代版本事件。 */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    /** 权益缓存的硬上限；目录修订版数量远小于该值，仍做有界淘汰以避免异常调用耗尽堆内存。 */
    static final int MAX_ENTRIES = 10_000;

    /** 权益权威读取仓储。 */
    private final EffectiveEntitlementRepository repository;
    /** 可注入时钟，避免缓存边界测试依赖真实等待。 */
    private final Clock clock;
    /** 以产品修订版 ID 为键的本机快照；条目数受目录修订版数量约束且 TTL 会回源。 */
    private final Map<UUID, CacheEntry> entries = new ConcurrentHashMap<>();
    /** 有界淘汰的短临界区；不会按 TTL 主动删除 last-known-good。 */
    private final Object evictionMonitor = new Object();

    /**
     * Spring 生产构造器。
     *
     * @param repository 权益权威读取仓储
     */
    @Autowired
    public CachedEffectiveEntitlementProvider(EffectiveEntitlementRepository repository) {
        this(repository, Clock.systemUTC());
    }

    /**
     * 可控时钟构造器，供不启动 Spring 的缓存单测使用。
     *
     * @param repository 权益权威读取仓储
     * @param clock UTC 时钟
     */
    CachedEffectiveEntitlementProvider(EffectiveEntitlementRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public EffectiveEntitlement resolvePlanRevision(UUID planRevisionId) {
        if (planRevisionId == null) {
            throw new IllegalArgumentException("产品修订版 ID 不能为空");
        }
        Instant now = clock.instant();
        CacheEntry entry = entries.get(planRevisionId);
        if (entry != null && !entry.stale && entry.loadedAt.plus(CACHE_TTL).isAfter(now)) {
            return entry.entitlement;
        }
        try {
            EffectiveEntitlement loaded = repository.findByPlanRevisionId(planRevisionId)
                    .orElseThrow(() -> new IllegalArgumentException("产品修订版不存在或未绑定配额模板"));
            if (entry != null && loaded.quotaPolicyVersion() < entry.desiredPolicyVersion) {
                // 事件已到但数据库副本尚未追上：保留 LKG，避免用旧版本制造缓存回滚。
                return entry.entitlement;
            }
            put(planRevisionId, new CacheEntry(loaded, now, false, loaded.quotaPolicyVersion()));
            return loaded;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (entry != null) {
                return entry.entitlement;
            }
            throw new IllegalStateException("无法解析产品修订版的权益投影", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public EffectiveEntitlement resolvePlanRevision(String revisionCode, String planCode) {
        if (revisionCode == null || planCode == null) {
            throw new IllegalArgumentException("产品修订版标识与档位编码不能为空");
        }
        Instant now = clock.instant();
        EffectiveEntitlement loaded = repository.findByRevisionAndPlan(revisionCode, planCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "产品修订版或档位不存在: " + revisionCode + "/" + planCode));
        CacheEntry entry = entries.get(loaded.planRevisionId());
        if (entry == null || loaded.quotaPolicyVersion() >= entry.desiredPolicyVersion) {
            put(loaded.planRevisionId(), new CacheEntry(loaded, now, false, loaded.quotaPolicyVersion()));
        }
        return loaded;
    }

    /**
     * 接收共享模板更新事件，只标记本机仍引用该模板的权益条目。
     *
     * @param changed 已验证的模板版本事件
     */
    public void markStale(QuotaPolicyTemplateChanged changed) {
        entries.replaceAll((revisionId, entry) ->
                entry.entitlement.quotaPolicyId().equals(changed.policyId())
                        && changed.policyVersion() > entry.desiredPolicyVersion
                        ? new CacheEntry(entry.entitlement, entry.loadedAt, true, changed.policyVersion())
                        : entry);
    }

    /**
     * 清空本机缓存，仅供生命周期测试使用；不发布跨实例事件。
     */
    void clearLocalCache() {
        entries.clear();
    }

    /** 有界写入权益缓存；容量满时按最旧加载时间淘汰。 */
    private void put(UUID revisionId, CacheEntry entry) {
        synchronized (evictionMonitor) {
            if (!entries.containsKey(revisionId) && entries.size() >= MAX_ENTRIES) {
                entries.entrySet().stream()
                        .min(java.util.Comparator.comparing(candidate -> candidate.getValue().loadedAt))
                        .map(Map.Entry::getKey)
                        .ifPresent(entries::remove);
            }
            entries.put(revisionId, entry);
        }
    }

    /** 本机权益缓存项；{@code stale} 只要求下次读取回源而不抹除 LKG。 */
    private record CacheEntry(EffectiveEntitlement entitlement, Instant loadedAt, boolean stale,
                              long desiredPolicyVersion) {
    }
}
