package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardGrantTargetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 看板授权目标锁服务的投影、空结果与失败传播轻量测试。 */
class DashboardGrantTargetServiceTests {

    /** 成功锁定只投影调用方提供的完整身份，不补充发布、草稿或用户事实。 */
    @Test
    void returnsExactTargetAfterDirectoryLockSucceeds() {
        DashboardGrantTargetRepository repository = mock(DashboardGrantTargetRepository.class);
        DashboardGrantTargetService service = new DashboardGrantTargetService(repository);
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        when(repository.lockForGrant(tenantId, projectId, dashboardId)).thenReturn(true);

        assertThat(service.lockForGrant(tenantId, projectId, dashboardId))
                .contains(new DashboardGrantTarget(tenantId, projectId, dashboardId));
        verify(repository).lockForGrant(tenantId, projectId, dashboardId);
    }

    /** 不存在、跨范围或已软删由仓储统一为空，本层不能虚构目标身份。 */
    @Test
    void returnsEmptyWhenDirectoryCannotBeLocked() {
        DashboardGrantTargetRepository repository = mock(DashboardGrantTargetRepository.class);
        DashboardGrantTargetService service = new DashboardGrantTargetService(repository);
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();

        assertThat(service.lockForGrant(tenantId, projectId, dashboardId)).isEmpty();
        verify(repository).lockForGrant(tenantId, projectId, dashboardId);
    }

    /** 事务、连接或数据库失败保留原异常，不能被降格成目标不存在。 */
    @Test
    void propagatesRepositoryFailureWithoutDowngrade() {
        DashboardGrantTargetRepository repository = mock(DashboardGrantTargetRepository.class);
        DashboardGrantTargetService service = new DashboardGrantTargetService(repository);
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        RuntimeException failure = new IllegalStateException("transaction missing");
        when(repository.lockForGrant(tenantId, projectId, dashboardId)).thenThrow(failure);

        assertThatThrownBy(() -> service.lockForGrant(tenantId, projectId, dashboardId)).isSameAs(failure);
    }

    /** 服务必须加入原可写事务，使项目许可、用户锁、目录锁、CAS和审计共享提交边界。 */
    @Test
    void requiresMandatoryWritableOuterTransaction() throws Exception {
        Method method = DashboardGrantTargetService.class.getMethod(
                "lockForGrant", UUID.class, UUID.class, UUID.class);

        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transactional.readOnly()).isFalse();
    }
}
