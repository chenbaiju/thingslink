package com.things.link.device.application;

import com.things.link.device.domain.DeviceCredentialRepository;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectQuotaService;
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
import static org.mockito.Mockito.when;

/** S12-2a1c动态注册范围编排测试；配额与并发硬限由既有真实PostgreSQL测试覆盖。 */
class DeviceRegistrationServiceTests {

    /** 项目确权必须先于事务，完整范围必须在事务回调内早于设备类型查询建立。 */
    @Test
    void establishesResolvedProjectScopeInsideRegistrationTransaction() {
        ProjectService projectService = mock(ProjectService.class);
        DeviceTypeRepository typeRepository = mock(DeviceTypeRepository.class);
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
        IllegalStateException stopped = new IllegalStateException("停止于设备类型查询");
        when(typeRepository.findPublishedByProductKey(projectId, "product-1")).thenThrow(stopped);
        DeviceRegistrationService service = new DeviceRegistrationService(
                projectService, mock(ProjectQuotaService.class), typeRepository,
                mock(DeviceRepository.class), mock(DeviceCredentialRepository.class),
                transactionLocalRlsScope, transactionTemplate);

        assertThatThrownBy(() -> service.register("project-1", "product-1", "secret", "device-1"))
                .isSameAs(stopped);

        var order = inOrder(projectService, transactionLocalRlsScope, typeRepository);
        order.verify(projectService).findDeviceAccessScope("project-1");
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(typeRepository).findPublishedByProductKey(projectId, "product-1");
        assertThat(insideTransaction).isFalse();
    }
}
