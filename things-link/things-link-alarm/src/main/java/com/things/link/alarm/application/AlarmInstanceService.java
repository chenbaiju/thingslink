package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.trace.TraceContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/** 告警历史查询和人工维护用例；ACK 与人工清除严格保留正交字段。 */
@Service
public class AlarmInstanceService implements ApplicationEventPublisherAware {
    /** Spring保证生产服务初始化时注入；保留原独立状态机测试构造器。 */
    private ApplicationEventPublisher eventPublisher;

    /** 接收事务内事件总线；订阅者必须在提交后才能发布网络提示。 */
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.eventPublisher = publisher;
    }

    /** 事故/事件存储端口。 */ private final AlarmInstanceRepository repository;
    /** 项目角色公开端口。 */ private final ProjectService projectService;
    /** 原事务项目持续写许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 原事务可靠公开来源。 */ private final AlarmWebhookSource webhook;
    /** 告警状态机低基数指标。 */ private final AlarmMetrics metrics;
    /** @param repository 事故端口 @param projectService 成员授权端口 @param lifecycle 项目持续写许可 @param metrics 状态机指标 */
    @Autowired
    public AlarmInstanceService(AlarmInstanceRepository repository, ProjectService projectService,
                                ProjectLifecycleAccessService lifecycle, AlarmMetrics metrics, AlarmWebhookSource webhook) {
        this.repository = repository; this.projectService = projectService; this.lifecycle = lifecycle;
        this.metrics = metrics; this.webhook = webhook;
    }
    /** 独立单测保留旧装配，生产强制可靠来源依赖。 */
    public AlarmInstanceService(AlarmInstanceRepository repository, ProjectService projects,
            ProjectLifecycleAccessService lifecycle, AlarmMetrics metrics) {
        this(repository, projects, lifecycle, metrics, null);
    }
    /** 查询单个实例。 */
    @Transactional(readOnly = true) public AlarmInstance get(UUID projectId, UUID instanceId) { requireMember(projectId); return find(projectId, instanceId); }
    /** 列出项目告警实例。 */
    @Transactional(readOnly = true) public CursorPage<AlarmInstance> page(UUID projectId, String cursor, int limit) {
        requireMember(projectId); return repository.page(projectId, cursor, pageSize(limit));
    }
    /** 列出实例不可变事件。 */
    @Transactional(readOnly = true) public CursorPage<AlarmEvent> pageEvents(UUID projectId, UUID instanceId, String cursor, int limit) {
        requireMember(projectId); find(projectId, instanceId); return repository.pageEvents(projectId, instanceId, cursor, pageSize(limit));
    }
    /** ACK 不会改变 PENDING/ACTIVE/CLEARED，只写 ack 两列与一条不可变事件。 */
    @Transactional public AlarmInstance acknowledge(UUID projectId, UUID instanceId, int version) {
        requireWrite(projectId); AlarmInstance old = find(projectId, instanceId);
        if (old.conditionState() == AlarmInstance.ConditionState.PENDING || old.ackState() == AlarmInstance.AckState.ACKNOWLEDGED
                || old.version() != version) throw new BusinessException(AlarmErrorCode.INSTANCE_STATE_CONFLICT);
        TenantScope scope = scope(); Instant now = Instant.now();
        AlarmInstance next = new AlarmInstance(old.id(), old.tenantId(), old.projectId(), old.ruleId(), old.originatorType(),
                old.originatorId(), old.alarmType(), old.severity(), old.conditionState(), AlarmInstance.AckState.ACKNOWLEDGED,
                old.clearReason(), old.firstConditionAt(), old.recoveryConditionAt(), old.activatedAt(), old.clearedAt(), now,
                scope.accountId(), old.lastReceivedAt(), old.lastOccurredAt(), old.lastValue(), old.version(), old.createdAt(), now);
        if (!repository.update(next)) throw new BusinessException(AlarmErrorCode.INSTANCE_STATE_CONFLICT);
        append(next, AlarmEvent.EventType.ACKNOWLEDGED, null, now, scope.accountId()); return repository.findById(projectId, instanceId).orElseThrow();
    }
    /** 人工清除结束活动事故但不伪造确认；已清除实例不可重复关闭。 */
    @Transactional public AlarmInstance clear(UUID projectId, UUID instanceId, int version) {
        requireWrite(projectId); AlarmInstance old = find(projectId, instanceId);
        if (!old.active() || old.version() != version) throw new BusinessException(AlarmErrorCode.INSTANCE_STATE_CONFLICT);
        TenantScope scope = scope(); Instant now = Instant.now();
        AlarmInstance next = new AlarmInstance(old.id(), old.tenantId(), old.projectId(), old.ruleId(), old.originatorType(),
                old.originatorId(), old.alarmType(), old.severity(), AlarmInstance.ConditionState.CLEARED, old.ackState(),
                AlarmInstance.ClearReason.MANUAL, old.firstConditionAt(), old.recoveryConditionAt(), old.activatedAt(), now,
                old.acknowledgedAt(), old.acknowledgedBy(), now, old.lastOccurredAt(), old.lastValue(), old.version(), old.createdAt(), now);
        if (!repository.update(next)) throw new BusinessException(AlarmErrorCode.INSTANCE_STATE_CONFLICT);
        append(next, AlarmEvent.EventType.CLEARED, AlarmInstance.ClearReason.MANUAL, now, scope.accountId());
        return repository.findById(projectId, instanceId).orElseThrow();
    }
    /** @return 项目可见实例或统一 404 */
    private AlarmInstance find(UUID projectId, UUID instanceId) { return repository.findById(projectId, instanceId).orElseThrow(() -> new BusinessException(AlarmErrorCode.INSTANCE_NOT_FOUND)); }
    /** @return 任意已加入项目的角色 */ private ProjectRole requireMember(UUID projectId) { return projectService.requireRoleInProject(projectId); }
    /** OPERATOR 可处理运行期事故；VIEWER 只读。 */
    private void requireMaintain(UUID projectId) { if (requireMember(projectId) == ProjectRole.VIEWER) throw new BusinessException(AlarmErrorCode.MAINTAIN_FORBIDDEN); }
    /** 角色预检后以项目持久归属取得原事务许可，并在锁等待结束后重验人工处理权限。 */
    private void requireWrite(UUID projectId) {
        requireMaintain(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(ownerTenant, projectId);
        requireMaintain(projectId);
    }
    /** @return 当前人工操作身份 */ private static TenantScope scope() { return TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文")); }
    /** 写不可变人工事件；source message 为空，因此重复动作先由 version/状态 CAS 拒绝。 */
    private void append(AlarmInstance instance, AlarmEvent.EventType type, AlarmInstance.ClearReason reason, Instant now, UUID actor) {
        var event = new AlarmEvent(Uuid7.generate(), instance.tenantId(), instance.projectId(), instance.id(), type,
                null, TraceContext.resolve(TraceContext.current()), null, instance.lastOccurredAt(), now, actor,
                instance.conditionState(), instance.ackState(), reason);
        boolean appended = repository.appendEvent(event);
        // ADR0022要求每次状态迁移都有不可变事件；静默接受false会提交无法审计的实例状态。
        if (!appended) throw new IllegalStateException("人工告警状态迁移必须追加唯一事件");
        // requireWrite已在实例CAS前持有项目许可，此处重复读取不能改变代次。
        if (webhook != null && type == AlarmEvent.EventType.CLEARED)
            webhook.append(instance, event, webhook.capture(instance.tenantId(), instance.projectId()));
        AlarmStateInvalidationPublisher.publish(eventPublisher, instance);
        metrics.recordTransition(type);
    }
    /** 统一限制列表放大。 */ private static int pageSize(int value) { if (value < 1 || value > 100) throw new BusinessException(AlarmErrorCode.RULE_INVALID, "分页大小必须在 1 到 100 之间"); return value; }
}
