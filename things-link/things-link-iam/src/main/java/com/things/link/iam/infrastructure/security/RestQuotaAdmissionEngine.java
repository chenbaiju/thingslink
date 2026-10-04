package com.things.link.iam.infrastructure.security;
import com.things.link.iam.application.*;
import com.things.link.project.application.*;
import com.things.link.shared.id.Uuid7;
import org.slf4j.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.*;
import java.util.*;
/** ADR0172共享REST准入内核，无Servlet或ThreadLocal身份依赖。 */
final class RestQuotaAdmissionEngine {
    /**
     * Redis 服务端时间驱动的单键令牌桶脚本。
     *
     * <p>架构文档 8.1.1 明确 REST 短窗使用原子令牌桶而非固定窗口：固定窗口边界会让客户端在
     * 相邻两个窗口各打满一次，从而形成接近两倍上限的突发。令牌、补充时间和 TTL 必须同脚本更新，
     * 否则多节点并发会把本应共享的额度拆开。</p>
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local now_ms = (now[1] * 1000) + math.floor(now[2] / 1000)
            local capacity = tonumber(ARGV[1])
            local refill_per_second = tonumber(ARGV[2])
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens')) or capacity
            local updated_at = tonumber(redis.call('HGET', KEYS[1], 'updated_at')) or now_ms
            local elapsed = math.max(0, now_ms - updated_at)
            tokens = math.min(capacity, tokens + elapsed * refill_per_second / 1000)
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_at', now_ms)
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            if tokens < 1 then
                return 0
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens - 1, 'updated_at', now_ms)
            return 1
            """, Long.class);

    /** 控制台 REST 配额 Redis 键的专用前缀，避免与认证限流或数据面令牌桶混淆。 */
    private static final String KEY_PREFIX = "quota:rest:rate:";
    /** 账号突发保护窗口。 */
    private static final Duration ACCOUNT_WINDOW = Duration.ofSeconds(1);
    /** 项目与租户共享兜底窗口。 */
    private static final Duration PROJECT_AND_TENANT_WINDOW = Duration.ofMinutes(1);
    /** project 提供者暂不可用时，仍保持有限保护而不把故障解释为无限额的读秒窗口。 */
    private static final long SAFE_DEFAULT_READ_PER_SECOND = 20L;
    /** project 提供者暂不可用时的写秒窗口，写操作更保守以保护数据库与外部副作用。 */
    private static final long SAFE_DEFAULT_WRITE_PER_SECOND = 10L;

    /** Redis 故障必须可观测，但日志不得包含账号、项目或租户等可枚举标识。 */
    private static final Logger log = LoggerFactory.getLogger(RestQuotaAdmissionEngine.class);

    private final RestQuotaPolicyResolver policyResolver;
    private final StringRedisTemplate redis;
    private final RestQuotaRateLimitMetrics metrics;
    private final ProjectDailyQuotaDecisionService dailyQuotaDecisionService;
    private final ProjectUsageFactRecorder usageFactRecorder;
    private final boolean dailyUsageRecordingEnabled;
    RestQuotaAdmissionEngine(RestQuotaPolicyResolver resolver,StringRedisTemplate redis,RestQuotaRateLimitMetrics metrics,
            ProjectDailyQuotaDecisionService daily,ProjectUsageFactRecorder recorder,boolean recording){
        this.policyResolver=resolver;this.redis=redis;this.metrics=metrics;this.dailyQuotaDecisionService=daily;
        this.usageFactRecorder=recorder;this.dailyUsageRecordingEnabled=recording;
    }
    /** Console的owner/key为空；Key入口两者同时提供，身份不能来自请求体。 */
    Decision admit(UUID account,UUID project,UUID owner,UUID key,RestQuotaRateLimitMetrics.RequestKind kind){
        if(account==null||project==null||(owner==null)!=(key==null))throw new IllegalArgumentException("REST配额身份不完整");
        Optional<RestQuotaPolicy> policy=resolvePolicy(project,kind);
        if(owner!=null){
            if(policy.isPresent()&&!owner.equals(policy.get().ownerTenantId()))throw new IllegalStateException("公开配额归属不匹配");
            if(policy.isEmpty()){
                var safe=com.things.link.project.application.EffectiveQuotaPolicy.safeDefault(owner);
                policy=Optional.of(new RestQuotaPolicy(owner,safe.restApiReadRatePerSecond(),safe.restApiWriteRatePerSecond(),safe.restApiReadRatePerMinute(),safe.restApiWriteRatePerMinute()));
            }
        }
        if(policy.isPresent()&&kind==RestQuotaRateLimitMetrics.RequestKind.WRITE&&dailyWriteRejected(policy.get(),project))
            return rejected(Decision.rejected(RestQuotaRateLimitMetrics.LimitScope.DAILY_SHARED,1),kind);
        if(key!=null){
            Decision perKey=acquireOne(KEY_PREFIX+kind.name().toLowerCase(java.util.Locale.ROOT)+":key:"+key,
                    policy.orElseThrow().accountPerSecond(kind==RestQuotaRateLimitMetrics.RequestKind.READ),ACCOUNT_WINDOW,
                    RestQuotaRateLimitMetrics.LimitScope.KEY_SECOND,kind);
            if(!perKey.allowed())return rejected(perKey,kind);
        }
        Decision decision=policy.isPresent()?acquire(account,project,policy.get(),kind):acquireAccountSafetyBucket(account,kind);
        if(!decision.allowed())return rejected(decision,kind);
        if(policy.isPresent()&&!recordUsage(policy.get(),project,kind))return rejected(Decision.rejected(RestQuotaRateLimitMetrics.LimitScope.DAILY_SHARED,1),kind);
        return decision;
    }
    private Decision rejected(Decision decision,RestQuotaRateLimitMetrics.RequestKind kind){
        metrics.recordRejected(kind,decision.scope());return decision;
    }
    /** 写请求达到硬限/降级，或权威日额度不可判定时 fail-closed；读请求不走此门禁。 */
    private boolean dailyWriteRejected(RestQuotaPolicy policy, UUID projectId) {
        try {
            QuotaStatus status = dailyQuotaDecisionService.decideTrustedProject(
                    policy.ownerTenantId(), projectId, QuotaMetric.REST_API_CALL);
            return status == QuotaStatus.HARD_LIMIT || status == QuotaStatus.DEGRADED;
        } catch (RuntimeException exception) {
            log.warn("REST 写请求日额度无法从 PostgreSQL 权威事实判定，本次 fail-closed", exception);
            return true;
        }
    }

