package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceCommandHistoryItem;
import com.things.link.telemetry.domain.DeviceCommandHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 游标不授予权限；实际SQL/RLS另由真实HTTP测试负责。 */
class DeviceCommandHistoryServiceTests {
    final ProjectService projects = mock(ProjectService.class);
    final TransactionLocalRlsScope scope = mock(TransactionLocalRlsScope.class);
    final DeviceIngestionService devices = mock(DeviceIngestionService.class);
    final DeviceCommandHistoryRepository repository = mock(DeviceCommandHistoryRepository.class);
    final SignedQueryCursorCodec cursors = new SignedQueryCursorCodec("command-history-test-secret-at-least-32");
    final DeviceCommandHistoryService service = new DeviceCommandHistoryService(projects, scope, devices, repository, cursors);
    final UUID project = UUID.randomUUID(), device = UUID.randomUUID(), tenant = UUID.randomUUID(), actor = UUID.randomUUID();
    final Instant time = Instant.parse("2026-09-30T12:00:00Z");
    @BeforeEach void identity() {
        TenantContext.set(new TenantScope(tenant, project, actor));
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.OPERATOR);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
    }
    @AfterEach void clear() { TenantContext.clear(); }
    private DeviceCommandHistoryItem item(UUID id) {
        return new DeviceCommandHistoryItem(id, device, "PROPERTY_SET", null, "ACCEPTED", 1, 3, time, null);
    }
    @Test void signsLastReturnedPositionAndRejectsChangesToDeviceActorAndPageSize() {
        UUID first = UUID.randomUUID();
        when(repository.find(project, device, null, null, 2)).thenReturn(List.of(item(first), item(UUID.randomUUID())));
        var page = service.list(project, device, null, 1);
        assertThat(page.items()).hasSize(1);
        when(repository.find(project, device, time, first, 2)).thenReturn(List.of());
        assertThat(service.list(project, device, page.nextCursor(), 1).items()).isEmpty();
        assertThatThrownBy(() -> service.list(project, device, page.nextCursor(), 2)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(project, UUID.randomUUID(), page.nextCursor(), 1)).isInstanceOf(BusinessException.class);
        TenantContext.set(new TenantScope(tenant, project, UUID.randomUUID()));
        assertThatThrownBy(() -> service.list(project, device, page.nextCursor(), 1)).isInstanceOf(BusinessException.class);
        verify(repository, times(2)).find(any(), any(), any(), any(), anyInt());
    }
    @Test void neverLoadsFactsForViewerOrInvalidLimits() {
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.VIEWER);
        assertThatThrownBy(() -> service.list(project, device, null, 20)).isInstanceOf(BusinessException.class);
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.ADMIN);
        for (int limit : List.of(0, -1, 51))
            assertThatThrownBy(() -> service.list(project, device, null, limit)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }
    @Test void rechecksDeviceAndRoleOnEveryPageAndFailsClosed() {
        when(repository.find(project, device, null, null, 2)).thenReturn(List.of(item(UUID.randomUUID()), item(UUID.randomUUID())));
        var cursor = service.list(project, device, null, 1).nextCursor();
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.VIEWER);
        assertThatThrownBy(() -> service.list(project, device, cursor, 1)).isInstanceOf(BusinessException.class);
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.ADMIN);
        doThrow(new IllegalStateException("deleted fixture")).when(devices).requireDeviceOwner(project, device);
        assertThatThrownBy(() -> service.list(project, device, cursor, 1)).hasMessage("deleted fixture");
        verify(repository, times(1)).find(any(), any(), any(), any(), anyInt());
    }
    @Test void establishesOwnerTenantWithoutImpersonatingCollaboratorIdentity() {
        UUID owner = UUID.randomUUID();
        when(projects.requireProjectTenant(project)).thenReturn(owner);
        when(repository.find(project, device, null, null, 21)).thenReturn(List.of());
        assertThat(service.list(project, device, null, 20).nextCursor()).isNull();
        verify(scope).establish(owner, project);
        assertThat(TenantContext.current().orElseThrow()).isEqualTo(new TenantScope(tenant, project, actor));
    }
}
