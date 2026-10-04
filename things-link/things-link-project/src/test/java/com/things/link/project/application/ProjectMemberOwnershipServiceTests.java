package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S12-P0-5d3：转让和主动退出必须先取得各自排他许可，退出规则及审计只能使用锁后角色。
 * 此处只检验生产接线，不把mock成功当真实事务、所有权原子性或锁排序证据。
 */
class ProjectMemberOwnershipServiceTests {

    /** 保持原审计租户，不传给项目管理许可冒充项目归属。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 路径项目独立于令牌选中项目，检验显式身份传递。 */
    private final UUID projectId = UUID.randomUUID();
    /** 已认证操作账号，可以在等待后得到不同角色。 */
    private final UUID actorId = UUID.randomUUID();
    /** 转让目标与操作账号独立，避免被自操作限制提前拒绝。 */
    private final UUID targetId = UUID.randomUUID();
    /** 完整成功桩让旧实现真正可执行，从而辨识漏接许可。 */
    private final ProjectRepository repository = mock(ProjectRepository.class);
    /** 两个操作均不需要查账号目录。 */
    private final AccountDirectory directory = mock(AccountDirectory.class);
    /** 审计载荷保持原目标与变更前角色。 */
    private final AuditLogService audit = mock(AuditLogService.class);
    /** 模拟许可结果与故障，仅检验调用方使用方式。 */
    private final ProjectManagementWriteGuard guard = mock(ProjectManagementWriteGuard.class);
    /** 运行真实服务方法，事务与行锁另由PG测试验收。 */
    private final CollaborationAdmissionService admission = mock(CollaborationAdmissionService.class);
    private final ProjectMemberService service = new ProjectMemberService(repository, directory, audit, guard,
            admission);

    /** 不从当前令牌项目替换接口目标，审计仍使用原scope租户和账号。 */
    @BeforeEach
    void establishCallerScope() {
        TenantContext.set(new TenantScope(tenantId, UUID.randomUUID(), actorId));
    }

    /** 防止同JVM用例继承身份。 */
    @AfterEach
    void clearCallerScope() {
        TenantContext.clear();
    }

    /** 合法旧OWNER/成员仍须因归档许可拒绝，不能继续目标读取、双角色更新或删除。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void permissionDenialStopsTransferOrLeaveBeforeBusinessAccess(Operation operation) {
        prepareSuccessfulLegacyPath(operation);
        BusinessException denial = new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        if (operation == Operation.TRANSFER) {
            when(guard.requireOwner(projectId, actorId)).thenThrow(denial);
        } else {
            when(guard.requireMember(projectId, actorId)).thenThrow(denial);
        }

        assertThatThrownBy(() -> invoke(operation)).isSameAs(denial);
        verifyNoInteractions(repository, directory, audit);
    }

    /** SQL许可故障原样传播，不能伪装为失权或沿旧角色继续。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void permissionSqlFailureStopsTransferOrLeaveBeforeBusinessAccess(Operation operation) {
        prepareSuccessfulLegacyPath(operation);
        var failure = new DataAccessResourceFailureException("项目排他许可连接失败");
        if (operation == Operation.TRANSFER) {
            when(guard.requireOwner(projectId, actorId)).thenThrow(failure);
        } else {
            when(guard.requireMember(projectId, actorId)).thenThrow(failure);
        }

        assertThatThrownBy(() -> invoke(operation)).isSameAs(failure);
        verifyNoInteractions(repository, directory, audit);
    }

    /** OWNER许可先于目标角色查询、原双角色更新及完整转让审计。 */
    @Test
    void transferPermissionPrecedesTargetAndPreservesOwnershipAudit() {
        prepareSuccessfulLegacyPath(Operation.TRANSFER);

        service.transferOwnership(projectId, targetId);

        var order = inOrder(guard, repository, audit);
        order.verify(guard).requireOwner(projectId, actorId);
        order.verify(repository).findRole(projectId, targetId);
        order.verify(repository).transferOwnership(projectId, actorId, targetId);
        order.verify(audit).record(new AuditLogEntry(tenantId, projectId, actorId, "project_member", targetId,
                "project.member.ownership_transferred", Map.of("oldOwnerId", actorId.toString(),
                "oldOwnerNewRole", "ADMIN", "newOwnerOldRole", "VIEWER", "newOwnerNewRole", "OWNER")));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(directory);
    }

    /** 锁后已转为VIEWER时可以退出，即使仓储预检旧桩仍为OWNER；审计必须记录最新VIEWER。 */
    @Test
    void leaveUsesCurrentViewerFromGuardInsteadOfOldOwnerSnapshot() {
        prepareSuccessfulLegacyPath(Operation.LEAVE);
        when(repository.findRole(projectId, actorId)).thenReturn(Optional.of(ProjectRole.OWNER));

        service.leave(projectId);

        var order = inOrder(guard, repository, audit);
        order.verify(guard).requireMember(projectId, actorId);
        order.verify(repository).removeMember(projectId, actorId);
        order.verify(audit).record(new AuditLogEntry(tenantId, projectId, actorId, "project_member", actorId,
                "project.member.left", Map.of("oldRole", "VIEWER")));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(directory);
    }

    /** 锁后已经成为OWNER时必须50016，不能沿仓储旧VIEWER角色移除最后管理入口。 */
    @Test
    void leaveRejectsCurrentOwnerFromGuardWithoutReadingOldViewerSnapshot() {
        prepareSuccessfulLegacyPath(Operation.LEAVE);
        when(guard.requireMember(projectId, actorId)).thenReturn(ProjectRole.OWNER);

        assertThatThrownBy(() -> service.leave(projectId)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ProjectErrorCode.OWNER_CANNOT_LEAVE));
        verifyNoInteractions(repository, directory, audit);
    }

    /** 原路径有效，避免拒绝反例实际因mock缺桩而失败。 */
    private void prepareSuccessfulLegacyPath(Operation operation) {
        when(repository.findRole(projectId, actorId)).thenReturn(Optional.of(
                operation == Operation.TRANSFER ? ProjectRole.OWNER : ProjectRole.VIEWER));
        when(repository.findRole(projectId, targetId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        when(repository.transferOwnership(projectId, actorId, targetId)).thenReturn(true);
        when(repository.removeMember(projectId, actorId)).thenReturn(1);
        when(guard.requireOwner(projectId, actorId)).thenReturn(ProjectRole.OWNER);
        when(guard.requireMember(projectId, actorId)).thenReturn(ProjectRole.VIEWER);
    }

    /** 两个真实公开入口使用相同可信项目与账号范围。 */
    private void invoke(Operation operation) {
        if (operation == Operation.TRANSFER) {
            service.transferOwnership(projectId, targetId);
        } else {
            service.leave(projectId);
        }
    }

    /** 两种权限不同的用例不能混成普通成员管理许可。 */
    private enum Operation {
        /** 转让需要锁后OWNER。 */ TRANSFER,
        /** 退出需要锁后成员角色并保留OWNER保护。 */ LEAVE
    }
}
