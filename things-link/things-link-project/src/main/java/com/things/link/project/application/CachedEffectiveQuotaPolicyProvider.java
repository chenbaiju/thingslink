package com.things.link.project.application;

import com.things.link.project.domain.EffectiveQuotaPolicyRepository;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.cache.CacheInvalidationMetrics;
import com.things.link.support.cache.CacheResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带 TTL、版本失效和 最近一次有效值 的有效策略提供者。
 *
 * <p>缓存只优化控制面策略读取，不保存计费事实。Redis Pub/Sub 事件只标记条目 stale，保留 LKG 以便
 * PostgreSQL 短暂故障时继续施加有限保护；丢失事件由 TTL 回源覆盖。
 */
@Service
public class CachedEffectiveQuotaPolicyProvider implements EffectiveQuotaPolicyProvider {

    /** TTL 是漏掉 Redis Pub/Sub 事件时的收敛上界，不应替代版本事件。 */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    /** 活跃租户策略缓存的硬上限，避免攻击者以伪造可信调用链耗尽堆内存。 */
    static final int MAX_TENANT_ENTRIES = 10_000;
    /** 已授权项目到所有者租户的路由缓存硬上限。 */
    static final int MAX_PROJECT_ENTRIES = 20_000;
    /** 设备协议项目归属路由的硬上限；与控制面项目缓存分开，防止公网 topic 触发无界增长。 */
    static final int MAX_DEVICE_PROJECT_ENTRIES = 20_000;
    /** 产品修订版模板缓存硬上限；目录修订版数量远小于该值。 */
    static final int MAX_PLAN_REVISION_ENTRIES = 10_000;

    /** 权威策略读取仓储。 */
    private final EffectiveQuotaPolicyRepository repository;
    /** 统一低基数指标门面。 */
    private final QuotaRuntimeMetrics metrics;
    /** S7-5 三类缓存统一指标；与既有配额细分指标并行保留兼容性。 */
    private final CacheInvalidationMetrics cacheMetrics;
    /** 可注入时钟，避免缓存边界测试依赖真实等待。 */
    private final Clock clock;
    /** 以租户为键的本机策略快照；条目数受活跃租户数约束且 TTL 会回源。 */
    private final Map<UUID, CacheEntry> entries = new ConcurrentHashMap<>();
    /** 项目到所有者租户的短 TTL 路由缓存，避免每个控制面请求都重新 join 项目表。 */
    private final Map<UUID, ProjectTenantEntry> projectTenants = new ConcurrentHashMap<>();
    /** 仅由已确权设备二元组填充的项目到 owner tenant 路由缓存。 */
    private final Map<UUID, DeviceProjectTenantEntry> deviceProjectTenants = new ConcurrentHashMap<>();
    /** S14-1b 产品修订版模板缓存；键是修订版 ID，值与租户缓存共用同一套 TTL/版本失效语义。 */
    private final Map<UUID, PlanRevisionEntry> planRevisionEntries = new ConcurrentHashMap<>();
    /** 有界淘汰跨两张 Map 的短临界区；不会按 TTL 主动删除 LKG。 */
    private final Object evictionMonitor = new Object();

    /**
     * Spring 生产构造器。
     *
     * @param repository 权威策略读取仓储
     * @param metrics 统一低基数指标门面
     */
    @Autowired
    public CachedEffectiveQuotaPolicyProvider(EffectiveQuotaPolicyRepository repository,
                                              QuotaRuntimeMetrics metrics,
                                              CacheInvalidationMetrics cacheMetrics) {
        this(repository, metrics, cacheMetrics, Clock.systemUTC());
    }

    /**
     * 可控时钟构造器，供不启动 Spring 的缓存单测使用。
     *
     * @param repository 权威策略读取仓储
     * @param metrics 统一低基数指标门面
     * @param clock UTC 时钟
     */
    CachedEffectiveQuotaPolicyProvider(EffectiveQuotaPolicyRepository repository,
                                       QuotaRuntimeMetrics metrics, Clock clock) {
        this(repository, metrics, null, clock);
    }

