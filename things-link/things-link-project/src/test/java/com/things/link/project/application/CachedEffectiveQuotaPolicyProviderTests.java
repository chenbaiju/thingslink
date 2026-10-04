package com.things.link.project.application;

import com.things.link.project.domain.EffectiveQuotaPolicyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 有效策略缓存的版本、LKG 与冷启动降级测试。
 */
class CachedEffectiveQuotaPolicyProviderTests {

    /**
     * 更高绑定版本允许更低模板版本；数据库副本先返回旧值时不得覆盖 LKG。
     */
    @Test
    void retainsLastKnownGoodUntilDatabaseReachesReceivedAssignmentVersion() {
        UUID tenantId = UUID.randomUUID();
        EffectiveQuotaPolicyRepository repository = mock(EffectiveQuotaPolicyRepository.class);
        EffectiveQuotaPolicy original = policy(tenantId, 1, 9, 10L);
        EffectiveQuotaPolicy staleReplica = policy(tenantId, 1, 10, 99L);
        EffectiveQuotaPolicy switchedOlderTemplate = policy(tenantId, 2, 1, 20L);
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.of(original), Optional.of(staleReplica),
                Optional.of(switchedOlderTemplate));
        CachedEffectiveQuotaPolicyProvider provider = provider(repository);

        assertThat(provider.resolveTrustedTenant(tenantId)).isEqualTo(original);
        provider.markStale(new QuotaPolicyChanged(tenantId, 2, 1));

        // assignment=1 的副本尚未追上事件，不能用较新 policyVersion 把绑定版本回滚。
        assertThat(provider.resolveTrustedTenant(tenantId)).isEqualTo(original);
        assertThat(provider.resolveTrustedTenant(tenantId)).isEqualTo(switchedOlderTemplate);
    }

    /**
     * 热缓存遇到权威源异常保留 LKG，冷启动才使用有限安全默认。
     */
    @Test
    void fallsBackToLastKnownGoodThenFiniteSafeDefault() {
        UUID tenantId = UUID.randomUUID();
        EffectiveQuotaPolicyRepository hotRepository = mock(EffectiveQuotaPolicyRepository.class);
        EffectiveQuotaPolicy original = policy(tenantId, 1, 1, 10L);
        when(hotRepository.findByTenantId(tenantId)).thenReturn(Optional.of(original))
                .thenThrow(new IllegalStateException("postgres down"));
        CachedEffectiveQuotaPolicyProvider hotProvider = provider(hotRepository);
        assertThat(hotProvider.resolveTrustedTenant(tenantId)).isEqualTo(original);
        hotProvider.markStale(new QuotaPolicyChanged(tenantId, 1, 2));
        assertThat(hotProvider.resolveTrustedTenant(tenantId)).isEqualTo(original);

        UUID coldTenantId = UUID.randomUUID();
        EffectiveQuotaPolicyRepository coldRepository = mock(EffectiveQuotaPolicyRepository.class);
        when(coldRepository.findByTenantId(coldTenantId)).thenThrow(new IllegalStateException("postgres down"));
        EffectiveQuotaPolicy safeDefault = provider(coldRepository).resolveTrustedTenant(coldTenantId);
        assertThat(safeDefault.restApiReadRatePerMinute()).isEqualTo(60L);
        assertThat(safeDefault.restApiWriteRatePerMinute()).isEqualTo(60L);
        assertThat(safeDefault.planQuota()).isNull();
        assertThat(safeDefault.uplinkDeviceRefillPerSecond()).isEqualTo(10L);
        assertThat(safeDefault.uplinkDeviceBurstCapacity()).isEqualTo(20L);
        assertThat(safeDefault.websocketConnectionLimit()).isEqualTo(10L);
        assertThat(safeDefault.taskProjectDispatchPerSecond()).isEqualTo(20L);
        assertThat(safeDefault.taskTenantDispatchPerSecond()).isEqualTo(100L);
        assertThat(safeDefault.ruleTenantConcurrencyLimit()).isEqualTo(1L);
        assertThat(safeDefault.ruleTenantQueueCapacity()).isEqualTo(100L);
        assertThat(safeDefault.ruleProjectQueueCapacity()).isEqualTo(20L);
    }

    /** 冷故障后回源恢复真实策略，可信身份错误不得进入默认分支。 */
    @Test
    void coldFailureRecoversAndInvalidIdentityNeverFallsBack() {
        UUID tenant=UUID.randomUUID();
        EffectiveQuotaPolicyRepository repository=mock(EffectiveQuotaPolicyRepository.class);
        EffectiveQuotaPolicy recovered=policy(tenant,1,1,77L);
        when(repository.findByTenantId(tenant)).thenThrow(new IllegalStateException("down"))
                .thenReturn(Optional.of(recovered));
        var provider=provider(repository);
        assertThat(provider.resolveTrustedTenant(tenant).restApiReadRatePerMinute()).isEqualTo(60L);
        assertThat(provider.resolveTrustedTenant(tenant)).isEqualTo(recovered);
        UUID unknown=UUID.randomUUID();
        when(repository.findByTenantId(unknown)).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->provider.resolveTrustedTenant(unknown))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 同一模板的重复和乱序事件不能把已观察到的更高模板版本回滚。 */
    @Test
    void retainsLastKnownGoodUntilDatabaseReachesReceivedTemplateVersion() {
        UUID tenantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        EffectiveQuotaPolicyRepository repository = mock(EffectiveQuotaPolicyRepository.class);
        EffectiveQuotaPolicy original = policy(tenantId, policyId, 1, 3, 10L);
        EffectiveQuotaPolicy staleReplica = policy(tenantId, policyId, 1, 3, 99L);
        EffectiveQuotaPolicy current = policy(tenantId, policyId, 1, 4, 20L);
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.of(original), Optional.of(staleReplica),
                Optional.of(current));
        CachedEffectiveQuotaPolicyProvider provider = provider(repository);

        assertThat(provider.resolveTrustedTenant(tenantId)).isEqualTo(original);
        provider.markStale(new QuotaPolicyTemplateChanged(policyId, 4));
        assertThat(provider.resolveTrustedTenant(tenantId)).isEqualTo(original);
        provider.markStale(new QuotaPolicyTemplateChanged(policyId, 3));
        assertThat(provider.resolveTrustedTenant(tenantId)).isEqualTo(current);
    }

    /**
     * 构造固定时间下的缓存提供者，避免本测试依赖 TTL 等待。
     */
    private static CachedEffectiveQuotaPolicyProvider provider(EffectiveQuotaPolicyRepository repository) {
        return new CachedEffectiveQuotaPolicyProvider(repository,
                new QuotaRuntimeMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC));
    }

    /**
     * 构造只改变版本和设备速率的有效策略快照。
     */
    private static EffectiveQuotaPolicy policy(UUID tenantId, long assignmentVersion, long policyVersion,
                                               Long deviceRate) {
        return policy(tenantId, UUID.randomUUID(), assignmentVersion, policyVersion, deviceRate);
    }

    /** 构造指定模板 ID 的策略快照，以验证模板事件只命中正确的本地缓存条目。 */
    private static EffectiveQuotaPolicy policy(UUID tenantId, UUID policyId, long assignmentVersion, long policyVersion,
                                               Long deviceRate) {
        return new EffectiveQuotaPolicy(tenantId, policyId, assignmentVersion, policyVersion,
                deviceRate, deviceRate * 2, 1_000L, 60_000L, 20L, 10L, 1_200L, 600L, 200L);
    }
}
