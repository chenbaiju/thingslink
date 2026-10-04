package com.things.link.project.application;

import com.things.link.project.domain.DailyQuotaDecisionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证可信项目日额度决策只采用 PostgreSQL 绝对事实并对错误归属关闭。 */
class ProjectDailyQuotaDecisionServiceTests {

    /** 120% 用量必须得到 DEGRADED，而不是被 100% 硬限提前截断。 */
    @Test
    void returnsDegradedAtConfiguredThreshold() {
        DailyQuotaDecisionRepository repository = mock(DailyQuotaDecisionRepository.class);
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        com.things.link.project.domain.DailyUsageScope scope =
                new com.things.link.project.domain.DailyUsageScope(tenantId, projectId);
        LocalDate date = LocalDate.of(2026, 8, 11);
        when(repository.find(scope, date, com.things.link.project.domain.QuotaMetric.TIME_SERIES_POINT))
                .thenReturn(Optional.of(
                new DailyQuotaDecisionRepository.DailyQuotaDecisionFact(100L, 120L, 8000, 12000)));
        ProjectDailyQuotaDecisionService service = new ProjectDailyQuotaDecisionService(repository,
                Clock.fixed(Instant.parse("2026-08-11T23:59:59Z"), ZoneOffset.UTC));

        assertThat(service.decideTrustedProject(tenantId, projectId, QuotaMetric.TIME_SERIES_POINT))
                .isEqualTo(QuotaStatus.DEGRADED);
    }

    /** 商业档 NULL 表示未设上限；运行时必须保留这个语义且不发生拆箱异常。 */
    @Test
    void commercialNullLimitRemainsUnlimited() {
        DailyQuotaDecisionRepository repository = mock(DailyQuotaDecisionRepository.class);
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 8, 11);
        when(repository.find(new com.things.link.project.domain.DailyUsageScope(tenantId, projectId),
                date, com.things.link.project.domain.QuotaMetric.SCRIPT_EXECUTION))
                .thenReturn(Optional.of(new DailyQuotaDecisionRepository.DailyQuotaDecisionFact(
                        null, 42L, 8000, 12000)));
        ProjectDailyQuotaDecisionService service = new ProjectDailyQuotaDecisionService(repository,
                Clock.fixed(Instant.parse("2026-08-11T23:59:59Z"), ZoneOffset.UTC));

        assertThat(service.decisionTrustedProject(tenantId, projectId, QuotaMetric.SCRIPT_EXECUTION))
                .isEqualTo(new ProjectDailyQuotaDecisionService.Decision(QuotaStatus.NORMAL, false));
    }

    /** tenant/project 不匹配或传入非日指标时必须 fail-closed。 */
    @Test
    void rejectsUntrustedScopeAndNonDailyMetric() {
        DailyQuotaDecisionRepository repository = mock(DailyQuotaDecisionRepository.class);
        ProjectDailyQuotaDecisionService service = new ProjectDailyQuotaDecisionService(repository,
                Clock.systemUTC());
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        assertThatThrownBy(() -> service.decideTrustedProject(tenantId, projectId, QuotaMetric.UPLINK_MESSAGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归属不匹配");
        assertThatThrownBy(() -> service.decideTrustedProject(tenantId, projectId, QuotaMetric.DEVICE_COUNT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持 UTC 日计量指标");
    }
}