    /**
     * 保存一次允许进入 MVC 的业务 REST 事实。写请求无法计量时拒绝，读请求按可用性优先放行。
     */
    private boolean recordUsage(RestQuotaPolicy policy, UUID projectId,
                                RestQuotaRateLimitMetrics.RequestKind kind) {
        if (!dailyUsageRecordingEnabled) {
            return true;
        }
        try {
            if (usageFactRecorder.record(policy.ownerTenantId(), projectId, QuotaMetric.REST_API_CALL,
                    Uuid7.generate().toString(), Instant.now())) {
                return true;
            }
        } catch (RuntimeException exception) {
            log.warn("REST 日用量事实保存失败 kind={}，写请求 fail-closed、读请求 fail-open", kind, exception);
        }
        // 架构文档8.1.1及ADR0093：false只表示未保存事实（如项目已归档），不能冒充短窗超限。
        // GET/HEAD仍交给MVC校验成员及生命周期；写请求保持fail-closed，指标只计实际降级放行。
        if (kind == RestQuotaRateLimitMetrics.RequestKind.READ) {
            metrics.recordFailOpen(kind);
            return true;
        }
        return false;
    }

    /**
     * @param projectId 当前 JWT 选中的项目 ID
     * @param kind 当前请求读写类别
     * @return 仅在已知项目所属租户时返回有效套餐；不可解析时为空
     */
    private Optional<RestQuotaPolicy> resolvePolicy(UUID projectId, RestQuotaRateLimitMetrics.RequestKind kind) {
        try {
            Optional<RestQuotaPolicy> resolved = policyResolver.resolve(projectId);
            if (resolved.isPresent()) {
                return resolved;
            }
        } catch (RuntimeException exception) {
            // 项目归属不可解析时绝不能把 JWT tid 冒充 owner tenant；跨租户协作者会因此扣错共享池。
            log.warn("REST 项目所属租户不可解析，项目与租户桶本次 fail-open", exception);
        }
        metrics.recordSafeDefault(kind);
        metrics.recordFailOpen(kind);
        return Optional.empty();
    }

    /**
     * @param accountId 已认证账号 ID
     * @param projectId 已选项目 ID
     * @param policy 项目所属租户的有效套餐
     * @param kind 当前请求读写类别
     * @return 首个拒绝窗口或允许结果
     */
    private Decision acquire(UUID accountId, UUID projectId, RestQuotaPolicy policy,
                                  RestQuotaRateLimitMetrics.RequestKind kind) {
        boolean readRequest = kind == RestQuotaRateLimitMetrics.RequestKind.READ;
        Decision account = acquireOne(KEY_PREFIX + kind.name().toLowerCase() + ":account:" + accountId,
                policy.accountPerSecond(readRequest), ACCOUNT_WINDOW,
                RestQuotaRateLimitMetrics.LimitScope.ACCOUNT_SECOND, kind);
        if (!account.allowed()) {
            return account;
        }
        Decision project = acquireOne(KEY_PREFIX + kind.name().toLowerCase() + ":project:" + projectId,
                policy.projectAndTenantPerMinute(readRequest), PROJECT_AND_TENANT_WINDOW,
                RestQuotaRateLimitMetrics.LimitScope.PROJECT_MINUTE, kind);
        if (!project.allowed()) {
            return project;
        }
        return acquireOne(KEY_PREFIX + kind.name().toLowerCase() + ":tenant:" + policy.ownerTenantId(),
                policy.projectAndTenantPerMinute(readRequest), PROJECT_AND_TENANT_WINDOW,
                RestQuotaRateLimitMetrics.LimitScope.TENANT_MINUTE, kind);
    }

