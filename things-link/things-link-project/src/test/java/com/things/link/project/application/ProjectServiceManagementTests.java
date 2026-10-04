package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectMembership;
import com.things.link.project.domain.ProjectRegionRepository;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S12-P0-5d4：改名和删除先取得OWNER排他许可，保留人数与条件更新失败边界。
 * 只检验服务接线及调用顺序，成员保全、真实锁/事务回滚由PG和HTTP验收承担。
 */
class ProjectServiceManagementTests {

    /** 调用者租户不充当项目归属，许可只接显式项目和账号。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 路径项目与Token当前项目独立，避免误用上下文目标。 */
    private final UUID projectId = UUID.randomUUID();
    /** 真实控制台调用账号，不从tenant推导OWNER。 */
    private final UUID accountId = UUID.randomUUID();
    /** 完整旧路径桩保证漏接许可时能真正成功，不因缺少成员桩伪红。 */
    private final ProjectRepository repository = mock(ProjectRepository.class);
    /** 两个既有项目写入口均无需区域目录，创建合同由原时区测试保护。 */
    private final ProjectRegionRepository regions = mock(ProjectRegionRepository.class);
    /** 门禁测试已承担角色矩阵，此处只关注调用方是否先请求许可。 */
    private final ProjectManagementWriteGuard guard = mock(ProjectManagementWriteGuard.class);
    /** S14-2b 的创建配额判据；本类不触达创建用例，桩仅用于接线。 */
    private final EffectiveQuotaPolicyProvider quotaPolicyProvider = mock(EffectiveQuotaPolicyProvider.class);
    /** 使用生产服务，不模拟额外审计或数据库行为。 */
    private final ProjectService service = new ProjectService(repository, regions, guard, quotaPolicyProvider,
            mock(SubscriptionExpansionGuard.class));
    /** 原改名后仓储回读结果，必须原样返回而非服务重建旧角色。 */
    private final ProjectMembership renamed = new ProjectMembership(new Project(projectId, UUID.randomUUID(),
            "新名称", "sh-1", "Asia/Shanghai", "management_test", Project.Status.ACTIVE, Instant.EPOCH), ProjectRole.OWNER);

    /** 旧角色检查与写入均有合法桩，许可的旧路径反例才能实际执行成功。 */
    @BeforeEach
    void prepareSuccessfulLegacyPath() {
        TenantContext.set(new TenantScope(tenantId, UUID.randomUUID(), accountId));
        when(repository.findRole(projectId, accountId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(repository.countActiveMembers(projectId)).thenReturn(1);
        when(repository.softDelete(projectId)).thenReturn(1);
        when(repository.updateName(projectId, "新名称")).thenReturn(1);
        when(repository.findMembership(projectId, accountId)).thenReturn(Optional.of(renamed));
        when(guard.requireOwner(projectId, accountId)).thenReturn(ProjectRole.OWNER);
    }

    /** 清空调用身份，不影响同JVM后续用例。 */
    @AfterEach
    void clearCallerScope() {
        TenantContext.clear();
    }

    /** 归档拒绝必须早于改名SQL、删除计数与写入，不能用合法旧OWNER继续。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void ownerPermissionDenialStopsBeforeAnyProjectReadOrWrite(Operation operation) {
        BusinessException denial = new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        when(guard.requireOwner(projectId, accountId)).thenThrow(denial);

        assertThatThrownBy(() -> invoke(operation)).isSameAs(denial);
        verify(guard).requireOwner(projectId, accountId);
        verifyNoInteractions(repository, regions);
    }

    /** SQL许可故障原样传播，不将连接或锁故障分类为项目不存在。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void ownerPermissionSqlFailureStopsBeforeAnyProjectReadOrWrite(Operation operation) {
        var failure = new DataAccessResourceFailureException("项目OWNER许可连接失败");
        when(guard.requireOwner(projectId, accountId)).thenThrow(failure);

        assertThatThrownBy(() -> invoke(operation)).isSameAs(failure);
        verifyNoInteractions(repository, regions);
    }

    /** 锁后仍限定仅一名活跃OWNER；零成员与多人保留不同错误且不进行软删。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void invalidActiveMemberCountRejectsBeforeSoftDelete(int count) {
        when(repository.countActiveMembers(projectId)).thenReturn(count);

        assertBusinessError(() -> service.delete(projectId), count == 0
                ? ProjectErrorCode.PROJECT_NOT_FOUND : ProjectErrorCode.PROJECT_HAS_MEMBERS);

        var order = inOrder(guard, repository);
        order.verify(guard).requireOwner(projectId, accountId);
        order.verify(repository).countActiveMembers(projectId);
        order.verifyNoMoreInteractions();
    }

    /** 条件更新零行不能静默当作删除成功，即使许可和计数已通过。 */
    @Test
    void zeroRowSoftDeleteStillReturnsProjectNotFound() {
        when(repository.softDelete(projectId)).thenReturn(0);

        assertBusinessError(() -> service.delete(projectId), ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** 名称条件更新零行必须50001，不能再用旧成员回读伪装成功。 */
    @Test
    void zeroRowRenameRejectsBeforeMembershipReadback() {
        when(repository.updateName(projectId, "新名称")).thenReturn(0);

        assertBusinessError(() -> service.updateName(projectId, " 新名称 "), ProjectErrorCode.PROJECT_NOT_FOUND);
        verify(repository, never()).findMembership(projectId, accountId);
    }

    /** 取得许可后才做trim、改名与回读，返回原仓储结果并保持身份参数。 */
    @Test
    void renamePermissionPrecedesUpdateAndReturnsCurrentMembership() {
        assertThat(service.updateName(projectId, " 新名称 ")).isSameAs(renamed);

        var order = inOrder(guard, repository);
        order.verify(guard).requireOwner(projectId, accountId);
        order.verify(repository).updateName(projectId, "新名称");
        order.verify(repository).findMembership(projectId, accountId);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(regions);
    }

    /** 许可必须先于人数快照，删除仅写项目状态；成员保全的真实行证据由PG验收。 */
    @Test
    void deletePermissionPrecedesCountAndOnlySoftDeletesProject() {
        service.delete(projectId);

        var order = inOrder(guard, repository);
        order.verify(guard).requireOwner(projectId, accountId);
        order.verify(repository).countActiveMembers(projectId);
        order.verify(repository).softDelete(projectId);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(regions);
    }

    /** 保持合法名称与现存项目，拒绝不能依赖无效请求碰巧触发。 */
    private void invoke(Operation operation) {
        if (operation == Operation.RENAME) service.updateName(projectId, " 新名称 ");
        else service.delete(projectId);
    }

    /** 按实际业务错误枚举断言，避免把任意异常当作正确拒绝。 */
    private void assertBusinessError(Runnable operation, ProjectErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(expected));
    }

    /** 本片仅接改名和删除，不扩展项目创建与普通读。 */
    private enum Operation {
        /** 项目名称修改。 */ RENAME,
        /** 保留成员的项目软删。 */ DELETE
    }
}