    /** 测试可注入时钟与统一指标的完整构造器。 */
    CachedEffectiveQuotaPolicyProvider(EffectiveQuotaPolicyRepository repository,
                                       QuotaRuntimeMetrics metrics,
                                       CacheInvalidationMetrics cacheMetrics, Clock clock) {
        this.repository = repository;
        this.metrics = metrics;
        this.cacheMetrics = cacheMetrics;
        this.clock = clock;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public EffectiveQuotaPolicy resolveTrustedTenant(UUID trustedTenantId) {
        requireTrustedId(trustedTenantId, "租户");
        return resolve(trustedTenantId, () -> repository.findByTenantId(trustedTenantId)
                .orElseThrow(() -> new IllegalArgumentException("可信租户不存在或未绑定有效配额策略")));
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public EffectiveQuotaPolicy resolveTrustedProject(UUID trustedProjectId) {
        requireTrustedId(trustedProjectId, "项目");
        if (!trustedProjectId.equals(TenantContext.requireProjectId())) {
            throw new IllegalArgumentException("可信项目 ID 必须与当前授权项目一致");
        }
        return resolveByProject(trustedProjectId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public EffectiveQuotaPolicy resolveTrustedDeviceProject(UUID trustedTenantId, UUID trustedProjectId) {
        requireTrustedId(trustedTenantId, "设备归属租户");
        requireTrustedId(trustedProjectId, "设备归属项目");
        Instant now = clock.instant();
        DeviceProjectTenantEntry route = deviceProjectTenants.get(trustedProjectId);
        if (route != null && !route.tenantId.equals(trustedTenantId)) {
            throw new IllegalArgumentException("设备确权租户与项目归属不匹配");
        }
        CacheEntry entry = entries.get(trustedTenantId);
        if (route != null && route.loadedAt.plus(CACHE_TTL).isAfter(now)
                && entry != null && !entry.stale && entry.loadedAt.plus(CACHE_TTL).isAfter(now)) {
            recordCache(QuotaRuntimeMetrics.CacheResult.HIT);
            return entry.policy;
        }
        try {
            EffectiveQuotaPolicy loaded = repository.findByDeviceProject(trustedTenantId, trustedProjectId)
                    .orElseThrow(() -> new IllegalArgumentException("设备确权租户与项目归属不匹配或策略无效"));
            putDeviceProjectEntry(trustedProjectId, new DeviceProjectTenantEntry(trustedTenantId, now));
            if (entry != null && !meetsDesired(loaded, entry)) {
                recordCache(QuotaRuntimeMetrics.CacheResult.VERSION_LAG);
                return entry.policy;
            }
            putTenantEntry(trustedTenantId, new CacheEntry(loaded, now, false,
                    loaded.assignmentVersion(), loaded.policyVersion()));
            recordCache(QuotaRuntimeMetrics.CacheResult.LOAD);
            return loaded;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (entry != null) {
                recordCache(QuotaRuntimeMetrics.CacheResult.LAST_KNOWN_GOOD);
                return entry.policy;
            }
            recordCache(QuotaRuntimeMetrics.CacheResult.SAFE_DEFAULT);
            return EffectiveQuotaPolicy.safeDefault(trustedTenantId);
        }
    }

    /**
     * 接收已验证的失效事件。
     *
     * <p>租户绑定版本优先：绑定版本升高时允许模板版本降低；绑定版本相同才比较模板版本。事件绝不删除
     * LKG，避免 PostgreSQL 短故障立刻把热实例降为无状态冷启动。
     *
     * @param changed Redis Pub/Sub 或本机事务提交后产生的失效事件
     */
    public void markStale(QuotaPolicyChanged changed) {
        entries.computeIfPresent(changed.tenantId(), (ignored, entry) ->
                isNewer(changed, entry.desiredAssignmentVersion, entry.desiredPolicyVersion)
                        ? entry.markStale(changed.assignmentVersion(), changed.policyVersion()) : entry);
    }

    /**
     * 接收共享模板更新事件，只标记本机仍绑定该模板的缓存条目。
     *
     * <p>S14-1b 的产品修订版模板缓存也复用同一事件：事件携带的模板 ID 与版本正是
     * {@code sys_plan_revision.quota_policy_id} 指向的那一行，因此不需要第二套版本方案。
     *
     * @param changed 已验证的模板版本事件
     */
    public void markStale(QuotaPolicyTemplateChanged changed) {
        entries.replaceAll((tenantId, entry) -> {
            // 若已收到更高绑定版本，旧模板已不再是该租户目标，模板事件不能反向污染该目标。
            if (!entry.policy.policyId().equals(changed.policyId())
                    || entry.desiredAssignmentVersion != entry.policy.assignmentVersion()
                    || changed.policyVersion() <= entry.desiredPolicyVersion) {
                return entry;
            }
            return entry.markStale(entry.desiredAssignmentVersion, changed.policyVersion());
        });
        planRevisionEntries.replaceAll((revisionId, entry) ->
                entry.policy.policyId().equals(changed.policyId())
                        && changed.policyVersion() > entry.desiredPolicyVersion
                        ? new PlanRevisionEntry(entry.policy, entry.loadedAt, true, changed.policyVersion())
                        : entry);
    }

    /**
     * 按产品修订版读取其冻结配额模板（S14-1b）。
     *
     * <p>复用 S7 的 TTL、版本失效与 最近一次有效值 语义；修订版模板不可变，因此缓存只在模板版本
     * 前进时回源。这里没有可信租户，读取失败且无 LKG 时拒绝服务，绝不用 {@code tenantId=null} 的
     * 安全默认冒充某个套餐。
     *
     * @param planRevisionId 产品修订版 ID
     * @return 该修订版的配额模板快照
     */
    @Override
    public EffectiveQuotaPolicy resolvePlanRevision(UUID planRevisionId) {
        requireTrustedId(planRevisionId, "产品修订版");
        Instant now = clock.instant();
        PlanRevisionEntry entry = planRevisionEntries.get(planRevisionId);
        if (entry != null && !entry.stale && entry.loadedAt.plus(CACHE_TTL).isAfter(now)) {
            recordCache(QuotaRuntimeMetrics.CacheResult.HIT);
            return entry.policy;
        }
        try {
            EffectiveQuotaPolicy loaded = repository.findByPlanRevision(planRevisionId)
                    .orElseThrow(() -> new IllegalArgumentException("产品修订版不存在或未绑定配额模板"));
            if (entry != null && loaded.policyVersion() < entry.desiredPolicyVersion) {
                recordCache(QuotaRuntimeMetrics.CacheResult.VERSION_LAG);
                return entry.policy;
            }
            putPlanRevisionEntry(planRevisionId,
                    new PlanRevisionEntry(loaded, now, false, loaded.policyVersion()));
            recordCache(QuotaRuntimeMetrics.CacheResult.LOAD);
            return loaded;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (entry != null) {
                recordCache(QuotaRuntimeMetrics.CacheResult.LAST_KNOWN_GOOD);
                return entry.policy;
            }
            throw new IllegalStateException("无法解析产品修订版的配额模板", exception);
        }
    }

    /**
     * 清空本机缓存，仅供生命周期测试和进程优雅关闭后的诊断使用；不发布跨实例事件。
     */
    void clearLocalCache() {
        entries.clear();
        projectTenants.clear();
        deviceProjectTenants.clear();
        planRevisionEntries.clear();
    }

    /**
     * 按项目读取时先获得可信的项目所属租户，再使用同一租户缓存键，确保协作者共享项目所有者套餐。
     */
    private EffectiveQuotaPolicy resolveByProject(UUID trustedProjectId) {
        Instant now = clock.instant();
        ProjectTenantEntry projectTenant = projectTenants.get(trustedProjectId);
        if (projectTenant != null && projectTenant.loadedAt.plus(CACHE_TTL).isAfter(now)) {
            return resolve(projectTenant.tenantId, () -> repository.findByTenantId(projectTenant.tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("可信租户不存在或未绑定有效配额策略")));
        }
        try {
            EffectiveQuotaPolicy loaded = repository.findByProjectId(trustedProjectId)
                    .orElseThrow(() -> new IllegalArgumentException("可信项目不存在或未绑定有效配额策略"));
            CacheEntry tenantEntry = entries.get(loaded.tenantId());
            if (tenantEntry != null && !meetsDesired(loaded, tenantEntry)) {
                recordCache(QuotaRuntimeMetrics.CacheResult.VERSION_LAG);
                putProjectEntry(trustedProjectId, new ProjectTenantEntry(loaded.tenantId(), now));
                return tenantEntry.policy;
            }
            putTenantEntry(loaded.tenantId(), new CacheEntry(loaded, now, false,
                    loaded.assignmentVersion(), loaded.policyVersion()));
            putProjectEntry(trustedProjectId, new ProjectTenantEntry(loaded.tenantId(), now));
            recordCache(QuotaRuntimeMetrics.CacheResult.LOAD);
            return loaded;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (projectTenant != null) {
                return resolve(projectTenant.tenantId, () -> repository.findByTenantId(projectTenant.tenantId)
                        .orElseThrow(() -> new IllegalArgumentException("可信租户不存在或未绑定有效配额策略")));
            }
            // 项目解析也可能在数据库短暂故障；项目 ID 不能安全地猜出租户，故不能构造错误归属的默认策略。
            throw new IllegalStateException("无法解析可信项目所属租户的配额策略", exception);
        }
    }

    /**
     * 读取一条租户缓存记录，必要时回源并在故障时保留最后已知策略。
     */
    private EffectiveQuotaPolicy resolve(UUID tenantId, PolicyLoader loader) {
        CacheEntry entry = entries.get(tenantId);
        Instant now = clock.instant();
        if (entry != null && !entry.stale && entry.loadedAt.plus(CACHE_TTL).isAfter(now)) {
            recordCache(QuotaRuntimeMetrics.CacheResult.HIT);
            return entry.policy;
        }
        try {
            EffectiveQuotaPolicy loaded = loader.load();
            if (entry != null && !meetsDesired(loaded, entry)) {
                // 例如失效事件已到、数据库副本尚未追上。把旧版本写回会制造真正的缓存回滚。
                recordCache(QuotaRuntimeMetrics.CacheResult.VERSION_LAG);
                return entry.policy;
            }
            putTenantEntry(tenantId, new CacheEntry(loaded, now, false,
                    loaded.assignmentVersion(), loaded.policyVersion()));
            recordCache(QuotaRuntimeMetrics.CacheResult.LOAD);
            return loaded;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (entry != null) {
                recordCache(QuotaRuntimeMetrics.CacheResult.LAST_KNOWN_GOOD);
                return entry.policy;
            }
            recordCache(QuotaRuntimeMetrics.CacheResult.SAFE_DEFAULT);
            return EffectiveQuotaPolicy.safeDefault(tenantId);
        }
    }

    /**
     * 比较失效事件与当前快照版本。
     *
     * @param changed 收到的失效事件
     * @param current 当前缓存策略
     * @return 事件是否严格更新，严格更新才标记 stale
     */
    private static boolean isNewer(QuotaPolicyChanged changed, long assignmentVersion, long policyVersion) {
        if (changed.assignmentVersion() != assignmentVersion) {
            return changed.assignmentVersion() > assignmentVersion;
        }
        return changed.policyVersion() > policyVersion;
    }

    /**
     * 判断权威源返回的版本是否已达到失效事件要求。assignment 版本高时允许 policy 版本低，正是租户
     * 切换到旧模板的合法语义。
     */
    private static boolean meetsDesired(EffectiveQuotaPolicy loaded, CacheEntry entry) {
        return !isNewer(new QuotaPolicyChanged(loaded.tenantId(), entry.desiredAssignmentVersion,
                entry.desiredPolicyVersion), loaded.assignmentVersion(), loaded.policyVersion());
    }

    /**
     * 统一拒绝空可信标识，避免缓存为 null 创建无法清理的伪条目。
     */
    private static void requireTrustedId(UUID id, String kind) {
        if (id == null) {
            throw new IllegalArgumentException("可信" + kind + " ID 不能为空");
        }
    }

    /** 延迟读取权威策略，避免项目路径在缓存命中时再次查询数据库。 */
    @FunctionalInterface
    private interface PolicyLoader {
        /** @return 权威策略快照 */
        EffectiveQuotaPolicy load();
    }

    /**
     * 有界地写入租户缓存。淘汰只在容量满且键为新租户时发生，按最旧加载时间移除；不会因 TTL 失效而
     * 删除 LKG，当前条目仍可在数据库故障时提供保护。
     */
    private void putTenantEntry(UUID tenantId, CacheEntry entry) {
        synchronized (evictionMonitor) {
            evictOldestIfNeeded(entries, tenantId, MAX_TENANT_ENTRIES);
            entries.put(tenantId, entry);
        }
    }

    /** 有界地写入项目路由缓存，规则同租户缓存。 */
    private void putProjectEntry(UUID projectId, ProjectTenantEntry entry) {
        synchronized (evictionMonitor) {
            evictOldestIfNeeded(projectTenants, projectId, MAX_PROJECT_ENTRIES);
            projectTenants.put(projectId, entry);
        }
    }

    /** 有界写入设备确权路由；TTL 失效时不会删除 tenant LKG，只让下次回源再次核对二元组。 */
    private void putDeviceProjectEntry(UUID projectId, DeviceProjectTenantEntry entry) {
        synchronized (evictionMonitor) {
            evictOldestIfNeeded(deviceProjectTenants, projectId, MAX_DEVICE_PROJECT_ENTRIES);
            deviceProjectTenants.put(projectId, entry);
        }
    }

    /** 有界写入产品修订版模板缓存；条目数受目录修订版数量约束，TTL 会在事件丢失时兜底回源。 */
    private void putPlanRevisionEntry(UUID revisionId, PlanRevisionEntry entry) {
        synchronized (evictionMonitor) {
            evictOldestIfNeeded(planRevisionEntries, revisionId, MAX_PLAN_REVISION_ENTRIES);
            planRevisionEntries.put(revisionId, entry);
        }
    }

    /** 同时保留配额专项指标与 S7-5 三类缓存统一指标。 */
    private void recordCache(QuotaRuntimeMetrics.CacheResult result) {
        metrics.recordCache(result);
        if (cacheMetrics == null) return;
        switch (result) {
            case HIT -> cacheMetrics.recordAccess(CacheResource.QUOTA_POLICY,
                    CacheInvalidationMetrics.AccessResult.HIT);
            case LOAD -> cacheMetrics.recordAccess(CacheResource.QUOTA_POLICY,
                    CacheInvalidationMetrics.AccessResult.LOAD);
            case LAST_KNOWN_GOOD, VERSION_LAG -> cacheMetrics.recordFallback(CacheResource.QUOTA_POLICY,
                    CacheInvalidationMetrics.FallbackResult.LAST_KNOWN_GOOD);
            case SAFE_DEFAULT -> cacheMetrics.recordFallback(CacheResource.QUOTA_POLICY,
                    CacheInvalidationMetrics.FallbackResult.SAFE_DEFAULT);
            default -> {
                // 发布、驱逐与拒绝仍由原有专项指标或统一事件消费者记录，避免重复计数。
            }
        }
    }

    /** 删除一个最旧条目以确保 map 不会无界增长。 */
    private <T> void evictOldestIfNeeded(Map<UUID, T> map, UUID incomingKey, int maximumSize) {
        if (map.containsKey(incomingKey) || map.size() < maximumSize) {
            return;
        }
        UUID oldestKey = map.entrySet().stream()
                .min(java.util.Comparator.comparing(entry -> loadedAt(entry.getValue())))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (oldestKey != null) {
            map.remove(oldestKey);
            metrics.recordCache(QuotaRuntimeMetrics.CacheResult.EVICTED);
        }
    }

    /** 取两种缓存条目的加载时刻，用于统一淘汰顺序。 */
    private static Instant loadedAt(Object entry) {
        if (entry instanceof CacheEntry tenantEntry) {
            return tenantEntry.loadedAt;
        }
        if (entry instanceof DeviceProjectTenantEntry deviceProjectEntry) {
            return deviceProjectEntry.loadedAt;
        }
        if (entry instanceof PlanRevisionEntry planRevisionEntry) {
            return planRevisionEntry.loadedAt;
        }
        return ((ProjectTenantEntry) entry).loadedAt;
    }

    /** 本机缓存项，stale 只要求下次读取回源而不抹除 LKG。 */
    private record CacheEntry(EffectiveQuotaPolicy policy, Instant loadedAt, boolean stale,
                              long desiredAssignmentVersion, long desiredPolicyVersion) {
        /** @return 保留原快照和目标版本的过期标记副本 */
        private CacheEntry markStale(long assignmentVersion, long policyVersion) {
            return new CacheEntry(policy, loadedAt, true, assignmentVersion, policyVersion);
        }
    }

    /** 项目归属租户的短 TTL 路由条目；它不含任何授权结果，调用方必须先完成项目授权。 */
    private record ProjectTenantEntry(UUID tenantId, Instant loadedAt) {
    }

    /** 设备确权已验证的 project -> owner tenant 路由；不同 tenant 命中同一 project 必须立即失败。 */
    private record DeviceProjectTenantEntry(UUID tenantId, Instant loadedAt) {
    }

    /** 产品修订版模板缓存项；{@code stale} 只要求下次读取回源，不抹除 LKG。 */
    private record PlanRevisionEntry(EffectiveQuotaPolicy policy, Instant loadedAt, boolean stale,
                                     long desiredPolicyVersion) {
    }
}
