package com.things.link.rule.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.rule.application.engine.DeterministicRuleEngine;
import com.things.link.rule.application.engine.node.NotificationActionNode;
import com.things.link.rule.application.engine.node.PayloadPropertyCompareNode;
import com.things.link.rule.application.outbox.RuleActionDispatcher;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.rule.domain.RuleScene;
import com.things.link.rule.domain.RuleSceneExecution;
import com.things.link.rule.domain.RuleSceneRepository;
import com.things.link.rule.domain.RuleSceneVersion;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S9-4 手动场景服务单测：OWNER/ADMIN 授权、owner tenant、条件/动作 fail-closed、幂等执行与 CAS。 */
class RuleSceneServiceTests {

    /** 场景仓储替身。 */
    private RuleSceneRepository repository;
    /** 项目公开端口替身。 */
    private ProjectService projectService;
    /** 项目持续许可替身；owner来源必须是已授权项目内的持久场景。 */
    private ProjectLifecycleAccessService lifecycleAccessService;
    /** 设备属主校验端口替身。 */
    private DeviceIngestionService deviceIngestionService;
    /** 中性动作派发层替身。 */
    private RuleActionDispatcher dispatcher;
    /** 审计服务替身。 */
    private AuditLogService auditLogService;
    /** 真实确定性引擎，含条件与通知动作节点。 */
    private DeterministicRuleEngine engine;
    /** 真实确定性执行处理器。 */
    private RuleSceneExecutionProcessor processor;
    /** JSON 映射器。 */
    private ObjectMapper objectMapper;
    /** 被测应用服务。 */
    private RuleSceneService service;
    /** 当前选择项目与目标设备。 */
    private UUID projectId;
    private UUID deviceId;
    /** 跨租户协作者账号。 */
    private UUID actorId;
    /** 项目所有者租户。 */
    private UUID ownerTenantId;

