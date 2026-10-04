package com.things.link.rule.application;

import com.things.link.project.application.ProjectService;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.rule.domain.RuleOption;
import com.things.link.rule.domain.RuleSceneExecution;
import com.things.link.rule.domain.RuleSceneExecutionDetail;
import com.things.link.rule.domain.RuleSceneExecutionQuery;
import com.things.link.rule.domain.RuleSceneExecutionReadRepository;
import com.things.link.rule.domain.RuleSceneExecutionSummary;
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
 * S9-5 手动场景执行事实只读查询服务。
 *
 * <p>与场景管理（{@link RuleSceneService}，OWNER/ADMIN）不同，执行记录的查询是只读可观测接口，任何项目成员
 * （VIEWER 及以上）都可访问。执行事实由 S9-4 同步落库，本服务只负责列表、详情（执行事实 + 通知/设备动作投递
 * 摘要）与筛选下拉框数据源。</p>
 */
@Service
public class RuleSceneExecutionQueryService {

    /** 状态筛选允许的执行终态，与 {@link RuleSceneExecution.Status} 一致。 */
    private static final Set<String> EXECUTION_STATUSES = Arrays.stream(RuleSceneExecution.Status.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /** 场景执行只读仓储。 */
    private final RuleSceneExecutionReadRepository repository;
    /** 项目成员授权端口。 */
    private final ProjectService projectService;

    /** @param repository 只读仓储 @param projectService 项目授权端口 */
    public RuleSceneExecutionQueryService(
            RuleSceneExecutionReadRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    /** 查询场景执行事实分页；状态与时间范围先做快速失败，坏游标由仓储统一处理。 */
    @Transactional(readOnly = true)
    public CursorPage<RuleSceneExecutionSummary> list(RuleSceneExecutionQuery query) {
        requireProjectRead(query.projectId());
        if (query.status() != null && !EXECUTION_STATUSES.contains(query.status())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "执行状态筛选不合法");
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "开始时间必须早于结束时间");
        }
        return repository.find(query);
    }

    /** 查询一次场景执行的详情：执行事实本体 + 通知与设备动作投递状态摘要。 */
    @Transactional(readOnly = true)
    public RuleSceneExecutionDetail detail(UUID projectId, UUID executionId) {
        requireProjectRead(projectId);
        if (executionId == null) {
            throw new BusinessException(RuleErrorCode.SCENE_EXECUTION_NOT_FOUND);
        }
        RuleSceneExecutionSummary summary = repository.findSummary(projectId, executionId)
                .orElseThrow(() -> new BusinessException(RuleErrorCode.SCENE_EXECUTION_NOT_FOUND));
        return new RuleSceneExecutionDetail(
                summary,
                repository.findNotifications(projectId, executionId),
                repository.findDeviceActions(projectId, executionId));
    }

    /** 查询已产生过执行事实的场景选项，作为列表筛选下拉框数据源。 */
    @Transactional(readOnly = true)
    public List<RuleOption> sceneOptions(UUID projectId) {
        requireProjectRead(projectId);
        return repository.findSceneOptions(projectId);
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
