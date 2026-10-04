package com.things.link.enduser.application;

import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** ADR0064决策2/4：写许可失败后只分类拒绝，数据库故障不能被伪装成业务状态。 */
class AppProjectWriteGuardTests {

    /** 只模拟公共项目端口，不跨域读取项目仓储。 */
    private ProjectLifecycleAccessService lifecycleAccessService;
    /** 无独立事务拦截的App拒绝分类编排。 */
    private AppProjectWriteGuard guard;
    /** 已验证App身份中的项目归属租户。 */
    private UUID tenantId;
    /** 已验证App身份中的项目。 */
    private UUID projectId;

    /** 每例使用不同可信二元组，所有端口桩均精确匹配这两个身份。 */
    @BeforeEach
    void setUp() {
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        guard = new AppProjectWriteGuard(lifecycleAccessService);
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
    }

    /** 成功持锁后读取同一行的稳定快照并返回代次，供能力签发与消费比较。 */
    @Test
    void grantedPermitReturnsGenerationFromLockedSnapshot() {
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(true);
        when(lifecycleAccessService.snapshot(tenantId, projectId))
                .thenReturn(new ProjectAccessPolicy(true, true, 7L));

        assertThat(guard.requireWritable(tenantId, projectId)).isEqualTo(7L);

        InOrder order = inOrder(lifecycleAccessService);
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(lifecycleAccessService).snapshot(tenantId, projectId);
        verifyNoMoreInteractions(lifecycleAccessService);
    }

    /** 只有归档只读分类为60022；即使快照重新看见ACTIVE也不得将失败许可变成通过。 */
    @ParameterizedTest
    @CsvSource({"true,false,60022", "false,false,60009", "true,true,60009", "false,true,60009"})
    void deniedPermitAlwaysRejectsAfterClassification(boolean readAllowed, boolean writeAllowed, int code) {
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        when(lifecycleAccessService.snapshot(tenantId, projectId))
                .thenReturn(new ProjectAccessPolicy(readAllowed, writeAllowed));

        assertThatThrownBy(() -> guard.requireWritable(tenantId, projectId))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode().code()).isEqualTo(code);

        InOrder order = inOrder(lifecycleAccessService);
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(lifecycleAccessService).snapshot(tenantId, projectId);
        verifyNoMoreInteractions(lifecycleAccessService);
    }

    /** 写许可SQL失败时不得继续读取快照并制造确定状态，原异常交给事务边界处理。 */
    @Test
    void lockDatabaseFailurePropagatesWithoutClassification() {
        QueryTimeoutException failure = new QueryTimeoutException("项目行锁等待超时");
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenThrow(failure);

        assertThatThrownBy(() -> guard.requireWritable(tenantId, projectId)).isSameAs(failure);

        verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        verifyNoMoreInteractions(lifecycleAccessService);
    }

    /** 许可失败后的分类SQL同样可能故障，此时保留数据库首因而非误报60009。 */
    @Test
    void classificationDatabaseFailurePropagates() {
        QueryTimeoutException failure = new QueryTimeoutException("项目分类查询超时");
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        when(lifecycleAccessService.snapshot(tenantId, projectId)).thenThrow(failure);

        assertThatThrownBy(() -> guard.requireWritable(tenantId, projectId)).isSameAs(failure);

        InOrder order = inOrder(lifecycleAccessService);
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(lifecycleAccessService).snapshot(tenantId, projectId);
        verifyNoMoreInteractions(lifecycleAccessService);
    }
}
