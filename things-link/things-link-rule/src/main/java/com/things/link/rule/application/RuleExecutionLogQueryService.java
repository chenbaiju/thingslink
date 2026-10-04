package com.things.link.rule.application;

import com.things.link.project.application.ProjectService;
import com.things.link.rule.application.queue.RuleExecutionLogEntry;
import com.things.link.rule.domain.RuleExecutionAttempt;
import com.things.link.rule.domain.RuleExecutionLogQuery;
import com.things.link.rule.domain.RuleExecutionLogReadRepository;
import com.things.link.rule.domain.RuleExecutionSummary;
import com.things.link.rule.domain.RuleOption;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * S9-5 上行规则执行日志只读查询服务。
 *
 * <p>只读可观测接口，任何项目成员（VIEWER 及以上）都可访问；执行事实本身由 S8-2C 写路径落库，本服务只负责
 * 执行级聚合列表、attempt 时间线详情与筛选下拉框数据源，不改变任何事实。</p>
 */
@Service
public class RuleExecutionLogQueryService {

    /** 状态筛选允许的封闭终态，与 {@link RuleExecutionLogEntry.Status} 一致。 */
    private static final Set<String> EXECUTION_STATUSES = Arrays.stream(RuleExecutionLogEntry.Status.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /** 执行日志只读仓储。 */
    private final RuleExecutionLogReadRepository repository;
    /** 项目成员授权端口。 */
    private final ProjectService projectService;

    /** @param repository 只读仓储 @param projectService 项目授权端口 */
    public RuleExecutionLogQueryService(
            RuleExecutionLogReadRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    /** 查询执行级聚合摘要分页；状态与时间范围先做快速失败，坏游标由仓储统一处理。 */
    @Transactional(readOnly = true)
    public CursorPage<RuleExecutionSummary> list(RuleExecutionLogQuery query) {
        requireProjectRead(query.projectId());
        if (query.status() != null && !EXECUTION_STATUSES.contains(query.status())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "执行状态筛选不合法");
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "开始时间必须早于结束时间");
        }
        return repository.findSummaries(query);
    }

    /** 查询一次逻辑执行的 attempt 时间线（升序）。 */
    @Transactional(readOnly = true)
    public List<RuleExecutionAttempt> attempts(
            UUID projectId, UUID messageId, UUID ruleId, UUID ruleVersionId) {
        requireProjectRead(projectId);
        if (messageId == null || ruleId == null || ruleVersionId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "执行详情参数不合法");
        }
        return repository.findAttempts(projectId, messageId, ruleId, ruleVersionId);
    }

    /** 查询已产生过执行事实的规则选项，作为列表筛选下拉框数据源。 */
    @Transactional(readOnly = true)
    public List<RuleOption> ruleOptions(UUID projectId) {
        requireProjectRead(projectId);
        return repository.findRuleOptions(projectId);
    }

    /** 二层项目授权：路径项目必须等于当前已选项目，且调用者是该项目成员；只读不区分角色。 */
    private void requireProjectRead(UUID projectId) {
        TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        if (projectId == null || !projectId.equals(TenantContext.requireProjectId())) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        projectService.requireRoleInProject(projectId);
    }
}
