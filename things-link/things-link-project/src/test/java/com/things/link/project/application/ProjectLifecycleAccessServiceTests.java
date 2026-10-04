package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR0064：只核验项目生命周期服务的状态映射、归属防御及失败传播。
 * 真实SQL过滤、事务代理、同连接与锁排序由PG整合测试证明，不在此模拟成功事务。
 */
class ProjectLifecycleAccessServiceTests {

    /** 固定本例可信租户轴，使错误返回身份与请求身份可以独立比较。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 固定本例可信项目轴，防止只校验租户却误用另一项目资格。 */
    private final UUID projectId = UUID.randomUUID();
    /** 单测只替换领域仓储边界，避免把预期状态写成一套SQL实现。 */
    private final ProjectRepository repository = mock(ProjectRepository.class);
    /** 直接构造用于检验服务防御，不能据此声明Spring事务代理已生效。 */
    private final ProjectLifecycleAccessService service = new ProjectLifecycleAccessService(repository);

    /** ARCHIVED必须保留普通读资格，不能因新写门禁而静默退化为全部拒绝。 */
    @Test
    void archivedProjectRemainsReadableButCannotAcceptWrites() {
        when(repository.findLiveByIdentity(tenantId, projectId))
                .thenReturn(Optional.of(project(tenantId, projectId, Project.Status.ARCHIVED)));

        assertThat(service.snapshot(tenantId, projectId)).isEqualTo(new ProjectAccessPolicy(true, false));
    }

    /** 注销中与查无有效项目均关闭读写，不能将没有deleted_at投影误当可用。 */
    @Test
    void deletingAndMissingProjectsDenyBothCapabilities() {
        when(repository.findLiveByIdentity(tenantId, projectId))
                .thenReturn(Optional.of(project(tenantId, projectId, Project.Status.DELETING)), Optional.empty());

        assertThat(service.snapshot(tenantId, projectId)).isEqualTo(new ProjectAccessPolicy(false, false));
        assertThat(service.snapshot(tenantId, projectId)).isEqualTo(new ProjectAccessPolicy(false, false));
    }

    /** 仓储返回ACTIVE也不能覆盖请求的可信二元组，两条身份轴分别破坏以防漏检。 */
    @Test
    void mismatchedTenantOrProjectCannotGrantActiveProjectCapabilities() {
        when(repository.findLiveByIdentity(tenantId, projectId)).thenReturn(
                Optional.of(project(UUID.randomUUID(), projectId, Project.Status.ACTIVE)),
                Optional.of(project(tenantId, UUID.randomUUID(), Project.Status.ACTIVE)));

        assertThat(service.snapshot(tenantId, projectId)).isEqualTo(new ProjectAccessPolicy(false, false));
        assertThat(service.snapshot(tenantId, projectId)).isEqualTo(new ProjectAccessPolicy(false, false));
    }

    /** 基础设施故障保留原异常实例，不能被转换为确定的生命周期失效。 */
    @Test
    void propagatesRepositoryFailureInsteadOfReturningDeniedPolicy() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("生命周期查询连接失败");
        when(repository.findLiveByIdentity(tenantId, projectId)).thenThrow(failure);

        assertThatThrownBy(() -> service.snapshot(tenantId, projectId)).isSameAs(failure);
    }

    /** 直调也须在仓储访问前拒绝无事务和只读状态；只设拒绝路径标志，不模拟任何真实连接或成功锁。 */
    @Test
    void rejectsMissingOrReadOnlyTransactionBeforeTouchingRepository() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThatThrownBy(() -> service.lockActiveForWrite(tenantId, projectId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.requireActiveForWrite(tenantId, projectId))
                .isInstanceOf(IllegalStateException.class);

        try {
            // 这里只检验只读防御分支；标志没有建立数据库事务，成功锁必须由真实PG测试验证。
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(() -> service.lockActiveForWrite(tenantId, projectId))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.requireActiveForWrite(tenantId, projectId))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(repository);
    }

    /** 两个入口的任一身份为空都是调用错误；锁入口应先校验身份，不能被无事务错误掩盖。 */
    @Test
    void rejectsNullIdentityBeforeAnyRepositoryAccess() {
        assertThatThrownBy(() -> service.snapshot(null, projectId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.snapshot(tenantId, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.lockActiveForWrite(null, projectId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.lockActiveForWrite(tenantId, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requireActiveForWrite(null, projectId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requireActiveForWrite(tenantId, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    /** 已取得真实许可的逻辑分支不再查询快照；物理持锁留给PG验收。 */
    @Test
    void requiredWriteAdmissionDoesNotRecheckSuccessfulPermit() {
        when(repository.lockActiveForWrite(tenantId, projectId)).thenReturn(true);
        logicalTransaction(() -> service.requireActiveForWrite(tenantId, projectId));
        verify(repository).lockActiveForWrite(tenantId, projectId);
        verify(repository, never()).findLiveByIdentity(any(), any());
    }

    /** 失败许可分类只允许拒绝；随后观察到ACTIVE也不能把已失败许可转成成功。 */
    @ParameterizedTest
    @EnumSource(Project.Status.class)
    void requiredWriteAdmissionClassifiesDenialWithoutReadmitting(Project.Status status) {
        when(repository.findLiveByIdentity(tenantId, projectId)).thenReturn(Optional.of(project(tenantId, projectId, status)));
        assertThatThrownBy(() -> logicalTransaction(() -> service.requireActiveForWrite(tenantId, projectId)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(status == Project.Status.ARCHIVED
                                ? ProjectErrorCode.PROJECT_READ_ONLY : ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    /** 缺失项目不泄露身份，公共调用方无需跨域引用项目错误枚举。 */
    @Test
    void requiredWriteAdmissionRejectsMissingProject() {
        assertThatThrownBy(() -> logicalTransaction(() -> service.requireActiveForWrite(tenantId, projectId)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    /** 锁故障与拒绝分类查询故障均保留原异常，不能假报确定失权。 */
    @Test
    void requiredWriteAdmissionPropagatesBothDatabaseFailureLocations() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("许可数据库不可用");
        when(repository.lockActiveForWrite(tenantId, projectId)).thenThrow(failure);
        assertThatThrownBy(() -> logicalTransaction(() -> service.requireActiveForWrite(tenantId, projectId)))
                .isSameAs(failure);
        verify(repository, never()).findLiveByIdentity(any(), any());
        doReturn(false).when(repository).lockActiveForWrite(tenantId, projectId);
        when(repository.findLiveByIdentity(tenantId, projectId)).thenThrow(failure);
        assertThatThrownBy(() -> logicalTransaction(() -> service.requireActiveForWrite(tenantId, projectId)))
                .isSameAs(failure);
    }

    /** 只设编排测试所需线程标志，不建立连接；finally恢复原标志，不冒充事务/锁验收。 */
    private void logicalTransaction(Runnable action) {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        try {
            action.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(active);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        }
    }

    /** 仅构造仓储公开领域投影；软删是否排除属于findLiveByIdentity真实SQL合同。 */
    private Project project(UUID factTenantId, UUID factProjectId, Project.Status status) {
        return new Project(factProjectId, factTenantId, "生命周期单测", "sh-1", "Asia/Shanghai",
                "lifecycle_test", status, Instant.parse("2026-09-03T00:00:00Z"));
    }
}
