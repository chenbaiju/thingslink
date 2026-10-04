package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleDeviceAlarmStatusServiceTests {
    private final ProjectService projects = mock(ProjectService.class);
    private final TransactionLocalRlsScope scope = mock(TransactionLocalRlsScope.class);
    private final DeviceRuntimeDataService devices = mock(DeviceRuntimeDataService.class);
    private final AlarmInstanceRepository alarms = mock(AlarmInstanceRepository.class);
    private final UUID project = UUID.randomUUID(), tenant = UUID.randomUUID(), device = UUID.randomUUID(), model = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-30T12:00:00Z");
    private final ConsoleDeviceAlarmStatusService service = new ConsoleDeviceAlarmStatusService(projects, scope, devices, alarms,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test void validatesRealProjectScopeAndModelsBeforeQueryingAlarms() {
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        when(devices.validatedCurrentModelVersions(project, Set.of(device))).thenReturn(Map.of(device, model));
        when(alarms.activeDeviceIds(project, List.of(device))).thenReturn(Set.of(device));
        var result = service.read(project, List.of(device));
        assertThat(result.observedAt()).isEqualTo(now);
        assertThat(result.devices()).containsExactly(new DeviceAlarmStatusSnapshot.DeviceStatus(device, model, DeviceAlarmStatusSnapshot.State.ACTIVE));
        var ordered = inOrder(projects, scope, devices, alarms);
        ordered.verify(projects).requireRoleInProject(project);
        ordered.verify(projects).requireProjectTenant(project);
        ordered.verify(scope).establish(tenant, project);
        ordered.verify(devices).validatedCurrentModelVersions(project, Set.of(device));
        ordered.verify(alarms).activeDeviceIds(project, List.of(device));
    }
    @Test void neverQueriesOrSynthesizesNormalOnModelFailure() {
        when(devices.validatedCurrentModelVersions(project, Set.of(device)))
                .thenThrow(new BusinessException(CommonErrorCode.INVALID_PARAMETER));
        assertThatThrownBy(() -> service.read(project, List.of(device))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(alarms);
    }
    @Test void rejectsIncompleteDevicePortAndOutOfScopeAlarmFacts() {
        when(devices.validatedCurrentModelVersions(project, Set.of(device))).thenReturn(Map.of());
        assertThatThrownBy(() -> service.read(project, List.of(device))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(alarms);
        when(devices.validatedCurrentModelVersions(project, Set.of(device))).thenReturn(Map.of(device, model));
        when(alarms.activeDeviceIds(project, List.of(device))).thenReturn(Set.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.read(project, List.of(device))).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsEmptyDuplicateAndOversizedBatchesBeforeDeviceLookup() {
        for (var ids : List.of(List.<UUID>of(), List.of(device, device),
                java.util.stream.IntStream.range(0, 21).mapToObj(i -> UUID.randomUUID()).toList())) {
            assertThatThrownBy(() -> service.read(project, ids)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(devices, alarms, scope);
    }
}