    /**
     * 项目所属租户无法确认时的最小安全降级。
     *
     * <p>普通业务 REST 按架构文档 8.1.1 可以对不可判定的辅助短窗 fail-open，但账号突发桶不需要
     * owner tenant 即可安全计算，因而继续使用有限默认值。项目与租户桶必须跳过，不能用 JWT {@code tid}
     * 代替 owner tenant，否则跨租户协作者会错误燃烧自己的租户额度。</p>
     *
     * @param accountId 已认证账号 ID
     * @param kind 当前请求读写类别
     * @return 账号安全桶的裁决
     */
    private Decision acquireAccountSafetyBucket(UUID accountId, RestQuotaRateLimitMetrics.RequestKind kind) {
        Long limit = kind == RestQuotaRateLimitMetrics.RequestKind.READ
                ? SAFE_DEFAULT_READ_PER_SECOND : SAFE_DEFAULT_WRITE_PER_SECOND;
        return acquireOne(KEY_PREFIX + kind.name().toLowerCase() + ":account:" + accountId, limit,
                ACCOUNT_WINDOW, RestQuotaRateLimitMetrics.LimitScope.ACCOUNT_SECOND, kind);
    }

    /**
     * @param key 固定类别加身份键组成的 Redis 令牌桶键，不进入 Prometheus 标签
     * @param limit 显式套餐上限；{@code null} 为不限，0 为立即拒绝
     * @param window 令牌桶从空桶补满所对应的速率窗口
     * @param scope 指标与错误重试提示所对应的固定层级
     * @param kind 当前请求读写类别
     * @return 单个窗口的计数裁决
     */
    private Decision acquireOne(String key, Long limit, Duration window,
                                     RestQuotaRateLimitMetrics.LimitScope scope,
                                     RestQuotaRateLimitMetrics.RequestKind kind) {
        if (limit == null) {
            return Decision.permitted();
        }
        if (limit == 0L) {
            return Decision.rejected(scope, 1L);
        }
        long retryAfter = retryAfterSeconds(limit, window);
        try {
            Long allowed = redis.execute(ACQUIRE_SCRIPT, List.of(key), Long.toString(limit),
                    Double.toString(limit / (double) window.toSeconds()), Long.toString(window.toMillis()));
            if (Long.valueOf(1L).equals(allowed)) {
                return Decision.permitted();
            }
            if (allowed == null) {
                // Lua 返回空值意味着限流设施未给出可审计裁决；按普通业务 fail-open，但不能静默吞掉指标。
                metrics.recordFailOpen(kind);
                log.warn("REST 配额 Redis 令牌桶返回空裁决，本次请求降级放行 scope={}", scope);
                return Decision.permitted();
            }
            return Decision.rejected(scope, retryAfter);
        } catch (RuntimeException exception) {
            // 普通控制台 API 的辅助短窗计数故障遵循架构文档 8.1.1 fail-open，不阻断已认证业务。
            log.warn("REST 配额 Redis 令牌桶不可用，本次请求降级放行 scope={}", scope, exception);
            metrics.recordFailOpen(kind);
            return Decision.permitted();
        }
    }

    /**
     * @param limit 当前令牌桶容量和每个速率窗口内补充量
     * @param window 套餐声明的补充窗口
     * @return 取得下一个令牌至少需要等待的整秒数
     */
    private static long retryAfterSeconds(long limit, Duration window) {
        return Math.max(1L, (long) Math.ceil(window.toMillis() / (double) limit / 1_000D));
    }

    /** 单个窗口的允许或拒绝结果。 */
    record Decision(boolean allowed, RestQuotaRateLimitMetrics.LimitScope scope,
                                 long retryAfterSeconds) {

        /** @return 不携带拒绝层级的允许结果 */
        private static Decision permitted() {
            return new Decision(true, null, 0L);
        }

        /**
         * @param scope 首个拒绝的固定窗口层级
         * @param retryAfterSeconds 建议等待秒数
         * @return 拒绝结果
         */
        private static Decision rejected(RestQuotaRateLimitMetrics.LimitScope scope, long retryAfterSeconds) {
            return new Decision(false, scope, retryAfterSeconds);
        }
    }
}
