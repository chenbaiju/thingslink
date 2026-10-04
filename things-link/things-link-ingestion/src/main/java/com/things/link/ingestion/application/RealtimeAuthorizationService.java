package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * WebSocket握手、订阅、配额与周期维护共享的显式身份范围。
 *
 * <p>容器WebSocket线程不经过Bearer认证过滤器，任何数据库访问都必须从已验签principal恢复范围；
 * finally恢复旧范围，避免测试或容器复用线程把另一个请求的身份清掉或泄漏。</p>
 */
@Service
public class RealtimeAuthorizationService {

    /** 项目成员资格权威端口。 */
    private final ProjectService projectService;

    /** 项目生命周期代次权威端口，删除后恢复也不能让旧JWT重新生效。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /** 设备项目归属权威端口。 */
    private final DeviceIngestionService deviceIngestionService;

    /**
     * @param projectService 项目成员资格权威端口
     * @param lifecycleAccessService 项目生命周期代次权威端口
     * @param deviceIngestionService 设备项目归属权威端口
     */
    @Autowired
    public RealtimeAuthorizationService(ProjectService projectService,
                                        ProjectLifecycleAccessService lifecycleAccessService,
                                        DeviceIngestionService deviceIngestionService) {
        this.projectService = projectService;
        this.lifecycleAccessService = lifecycleAccessService;
        this.deviceIngestionService = deviceIngestionService;
    }

    /**
     * 复核当前账号仍可读取项目；ARCHIVED成员按ADR0064保留普通读取。
     *
     * @param principal 已验签实时身份
     * @throws RealtimeProjectAccessDeniedException 项目缺失、删除或成员已经失效
     */
    public void requireProjectAccess(RealtimePrincipal principal) {
        inPrincipalScope(principal, () -> {
            try {
                projectService.requireRoleInProject(principal.projectId());
                ProjectAccessPolicy policy = lifecycleAccessService.tokenSnapshot(
                        principal.accountId(), principal.projectId());
                if (!policy.readAllowed() || !policy.matchesGeneration(principal.projectGeneration())) {
                    throw new RealtimeProjectAccessDeniedException(
                            new IllegalStateException("实时项目生命周期代次已失效"));
                }
            } catch (BusinessException denied) {
                // 仅项目域确定业务拒绝触发1008；数据库异常必须留给授权截止门按1011收束。
                throw new RealtimeProjectAccessDeniedException(denied);
            }
            return null;
        });
    }

    /**
     * 在项目资格已经成功复核后验证本次订阅中的设备归属。
     *
     * <p>设备不存在或输入错误属于普通INVALID_SUBSCRIPTION，不能清除先前合法订阅；
     * 因此本方法不把设备域业务异常包装成项目失权。</p>
     *
     * @param principal 已验签且刚完成项目复核的实时身份
     * @param deviceIds 浏览器本次请求的去重设备ID
     */
    public void requireSubscriptionAccess(RealtimePrincipal principal, Set<UUID> deviceIds) {
        Objects.requireNonNull(deviceIds, "订阅设备集合不能为空");
        inPrincipalScope(principal, () -> {
            deviceIds.forEach(deviceId ->
                    deviceIngestionService.requireDeviceOwner(principal.projectId(), deviceId));
            return null;
        });
    }

    /**
     * 在principal显式范围内执行需要RLS或当前项目的内部动作。
     *
     * @param principal 已验签实时身份
     * @param action 只使用服务端可信身份的动作
     * @param <T> 返回类型
     * @return 动作结果
     */
    public <T> T inPrincipalScope(RealtimePrincipal principal, Supplier<T> action) {
        Objects.requireNonNull(principal, "实时身份不能为空");
        Objects.requireNonNull(action, "实时范围动作不能为空");
        TenantScope previous = TenantContext.current().orElse(null);
        TenantContext.set(new TenantScope(principal.tenantId(), principal.projectId(), principal.accountId()));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }
}
