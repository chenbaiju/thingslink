package com.things.link.alarm.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;

import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.alarm.domain.AlarmNotificationGroup;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 通知配置入口的角色、固定模板变量、Webhook 目标和规则归属单测。 */
class NotificationConfigurationServiceTests {
    /** 通知端口模拟。 */
    private AlarmNotificationRepository notifications;

    /** 项目角色端口模拟。 */
    private ProjectService projects;

    /** 原事务项目持续许可模拟。 */
    private ProjectLifecycleAccessService lifecycle;

    /** 告警规则端口模拟。 */
    private AlarmRuleRepository rules;

    /** 被测服务。 */
    private NotificationConfigurationService service;

    /** 当前项目。 */
    private UUID projectId;

    /** 每个用例建立 OWNER 上下文，避免授权无关因素掩盖输入校验。 */
    @BeforeEach
    void setUp() {
        notifications = mock(AlarmNotificationRepository.class);
        projects = mock(ProjectService.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        rules = mock(AlarmRuleRepository.class);
        service = new NotificationConfigurationService(notifications, projects, lifecycle, rules);
        projectId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projects.requireProjectTenant(projectId)).thenReturn(UUID.randomUUID());
    }

    /** ThreadLocal 必须清理，避免不同测试错误继承身份。 */
    @AfterEach
    void clear() {
        TenantContext.clear();
    }


    /** 新通知事实使用持久项目owner tenant，且写入口执行锁前后两次角色核验。 */
    @Test
    void createGroupUsesOwnerTenantAfterLifecyclePermit() {
        UUID ownerTenant = UUID.randomUUID();
        when(projects.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        when(notifications.createGroup(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        service.createGroup(projectId, "值班", true);
        verify(lifecycle).requireActiveForWrite(ownerTenant, projectId);
        verify(projects, org.mockito.Mockito.times(2)).requireRoleInProject(projectId);
        verify(notifications).createGroup(argThat(value -> ownerTenant.equals(value.tenantId())));
    }

    /** VIEWER 不能改变投递配置。 */
    @Test
    void rejectsViewerConfigurationMutation() {
        when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        assertThatThrownBy(() -> service.createGroup(projectId, "值班", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(AlarmErrorCode.MAINTAIN_FORBIDDEN);
    }

    /** 模板不是脚本执行面，未知变量必须在存储前拒绝。 */
    @Test
    void rejectsTemplateVariableOutsideWhitelist() {
        assertThatThrownBy(
                        () ->
                                service.createTemplate(
                                        projectId,
                                        "邮件",
                                        NotificationChannel.EMAIL,
                                        "${spel}",
                                        "正文",
                                        true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(AlarmErrorCode.NOTIFICATION_CONFIGURATION_INVALID);
    }

    /** Webhook URL 不得把认证信息或泄露性查询参数嵌入目标字段。 */
    @Test
    void rejectsWebhookTargetWithCredentialOrQuery() {
        UUID groupId = UUID.randomUUID();
        when(notifications.findGroup(projectId, groupId))
                .thenReturn(
                        Optional.of(
                                new AlarmNotificationGroup(
                                        groupId,
                                        TenantContext.require().tenantId(),
                                        projectId,
                                        "值班",
                                        true,
                                        0,
                                        Instant.now(),
                                        Instant.now(),
                                        null)));
        assertThatThrownBy(
                        () ->
                                service.createRecipient(
                                        projectId,
                                        groupId,
                                        NotificationChannel.WEBHOOK,
                                        "https://user:password@example.com/hook?token=x",
                                        true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(AlarmErrorCode.NOTIFICATION_CONFIGURATION_INVALID);
    }

    /** PUSH 受众必须来自 enduser 有效关系，不允许在 recipient.target 伪造 token 或内部 ID。 */
    @Test
    void rejectsConfiguredPushRecipient() {
        UUID groupId = UUID.randomUUID();
        when(notifications.findGroup(projectId, groupId))
                .thenReturn(Optional.of(new AlarmNotificationGroup(
                        groupId,
                        TenantContext.require().tenantId(),
                        projectId,
                        "PUSH 路由组",
                        true,
                        0,
                        Instant.now(),
                        Instant.now(),
                        null)));

        assertThatThrownBy(() -> service.createRecipient(
                        projectId, groupId, NotificationChannel.PUSH, UUID.randomUUID().toString(), true))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(AlarmErrorCode.NOTIFICATION_CONFIGURATION_INVALID);
    }

    /** 规则不存在或跨项目时必须在应用层返回既有规则 404，不能穿透复合外键为 500。 */
    @Test
    void rejectsBindingForRuleOutsideProjectBeforeWrite() {
        UUID ruleId = UUID.randomUUID();
        when(rules.findById(projectId, ruleId)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                service.createBinding(
                                        projectId,
                                        ruleId,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        NotificationChannel.EMAIL,
                                        true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(AlarmErrorCode.RULE_NOT_FOUND);
    }
}
