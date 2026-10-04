package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignRuntimeRepository;

import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaFirmwareLifecycleRepository;
import com.things.link.ota.domain.OtaFirmwareLifecycleState;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 原因Unicode边界与权限复核反例；真实数据库CAS和审计原子性由集成测试证明。 */
class OtaFirmwareLifecycleServiceTests {
    /** 本例结束不把身份遗留给复用线程。 */
    @AfterEach
    void clear() { TenantContext.clear(); }

    /** 512个补充平面字符按码点而非UTF16单元计数，原样原因不被trim。 */
    @Test
    void reasonCountsUnicodeCodePointsAndRejectsAmbiguousWhitespace() {
        OtaFirmwareLifecycleService.requireReason("😀".repeat(512));
        OtaFirmwareLifecycleService.requireReason("修复 风险");
        for (String reason : new String[] {"", "😀".repeat(513), " leading", "trailing ",
                "\u00a0reason", "reason\u2007", "line\nfeed", "control\u0085", "\ud800", "x\udfff"}) {
            assertThatThrownBy(() -> OtaFirmwareLifecycleService.requireReason(reason)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> OtaFirmwareLifecycleService.requireReason(null)).isInstanceOf(BusinessException.class);
    }

    /** 修订精确支持long上限，不接受有损转换、前导零或符号。 */
    @Test
    void revisionUsesCanonicalNonnegativeLongText() {
        assertThat(OtaFirmwareLifecycleService.revision("0")).isZero();
        assertThat(OtaFirmwareLifecycleService.revision("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        for (String value : new String[] {"01", "-0", "+1", " 2", "2 ", "1.0", "1e2", "9223372036854775808"}) {
            assertThatThrownBy(() -> OtaFirmwareLifecycleService.revision(value)).isInstanceOf(BusinessException.class);
        }
    }

    /** 管理角色在项目锁后已撤销时不能读取固件或继续CAS。 */
    @Test
    void revokedRoleAfterProjectLockPreventsFirmwareAccess() {
        UUID tenant = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID firmware = UUID.randomUUID();
        OtaFirmwareLifecycleRepository repository = mock(OtaFirmwareLifecycleRepository.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
        OtaFirmwareLifecycleService service = new OtaFirmwareLifecycleService(repository, projects, lifecycle,
                mock(AuditLogService.class), mock(OtaCampaignRuntimeRepository.class));
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.ADMIN, ProjectRole.VIEWER);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        assertThatThrownBy(() -> service.deprecate(project, firmware, "key", "2", "风险控制"))
                .isInstanceOf(BusinessException.class);
        verify(lifecycle).requireActiveForWrite(tenant, project);
        verify(repository, never()).find(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** 仓储CAS失败时不会伪造成功读取或写入审计。 */
    @Test
    void failedCasDoesNotWriteSuccessAudit() {
        UUID tenant = UUID.randomUUID(); UUID project = UUID.randomUUID(); UUID firmware = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantContext.set(new TenantScope(tenant, project, actor));
        OtaFirmwareLifecycleRepository repository = mock(OtaFirmwareLifecycleRepository.class);
        ProjectService projects = mock(ProjectService.class);
        AuditLogService audit = mock(AuditLogService.class);
        OtaFirmwareLifecycleService service = new OtaFirmwareLifecycleService(repository, projects,
                mock(ProjectLifecycleAccessService.class), audit, mock(OtaCampaignRuntimeRepository.class));
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.OWNER);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        OtaFirmware row = new OtaFirmware(firmware, tenant, project, actor, UUID.randomUUID(), UUID.randomUUID(),
                "product", "version", "PG_JSONB_TEXT_V1_SHA256", "a".repeat(64), "TC_PROPERTY_COMPOSITE_V1",
                "READY", 2, Instant.EPOCH, null);
        when(repository.find(project, firmware, true)).thenReturn(Optional.of(new OtaFirmwareLifecycleState(row, null, null)));
        when(repository.deprecate(any(), any(), any(), anyLong(), anyString(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.deprecate(project, firmware, "key", "2", "风险控制"))
                .isInstanceOf(BusinessException.class);
        verify(audit, never()).record(any());
        verify(repository, never()).find(project, firmware, false);
    }
}
