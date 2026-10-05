package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.PublicDeviceReadService;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.PlanHistoryWindow;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleHistoryEvidenceServiceTests {
    final UUID project = UUID.randomUUID(), tenant = UUID.randomUUID(), device = UUID.randomUUID(), model = UUID.randomUUID();
    final Instant end = Instant.parse("2026-10-04T12:00:00Z"), start = end.minusSeconds(3600);
    final ProjectService projects = mock(ProjectService.class);
    final TransactionLocalRlsScope scope = mock(TransactionLocalRlsScope.class);
    final DeviceRuntimeDataService devices = mock(DeviceRuntimeDataService.class);
    final PublicDeviceReadService catalog = mock(PublicDeviceReadService.class);
    final PlanCapacityService capacity = mock(PlanCapacityService.class);
    final PublicPropertyHistoryService history = mock(PublicPropertyHistoryService.class);
    final ConsoleHistoryEvidenceService service = new ConsoleHistoryEvidenceService(projects, scope, devices, catalog,
            capacity, history, Clock.fixed(end, ZoneOffset.UTC));
    @BeforeEach void setup() {
        TenantContext.set(new TenantScope(tenant, project, UUID.randomUUID()));
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        when(catalog.model(project, model)).thenReturn(new PublicDeviceReadService.Model(model, UUID.randomUUID(), "1.0.0",
                "SHA", "a", start, JsonMapper.builder().build().readTree("{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\"}}}")));
        when(capacity.historyWindow(tenant, project)).thenReturn(new PlanHistoryWindow(end.minusSeconds(7 * 86400), end));
        when(history.query(project, device, model, "temperature", start, end, "RAW", "AVG"))
                .thenReturn(new PublicPropertyHistoryService.Result("RAW", "RAW", "AVG", List.of()));
    }
    @AfterEach void clear() { TenantContext.clear(); }
    ConsoleHistoryEvidence read() { return service.read(project, device, model, "temperature", start, end); }
    @Test void establishesCurrentMemberAndRealTenantBeforeAnyFacts() {
        var result = read();
        assertThat(result.state()).isEqualTo(ConsoleHistoryEvidence.State.NO_POINTS);
        assertThat(result.retentionClipped()).isFalse();
        var order = inOrder(projects, scope, devices, catalog, capacity, history);
        order.verify(projects).requireRoleInProject(project); order.verify(projects).requireProjectTenant(project);
        order.verify(scope).establish(tenant, project);
        order.verify(devices).requireAllAvailable(project, List.of(new RuntimeDeviceQuery(device, model, List.of())));
        order.verify(catalog).model(project, model); order.verify(capacity).historyWindow(tenant, project);
        order.verify(history).query(project, device, model, "temperature", start, end, "RAW", "AVG");
    }
    @Test void wrongProjectAndRevokedMembershipDoNotReadFacts() {
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), device, model, "temperature", start, end))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(projects, devices, catalog, capacity, history);
        doThrow(new IllegalStateException("revoked")).when(projects).requireRoleInProject(project);
        assertThatThrownBy(this::read).hasMessage("revoked"); verifyNoInteractions(devices, catalog, capacity, history);
    }
    @Test void strictWindowAndPropertyValidationNeverQueriesHistoricalData() {
        for (Instant[] window : List.of(new Instant[]{start, start}, new Instant[]{end.minusSeconds(86401), end},
                new Instant[]{Instant.EPOCH.minusSeconds(1), Instant.EPOCH}, new Instant[]{start, end.plusNanos(1)})) {
            assertThatThrownBy(() -> service.read(project, device, model, "temperature", window[0], window[1]))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.read(project, device, model, "unknown", start, end)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.read(project, device, model, "x,temperature", start, end)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(history);
    }
    @Test void quotaIntersectionAndOutsideRetentionAreExplicitNotNormal() {
        var clipped = start.plusSeconds(60);
        when(capacity.historyWindow(tenant, project)).thenReturn(new PlanHistoryWindow(clipped, end));
        when(history.query(project, device, model, "temperature", clipped, end, "RAW", "AVG"))
                .thenReturn(new PublicPropertyHistoryService.Result("RAW", "RAW", "AVG", List.of()));
        var result = read(); assertThat(result.retentionClipped()).isTrue(); assertThat(result.effectiveFrom()).isEqualTo(clipped);
        when(capacity.historyWindow(tenant, project)).thenReturn(new PlanHistoryWindow(end, end.plusSeconds(1)));
        reset(history);
        result = read(); assertThat(result.state()).isEqualTo(ConsoleHistoryEvidence.State.OUTSIDE_RETENTION);
        assertThat(result.effectiveFrom()).isEqualTo(end); assertThat(result.effectiveTo()).isEqualTo(end);
        verifyNoInteractions(history);
    }
    @Test void versionSegmentsAndEqualTimesRemainSeparateUnknownValuesAreWithheld() {
        UUID old = UUID.randomUUID();
        when(history.query(project, device, model, "temperature", start, end, "RAW", "AVG"))
                .thenReturn(new PublicPropertyHistoryService.Result("RAW", "ONE_MINUTE", "AVG", List.of(
                    new PublicPropertyHistoryService.Point(start, 1, "9007199254740993", model, "1.0.0"),
                    new PublicPropertyHistoryService.Point(start, 2, "1", old, "0.9.0"),
                    new PublicPropertyHistoryService.Point(start, 3, "1", null, "LEGACY_UNVERSIONED"))));
        var result = read(); assertThat(result.points()).hasSize(3); assertThat(result.actualGranularity()).isEqualTo("ONE_MINUTE");
        assertThat(result.points().get(0).sampleCount()).isEqualTo("9007199254740993");
        assertThat(result.points().get(1).source()).isEqualTo(ConsoleHistoryEvidence.Source.HISTORICAL_MODEL);
        assertThat(result.points().get(2).value()).isNull();
        assertThat(result.toString()).doesNotContain(project.toString(), "temperature");
        assertThat(result.points().get(0).toString()).doesNotContain("9007199254740993");
        assertThatThrownBy(() -> result.points().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void sourceFailureCannotBecomeEmptySuccess() {
        var failure = new IllegalStateException("source unavailable");
        when(history.query(project, device, model, "temperature", start, end, "RAW", "AVG")).thenThrow(failure);
        assertThatThrownBy(this::read).isSameAs(failure);
    }
    @Test void invalidPointsBudgetsAndUsageAreRejectedWhole() {
        for (var point : List.of(new PublicPropertyHistoryService.Point(end, 1, "1", model, "1"),
                new PublicPropertyHistoryService.Point(start, Double.NaN, "1", model, "1"),
                new PublicPropertyHistoryService.Point(start, 1, "0", model, "1"),
                new PublicPropertyHistoryService.Point(start, 1, "9999999999999999999", model, "1"))) {
            when(history.query(project, device, model, "temperature", start, end, "RAW", "AVG"))
                    .thenReturn(new PublicPropertyHistoryService.Result("RAW", "RAW", "AVG", List.of(point)));
            assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
        }
        when(history.query(project, device, model, "temperature", start, end, "RAW", "AVG"))
                .thenReturn(new PublicPropertyHistoryService.Result("RAW", "RAW", "AVG", Collections.nCopies(2001,
                    new PublicPropertyHistoryService.Point(start, 1, "1", model, "1"))));
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
    }
    @Test void aggregateBucketCannotIncludeValuesBeyondVisibleEnd() {
        when(history.query(project, device, model, "temperature", start, end, "RAW", "AVG"))
                .thenReturn(new PublicPropertyHistoryService.Result("RAW", "ONE_HOUR", "AVG", List.of(
                    new PublicPropertyHistoryService.Point(start.plusSeconds(1), 1, "1", model, "1"))));
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class)
                .hasMessage("历史聚合桶越过可见窗口");
    }
}
