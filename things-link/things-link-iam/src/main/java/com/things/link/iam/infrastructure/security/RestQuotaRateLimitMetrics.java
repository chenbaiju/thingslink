package com.things.link.iam.infrastructure.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * REST 运行时配额限流的低基数指标。
 *
 * <p>指标只含读写类别和固定窗口层级，绝不把租户、项目、账号或路径放入标签；这些 ID 的增长不受控，
 * 会先耗尽 Prometheus 时序预算并掩盖真实故障。</p>
 */
@Component
public class RestQuotaRateLimitMetrics {

    /** 限流拒绝计数器名称。 */
    static final String REJECTED = "thingslink.quota.rest.rate_limit.rejected";
    /** Redis 故障或项目归属无法确认后普通业务请求降级放行的计数器名称。 */
    static final String FAIL_OPEN = "thingslink.quota.rest.rate_limit.fail_open";
    /** 无法解析项目所属租户时仅保留账号安全桶的计数器名称。 */
    static final String SAFE_DEFAULT = "thingslink.quota.rest.policy.safe_default";

    /** 固定维度组合的拒绝计数器。 */
    private final Map<RequestKind, Map<LimitScope, Counter>> rejected = new EnumMap<>(RequestKind.class);
    /** 固定读写类别的短窗限流降级计数器。 */
    private final Map<RequestKind, Counter> failOpen = new EnumMap<>(RequestKind.class);
    /** 固定读写类别的项目/租户桶 fail-open 计数器。 */
    private final Map<RequestKind, Counter> safeDefault = new EnumMap<>(RequestKind.class);

    /**
     * @param registryProvider 可选的应用指标注册表；隔离测试没有 Actuator 时退回内存注册表
     */
    @Autowired
    public RestQuotaRateLimitMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * @param registry 指标注册表，供单元测试校验固定标签语义
     */
    public RestQuotaRateLimitMetrics(MeterRegistry registry) {
        for (RequestKind kind : RequestKind.values()) {
            Map<LimitScope, Counter> byScope = new EnumMap<>(LimitScope.class);
            for (LimitScope scope : LimitScope.values()) {
                byScope.put(scope, Counter.builder(REJECTED)
                        .description("REST 配额短窗口拒绝次数")
                        .tags("kind", kind.tagValue, "scope", scope.tagValue)
                        .register(registry));
            }
            rejected.put(kind, byScope);
            failOpen.put(kind, Counter.builder(FAIL_OPEN)
                    .description("Redis 不可用或项目归属无法确认时 REST 短窗口限流降级放行次数")
                    .tag("kind", kind.tagValue)
                    .register(registry));
            safeDefault.put(kind, Counter.builder(SAFE_DEFAULT)
                    .description("项目所属租户不可解析时 REST 仅保留账号安全桶次数")
                    .tag("kind", kind.tagValue)
                    .register(registry));
        }
    }

    /**
     * @param kind 当前请求读写类别
     * @param scope 首个命中的固定窗口层级
     */
    public void recordRejected(RequestKind kind, LimitScope scope) {
        rejected.get(kind).get(scope).increment();
    }

    /**
     * @param kind 当前请求读写类别
     */
    public void recordFailOpen(RequestKind kind) {
        failOpen.get(kind).increment();
    }

    /**
     * @param kind 当前请求读写类别
     */
    public void recordSafeDefault(RequestKind kind) {
        safeDefault.get(kind).increment();
    }

    /** REST 请求的固定读写分类。 */
    public enum RequestKind {
        /** GET/HEAD 只读请求。 */
        READ("read"),
        /** 其余可能改变服务端状态的请求。 */
        WRITE("write");

        /** Prometheus 使用的固定低基数标签值。 */
        private final String tagValue;

        /**
         * @param tagValue Prometheus 使用的固定低基数标签值
         */
        RequestKind(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** REST 配额短窗口的固定计数层级。 */
    public enum LimitScope {
        /** 独立Key秒桶，与账号及项目共享桶叠加。 */
        KEY_SECOND("key_second"),
        /** 账号秒窗口，限制单个控制台身份的突发流量。 */
        ACCOUNT_SECOND("account_second"),
        /** 项目分钟窗口，避免单项目绕过账号切换。 */
        PROJECT_MINUTE("project_minute"),
        /** 项目所属租户分钟窗口，落实租户共享套餐。 */
        TENANT_MINUTE("tenant_minute"),
        /** PostgreSQL 权威 UTC 日共享池，写请求达到硬限或无法计量时拒绝。 */
        DAILY_SHARED("daily_shared");

        /** Prometheus 使用的固定低基数标签值。 */
        private final String tagValue;

        /**
         * @param tagValue Prometheus 使用的固定低基数标签值
         */
        LimitScope(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
