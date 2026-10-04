package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 供App运行入口编排读取项目公开描述的application端口。
 *
 * <p>调用方必须传入从应用权威事实取得的tenant/project二元组。本端口复用project域的身份限定查询，
 * 只允许ACTIVE与ARCHIVED项目返回；返回值用于公开resolve定位，不授予成员、Dashboard或数据访问权限。</p>
 */
@Service
public class ProjectRuntimeResolutionService {

    /** 项目权威事实仓储；端口不允许消费方跨模块读取{@code sys_project}。 */
    private final ProjectRepository repository;

    /**
     * 创建项目运行描述端口。
     *
     * @param repository 项目权威事实仓储
     */
    public ProjectRuntimeResolutionService(ProjectRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 按可信归属二元组读取仍可运行的最小项目描述。
     *
     * <p>ARCHIVED保持只读运行；DELETING、PURGING、PURGED、软删和不存在统一为空。
     * 身份错配、数据库或持久事实完整性异常原样传播，不能伪装为S12运行入口的60023。</p>
     *
     * @param tenantId 从应用权威事实取得的项目归属租户
     * @param projectId 从应用权威事实取得的项目身份
     * @return ACTIVE或ARCHIVED且归属精确匹配的最小描述；缺失或生命周期不可读时为空
     * @throws IllegalStateException 仓储返回与查询双轴不一致的项目事实
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<ProjectRuntimeDescriptor> findReadable(UUID tenantId, UUID projectId) {
        requireIdentity(tenantId, projectId);
        return repository.findLiveByIdentity(tenantId, projectId)
                .map(project -> requireIdentityMatch(project, tenantId, projectId))
                .filter(ProjectRuntimeResolutionService::isReadable)
                .map(project -> new ProjectRuntimeDescriptor(project.tenantId(), project.id(), project.projectKey()));
    }

    /** 仓储按双轴查询却返回其他身份属于持久层不变量破坏，不能伪装成60023不可见。 */
    private static Project requireIdentityMatch(Project project, UUID tenantId, UUID projectId) {
        if (!tenantId.equals(project.tenantId()) || !projectId.equals(project.id())) {
            throw new IllegalStateException("项目运行仓储返回了不一致的身份");
        }
        return project;
    }

    /** ACTIVE与ARCHIVED是S12运行读取唯一允许的项目生命周期状态。 */
    private static boolean isReadable(Project project) {
        return project.status() == Project.Status.ACTIVE || project.status() == Project.Status.ARCHIVED;
    }

    /** 缺失可信身份属于调用编程错误，必须在访问无RLS兜底的项目仓储前拒绝。 */
    private static void requireIdentity(UUID tenantId, UUID projectId) {
        if (tenantId == null || projectId == null) {
            throw new IllegalArgumentException("项目运行描述查询必须提供租户与项目身份");
        }
    }
}
