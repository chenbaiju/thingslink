package com.things.link.project.infrastructure.cache;

import com.things.link.project.application.CachedEffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaPolicyChanged;
import com.things.link.project.application.QuotaPolicyTemplateChanged;
import com.things.link.project.application.plan.CachedEffectiveEntitlementProvider;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.support.cache.CacheResource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 控制面适配必须保留租户绑定与模板版本的不同排序维度，并把模板事件同时投递给权益缓存。 */
class QuotaPolicyCacheInvalidationHandlerTests {
    /** 租户切换模板时，即使模板版本更低也由更高 assignmentVersion 触发刷新。 */
    @Test
    void assignmentKeepsBothVersions() {
        CachedEffectiveQuotaPolicyProvider provider = mock(CachedEffectiveQuotaPolicyProvider.class);
        CachedEffectiveEntitlementProvider entitlementProvider = mock(CachedEffectiveEntitlementProvider.class);
        UUID tenantId = UUID.randomUUID();
        var handler = new QuotaPolicyCacheInvalidationHandler(provider, entitlementProvider);

        handler.handle(new CacheInvalidationEvent(UUID.randomUUID(), CacheResource.QUOTA_POLICY,
                CacheInvalidationOperation.ASSIGN, tenantId, null, 9L, 2L, Instant.now()));

        verify(provider).markStale(new QuotaPolicyChanged(tenantId, 9L, 2L));
    }

    /** 模板事件只按 policyId 和 policyVersion 标记本机命中的模板条目，并同步失效权益投影。 */
    @Test
    void templateKeepsPolicyVersion() {
        CachedEffectiveQuotaPolicyProvider provider = mock(CachedEffectiveQuotaPolicyProvider.class);
        CachedEffectiveEntitlementProvider entitlementProvider = mock(CachedEffectiveEntitlementProvider.class);
        UUID policyId = UUID.randomUUID();
        var handler = new QuotaPolicyCacheInvalidationHandler(provider, entitlementProvider);

        handler.handle(new CacheInvalidationEvent(UUID.randomUUID(), CacheResource.QUOTA_POLICY,
                CacheInvalidationOperation.UPDATE, policyId, null, 7L, 0L, Instant.now()));

        QuotaPolicyTemplateChanged expected = new QuotaPolicyTemplateChanged(policyId, 7L);
        verify(provider).markStale(expected);
        verify(entitlementProvider).markStale(expected);
    }
}
