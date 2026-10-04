package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR0064决策2/5：检验授权顺序、锁后失权与错误分类，不用Mockito宣称真实事务或行锁成立。
 * 这里只设置事务标志越过显式前置；Spring MANDATORY、实际SQL和并发锁由真实PG测试承担。
 */
class ProjectManagementWriteGuardTests {

    /** 请求中的项目轴，不以JWT租户推导归属。 */
    private final UUID projectId = UUID.randomUUID();
    /** 当前控制台账号，独立于项目归属租户。 */
    private final UUID accountId = UUID.randomUUID();
    /** 只替换项目域仓储，行为断言不复制SQL实现。 */
    private final ProjectRepository repository = mock(ProjectRepository.class);
    /** 直接实例用于规则检查；不能据此证明Spring事务代理。 */
    private final ProjectManagementWriteGuard guard = new ProjectManagementWriteGuard(repository);

    /** 标志只用于到达规则分支，不建立数据库连接或锁。 */
    @BeforeEach
    void markWritableTransactionForRuleTests() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    /** 每例恢复线程标志，避免污染同JVM后续真实事务测试。 */
    @AfterEach
    void clearTransactionMarkers() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /** 三种许可均先预检，再排他锁，最后使用重新查询的角色。 */
    @ParameterizedTest
    @EnumSource(Access.class)
    void activeProjectRequiresAuthorizationBeforeAndAfterExclusiveLock(Access access) {
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(access.allowedRole()));
        when(repository.lockForManagement(projectId)).thenReturn(Optional.of(project(Project.Status.ACTIVE)));

        assertThat(invoke(access, projectId, accountId)).isEqualTo(access.allowedRole());

