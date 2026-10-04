package com.things.link.rule.application;

import com.things.link.project.application.ProjectService;
import com.things.link.rule.domain.MessageRule;
import com.things.link.rule.domain.MessageRuleRepository;
import com.things.link.rule.domain.MessageRuleVersion;
import com.things.link.rule.domain.RuleDebugEvent;
import com.things.link.rule.domain.RuleDebugEventRepository;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S8-1C 调试服务单测：授权、固定版本、真实沙箱入口与持久化脱敏摘要。 */
class MessageRuleDebugServiceTests {

    /** 规则事实替身。 */
    private MessageRuleRepository rules;
    /** 调试事实替身。 */
    private RuleDebugEventRepository events;
    /** 项目端口替身。 */
    private ProjectService projects;
    /** 沙箱替身。 */
    private ScriptSandbox sandbox;
    /** 被测服务。 */
    private MessageRuleDebugService service;
    /** 当前项目。 */
    private UUID projectId;
    /** 项目 owner tenant。 */
    private UUID ownerTenantId;
    /** 当前协作者账号。 */
    private UUID actorId;
    /** 规则定义。 */
    private MessageRule rule;
    /** 不可变版本。 */
    private MessageRuleVersion version;

    /** 每个用例固定授权夹具，并让协作者 tenant 与 owner tenant 不同。 */
    @BeforeEach
    void setUp() {
        rules = mock(MessageRuleRepository.class);
        events = mock(RuleDebugEventRepository.class);
        projects = mock(ProjectService.class);
        sandbox = mock(ScriptSandbox.class);
        var lifecycle = mock(com.things.link.project.application.ProjectLifecycleAccessService.class);
        when(lifecycle.snapshot(any(), any())).thenReturn(new com.things.link.project.application.ProjectAccessPolicy(true, true, 0));
        var access = new RuleManagementAccess(projects, lifecycle);
        service = new MessageRuleDebugService(rules, new RuleDebugFactWriter(events, access), sandbox, new ObjectMapper(), access);
        projectId = UUID.randomUUID();
        ownerTenantId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        rule = new MessageRule(ruleId, ownerTenantId, projectId, "规则", null,
                MessageRule.Status.DRAFT, null, 1L, actorId, now, now, null);
        version = new MessageRuleVersion(versionId, ownerTenantId, projectId, ruleId, 1L,
                "input => input", "0".repeat(64), actorId, now);
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, actorId));
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(projects.requireProjectTenant(projectId)).thenReturn(ownerTenantId);
        when(rules.find(projectId, ruleId)).thenReturn(Optional.of(rule));
        when(rules.findVersion(projectId, ruleId, versionId)).thenReturn(Optional.of(version));
    }

    /** ThreadLocal 不得污染其他测试。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** 完整结果只返回调用方，事件递归遮蔽输入输出凭据并保留原始字节计数。 */
    @Test
    void executesDebugAndPersistsSanitizedBoundedFact() {
        String input = """
                {"device":{"access_token":"in-secret"},"name":"温度"}
                """;
        String output = """
                {"nested":{"password":"out-secret"},"ok":true}
                """;
        when(sandbox.execute(any())).thenReturn(
                ScriptExecutionResult.success(output, Duration.ofMillis(23)));

        MessageRuleDebugResult result = service.execute(projectId,
                new DebugMessageRuleCommand(rule.id(), version.id(), input));

        ArgumentCaptor<ScriptExecutionRequest> request = ArgumentCaptor.forClass(ScriptExecutionRequest.class);
        verify(sandbox).execute(request.capture());
        assertThat(request.getValue().kind()).isEqualTo(ScriptKind.DEBUG);
        assertThat(request.getValue().source()).isEqualTo(version.source());
        assertThat(result.outputJson()).isEqualTo(output);

        ArgumentCaptor<RuleDebugEvent> event = ArgumentCaptor.forClass(RuleDebugEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(ownerTenantId);
        assertThat(event.getValue().createdBy()).isEqualTo(actorId);
        assertThat(event.getValue().resultCode()).isEqualTo("SUCCESS");
        assertThat(event.getValue().inputBytes()).isEqualTo(
                input.getBytes(StandardCharsets.UTF_8).length);
        assertThat(event.getValue().inputSummary()).contains("[REDACTED]").doesNotContain("in-secret");
        assertThat(event.getValue().outputSummary()).contains("[REDACTED]").doesNotContain("out-secret");
        assertThat(event.getValue().expiresAt()).isEqualTo(
                event.getValue().createdAt().plus(MessageRuleDebugService.RETENTION));
    }

    /** 沙箱固定失败分类必须形成事件，但不能保存 guest 异常正文或伪输出。 */
    @Test
    void persistsClosedFailureWithoutOutput() {
        when(sandbox.execute(any())).thenReturn(ScriptExecutionResult.failed(
                ScriptExecutionStatus.FAILURE, "SANDBOX_SCRIPT_FAILURE", Duration.ofMillis(7)));

        service.execute(projectId, new DebugMessageRuleCommand(rule.id(), version.id(), "{}"));

        ArgumentCaptor<RuleDebugEvent> event = ArgumentCaptor.forClass(RuleDebugEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().status()).isEqualTo("FAILURE");
        assertThat(event.getValue().resultCode()).isEqualTo("SANDBOX_SCRIPT_FAILURE");
        assertThat(event.getValue().outputSummary()).isNull();
        assertThat(event.getValue().outputBytes()).isZero();
    }

    /** 不合法 JSON 尚未执行，不得伪造调试事件。 */
    @Test
    void rejectsInvalidJsonBeforeSandboxAndPersistence() {
        assertThatThrownBy(() -> service.execute(projectId,
                new DebugMessageRuleCommand(rule.id(), version.id(), "{")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(RuleErrorCode.RULE_DEBUG_INPUT_INVALID));
        verify(sandbox, never()).execute(any());
        verify(events, never()).save(any());
    }

    /** VIEWER 必须在读取源码和进入 Worker 前被拒绝。 */
    @Test
    void rejectsViewerBeforeReadingVersionOrExecuting() {
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);

        assertThatThrownBy(() -> service.execute(projectId,
                new DebugMessageRuleCommand(rule.id(), version.id(), "{}")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(RuleErrorCode.RULE_MANAGE_FORBIDDEN));
        verify(rules, never()).find(any(), any());
        verify(sandbox, never()).execute(any());
    }
}
