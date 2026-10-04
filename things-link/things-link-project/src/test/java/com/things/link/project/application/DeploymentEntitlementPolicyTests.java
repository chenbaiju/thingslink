package com.things.link.project.application;

import com.things.link.project.domain.DailyQuotaDecisionRepository;
import com.things.link.project.domain.DailyUsageScope;
import com.things.link.project.domain.PlanCapacityRepository;
import com.things.link.project.domain.PlanHistoryWindow;
import com.things.link.project.domain.QuotaMetric;
import com.things.link.project.domain.QuotaOverviewRepository;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.project.domain.PlanSummaryRepository;
import org.springframework.beans.factory.ObjectProvider;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 非商业容量是独立、有限且仍以权威归属查询为前置；原商业模式保持默认。 */
class DeploymentEntitlementPolicyTests {
    private static final String PREFIX = "things-link.deployment.noncommercial-capacity.";

    @Test
    void commercialDefaultAndIncompleteNoncommercialFailClosed() {
        assertThat(new DeploymentEntitlementPolicy(new MockEnvironment()).nonCommercial()).isFalse();
        assertThatThrownBy(() -> new DeploymentEntitlementPolicy(new MockEnvironment()
                .withProperty("things-link.deployment.entitlement-mode", "NONCOMMERCIAL")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("projects");
    }

    @Test
    void noncommercialOverridesCommercialLimitsButRequiresValidScope() {
        DeploymentEntitlementPolicy policy = localPolicy();
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        PlanCapacityRepository capacity = mock(PlanCapacityRepository.class);
        when(capacity.findEndUsersLimit(tenant, project)).thenReturn(OptionalLong.of(1));
        when(capacity.findHistoryWindow(tenant, project)).thenReturn(Optional.of(new PlanHistoryWindow(
                Instant.parse("2026-09-24T00:00:00Z"), Instant.parse("2026-09-25T00:00:00Z"))));
        PlanCapacityService capacityService = new PlanCapacityService(capacity, policy);
        assertThat(capacityService.endUsersLimit(tenant, project)).isEqualTo(100);
        assertThat(capacityService.historyWindow(tenant, project).from())
                .isEqualTo(Instant.parse("2026-08-26T00:00:00Z"));
        assertThatThrownBy(() -> capacityService.endUsersLimit(tenant, UUID.randomUUID()))
                .isInstanceOf(com.things.link.shared.error.BusinessException.class);

        TenantSubscriptionLifecycleRepository lifecycle = mock(TenantSubscriptionLifecycleRepository.class);
        new SubscriptionExpansionGuard(lifecycle, policy).requireExpansionAllowed(tenant);
        verifyNoInteractions(lifecycle);

        DailyQuotaDecisionRepository daily = mock(DailyQuotaDecisionRepository.class);
        LocalDate date = LocalDate.of(2026, 9, 25);
        when(daily.find(new DailyUsageScope(tenant, project), date, QuotaMetric.UPLINK_MESSAGE))
                .thenReturn(Optional.of(new DailyQuotaDecisionRepository.DailyQuotaDecisionFact(1L, 2L,
                        8000, 12000)));
        ProjectDailyQuotaDecisionService decisions = new ProjectDailyQuotaDecisionService(daily, policy,
                Clock.fixed(Instant.parse("2026-09-25T02:00:00Z"), ZoneOffset.UTC));
        assertThat(decisions.decisionTrustedProject(tenant, project,
                com.things.link.project.application.QuotaMetric.UPLINK_MESSAGE).status())
                .isEqualTo(QuotaStatus.NORMAL);
        assertThatThrownBy(() -> decisions.decisionTrustedProject(tenant, UUID.randomUUID(),
                com.things.link.project.application.QuotaMetric.UPLINK_MESSAGE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deviceQuotaUsesFiniteTechnicalLimitAfterTrustedOwnerLookup() {
        DeploymentEntitlementPolicy policy = localPolicy();
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        QuotaOverviewRepository quota = mock(QuotaOverviewRepository.class);
        when(quota.findDeviceQuotaPolicy(tenant, project)).thenReturn(Optional.of(
                new QuotaOverviewRepository.DeviceQuotaPolicyRow(1L, 8_000, 12_000)));
        ProjectDeviceQuotaContributor contributor = mock(ProjectDeviceQuotaContributor.class);
        when(contributor.countActiveDevices(tenant, project)).thenReturn(
                new ProjectDeviceQuotaContributor.ProjectDeviceQuotaUsage(2, 2));
        @SuppressWarnings("unchecked")
        ObjectProvider<ProjectDeviceQuotaContributor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(contributor);
        ProjectQuotaService service = new ProjectQuotaService(mock(ProjectRepository.class), provider,
                quota, mock(PlanSummaryRepository.class),
                new SubscriptionExpansionGuard(mock(TenantSubscriptionLifecycleRepository.class), policy), policy);
        assertThat(service.deviceQuotaStatus(tenant, project)).isEqualTo(QuotaStatus.NORMAL);
        when(contributor.countActiveDevices(tenant, project)).thenReturn(
                new ProjectDeviceQuotaContributor.ProjectDeviceQuotaUsage(100, 100));
        assertThat(service.deviceQuotaStatus(tenant, project)).isEqualTo(QuotaStatus.HARD_LIMIT);
        assertThatThrownBy(() -> service.deviceQuotaStatus(tenant, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DeploymentEntitlementPolicy localPolicy() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("things-link.deployment.entitlement-mode", "NONCOMMERCIAL");
        for (DeploymentEntitlementPolicy.Capacity capacity : DeploymentEntitlementPolicy.Capacity.values()) {
            String key = switch (capacity) {
                case PROJECTS -> "projects";
                case DEVICES -> "devices";
                case END_USERS -> "end-users";
                case DASHBOARDS -> "dashboards";
                case EXTERNAL_SEATS -> "external-seats";
                case HISTORY_DAYS -> "history-days";
            };
            environment.withProperty(PREFIX + key, capacity == DeploymentEntitlementPolicy.Capacity.HISTORY_DAYS
                    ? "30" : "100");
        }
        for (QuotaMetric metric : QuotaMetric.values()) {
            if (metric.dailyCounter()) {
                environment.withProperty(PREFIX + "daily." + metric.name().toLowerCase(java.util.Locale.ROOT)
                        .replace('_', '-'), "100");
            }
        }
        return new DeploymentEntitlementPolicy(environment);
    }
}
