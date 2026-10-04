package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectService.ProjectRoutingContext;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 设备解绑与关系历史关闭用例（ADR 0035、ADR 0037、G2-A1d）。
 *
 * <h2>权限矩阵</h2>
 * App 用户可在仍有 ACTIVE 项目角色时自解绑；控制台仅项目 OWNER/ADMIN 可解绑指定用户。
 * 两个入口最终都调用同一个 {@code ACTIVE -> CLOSED} 条件更新，不按关系角色区分关闭能力。
 *
 * <h2>幂等与隐私</h2>
 * 不存在、已关闭或当前项目不可见的关系统一按成功无动作处理，避免把关系存在性变成枚举
 * 通道。只有实际关闭者写一条审计，因此并发或重试不会产生虚假的重复变更记录。
 */
@Service
public class DeviceUnbindService {

    /** App 项目角色前置门禁。 */
    private final AppUserRoleRepository roleRepository;

    /** 关系权威事实仓储。 */
    private final AppUserDeviceRepository bindingRepository;

    /** 控制台项目授权与路由入口。 */
    private final ProjectService projectService;

    /** 不可篡改审计写入端口。 */
    private final AuditLogService auditLogService;

    /** S12-2a1e 以控制台路由或 App JWT 身份建立完整事务局部 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** ADR0064决策4：App自身解绑属于业务写，关系更新前必须持有项目写许可。 */
    private final AppProjectWriteGuard projectWriteGuard;

    /**
     * 创建解绑用例服务。
     *
     * @param roleRepository App 项目角色仓储
     * @param bindingRepository 设备关系仓储
     * @param projectService 项目授权服务
     * @param auditLogService 审计服务
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     * @param projectWriteGuard 加入原App解绑事务的项目写许可
     */
    public DeviceUnbindService(AppUserRoleRepository roleRepository,
                               AppUserDeviceRepository bindingRepository,
                               ProjectService projectService,
                               AuditLogService auditLogService,
                               TransactionLocalRlsScope transactionLocalRlsScope,
                               AppProjectWriteGuard projectWriteGuard) {
        this.roleRepository = roleRepository;
        this.bindingRepository = bindingRepository;
        this.projectService = projectService;
        this.auditLogService = auditLogService;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.projectWriteGuard = projectWriteGuard;
    }

    /**
     * 当前 App 用户自解绑。
     *
     * @param tenantId 令牌租户 ID
     * @param projectId 令牌项目 ID
     * @param appUserId 令牌 App 用户 ID
     * @param deviceId 目标设备 ID
     */
    @Transactional
    public void unbindSelf(UUID tenantId, UUID projectId, UUID appUserId, UUID deviceId) {
        transactionLocalRlsScope.establish(tenantId, projectId);
        projectWriteGuard.requireWritable(tenantId, projectId);
        requireActiveRole(tenantId, projectId, appUserId);
        bindingRepository.closeActive(projectId, appUserId, deviceId)
                .ifPresent(binding -> audit(binding, null, "APP_USER", appUserId,
                        "enduser.device.unbound.self"));
    }

    /**
     * 控制台项目管理员解绑指定用户。
     *
     * @param projectId 项目 ID
     * @param appUserId 目标 App 用户 ID
     * @param deviceId 目标设备 ID
     */
    @Transactional
    public void unbindByManager(UUID projectId, UUID appUserId, UUID deviceId) {
        requireManager(projectId);
        ProjectRoutingContext routing = projectService.requireRoutingContext(projectId);
        transactionLocalRlsScope.establish(routing.tenantId(), projectId);
        TenantScope scope = TenantContext.require();
        bindingRepository.closeActive(projectId, appUserId, deviceId)
                .ifPresent(binding -> audit(binding, scope.accountId(), "ACCOUNT",
                        scope.accountId(), "enduser.device.unbound.manager"));

        // 双轴必须同时一致；若过滤器上下文与项目路由矛盾，应立即失败而非写错误租户审计。
        if (!routing.tenantId().equals(scope.tenantId())) {
            throw new IllegalStateException("项目归属租户与当前控制台租户不一致");
        }
    }

    /** App 用户必须仍有本项目 ACTIVE 角色，且角色租户与令牌租户一致。 */
    private void requireActiveRole(UUID tenantId, UUID projectId, UUID appUserId) {
        AppUserRole role = roleRepository.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID));
        if (role.status() != AppUserRole.Status.ACTIVE || !role.tenantId().equals(tenantId)) {
            throw new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
        }
    }

    /** 控制台调用者必须具备 OWNER/ADMIN 的 enduser:manage 权限。 */
    private void requireManager(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (!role.canManageMembers()) {
            throw new BusinessException(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        }
    }

    /** 实际关闭关系后，在同一事务记录行为人类型、稳定身份和原关系角色。 */
    private void audit(AppUserDevice binding,
                       UUID actorAccountId,
                       String actorType,
                       UUID actorId,
                       String action) {
        auditLogService.record(new AuditLogEntry(
                binding.tenantId(),
                binding.projectId(),
                actorAccountId,
                "app_user_device",
                binding.id(),
                action,
                Map.of(
                        "actorType", actorType,
                        "actorId", actorId,
                        "appUserId", binding.appUserId(),
                        "deviceId", binding.deviceId(),
                        "relationRole", binding.relationRole().name())));
    }
}
