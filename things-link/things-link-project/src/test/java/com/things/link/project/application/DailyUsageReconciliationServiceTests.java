package com.things.link.project.application;

import com.things.link.project.domain.DailyUsageReconciliationRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证今天/昨天绝对归并、同指标求和与租约完成顺序。 */
class DailyUsageReconciliationServiceTests {

    /** 两个贡献器同指标求和，零值不落行，今天和昨天都必须重算。 */
    @Test
    void reconcilesTodayAndYesterdayAsAbsoluteTotals() {
        DailyUsageReconciliationRepository repository = mock(DailyUsageReconciliationRepository.class);
        TransactionLocalRlsScope transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        DailyUsageContributor first = mock(DailyUsageContributor.class);
        DailyUsageContributor second = mock(DailyUsageContributor.class);
        DailyUsageScope scope = new DailyUsageScope(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        LocalDate today = LocalDate.of(2026, 8, 11);
        when(first.calculate(eq(scope), any())).thenReturn(List.of(
                new DailyUsageValue(QuotaMetric.UPLINK_MESSAGE, 5),
                new DailyUsageValue(QuotaMetric.UPLINK_BYTES, 0)));
        when(second.calculate(eq(scope), any())).thenReturn(List.of(
                new DailyUsageValue(QuotaMetric.UPLINK_MESSAGE, 7),
                new DailyUsageValue(QuotaMetric.TIME_SERIES_POINT, 3)));
        DailyUsageReconciliationService service = new DailyUsageReconciliationService(
                repository, List.of(first, second), transactionLocalRlsScope,
                Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC));

        service.reconcile(scope);

        verify(first).calculate(scope, today.minusDays(1));
        verify(first).calculate(scope, today);
        verify(second).calculate(scope, today.minusDays(1));
        verify(second).calculate(scope, today);
        ArgumentCaptor<com.things.link.project.domain.DailyUsageValue> values =
                ArgumentCaptor.forClass(com.things.link.project.domain.DailyUsageValue.class);
        com.things.link.project.domain.DailyUsageScope domainScope =
                new com.things.link.project.domain.DailyUsageScope(scope.tenantId(), scope.projectId());
        verify(repository, times(4)).mergeAbsolute(eq(domainScope), any(), values.capture());
        assertThat(values.getAllValues()).extracting(
                        com.things.link.project.domain.DailyUsageValue::metric,
                        com.things.link.project.domain.DailyUsageValue::usedValue)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                com.things.link.project.domain.QuotaMetric.UPLINK_MESSAGE, 12L),
                        org.assertj.core.groups.Tuple.tuple(
                                com.things.link.project.domain.QuotaMetric.TIME_SERIES_POINT, 3L),
                        org.assertj.core.groups.Tuple.tuple(
                                com.things.link.project.domain.QuotaMetric.UPLINK_MESSAGE, 12L),
                        org.assertj.core.groups.Tuple.tuple(
                                com.things.link.project.domain.QuotaMetric.TIME_SERIES_POINT, 3L));
        verify(repository).complete(domainScope);
        var order = inOrder(transactionLocalRlsScope, first, repository);
        order.verify(transactionLocalRlsScope).establish(scope.tenantId(), scope.projectId());
        order.verify(repository).lockScope(domainScope);
        order.verify(first).calculate(scope, today.minusDays(1));
        order.verify(first).calculate(scope, today);
        order.verify(repository).complete(domainScope);
    }
}
