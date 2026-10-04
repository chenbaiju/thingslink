package com.things.link.export.application;

import com.things.link.export.domain.ProjectExportJob;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.export.domain.ProjectExportStatus;
import com.things.link.project.application.ProjectExportSource;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.storage.PrivateObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 项目导出请求在额度、限流和幂等回读之间的失败优先级测试。 */
class ProjectExportServiceTests {

    /** 每例清除线程范围。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** Redis不可确认额度时必须在项目授权和数据库查询前10029拒绝且零副作用。 */
    @Test
    void rateLimiterFailureRejectsBeforeAuthorizationAndPersistence() {
        Dependencies dependencies = dependencies();
        UUID accountId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), null, accountId));
        when(dependencies.rateLimiter().tryAcquire(accountId, projectId)).thenReturn(false);

        Throwable failure = catchThrowable(() -> dependencies.service().request(projectId));

        assertThat(failure).isExactlyInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(10029);
        verifyNoInteractions(dependencies.projectSource(), dependencies.jobs(), dependencies.audits());
    }

    /** 已启用但无统一账本的存储额度必须50019拒绝，且不能建立任务或请求审计。 */
    @Test
    void configuredStorageQuotaRejectsNewTaskWithoutSideEffects() {
        Dependencies dependencies = dependencies();
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        TenantContext.set(new TenantScope(tenantId, null, accountId));
        when(dependencies.rateLimiter().tryAcquire(accountId, projectId)).thenReturn(true);
        when(dependencies.projectSource().authorizeRequest(accountId, projectId)).thenReturn(
                new ProjectExportSource.ProjectExportScope(
                        tenantId, projectId, 3, Instant.now().minusSeconds(60), true));
        when(dependencies.jobs().findActive(tenantId, projectId, 3)).thenReturn(Optional.empty());

        Throwable failure = catchThrowable(() -> dependencies.service().request(projectId));

        assertThat(failure).isExactlyInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(50019);
        verify(dependencies.jobs(), never()).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(dependencies.audits());
    }

    /** 存储额度后来启用时，同代次既有非终态任务仍应先回读，避免幂等重试被新策略截断。 */
    @Test
    void existingNonTerminalTaskPrecedesStorageQuotaRejection() {
        Dependencies dependencies = dependencies();
        UUID accountId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        TenantContext.set(new TenantScope(tenantId, null, accountId));
        ProjectExportJob existing = new ProjectExportJob(
                UUID.randomUUID(), tenantId, projectId, 3, accountId, ProjectExportStatus.QUEUED,
                0, Instant.now(), null, null, null, null, null, Instant.now(), null, null, null);
        when(dependencies.rateLimiter().tryAcquire(accountId, projectId)).thenReturn(true);
        when(dependencies.projectSource().authorizeRequest(accountId, projectId)).thenReturn(
                new ProjectExportSource.ProjectExportScope(
                        tenantId, projectId, 3, Instant.now().minusSeconds(60), true));
        when(dependencies.jobs().findActive(tenantId, projectId, 3)).thenReturn(Optional.of(existing));

        assertThat(dependencies.service().request(projectId)).isSameAs(existing);
        verify(dependencies.jobs(), never()).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(dependencies.audits());
    }

    /** 构造隔离的用例依赖。 */
    private static Dependencies dependencies() {
        ProjectExportSource projectSource = mock(ProjectExportSource.class);
        ProjectExportJobRepository jobs = mock(ProjectExportJobRepository.class);
        AuditLogService audits = mock(AuditLogService.class);
        ProjectExportRateLimiter rateLimiter = mock(ProjectExportRateLimiter.class);
        PrivateObjectStorage objectStorage = mock(PrivateObjectStorage.class);
        return new Dependencies(projectSource, jobs, audits, rateLimiter,
                new ProjectExportService(projectSource, jobs, audits, rateLimiter, objectStorage));
    }

    /** 用例依赖集合。 */
    private record Dependencies(ProjectExportSource projectSource,
                                ProjectExportJobRepository jobs,
                                AuditLogService audits,
                                ProjectExportRateLimiter rateLimiter,
                                ProjectExportService service) {
    }
}
