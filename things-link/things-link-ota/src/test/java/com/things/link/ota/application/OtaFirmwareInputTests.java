package com.things.link.ota.application;

import com.things.link.device.application.OtaModelSnapshotPort;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaUploadRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.audit.AuditLogService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** UTF8替换编码不能让非法输入与既有问号文本共享幂等摘要，即使绕过HTTP直接调用应用服务。 */
class OtaFirmwareInputTests {
    /** 参数在查询恢复映射前拒绝；本例不模拟数据库事务资格。 */
    @Test
    void rejectsUnpairedSurrogatesBeforeIdempotencyLookup() {
        UUID project = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        ProjectService projects = mock(ProjectService.class);
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.OWNER);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        OtaFirmwareRepository repository = mock(OtaFirmwareRepository.class);
        OtaModelSnapshotPort models = mock(OtaModelSnapshotPort.class);
        OtaFirmwareService service = new OtaFirmwareService(repository, projects,
                mock(ProjectLifecycleAccessService.class), models, mock(AuditLogService.class), mock(OtaUploadRepository.class));
        assertInvalid(() -> service.createIdempotent(project, "key", UUID.randomUUID(), UUID.randomUUID(), "\ud800"));
        assertInvalid(() -> service.createIdempotent(project, "\ud800", UUID.randomUUID(), UUID.randomUUID(), "valid"));
        verifyNoInteractions(repository, models);
    }

    /** 固定参数码，不能先访问恢复映射或回显非法文本。 */
    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
    }
}