        var order = inOrder(repository);
        order.verify(repository).findRole(projectId, accountId);
        order.verify(repository).lockForManagement(projectId);
        order.verify(repository).findRole(projectId, accountId);
        order.verifyNoMoreInteractions();
    }

    /** 非成员不能为了探测归档状态或存在性而锁住任意项目。 */
    @ParameterizedTest
    @EnumSource(Access.class)
    void nonMemberIsRejectedBeforeLockingOrReadingProjectStatus(Access access) {
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.empty());

        assertBusinessError(access, ProjectErrorCode.PROJECT_NOT_FOUND);

        verify(repository).findRole(projectId, accountId);
        verifyNoMoreInteractions(repository);
    }

    /** 归档不是绕过权限的替代码：原来角色不足时仍返回原50002/50003。 */
    @ParameterizedTest
    @EnumSource(value = Access.class, names = {"OWNER", "MANAGER"})
    void insufficientRoleIsRejectedBeforeLockingProject(Access access) {
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(ProjectRole.VIEWER));

        assertBusinessError(access, access.forbidden());

        verify(repository).findRole(projectId, accountId);
        verifyNoMoreInteractions(repository);
    }

    /** 等待期间项目被删除时，锁查询为空直接50001，不用旧角色继续操作。 */
    @ParameterizedTest
    @EnumSource(Access.class)
    void disappearedProjectCannotUsePreflightRole(Access access) {
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(access.allowedRole()));
        when(repository.lockForManagement(projectId)).thenReturn(Optional.empty());

        assertBusinessError(access, ProjectErrorCode.PROJECT_NOT_FOUND);

        var order = inOrder(repository);
        order.verify(repository).findRole(projectId, accountId);
        order.verify(repository).lockForManagement(projectId);
        order.verifyNoMoreInteractions();
    }

    /** 锁后成员丢失时归档也只能50001，禁止先发50017泄露归档事实。 */
    @ParameterizedTest
    @EnumSource(Access.class)
    void membershipLostWhileWaitingHidesArchivedState(Access access) {
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(access.allowedRole()), Optional.empty());
        when(repository.lockForManagement(projectId)).thenReturn(Optional.of(project(Project.Status.ARCHIVED)));

        assertBusinessError(access, ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** OWNER被转让或管理者被降级后必须重新判定，归档不能覆盖原权限错误。 */
    @ParameterizedTest
    @EnumSource(value = Access.class, names = {"OWNER", "MANAGER"})
    void downgradedRoleAfterLockCannotReuseEarlierAuthority(Access access) {
        ProjectRole downgraded = access == Access.OWNER ? ProjectRole.ADMIN : ProjectRole.OPERATOR;
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(access.allowedRole()), Optional.of(downgraded));
        when(repository.lockForManagement(projectId)).thenReturn(Optional.of(project(Project.Status.ARCHIVED)));

        assertBusinessError(access, access.forbidden());
    }

    /** ACTIVE成员入口返回最新角色，让leave等调用方基于当前OWNER执行专属限制。 */
    @Test
    void memberPermissionReturnsPostLockRoleWhenOwnershipChanges() {
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(ProjectRole.VIEWER), Optional.of(ProjectRole.OWNER));
        when(repository.lockForManagement(projectId)).thenReturn(Optional.of(project(Project.Status.ACTIVE)));

        assertThat(guard.requireMember(projectId, accountId)).isEqualTo(ProjectRole.OWNER);
    }

    /** 只有锁后仍有该操作权限的归档项目才返回控制台50017，而非App60022。 */
    @ParameterizedTest
    @EnumSource(Access.class)
    void authorizedArchivedProjectReturnsReadOnlyAfterReauthorization(Access access) {
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(access.allowedRole()));
        when(repository.lockForManagement(projectId)).thenReturn(Optional.of(project(Project.Status.ARCHIVED)));

        assertBusinessError(access, ProjectErrorCode.PROJECT_READ_ONLY);
        assertThat(ProjectErrorCode.PROJECT_READ_ONLY.code()).isEqualTo(50017);
        assertThat(ProjectErrorCode.PROJECT_READ_ONLY.httpStatus()).isEqualTo(403);

        var order = inOrder(repository);
        order.verify(repository).findRole(projectId, accountId);
        order.verify(repository).lockForManagement(projectId);
        order.verify(repository).findRole(projectId, accountId);
        order.verifyNoMoreInteractions();
    }

    /** 原锁异常原样传播，不重查快照将基础设施故障伪装为项目只读或不存在。 */
    @ParameterizedTest
    @EnumSource(Access.class)
    void lockSqlFailurePropagatesWithoutFallback(Access access) {
        var failure = new DataAccessResourceFailureException("项目排他锁连接失败");
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(access.allowedRole()));
        when(repository.lockForManagement(projectId)).thenThrow(failure);

        assertThatThrownBy(() -> invoke(access, projectId, accountId)).isSameAs(failure);

        var order = inOrder(repository);
        order.verify(repository).findRole(projectId, accountId);
        order.verify(repository).lockForManagement(projectId);
        order.verifyNoMoreInteractions();
    }

    /** 锁后重新授权的SQL失败也必须穿透原事务，不能沿用预检角色。 */
    @Test
    void postLockRoleQueryFailurePropagatesUnchanged() {
        var failure = new DataAccessResourceFailureException("锁后成员查询失败");
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(ProjectRole.OWNER)).thenThrow(failure);
        when(repository.lockForManagement(projectId)).thenReturn(Optional.of(project(Project.Status.ACTIVE)));

        assertThatThrownBy(() -> guard.requireOwner(projectId, accountId)).isSameAs(failure);
    }

    /** 预检SQL故障不应加锁，也不能分类成非成员。 */
    @Test
    void preflightSqlFailureDoesNotLockProject() {
        var failure = new DataAccessResourceFailureException("成员预检失败");
        when(repository.findRole(projectId, accountId)).thenThrow(failure);

        assertThatThrownBy(() -> guard.requireMember(projectId, accountId)).isSameAs(failure);
        verify(repository).findRole(projectId, accountId);
        verifyNoMoreInteractions(repository);
    }

    /** 直调无事务和只读事务都必须在任何仓储访问前拒绝，不能依赖代理单一防线。 */
    @ParameterizedTest
    @EnumSource(Access.class)
    void missingOrReadOnlyTransactionIsRejectedBeforeRepository(Access access) {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> invoke(access, projectId, accountId)).isExactlyInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> invoke(access, projectId, accountId)).isExactlyInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    /** 参数不完整是调用错误；不能由ThreadLocal补齐，也不能被无事务检查掩盖。 */
    @ParameterizedTest
    @EnumSource(Access.class)
    void missingIdentityIsRejectedBeforeTransactionAndRepository(Access access) {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> invoke(access, null, accountId)).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoke(access, projectId, null)).isExactlyInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    /** 领域投影也要匹配请求项目；仓储错误返回其他ACTIVE事实不能误授许可。 */
    @Test
    void mismatchedProjectFactCannotAuthorizeRequestedProject() {
        Project other = new Project(UUID.randomUUID(), UUID.randomUUID(), "其他项目", "sh-1", "Asia/Shanghai",
                "other_project", Project.Status.ACTIVE, Instant.EPOCH);
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(repository.lockForManagement(projectId)).thenReturn(Optional.of(other));

        assertBusinessError(Access.OWNER, ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** 仓储合同外的DELETING投影也不能被误当ACTIVE默认放行。 */
    @Test
    void deletingProjectFactCannotGrantWritePermission() {
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(repository.lockForManagement(projectId)).thenReturn(Optional.of(project(Project.Status.DELETING)));

        assertBusinessError(Access.OWNER, ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** 每种公共入口都走实际方法，不用测试自写授权算法替代被测对象。 */
    private ProjectRole invoke(Access access, UUID requestedProject, UUID requestedAccount) {
        return switch (access) {
            case OWNER -> guard.requireOwner(requestedProject, requestedAccount);
            case MANAGER -> guard.requireMemberManager(requestedProject, requestedAccount);
            case MEMBER -> guard.requireMember(requestedProject, requestedAccount);
        };
    }

    /** 精确断言错误枚举，避免只看403/404而混淆归档与权限失效。 */
    private void assertBusinessError(Access access, ProjectErrorCode expected) {
        assertThatThrownBy(() -> invoke(access, projectId, accountId))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(expected));
    }

    /** 项目归属是仓储事实，与调用账号独立；门禁不要求调用者传tenant。 */
    private Project project(Project.Status status) {
        return new Project(projectId, UUID.randomUUID(), "项目管理许可", "sh-1", "Asia/Shanghai",
                "management_guard", status, Instant.EPOCH);
    }

    /** 参数化公共入口边界，不额外增加领域角色定义。 */
    private enum Access {
        /** 生命周期与转让OWNER要求。 */ OWNER,
        /** 普通成员管理要求。 */ MANAGER,
        /** 主动退出所需最低成员要求。 */ MEMBER;

        /** 各入口选择不同有效角色，避免全部OWNER掩盖权限差异。 */
        private ProjectRole allowedRole() {
            return switch (this) {
                case OWNER -> ProjectRole.OWNER;
                case MANAGER -> ProjectRole.ADMIN;
                case MEMBER -> ProjectRole.VIEWER;
            };
        }

        /** 不足角色沿用已有错误，归档分类必须排在它之后。 */
        private ProjectErrorCode forbidden() {
            return this == OWNER ? ProjectErrorCode.PROJECT_OWNER_REQUIRED
                    : ProjectErrorCode.MEMBER_MANAGEMENT_FORBIDDEN;
        }
    }
}
