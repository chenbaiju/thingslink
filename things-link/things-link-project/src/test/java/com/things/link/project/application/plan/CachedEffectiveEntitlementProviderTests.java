package com.things.link.project.application.plan;

import com.things.link.project.application.QuotaPolicyTemplateChanged;
import com.things.link.project.domain.plan.EffectiveEntitlement;
import com.things.link.project.domain.plan.EffectiveEntitlementRepository;
import com.things.link.project.domain.plan.PlanEntitlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S14-1b 权益投影缓存的失效语义单测。
 *
 * <p>用例钉住两件事：同一修订版在 TTL 内只回源一次，且只有携带匹配模板 ID 与更高版本的
 * S7 模板事件才会让它回源；不匹配的模板事件不得误伤本机条目。
 */
@DisplayName("S14-1b 权益投影缓存")
class CachedEffectiveEntitlementProviderTests {

    /** 模板 ID 在所有用例中复用，便于验证匹配/不匹配两种事件。 */
    private static final UUID POLICY_ID = UUID.fromString("0198c220-0000-7000-8000-000000000001");

    /** TTL 内重复解析只回源一次。 */
    @Test
    void resolveWithinTtlLoadsOnce() {
        FakeRepository repository = new FakeRepository();
        CachedEffectiveEntitlementProvider provider = provider(repository);

        assertThat(provider.resolvePlanRevision(revisionId()).planRevisionId()).isEqualTo(revisionId());
        assertThat(provider.resolvePlanRevision(revisionId()).planRevisionId()).isEqualTo(revisionId());

        assertThat(repository.loads()).isEqualTo(1);
    }

    /** 不匹配的模板事件不得让本机条目回源。 */
    @Test
    void unrelatedTemplateEventDoesNotInvalidate() {
        FakeRepository repository = new FakeRepository();
        CachedEffectiveEntitlementProvider provider = provider(repository);
        provider.resolvePlanRevision(revisionId());

        provider.markStale(new QuotaPolicyTemplateChanged(UUID.randomUUID(), 2L));
        provider.resolvePlanRevision(revisionId());

        assertThat(repository.loads()).isEqualTo(1);
    }

    /** 匹配模板且版本前进时必须回源，从而读到新的权益/模板版本。 */
    @Test
    void matchingTemplateEventInvalidates() {
        FakeRepository repository = new FakeRepository();
        CachedEffectiveEntitlementProvider provider = provider(repository);
        provider.resolvePlanRevision(revisionId());

        provider.markStale(new QuotaPolicyTemplateChanged(POLICY_ID, 2L));
        provider.resolvePlanRevision(revisionId());

        assertThat(repository.loads()).isEqualTo(2);
    }

    /** {@code DISABLED} 语义原样保留，且不等同于「额度 0」。 */
    @Test
    void entitlementPreservesDisabledSemantics() {
        FakeRepository repository = new FakeRepository();
        CachedEffectiveEntitlementProvider provider = provider(repository);

        EffectiveEntitlement entitlement = provider.resolvePlanRevision(revisionId());

        assertThat(entitlement.enabled("OBJECT_STORAGE")).isTrue();
        assertThat(entitlement.disabled("OTA")).isTrue();
        assertThat(entitlement.enabled("OTA")).isFalse();
        assertThat(entitlement.find("OTA")).get()
                .extracting(PlanEntitlement::enabled).isEqualTo(false);
    }

    /**
     * @param repository 伪仓储
     * @return 固定时钟下的缓存提供者
     */
    private static CachedEffectiveEntitlementProvider provider(FakeRepository repository) {
        return new CachedEffectiveEntitlementProvider(repository,
                Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC));
    }

    /** @return 固定产品修订版 ID */
    private static UUID revisionId() {
        return UUID.fromString("0198c210-0000-7000-8000-000000000001");
    }

    /** 只统计回源次数的内存仓储。 */
    private static final class FakeRepository implements EffectiveEntitlementRepository {

        /** 权威读取次数。 */
        private final AtomicInteger loads = new AtomicInteger();

        /** @return 已发生的回源次数 */
        int loads() {
            return loads.get();
        }

        /** {@inheritDoc} */
        @Override
        public Optional<EffectiveEntitlement> findByPlanRevisionId(UUID planRevisionId) {
            loads.incrementAndGet();
            if (!revisionId().equals(planRevisionId)) {
                return Optional.empty();
            }
            return Optional.of(entitlement());
        }

        /** {@inheritDoc} */
        @Override
        public Optional<EffectiveEntitlement> findByRevisionAndPlan(String revisionCode, String planCode) {
            loads.incrementAndGet();
            return "product-revision-1".equals(revisionCode) && "FREE".equals(planCode)
                    ? Optional.of(entitlement()) : Optional.empty();
        }

        /** @return 一档最小权益闭集：已交付启用、未交付显式禁用 */
        private static EffectiveEntitlement entitlement() {
            return new EffectiveEntitlement(revisionId(), "FREE", "product-revision-1", POLICY_ID, 1L,
                    List.of(new PlanEntitlement("OBJECT_STORAGE", true),
                            new PlanEntitlement("OTA", false)));
        }
    }
}
