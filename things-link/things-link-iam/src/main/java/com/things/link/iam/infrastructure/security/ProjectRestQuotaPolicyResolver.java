package com.things.link.iam.infrastructure.security;

import com.things.link.iam.application.RestQuotaPolicy;
import com.things.link.iam.application.RestQuotaPolicyResolver;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * project 有效套餐提供者到 IAM REST 限流端口的单向适配器。
 *
 * <p>IAM 允许依赖 project 的 application 包；适配器将项目归属租户解析留在 project 模块，避免
 * 跨租户协作者把 JWT 自身 {@code tid} 误当作共享套餐键。测试或项目提供者尚未装配时返回空，
 * 限流器会改用有限代码安全默认值而不是无限额。</p>
 */
@Component
public class ProjectRestQuotaPolicyResolver implements RestQuotaPolicyResolver {

    /** project 模块的跨模块公开策略端口；使用可选注入让 IAM 独立测试不必启动 project 实现。 */
    private final ObjectProvider<EffectiveQuotaPolicyProvider> provider;

    /**
     * @param provider project 模块的可选有效策略提供者
     */
    public ProjectRestQuotaPolicyResolver(ObjectProvider<EffectiveQuotaPolicyProvider> provider) {
        this.provider = provider;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<RestQuotaPolicy> resolve(UUID projectId) {
        EffectiveQuotaPolicyProvider quotaPolicyProvider = provider.getIfAvailable();
        if (quotaPolicyProvider == null) {
            return Optional.empty();
        }
        EffectiveQuotaPolicy policy = quotaPolicyProvider.resolveTrustedProject(projectId);
        return Optional.of(new RestQuotaPolicy(policy.tenantId(), policy.restApiReadRatePerSecond(),
                policy.restApiWriteRatePerSecond(), policy.restApiReadRatePerMinute(),
                policy.restApiWriteRatePerMinute()));
    }
}
