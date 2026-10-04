package com.things.link.rule.application;

import com.things.link.project.application.ProjectService;
import com.things.link.rule.domain.RuleExecutionAttempt;
import com.things.link.rule.domain.RuleExecutionLogQuery;
import com.things.link.rule.domain.RuleExecutionLogReadRepository;
import com.things.link.rule.domain.RuleExecutionSummary;
import com.things.link.rule.domain.RuleOption;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S9-5 上行规则执行日志查询服务单测：状态/时间/参数校验、只读项目授权与仓储委派。 */
class RuleExecutionLogQueryServiceTests {

    /** 执行日志只读仓储替身。 */
    private RuleExecutionLogReadRepository repository;
    /** 项目成员授权端口替身。 */
    private ProjectService projectService;
    /** 被测查询服务。 */
    private RuleExecutionLogQueryService service;
    /** 当前已选项目。 */
    private UUID projectId;
    /** 另一个项目，用于跨项目越权拒绝。 */
    private UUID otherProjectId;

    /** 每个用例独立 ID 与项目范围。 */
    @BeforeEach
    void setUp() {
        repository = mock(RuleExecutionLogReadRepository.class);
        projectService = mock(ProjectService.class);
        service = new RuleExecutionLogQueryService(repository, projectService);
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

    /** 合法查询委派只读仓储并原样返回聚合摘要。 */
    @Test
    void listDelegatesToRepository() {
        RuleExecutionLogQuery query = new RuleExecutionLogQuery(projectId, null, "SUCCESS",
                null, null, null, 50);
        CursorPage<RuleExecutionSummary> page = CursorPage.last(List.of(summary()));
        when(repository.findSummaries(query)).thenReturn(page);

        assertThat(service.list(query)).isSameAs(page);
    }

    /** 状态筛选只接受封闭终态枚举，未知值在进仓储前快速失败。 */
    @Test
    void rejectsUnknownStatusBeforeRepository() {
        RuleExecutionLogQuery query = new RuleExecutionLogQuery(projectId, null, "BOGUS",
                null, null, null, 50);

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
        verify(repository, never()).findSummaries(query);
    }

    /** 反向时间窗是无意义输入，进仓储前拒绝。 */
    @Test
    void rejectsBackwardsTimeWindow() {
        Instant now = Instant.now();
        RuleExecutionLogQuery query = new RuleExecutionLogQuery(projectId, null, null,
                now, now.minusSeconds(1), null, 50);

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
        verify(repository, never()).findSummaries(query);
    }

    /** attempt 时间线必须给出完整执行身份，缺任一参数即参数错误。 */
    @Test
    void rejectsMissingAttemptParams() {
        assertThatThrownBy(() -> service.attempts(projectId, null, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
        verify(repository, never()).findAttempts(
                projectId, null, UUID.randomUUID(), UUID.randomUUID());
    }

    /** 路径项目必须等于当前已选项目，否则按「不存在」语义隐藏跨项目事实。 */
    @Test
    void hidesCrossProjectRead() {
        RuleExecutionLogQuery query = new RuleExecutionLogQuery(otherProjectId, null, null,
                null, null, null, 50);

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(repository, never()).findSummaries(query);
    }

    /** 没有租户上下文时只读查询不可执行，暴露为内部错误而非静默放行。 */
    @Test
    void failsWithoutTenantContext() {
        TenantContext.clear();
        RuleExecutionLogQuery query = new RuleExecutionLogQuery(projectId, null, null,
                null, null, null, 50);

        assertThatThrownBy(() -> service.list(query)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).findSummaries(query);
    }

    /** 规则筛选选项与 attempt 时间线同样只做授权后委派。 */
    @Test
    void ruleOptionsAndAttemptsDelegate() {
        List<RuleOption> options = List.of(new RuleOption(UUID.randomUUID(), "规则甲"));
        List<RuleExecutionAttempt> attempts = List.of(new RuleExecutionAttempt(
                1, "SUCCESS", "SUCCESS", 12L, 30, 42, Instant.now()));
        when(repository.findRuleOptions(projectId)).thenReturn(options);
        UUID messageId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(repository.findAttempts(projectId, messageId, ruleId, versionId)).thenReturn(attempts);

        assertThat(service.ruleOptions(projectId)).isSameAs(options);
        assertThat(service.attempts(projectId, messageId, ruleId, versionId)).isSameAs(attempts);
    }

    /** @return 一条执行级聚合摘要，字段互不冲突。 */
    private static RuleExecutionSummary summary() {
        return new RuleExecutionSummary(
                UUID.randomUUID(), UUID.randomUUID(), "规则甲", UUID.randomUUID(),
                1, "SUCCESS", "SUCCESS", 12L, Instant.now(), Instant.now());
    }
}
