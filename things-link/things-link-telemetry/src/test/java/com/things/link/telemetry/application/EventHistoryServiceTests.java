package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.PlanHistoryWindow;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceEventHistoryItem;
import com.things.link.telemetry.domain.DeviceEventHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 当前许可及精度的单元反例；SQL/RLS/HTTP资格另由真实PG集成验证。 */
class EventHistoryServiceTests {
    final ProjectService projects = mock(ProjectService.class);
    final TransactionLocalRlsScope scope = mock(TransactionLocalRlsScope.class);
    final DeviceIngestionService devices = mock(DeviceIngestionService.class);
    final PlanCapacityService capacity = mock(PlanCapacityService.class);
    final DeviceEventHistoryRepository repository = mock(DeviceEventHistoryRepository.class);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final EventHistoryService service = new EventHistoryService(projects, scope, devices, capacity, repository, json);
    final UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), device = UUID.randomUUID(), type = UUID.randomUUID(), version = UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-06T12:00:00Z"), lower = now.minus(Duration.ofDays(30));
    @BeforeEach void fixture() {
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.VIEWER);
        when(projects.requireProjectTenant(project)).thenReturn(owner);
        when(capacity.historyWindow(owner, project)).thenReturn(new PlanHistoryWindow(lower, now));
    }
    private DeviceEventHistoryItem item(UUID message, String params, boolean redacted) {
        return new DeviceEventHistoryItem(message, device, type, "event", "WARNING", version, "1.0.0", "HISTORY_ONLY",
                now.minusSeconds(1), now.minusSeconds(1), now, params, redacted);
    }
    @Test void fourRolesReadOriginalFactsWithOwnerScopeAndNoCurrentModelLookup() {
        UUID message = UUID.randomUUID();
        when(repository.find(project, device, null, null, null, lower, now, null, null, 21)).thenReturn(List.of(item(message, "{}", false)));
        for (ProjectRole role : ProjectRole.values()) {
            when(projects.requireRoleInProject(project)).thenReturn(role);
            var page = service.list(project, device, Map.of());
            assertThat(page.items().getFirst().modelVersion()).isEqualTo("1.0.0");
            assertThat(page.items().getFirst().eligibility()).isEqualTo("HISTORY_ONLY");
            assertThat(page.retentionDays()).isEqualTo(90);
        }
        verify(scope, times(ProjectRole.values().length)).establish(owner, project);
        verify(devices, times(ProjectRole.values().length)).requireDeviceOwner(project, device);
        verifyNoMoreInteractions(devices);
    }
    @Test void emptyIntersectionStillRejectsBadCursorAndUsesEqualUpperPoint() {
        var params = Map.of("from", new String[]{"2027-01-01T00:00:00Z"});
        var page = service.list(project, device, params);
        assertThat(page.items()).isEmpty();
        assertThat(page.windowFrom()).isEqualTo(now).isEqualTo(page.windowTo());
        assertThatThrownBy(() -> service.list(project, device, Map.of("from", new String[]{"2027-01-01T00:00:00Z"},
                "cursor", new String[]{"bad"}))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.detail(project, device, UUID.randomUUID(), Map.of("limit", new String[]{"1"}))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }
    @Test void reauthorizesAndRecomputesWindowForEachCursorPage() {
        UUID first = UUID.randomUUID();
        when(repository.find(project, device, null, null, null, lower, now, null, null, 2))
                .thenReturn(List.of(item(first, "{}", false), item(UUID.randomUUID(), "{}", false)));
        var page = service.list(project, device, Map.of("limit", new String[]{"1"}));
        Instant shortened = now.minusSeconds(2);
        when(capacity.historyWindow(owner, project)).thenReturn(new PlanHistoryWindow(shortened, now));
        when(repository.find(project, device, null, null, null, shortened, now, now.minusSeconds(1), first, 2)).thenReturn(List.of());
        service.list(project, device, Map.of("limit", new String[]{"1"}, "cursor", new String[]{page.nextCursor()}));
        verify(repository).find(project, device, null, null, null, shortened, now, now.minusSeconds(1), first, 2);
        when(projects.requireRoleInProject(project)).thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.list(project, device, Map.of())).isInstanceOf(BusinessException.class);
        verify(capacity, times(2)).historyWindow(owner, project);
    }
    @Test void readsAndSerializesDecimalScaleAndLargeIntegerWithoutSecretFields() {
        UUID message = UUID.randomUUID();
        when(repository.findOne(project, device, message, lower, now)).thenReturn(Optional.of(item(message,
                "{\"password\":\"SYNTHETIC_SECRET\",\"number\":12345678901234567890123456789012345678,\"decimal\":1.2300,\"tiny\":1e-308,\"huge\":1e308,\"enabled\":true}", false)));
        var result = service.detail(project, device, message, Map.of());
        assertThat(result.paramsRedacted()).isTrue();
        assertThat(result.params()).doesNotContainKey("password");
        assertThat(result.params().get("number")).isEqualTo(new BigInteger("12345678901234567890123456789012345678"));
        assertThat(result.params().get("decimal")).isEqualTo(new BigDecimal("1.2300"));
        assertThat(result.params().get("tiny")).isEqualTo(new BigDecimal("1e-308"));
        assertThat(result.params().get("huge")).isEqualTo(new BigDecimal("1e308"));
        assertThat(json.writeValueAsString(result)).contains("12345678901234567890123456789012345678", "1.2300").doesNotContain("SYNTHETIC_SECRET", "password", "inputDigest");
        when(repository.findOne(project, device, message, lower, now)).thenReturn(Optional.of(item(message, "{}", true)));
        assertThat(service.detail(project, device, message, Map.of()).paramsRedacted()).isTrue();
    }
    @Test void unknownOrWindowInvisibleDetailHasUniformCodeAndMissingCapacityIsNotEmpty() {
        UUID message = UUID.randomUUID();
        when(repository.findOne(project, device, message, lower, now)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(project, device, message, Map.of())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo(30072));
        when(capacity.historyWindow(owner, project)).thenThrow(new IllegalStateException("quota source unavailable"));
        assertThatThrownBy(() -> service.list(project, device, Map.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void retentionCapsLongEntitlementAndEqualOrDisjointBoundsAreEmpty() {
        var longPlan = new PlanHistoryWindow(now.minus(Duration.ofDays(365)), now);
        assertThat(EventHistoryService.window(longPlan, null, null).from()).isEqualTo(now.minus(Duration.ofDays(90)));
        var equal = EventHistoryService.window(longPlan, now, null);
        assertThat(equal.from()).isEqualTo(equal.to());
        var old = EventHistoryService.window(longPlan, null, now.minus(Duration.ofDays(200)));
        assertThat(old.from()).isEqualTo(old.to()).isEqualTo(now.minus(Duration.ofDays(200)));
    }
}