    /** 每个用例独立 ID，调用者 tenant 故意不同于项目 owner tenant。 */
    @BeforeEach
    void setUp() {
        repository = mock(RuleSceneRepository.class);
        projectService = mock(ProjectService.class);
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        deviceIngestionService = mock(DeviceIngestionService.class);
        dispatcher = mock(RuleActionDispatcher.class);
        auditLogService = mock(AuditLogService.class);
        engine = new DeterministicRuleEngine(List.of(new PayloadPropertyCompareNode(), new NotificationActionNode()));
        processor = new RuleSceneExecutionProcessor(engine);
        objectMapper = new ObjectMapper();
        service = new RuleSceneService(repository, new RuleManagementAccess(projectService, lifecycleAccessService),
                deviceIngestionService, new RuleNodeCatalog(List.of(new PayloadPropertyCompareNode(), new NotificationActionNode()), objectMapper),
                processor, dispatcher, auditLogService, objectMapper);
        projectId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        ownerTenantId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, actorId));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenantId);
    }

    /** ThreadLocal 不得污染后续测试。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** 跨租户协作者创建的场景必须归项目 owner tenant，审计只留版本摘要不留条件/动作正文。 */
    @Test
    void createsOwnerTenantSceneAndSanitizedAudit() {
        when(repository.create(any(), any())).thenReturn(true);

        RuleSceneView created = service.create(projectId, new CreateSceneCommand(
                " 一键关灯 ", " 下班关灯 ", List.of(condition("/on", "EQ", true)),
                List.of(action())));

        ArgumentCaptor<RuleScene> scene = ArgumentCaptor.forClass(RuleScene.class);
        ArgumentCaptor<RuleSceneVersion> version = ArgumentCaptor.forClass(RuleSceneVersion.class);
        verify(repository).create(scene.capture(), version.capture());
        assertThat(scene.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(scene.getValue().createdBy()).isEqualTo(actorId);
        assertThat(scene.getValue().name()).isEqualTo("一键关灯");
        assertThat(version.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(version.getValue().versionNumber()).isEqualTo(1);
        assertThat(created.status()).isEqualTo("DRAFT");

        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(audit.getValue().actorAccountId()).isEqualTo(actorId);
        assertThat(audit.getValue().details()).containsKeys("versionId", "versionNumber");
        assertThat(audit.getValue().details().toString()).doesNotContain("notification-action");
    }

    /** VIEWER 即使已选项目也不能管理场景。 */
    @Test
    void rejectsViewerBeforeEngine() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);

        assertThatThrownBy(() -> service.create(projectId, new CreateSceneCommand(
                "场景", null, List.of(), List.of(action()))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_MANAGE_FORBIDDEN));
        verify(repository, never()).create(any(), any());
    }

    /** 条件配置未通过确定性引擎校验时，必须在写版本事实之前 fail-closed。 */
    @Test
    void rejectsInvalidConditionBeforePersistence() {
        assertThatThrownBy(() -> service.create(projectId, new CreateSceneCommand(
                "坏条件", null, List.of(new ConditionSpec("payload-property-compare", objectMapper.createObjectNode())),
                List.of(action()))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_CONDITION_INVALID));
        verify(repository, never()).create(any(), any());
    }

    /** 动作配置未通过确定性引擎校验时 fail-closed，复用规则动作错误码。 */
    @Test
    void rejectsInvalidActionBeforePersistence() {
        assertThatThrownBy(() -> service.create(projectId, new CreateSceneCommand(
                "坏动作", null, List.of(), List.of(new ActionSpec("notification-action", objectMapper.createObjectNode())))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.RULE_ACTION_INVALID));
        verify(repository, never()).create(any(), any());
    }

    /** 手动场景必须至少一个动作，否则一键执行没有任何副作用。 */
    @Test
    void rejectsEmptyActions() {
        assertThatThrownBy(() -> service.create(projectId, new CreateSceneCommand("空动作", null, List.of(), List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_INVALID));
        verify(repository, never()).create(any(), any());
    }

    /** 发布历史版本只移动活动指针；同一 expectedVersion 的 CAS 失败必须报告状态冲突。 */
    @Test
    void reportsActivateCasConflict() {
        UUID sceneId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(repository.lock(projectId, sceneId)).thenReturn(Optional.of(scene(projectId, sceneId, null)));
        when(repository.findVersion(projectId, sceneId, versionId))
                .thenReturn(Optional.of(version(sceneId, versionId)));
        when(repository.activate(projectId, sceneId, versionId, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.activate(projectId, sceneId, versionId, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_STATE_CONFLICT));
        verify(auditLogService, never()).record(any());
    }

    /** 一键执行活动版本：全部条件成立则写执行事实并派发动作意图，同时校验设备属主。 */
    @Test
    void executesActiveSceneAndDispatchesIntents() {
        UUID sceneId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        JsonNode payload = json("{\"temperature\":42}");
        when(repository.lock(projectId, sceneId)).thenReturn(Optional.of(scene(projectId, sceneId, versionId)));
        when(repository.findVersion(projectId, sceneId, versionId))
                .thenReturn(Optional.of(version(sceneId, versionId)));
        when(repository.findExecutionByKey(projectId, sceneId, "key-1")).thenReturn(Optional.empty());
        when(repository.insertExecution(any())).thenReturn(true);

        RuleSceneExecutionView result = service.execute(projectId, sceneId, "key-1",
                new ExecuteSceneCommand(deviceId, payload));

        assertThat(result.status()).isEqualTo("DISPATCHED");
        assertThat(result.sceneId()).isEqualTo(sceneId);
        assertThat(result.sceneVersionId()).isEqualTo(versionId);
        assertThat(result.deviceId()).isEqualTo(deviceId);
        assertThat(result.trigger()).isEqualTo("MANUAL");
        verify(deviceIngestionService).requireDeviceOwner(projectId, deviceId);
        verify(dispatcher).dispatch(any(), any());
    }

    /** 首个条件不成立时 SKIPPED，不写 Outbox、不派发任何动作。 */
    @Test
    void skipsWhenConditionFalseAndNeverDispatches() {
        UUID sceneId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(repository.lock(projectId, sceneId)).thenReturn(Optional.of(scene(projectId, sceneId, versionId)));
        when(repository.findVersion(projectId, sceneId, versionId))
                .thenReturn(Optional.of(version(sceneId, versionId)));
        when(repository.findExecutionByKey(projectId, sceneId, "key-1")).thenReturn(Optional.empty());
        when(repository.insertExecution(any())).thenReturn(true);

        RuleSceneExecutionView result = service.execute(projectId, sceneId, "key-1",
                new ExecuteSceneCommand(deviceId, json("{\"temperature\":10}")));

        assertThat(result.status()).isEqualTo("SKIPPED");
        verify(dispatcher, never()).dispatch(any(), any());
    }

    /** 同一幂等键同内容重试返回既有执行事实，绝不重复派发。 */
    @Test
    void replaysSameIdempotencyKeyWithoutRedispatches() {
        UUID sceneId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        JsonNode payload = json("{\"temperature\":42}");
        RuleSceneExecution existing = new RuleSceneExecution(
                executionId, ownerTenantId, projectId, sceneId, versionId, "key-1",
                digest(deviceId, payload), deviceId, RuleSceneExecution.Status.DISPATCHED,
                RuleSceneExecution.Trigger.MANUAL, actorId, "trace", Instant.now(), Instant.now(), Instant.now());
        when(repository.lock(projectId, sceneId)).thenReturn(Optional.of(scene(projectId, sceneId, versionId)));
        when(repository.findVersion(projectId, sceneId, versionId))
                .thenReturn(Optional.of(version(sceneId, versionId)));
        when(repository.findExecutionByKey(projectId, sceneId, "key-1")).thenReturn(Optional.of(existing));

        RuleSceneExecutionView result = service.execute(projectId, sceneId, "key-1",
                new ExecuteSceneCommand(deviceId, payload));

        assertThat(result.id()).isEqualTo(executionId);
        assertThat(result.status()).isEqualTo("DISPATCHED");
        var order = inOrder(repository, lifecycleAccessService);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        order.verify(repository).lock(projectId, sceneId);
        verify(repository, never()).findVersion(any(), any(), any());
        order.verify(repository).findExecutionByKey(projectId, sceneId, "key-1");
        verify(projectService, never()).requireSchedulingContext(any());
        verify(dispatcher, never()).dispatch(any(), any());
        verify(repository, never()).insertExecution(any());
    }

    /** 同一幂等键不同内容必须拒绝，避免把旧执行事实误当新请求的结果。 */
    @Test
    void rejectsSameIdempotencyKeyDifferentContent() {
        UUID sceneId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        RuleSceneExecution existing = new RuleSceneExecution(
                executionId, ownerTenantId, projectId, sceneId, versionId, "key-1",
                "0".repeat(64), deviceId, RuleSceneExecution.Status.DISPATCHED,
                RuleSceneExecution.Trigger.MANUAL, actorId, "trace", Instant.now(), Instant.now(), Instant.now());
        when(repository.lock(projectId, sceneId)).thenReturn(Optional.of(scene(projectId, sceneId, versionId)));
        when(repository.findVersion(projectId, sceneId, versionId))
                .thenReturn(Optional.of(version(sceneId, versionId)));
        when(repository.findExecutionByKey(projectId, sceneId, "key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.execute(projectId, sceneId, "key-1",
                new ExecuteSceneCommand(deviceId, json("{\"temperature\":42}"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_STATE_CONFLICT));
        verify(dispatcher, never()).dispatch(any(), any());
    }

    /** DRAFT 或未发布活动版本不可一键执行。 */
    @Test
    void rejectsDraftSceneExecution() {
        UUID sceneId = UUID.randomUUID();
        when(repository.lock(projectId, sceneId))
                .thenReturn(Optional.of(scene(projectId, sceneId, null)));

        assertThatThrownBy(() -> service.execute(projectId, sceneId, "key-1",
                new ExecuteSceneCommand(deviceId, json("{}"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_NOT_EXECUTABLE));
        verify(dispatcher, never()).dispatch(any(), any());
    }

    /** 幂等键缺失或越界是输入错误，必须在任何求值之前拒绝。 */
    @Test
    void rejectsMissingIdempotencyKey() {
        UUID sceneId = UUID.randomUUID();
        when(repository.lock(projectId, sceneId)).thenReturn(Optional.of(scene(projectId, sceneId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.execute(projectId, sceneId, " ",
                new ExecuteSceneCommand(deviceId, json("{}"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_EXECUTION_INPUT_INVALID));
        verify(dispatcher, never()).dispatch(any(), any());
    }

    /** 已授权项目公开端口提供owner身份，许可成功前不处理输入、设备或幂等事实。 */
    @Test
    void sceneAdmissionRejectsBeforeInputDeviceAndIdempotencyLookup() {
        UUID sceneId = UUID.randomUUID();
        when(repository.lock(projectId, sceneId)).thenReturn(Optional.of(scene(projectId, sceneId, UUID.randomUUID())));
        RuntimeException failure = new IllegalStateException("project admission rejected");
        doThrow(failure).when(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        assertThatThrownBy(() -> service.execute(projectId, sceneId, null, null)).isSameAs(failure);
        var order = inOrder(projectService, repository, lifecycleAccessService);
        order.verify(projectService).requireRoleInProject(projectId);
        order.verify(lifecycleAccessService).requireActiveForWrite(ownerTenantId, projectId);
        verify(projectService, never()).requireSchedulingContext(any());
        verify(repository, never()).findVersion(any(), any(), any());
        verify(repository, never()).findExecutionByKey(any(), any(), any());
        verifyNoInteractions(deviceIngestionService, dispatcher, auditLogService);
    }

    /** 无管理角色不能读取本域归属或占项目许可；不能把可信tenant检查替代角色授权。 */
    @Test
    void sceneExecutionRejectsViewerBeforeReadingOwnershipOrAdmission() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        assertThatThrownBy(() -> service.execute(projectId, UUID.randomUUID(), "key", null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_MANAGE_FORBIDDEN));
        verifyNoInteractions(repository, lifecycleAccessService, deviceIngestionService, dispatcher, auditLogService);
    }

    /** @return 一个合法条件规格 */
    private ConditionSpec condition(String pointer, String operator, boolean value) {
        return new ConditionSpec("payload-property-compare",
                json("{\"pointer\":\"" + pointer + "\",\"operator\":\"" + operator + "\",\"value\":" + value + "}"));
    }

    /** @return 一个合法通知动作规格 */
    private ActionSpec action() {
        return new ActionSpec("notification-action",
                json("{\"channel\":\"email\",\"recipient\":\"ops@example.com\"}"));
    }

    /** @return 一个 ACTIVE 或 DRAFT 场景定义 */
    private RuleScene scene(UUID projectId, UUID sceneId, UUID activeVersionId) {
        return new RuleScene(sceneId, ownerTenantId, projectId, "场景", null,
                activeVersionId == null ? RuleScene.Status.DRAFT : RuleScene.Status.ACTIVE,
                activeVersionId, 1L, actorId, Instant.now(), Instant.now(), null);
    }

    /** @return 一个冻结条件与动作的版本事实 */
    private RuleSceneVersion version(UUID sceneId, UUID versionId) {
        return new RuleSceneVersion(versionId, ownerTenantId, projectId, sceneId, 1L,
                json("[{\"nodeType\":\"payload-property-compare\",\"config\":{\"pointer\":\"/temperature\",\"operator\":\"GT\",\"value\":30}}]"),
                json("[{\"nodeType\":\"notification-action\",\"config\":{\"channel\":\"email\",\"recipient\":\"ops@example.com\"}}]"),
                actorId, Instant.now());
    }

    /** @return 与 {@link RuleSceneService} 一致的请求摘要 */
    private String digest(UUID deviceId, JsonNode payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((deviceId + "\n" + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 解析测试 JSON 字面量。 */
    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }
}
