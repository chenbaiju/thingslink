package com.things.link.device.application;

import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectService.DeviceAccessScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Broker 设备接入范围解析的事务边界回归。 */
class DeviceAccessScopeServiceTests {

    /** 项目不存在也必须只进入一次外层事务，防止项目定位在事务外另借 CONTROL/DATA 连接。 */
    @Test
    void resolvesProjectInsideSingleOuterTransaction() {
        ProjectService projectService = mock(ProjectService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AtomicBoolean insideTransaction = new AtomicBoolean();
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            assertThat(insideTransaction.compareAndSet(false, true)).isTrue();
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                insideTransaction.set(false);
            }
        }).when(transactionTemplate).execute(any());
        when(projectService.findDeviceAccessScope("project-1")).thenAnswer(invocation -> {
            assertThat(insideTransaction).isTrue();
            return Optional.empty();
        });
        DeviceAccessScopeService service = new DeviceAccessScopeService(
                projectService, mock(DeviceRepository.class), mock(DeviceTypeRepository.class),
                mock(TransactionLocalRlsScope.class), transactionTemplate, mock(DeviceAccessSessionRepository.class));

        assertThat(service.resolve("project-1", "device-1")).isEmpty();

        verify(transactionTemplate).execute(any());
        verify(projectService).findDeviceAccessScope("project-1");
        assertThat(insideTransaction).isFalse();
    }

    /** 权威项目二元组必须在同一事务回调内先建立范围，再执行设备仓储查询。 */
    @Test
    void establishesResolvedProjectScopeBeforeDeviceLookup() {
        ProjectService projectService = mock(ProjectService.class);
        DeviceRepository deviceRepository = mock(DeviceRepository.class);
        TransactionLocalRlsScope transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        AtomicBoolean insideTransaction = new AtomicBoolean();
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            insideTransaction.set(true);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                insideTransaction.set(false);
            }
        }).when(transactionTemplate).execute(any());
        UUID projectId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        DeviceAccessScope scope = new DeviceAccessScope(projectId, tenantId);
        when(projectService.findDeviceAccessScope("project-1")).thenReturn(Optional.of(scope));
        doAnswer(invocation -> {
            assertThat(insideTransaction).isTrue();
            return null;
        }).when(transactionLocalRlsScope).establish(tenantId, projectId);
        IllegalStateException stopped = new IllegalStateException("停止于设备查询");
        when(deviceRepository.findByDeviceKey(projectId, "device-1")).thenThrow(stopped);
        DeviceAccessScopeService service = new DeviceAccessScopeService(
                projectService, deviceRepository, mock(DeviceTypeRepository.class),
                transactionLocalRlsScope, transactionTemplate, mock(DeviceAccessSessionRepository.class));

        assertThatThrownBy(() -> service.resolve("project-1", "device-1")).isSameAs(stopped);

        var order = inOrder(projectService, transactionLocalRlsScope, deviceRepository);
        order.verify(projectService).findDeviceAccessScope("project-1");
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(deviceRepository).findByDeviceKey(projectId, "device-1");
        assertThat(insideTransaction).isFalse();
    }
}
