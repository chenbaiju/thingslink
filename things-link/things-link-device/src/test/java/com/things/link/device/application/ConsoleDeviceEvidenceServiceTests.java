package com.things.link.device.application;

import com.things.link.device.domain.DeviceCurrentValue;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleDeviceEvidenceServiceTests {
    final UUID project = UUID.randomUUID(), device = UUID.randomUUID(), model = UUID.randomUUID(), tenant = UUID.randomUUID();
    final ProjectService projects = mock(ProjectService.class);
    final PublicDeviceReadService catalog = mock(PublicDeviceReadService.class);
    final DeviceRuntimeDataService runtime = mock(DeviceRuntimeDataService.class);
    final DeviceCurrentValueService values = mock(DeviceCurrentValueService.class);
    final ConsoleDeviceEvidenceService service = new ConsoleDeviceEvidenceService(projects,
            mock(TransactionLocalRlsScope.class), catalog, runtime, values, Clock.systemUTC());
    @BeforeEach void setup() {
        TenantContext.set(new TenantScope(tenant, project, UUID.randomUUID()));
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        when(catalog.detail(project, device)).thenReturn(new PublicDeviceReadService.Detail(device, "private", "ONLINE", model, Instant.now(), null));
        when(runtime.validatedCurrentModelVersions(project, Set.of(device))).thenReturn(Map.of(device, model));
        when(catalog.model(project, model)).thenReturn(new PublicDeviceReadService.Model(model, UUID.randomUUID(), "1.0.0", "digest", "hash", Instant.now(),
                new ObjectMapper().readTree("{\"properties\":{\"a\":{},\"b\":{},\"c\":{},\"d\":{}}}")));
    }
    @AfterEach void cleanup() { TenantContext.clear(); }
    @Test void distinguishesMissingUnknownAndOldSourceWithoutInventingValues() {
        var json = new ObjectMapper();
        when(values.findAll(project, List.of(device), List.of("a", "b", "c", "d"))).thenReturn(List.of(
                new DeviceCurrentValue(device, "a", json.readTree("12.34567890123456789"), Instant.now(), 0, "1", model),
                new DeviceCurrentValue(device, "b", json.readTree("2"), Instant.now(), 0, "2", null),
                new DeviceCurrentValue(device, "c", json.readTree("3"), Instant.now(), 0, "3", UUID.randomUUID())));
        var result = service.read(project, device, model, List.of("a", "b", "c", "d"));
        assertThat(result.properties()).extracting(ConsoleDeviceEvidence.Property::availability)
                .containsExactly(ConsoleDeviceEvidence.Availability.PRESENT, ConsoleDeviceEvidence.Availability.SOURCE_UNKNOWN,
                        ConsoleDeviceEvidence.Availability.MODEL_MISMATCH, ConsoleDeviceEvidence.Availability.MISSING);
        assertThat(result.properties().subList(1, 4)).allMatch(p -> p.value() == null);
        verify(values).findAll(project, List.of(device), List.of("a", "b", "c", "d"));
    }
    @Test void rejectsUnknownDuplicateOversizedAndNonPropertyKeys() {
        for (var keys : List.of(List.<String>of(), List.of("a", "a"), List.of("unknown"), List.of("a,b"),
                java.util.stream.IntStream.range(0, 11).mapToObj(i -> "p" + i).toList())) {
            assertThatThrownBy(() -> service.read(project, device, model, keys)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(values);
    }
    @Test void rejectsProjectMismatchBeforeQuerying() {
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), device, model, List.of("a"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(projects, catalog, runtime, values);
    }
}
