package com.things.link.rule.application;

import com.things.link.project.application.ProjectService;
import com.things.link.rule.application.engine.DeterministicRuleEngine;
import com.things.link.rule.application.engine.node.NotificationActionNode;
import com.things.link.rule.domain.MessageRule;
import com.things.link.rule.domain.MessageRuleRepository;
import com.things.link.rule.domain.MessageRuleVersion;
import com.things.link.rule.domain.RuleErrorCode;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S8-1B 无 UI 服务单测：授权、owner tenant、沙箱门禁、CAS 与审计不得依赖 Controller。 */
class MessageRuleServiceTests {

    /** 规则仓储替身。 */
    private MessageRuleRepository repository;
    /** 项目公开端口替身。 */
    private ProjectService projectService;
    /** 沙箱端口替身。 */
    private ScriptSandbox sandbox;
    /** 审计服务替身。 */
    private AuditLogService auditLogService;
    /** 动作节点确定性引擎，真实节点验证 fail-closed。 */
    private DeterministicRuleEngine engine;
    /** 动作规格序列化映射器。 */
    private ObjectMapper objectMapper;
    /** 被测无 UI 服务。 */
    private MessageRuleService service;
    /** 当前选择项目。 */
    private UUID projectId;
    /** 跨租户协作者账号。 */
    private UUID actorId;
    /** 项目所有者租户。 */
    private UUID ownerTenantId;

    /** 每个用例使用独立 ID，并让调用者 tenant 故意不同于项目 owner tenant。 */
    @BeforeEach
    void setUp() {
        repository = mock(MessageRuleRepository.class);
        projectService = mock(ProjectService.class);
        sandbox = mock(ScriptSandbox.class);
        auditLogService = mock(AuditLogService.class);
        engine = new DeterministicRuleEngine(List.of(new NotificationActionNode()));
        objectMapper = new ObjectMapper();
        service = new MessageRuleService(repository, sandbox, auditLogService,
                objectMapper, new RuleManagementAccess(projectService,
                        mock(com.things.link.project.application.ProjectLifecycleAccessService.class)),
                new RuleNodeCatalog(List.of(new NotificationActionNode()), objectMapper));
        projectId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        ownerTenantId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, actorId));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenantId);
        when(sandbox.validate(any(), any())).thenReturn(
                ScriptValidationResult.valid(Duration.ofMillis(1)));
    }

    /** ThreadLocal 不得污染后续测试。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** 跨租户协作者创建的规则必须归项目 owner tenant，审计只留摘要而不留源码。 */
    @Test
    void createsOwnerTenantRuleAndSanitizedAudit() {
        when(repository.create(any(), any())).thenReturn(true);

        MessageRuleView created = service.create(projectId,
                new CreateMessageRuleCommand(" 温度归一化 ", " 处理上报温度 ",
                        "input => ({temperature: input.temperature})"));

        ArgumentCaptor<MessageRule> rule = ArgumentCaptor.forClass(MessageRule.class);
        ArgumentCaptor<MessageRuleVersion> version = ArgumentCaptor.forClass(MessageRuleVersion.class);
        verify(repository).create(rule.capture(), version.capture());
        assertThat(rule.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(rule.getValue().createdBy()).isEqualTo(actorId);
        assertThat(rule.getValue().name()).isEqualTo("温度归一化");
        assertThat(version.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(version.getValue().versionNumber()).isEqualTo(1);
        assertThat(version.getValue().sourceSha256()).hasSize(64);
        assertThat(created.status()).isEqualTo("DRAFT");

        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(audit.getValue().actorAccountId()).isEqualTo(actorId);
        assertThat(audit.getValue().details()).containsKeys("versionId", "versionNumber", "sourceSha256");
        assertThat(audit.getValue().details().toString()).doesNotContain("temperature: input.temperature");
    }

    /** 沙箱解析失败必须在写规则和审计之前 fail-closed。 */
    @Test
    void rejectsInvalidScriptBeforePersistence() {
        when(sandbox.validate(any(), any())).thenReturn(
                ScriptValidationResult.invalid("SANDBOX_SCRIPT_FAILURE", Duration.ofMillis(1)));

        assertThatThrownBy(() -> service.create(projectId,
                new CreateMessageRuleCommand("坏脚本", null, "input => ({")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.RULE_SCRIPT_INVALID));

        verify(repository, never()).create(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 动作配置未通过确定性引擎校验时，必须在写版本事实之前 fail-closed。 */
    @Test
    void rejectsInvalidActionBeforePersistence() {
        JsonNode config = objectMapper.createObjectNode().put("channel", "email");

        assertThatThrownBy(() -> service.create(projectId,
                new CreateMessageRuleCommand("动作规则", null, "input => input",
                        List.of(new ActionSpec(NotificationActionNode.TYPE, config)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.RULE_ACTION_INVALID));

        verify(repository, never()).create(any(), any());
        verify(auditLogService, never()).record(any());
    }

    /** 合法动作随不可变版本一起冻结，序列化为 jsonb 数组进入版本事实。 */
    @Test
    void persistsValidActionOnVersion() {
        when(repository.create(any(), any())).thenReturn(true);
        ActionSpec action = new ActionSpec(NotificationActionNode.TYPE,
                objectMapper.createObjectNode().put("channel", "email").put("recipient", "ops@example.com"));

        service.create(projectId, new CreateMessageRuleCommand("动作规则", null, "input => input", List.of(action)));

        ArgumentCaptor<MessageRuleVersion> version = ArgumentCaptor.forClass(MessageRuleVersion.class);
        verify(repository).create(any(), version.capture());
        assertThat(version.getValue().actions()).hasSize(1);
        assertThat(version.getValue().actions().get(0).path("nodeType").asText())
                .isEqualTo(NotificationActionNode.TYPE);
    }

    /** VIEWER 即使已选项目也不能让沙箱执行未授权源码。 */
    @Test
    void rejectsViewerBeforeSandbox() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);

        assertThatThrownBy(() -> service.create(projectId,
                new CreateMessageRuleCommand("规则", null, "input => input")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.RULE_MANAGE_FORBIDDEN));

        verify(sandbox, never()).validate(any(), any());
    }

    /** 发布历史版本只移动活动指针；同一 expectedVersion 的 CAS 失败必须报告状态冲突。 */
    @Test
    void reportsActivationCasConflict() {
        UUID ruleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        MessageRule rule = new MessageRule(
                ruleId, ownerTenantId, projectId, "规则", null, MessageRule.Status.DRAFT,
                null, 1L, actorId, now, now, null);
        MessageRuleVersion version = new MessageRuleVersion(
                versionId, ownerTenantId, projectId, ruleId, 1L,
                "input => input", "0".repeat(64), actorId, now);
        when(repository.lock(projectId, ruleId)).thenReturn(Optional.of(rule));
        when(repository.findVersion(projectId, ruleId, versionId)).thenReturn(Optional.of(version));
        when(repository.activate(projectId, ruleId, versionId, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.activate(projectId, ruleId, versionId, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.RULE_STATE_CONFLICT));

        verify(auditLogService, never()).record(any());
    }
}
