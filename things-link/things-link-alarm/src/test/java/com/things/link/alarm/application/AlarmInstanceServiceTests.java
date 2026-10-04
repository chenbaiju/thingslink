package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.shared.message.AlarmStateInvalidated;
import org.springframework.context.ApplicationEventPublisher;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 人工告警状态维护的应用层合同测试。
 *
 * <p>这里关注 ADR0064 的授权与项目锁顺序，以及 ADR0022 的状态迁移事件原子性；
 * PostgreSQL 行锁等待和事务回滚由 Bootstrap 真实数据库验收覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class AlarmInstanceServiceTests {

    /** 只捕获事务内类型化事件，AFTER_COMMIT与回滚由真实集成覆盖。 */
    private final ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);

    /** 告警实例持久化替身。 */
    @Mock
    private AlarmInstanceRepository repository;

    /** 项目成员角色与持久归属端口替身。 */
    @Mock
    private ProjectService projectService;

    /** 原事务项目持续写许可替身。 */
    @Mock
    private ProjectLifecycleAccessService lifecycle;

    /** 告警状态机指标替身。 */
    @Mock
    private AlarmMetrics metrics;

    /** 被测应用服务。 */
    private AlarmInstanceService service;

    /** 当前操作项目。 */
    private UUID projectId;

    /** 当前告警实例。 */
    private UUID instanceId;

    /** 项目的持久归属租户，与协作者租户刻意不同。 */
    private UUID ownerTenant;

    /** 当前操作账号。 */
    private UUID actorId;

    /** 为每个用例建立独立身份与被测服务。 */
    @BeforeEach
    void setUp() {
        service = new AlarmInstanceService(repository, projectService, lifecycle, metrics);
        service.setApplicationEventPublisher(eventPublisher);
        projectId = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        ownerTenant = UUID.randomUUID();
        actorId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, actorId));
    }

    /** 防止测试线程复用时泄漏租户范围。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** ACK 应按角色、持久归属、项目许可、角色复核的顺序进入状态机。 */
    @Test
    void acknowledgeUsesOwnerTenantAndLifecycleOrder() {
        assertOrderedMutation(false);
    }

    /** 事件总线故障只丢提示，不把已合法维护改成失败或抹去审计事件。 */
    @Test
    void realtimePublisherFailureDoesNotAlterMutation() {
        doThrow(new IllegalStateException("测试事件总线故障")).when(eventPublisher).publishEvent(any(Object.class));
        assertOrderedMutation(false);
    }

    /** 人工清除与 ACK 共用同一持续许可顺序，且不改变原确认维度。 */
    @Test
    void clearUsesOwnerTenantAndLifecycleOrder() {
        assertOrderedMutation(true);
    }

    /** 归档拒绝必须早于实例查询和任何状态副作用。 */
    @Test
    void archivedProjectShortCircuitsBeforeInstanceAccess() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        doThrow(new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY))
                .when(lifecycle).requireActiveForWrite(ownerTenant, projectId);

        assertThatThrownBy(() -> service.acknowledge(projectId, instanceId, 3))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_READ_ONLY));

        verify(projectService).requireRoleInProject(projectId);
        verify(projectService).requireProjectTenant(projectId);
        verify(lifecycle).requireActiveForWrite(ownerTenant, projectId);
        verifyNoInteractions(repository);
    }

    /** 锁等待后角色被降为 VIEWER 时必须拒绝，不能读取或修改实例。 */
    @Test
    void roleDowngradeAfterLifecycleLockShortCircuitsBeforeInstanceAccess() {
        when(projectService.requireRoleInProject(projectId))
                .thenReturn(ProjectRole.OPERATOR, ProjectRole.VIEWER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);

        assertThatThrownBy(() -> service.clear(projectId, instanceId, 3))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(AlarmErrorCode.MAINTAIN_FORBIDDEN));

        verify(lifecycle).requireActiveForWrite(ownerTenant, projectId);
        verify(projectService, times(2)).requireRoleInProject(projectId);
        verifyNoInteractions(repository);
    }

    /** 实例 CAS 成功而不可变事件未落库时必须抛异常，让事务代理回滚整次迁移。 */
    @Test
    void appendEventFalsePropagatesForTransactionRollback() {
        allowWrite();
        AlarmInstance current = activeInstance();
        when(repository.findById(projectId, instanceId)).thenReturn(Optional.of(current));
        when(repository.update(any(AlarmInstance.class))).thenReturn(true);
        when(repository.appendEvent(any(AlarmEvent.class))).thenReturn(false);

        assertThatThrownBy(() -> service.acknowledge(projectId, instanceId, current.version()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("人工告警状态迁移必须追加唯一事件");

        verify(repository).update(any(AlarmInstance.class));
        verify(repository).appendEvent(any(AlarmEvent.class));
        verify(metrics, never()).recordTransition(any(AlarmEvent.EventType.class));
        verifyNoInteractions(eventPublisher);
    }

    /**
     * 验证两个人工迁移共享的准入顺序、持久 owner tenant 和事件内容。
     *
     * @param clear true 表示人工清除，false 表示 ACK
     */
    private void assertOrderedMutation(boolean clear) {
        allowWrite();
        AlarmInstance current = activeInstance();
        when(repository.findById(projectId, instanceId))
                .thenReturn(Optional.of(current), Optional.of(current));
        when(repository.update(any(AlarmInstance.class))).thenReturn(true);
        when(repository.appendEvent(any(AlarmEvent.class))).thenReturn(true);

        if (clear) {
            service.clear(projectId, instanceId, current.version());
        } else {
            service.acknowledge(projectId, instanceId, current.version());
        }

        InOrder order = inOrder(projectService, lifecycle, repository);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(projectService).requireProjectTenant(projectId);
        order.verify(lifecycle).requireActiveForWrite(ownerTenant, projectId);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(repository).findById(projectId, instanceId);

        ArgumentCaptor<AlarmInstance> nextCaptor = ArgumentCaptor.forClass(AlarmInstance.class);
        ArgumentCaptor<AlarmEvent> eventCaptor = ArgumentCaptor.forClass(AlarmEvent.class);
        verify(repository).update(nextCaptor.capture());
        verify(repository).appendEvent(eventCaptor.capture());

        AlarmInstance next = nextCaptor.getValue();
        AlarmEvent event = eventCaptor.getValue();
        AlarmEvent.EventType expectedType = clear
                ? AlarmEvent.EventType.CLEARED : AlarmEvent.EventType.ACKNOWLEDGED;
        assertThat(next.tenantId()).isEqualTo(ownerTenant);
        assertThat(next.conditionState()).isEqualTo(clear
                ? AlarmInstance.ConditionState.CLEARED : AlarmInstance.ConditionState.ACTIVE);
        assertThat(next.ackState()).isEqualTo(clear
                ? AlarmInstance.AckState.UNACKNOWLEDGED : AlarmInstance.AckState.ACKNOWLEDGED);
        assertThat(event.tenantId()).isEqualTo(ownerTenant);
        assertThat(event.projectId()).isEqualTo(projectId);
        assertThat(event.instanceId()).isEqualTo(instanceId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.eventType()).isEqualTo(expectedType);
        verify(metrics).recordTransition(expectedType);
        verify(eventPublisher).publishEvent(new AlarmStateInvalidated(ownerTenant, projectId, current.originatorId()));
    }

    /** 配置允许写入的两次角色读取与持久项目归属。 */
    private void allowWrite() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);
    }

    /** @return 满足 ACK 与人工清除前置状态的活动告警实例 */
    private AlarmInstance activeInstance() {
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        return new AlarmInstance(instanceId, ownerTenant, projectId, UUID.randomUUID(),
                AlarmRule.OriginatorType.DEVICE, UUID.randomUUID(), "HIGH_TEMPERATURE",
                AlarmRule.Severity.MAJOR, AlarmInstance.ConditionState.ACTIVE,
                AlarmInstance.AckState.UNACKNOWLEDGED, null, now.minusSeconds(30), null,
                now.minusSeconds(20), null, null, null, now.minusSeconds(1),
                now.minusSeconds(2), 31D, 3, now.minusSeconds(30), now.minusSeconds(1));
    }
}
