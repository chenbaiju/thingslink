package com.things.link.project.infrastructure.cache;

import com.things.link.project.application.CachedEffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaPolicyChanged;
import com.things.link.project.application.QuotaPolicyTemplateChanged;
import com.things.link.project.application.plan.CachedEffectiveEntitlementProvider;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationHandler;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.support.cache.CacheResource;
import org.springframework.stereotype.Component;

/**
 * 把统一控制面缓存事件还原为配额与权益缓存的双版本失效语义。
 *
 * <p>S14-1b 的产品修订版权益缓存不另造事件：修订版引用的模板行版本变化时，同一
 * {@code QUOTA_POLICY/UPDATE} 事件既失效租户策略缓存，也失效按修订版缓存的权益投影。
 */
@Component
public class QuotaPolicyCacheInvalidationHandler implements CacheInvalidationHandler {
    /** 保有 assignmentVersion、policyVersion 与 LKG 的控制面缓存。 */
    private final CachedEffectiveQuotaPolicyProvider provider;
    /** 按产品修订版缓存权益投影的提供者。 */
    private final CachedEffectiveEntitlementProvider entitlementProvider;

    /**
     * @param provider 配额有效策略缓存
     * @param entitlementProvider 产品修订版权益缓存
     */
    public QuotaPolicyCacheInvalidationHandler(CachedEffectiveQuotaPolicyProvider provider,
                                               CachedEffectiveEntitlementProvider entitlementProvider) {
        this.provider = provider;
        this.entitlementProvider = entitlementProvider;
    }

    /** {@inheritDoc} */
    @Override
    public boolean handle(CacheInvalidationEvent event) {
        if (event.resource() != CacheResource.QUOTA_POLICY) return false;
        if (event.operation() == CacheInvalidationOperation.ASSIGN) {
            provider.markStale(new QuotaPolicyChanged(
                    event.resourceId(), event.version(), event.relatedVersion()));
        } else if (event.operation() == CacheInvalidationOperation.UPDATE) {
            QuotaPolicyTemplateChanged changed =
                    new QuotaPolicyTemplateChanged(event.resourceId(), event.version());
            provider.markStale(changed);
            entitlementProvider.markStale(changed);
        }
        return true;
    }
}
