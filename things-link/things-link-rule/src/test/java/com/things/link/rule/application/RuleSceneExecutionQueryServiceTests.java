package com.things.link.rule.application;

import com.things.link.project.application.ProjectService;
import com.things.link.rule.domain.DeviceActionDeliverySummary;
import com.things.link.rule.domain.NotificationDeliverySummary;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.rule.domain.RuleSceneExecutionDetail;
import com.things.link.rule.domain.RuleSceneExecutionQuery;
import com.things.link.rule.domain.RuleSceneExecutionReadRepository;
import com.things.link.rule.domain.RuleSceneExecutionSummary;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S9-5 手动场景执行查询服务单测：状态/时间校验、详情不存在与只读项目授权。 */
class RuleSceneExecutionQueryServiceTests {

    /** 场景执行只读仓储替身。 */
    private RuleSceneExecutionReadRepository repository;
    /** 项目成员授权端口替身。 */
    private ProjectService projectService;
    /** 被测查询服务。 */
    private RuleSceneExecutionQueryService service;
    /** 当前已选项目。 */
    private UUID projectId;
    /** 另一个项目，用于跨项目越权拒绝。 */
    private UUID otherProjectId;

    /** 每个用例独立 ID 与项目范围。 */
    @BeforeEach
    void setUp() {
        repository = mock(RuleSceneExecutionReadRepository.class);
        projectService = mock(ProjectService.class);
        service = new RuleSceneExecutionQueryService(repository, projectService);
        projectId = UUID.randomUUID();
        otherProjectId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
    }

    /** ThreadLocal 不得污染后续测试。 */
    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    /** 合法查询委派只读仓储并原样返回执行事实分页。 */
    @Test
    void listDelegatesToRepository() {
        RuleSceneExecutionQuery query = new RuleSceneExecutionQuery(projectId, null, "DISPATCHED",
                null, null, null, 50);
        CursorPage<RuleSceneExecutionSummary> page = CursorPage.last(List.of(summary()));
        when(repository.find(query)).thenReturn(page);

        assertThat(service.list(query)).isSameAs(page);
    }

    /** 状态筛选只接受场景执行封闭终态，未知值进仓储前快速失败。 */
    @Test
    void rejectsUnknownStatusBeforeRepository() {
        RuleSceneExecutionQuery query = new RuleSceneExecutionQuery(projectId, null, "BOGUS",
                null, null, null, 50);

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
        verify(repository, never()).find(query);
    }

    /** 反向时间窗是无意义输入，进仓储前拒绝。 */
    @Test
    void rejectsBackwardsTimeWindow() {
        Instant now = Instant.now();
        RuleSceneExecutionQuery query = new RuleSceneExecutionQuery(projectId, null, null,
                now, now.minusSeconds(1), null, 50);

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
        verify(repository, never()).find(query);
    }

    /** 路径项目必须等于当前已选项目，否则按「不存在」语义隐藏跨项目事实。 */
    @Test
    void hidesCrossProjectRead() {
        RuleSceneExecutionQuery query = new RuleSceneExecutionQuery(otherProjectId, null, null,
                null, null, null, 50);

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(repository, never()).find(query);
    }

    /** 详情不存在的执行事实返回专门业务错误码，而非通用参数错误。 */
    @Test
    void detailNotFoundUsesSceneExecutionErrorCode() {
        UUID executionId = UUID.randomUUID();
        when(repository.findSummary(projectId, executionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(projectId, executionId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(RuleErrorCode.SCENE_EXECUTION_NOT_FOUND));
        verify(repository, never()).findNotifications(projectId, executionId);
        verify(repository, never()).findDeviceActions(projectId, executionId);
    }

    /** 详情在找到执行事实后继续装配通知与设备动作两类投递摘要。 */
    @Test
    void detailAssemblesDeliverySummaries() {
        UUID executionId = UUID.randomUUID();
        RuleSceneExecutionSummary summary = summary();
        List<NotificationDeliverySummary> notifications = List.of(new NotificationDeliverySummary(
                "EMAIL", "ops@example.com", "DELIVERED", 1, 3, null, Instant.now(), Instant.now()));
        List<DeviceActionDeliverySummary> deviceActions = List.of(new DeviceActionDeliverySummary(
                UUID.randomUUID(), "COMMAND", UUID.randomUUID(), "SUCCEEDED", null,
                Instant.now(), Instant.now()));
        when(repository.findSummary(projectId, executionId)).thenReturn(Optional.of(summary));
        when(repository.findNotifications(projectId, executionId)).thenReturn(notifications);
        when(repository.findDeviceActions(projectId, executionId)).thenReturn(deviceActions);

        RuleSceneExecutionDetail detail = service.detail(projectId, executionId);

        assertThat(detail.summary()).isSameAs(summary);
        assertThat(detail.notifications()).isSameAs(notifications);
        assertThat(detail.deviceActions()).isSameAs(deviceActions);
    }

    /** @return 一条场景执行事实投影。 */
    private static RuleSceneExecutionSummary summary() {
        return new RuleSceneExecutionSummary(
                UUID.randomUUID(), UUID.randomUUID(), "场景甲", UUID.randomUUID(), UUID.randomUUID(),
                "DISPATCHED", UUID.randomUUID(), "trace-s9-5", Instant.now(), Instant.now(), Instant.now());
    }
}
