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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S12-P0-5d2：验证三个成员管理入口真正使用项目排他许可，许可拒绝先于目标读取及所有副作用。
 * 不重写guard角色矩阵；真实Spring事务、PG排序和回滚由专用整合测试承担。
 */
class ProjectMemberServiceTests {

    /** 调用者所属租户仅保持原审计范围，不作为guard项目归属参数。 */
    private final UUID callerTenantId = UUID.randomUUID();
    /** 路径项目独立于当前Token选中的项目，禁止从Token替换请求目标。 */
    private final UUID projectId = UUID.randomUUID();
    /** 已认证的真实控制台操作账号。 */
    private final UUID actorId = UUID.randomUUID();
    /** 与操作人不同的合法目标，拒绝用例不能靠自操作校验意外通过。 */
    private final UUID targetId = UUID.randomUUID();
    /** 仓储成功桩让缺少新许可的旧路径真正能成功，保证拒绝反例有辨识度。 */
    private final ProjectRepository repository = mock(ProjectRepository.class);
    /** 邀请查人的跨域端口，项目拒绝后绝不能访问。 */
    private final AccountDirectory directory = mock(AccountDirectory.class);
    /** 审计与写入同属许可后的副作用。 */
    private final AuditLogService audit = mock(AuditLogService.class);
    /** 许可由mock控制，配合仓储/目录/审计桩检验接线及传播顺序，不声称真实行锁。 */
    private final ProjectManagementWriteGuard guard = mock(ProjectManagementWriteGuard.class);
    /** 三个公共用例入口均调用生产实现。 */
    private final CollaborationAdmissionService admission = mock(CollaborationAdmissionService.class);
    private final ProjectMemberService service = new ProjectMemberService(repository, directory, audit, guard,
            admission);

    /** 完整合法目标与旧管理者授权使未接线实现不会因缺桩提前失败。 */
    @BeforeEach
    void prepareSuccessfulLegacyPath() {
        TenantContext.set(new TenantScope(callerTenantId, UUID.randomUUID(), actorId));
        when(repository.findRole(projectId, actorId)).thenReturn(Optional.of(ProjectRole.ADMIN));
        when(repository.findRole(projectId, targetId)).thenReturn(Optional.of(ProjectRole.VIEWER));
        when(directory.findByEmail("member@example.com"))
                .thenReturn(Optional.of(new AccountRef(targetId, "member@example.com", "目标成员")));
        when(repository.updateMemberRole(projectId, targetId, ProjectRole.OPERATOR)).thenReturn(1);
        when(repository.removeMember(projectId, targetId)).thenReturn(1);
        when(guard.requireMemberManager(projectId, actorId)).thenReturn(ProjectRole.ADMIN);
    }

    /** 不让调用范围泄漏到同JVM的后续测试。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** 归档拒绝原样抛出；目标、账号目录和审计必须完全无交互。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void permissionDenialStopsBeforeTargetDirectoryAndAudit(Operation operation) {
        BusinessException denial = new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        when(guard.requireMemberManager(projectId, actorId)).thenThrow(denial);

        assertThatThrownBy(() -> invoke(operation)).isSameAs(denial);

        verify(guard).requireMemberManager(projectId, actorId);
        verifyNoInteractions(repository, directory, audit);
    }

    /** 基础设施故障不得被转为业务成功/失败，也不能继续读取目标或产生审计。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void permissionSqlFailurePropagatesBeforeAnyDownstreamAccess(Operation operation) {
        var failure = new DataAccessResourceFailureException("项目许可数据库不可用");
        when(guard.requireMemberManager(projectId, actorId)).thenThrow(failure);

        assertThatThrownBy(() -> invoke(operation)).isSameAs(failure);

        verifyNoInteractions(repository, directory, audit);
    }

    /** 许可成功先于全部业务读取/修改；审计仍携带原调用scope和路径项目，避免接线误换归属。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void permissionPrecedesBusinessWorkAndPreservesAuditIdentity(Operation operation) {
        invoke(operation);

        var order = inOrder(guard, repository, directory, audit);
        order.verify(guard).requireMemberManager(projectId, actorId);
        switch (operation) {
            case INVITE -> {
                order.verify(directory).findByEmail("member@example.com");
                order.verify(repository).addMember(any(UUID.class), eq(projectId), eq(targetId), eq(ProjectRole.OPERATOR));
            }
            case UPDATE_ROLE -> {
                order.verify(repository).findRole(projectId, targetId);
                order.verify(repository).updateMemberRole(projectId, targetId, ProjectRole.OPERATOR);
            }
            case REMOVE -> {
                order.verify(repository).findRole(projectId, targetId);
                order.verify(repository).removeMember(projectId, targetId);
            }
        }
        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        order.verify(audit).record(entry.capture());
        order.verifyNoMoreInteractions();
        assertThat(entry.getValue()).isEqualTo(new AuditLogEntry(callerTenantId, projectId, actorId,
                "project_member", targetId, operation.action(), operation.details()));
    }

    /** 传合法参数调用真正入口，邀请保留trim合同。 */
    private void invoke(Operation operation) {
        switch (operation) {
            case INVITE -> service.invite(projectId, " member@example.com ", ProjectRole.OPERATOR);
            case UPDATE_ROLE -> service.updateRole(projectId, targetId, ProjectRole.OPERATOR);
            case REMOVE -> service.remove(projectId, targetId);
        }
    }

    /** 本片只覆盖邀请/改角色/移除，不把转让或主动退出提前算作已接线。 */
    private enum Operation {
        /** 邀请涉及账号目录与新成员写。 */ INVITE,
        /** 改角色涉及目标读取与角色写。 */ UPDATE_ROLE,
        /** 移除涉及目标读取与成员删除。 */ REMOVE;

        /** 原审计动作不可因加锁改名，避免审计查询与既有合同断裂。 */
        private String action() {
            return switch (this) {
                case INVITE -> "project.member.invited";
                case UPDATE_ROLE -> "project.member.role_updated";
                case REMOVE -> "project.member.removed";
            };
        }

        /** 保持原动作的审计字段与变更前角色，不让新guard覆盖目标事实。 */
        private Map<String, ?> details() {
            return switch (this) {
                case INVITE -> Map.of("email", "member@example.com", "role", "OPERATOR");
                case UPDATE_ROLE -> Map.of("oldRole", "VIEWER", "newRole", "OPERATOR");
                case REMOVE -> Map.of("oldRole", "VIEWER");
            };
        }
    }
}
