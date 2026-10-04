package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectService.ProjectRoutingContext;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 终端用户项目角色用例：分配、停用、恢复（S11-1a）。
 *
 * <h2>与预置账号的边界</h2>
 * {@link EndUserProvisioningService} 只创建租户级账号，本服务负责「账号 ↔ 项目」的角色
 * 赋值。二者分开，因为「创建账号」与「给账号项目角色」是可分别撤销的动作。
 *
 * <h2>两个关键不变式</h2>
 * <ul>
 *   <li><b>角色只能落在项目归属租户下</b>：{@code app_user_role} 受项目 RLS 保护，
 *       写入前把 {@code app.tenant_id} 与 {@code app.project_id} 切到项目归属租户与目标项目；
 *       复合外键 {@code (tenant_id, app_user_id) → app_user} 在数据库层兜底「不能跨租户赋值」。</li>
 *   <li><b>项目管理员只能动项目级状态</b>：本服务只写 {@code app_user_role.status}，
 *       不提供修改租户级 {@code app_user.status} 的入口（ADR 0035）。</li>
 * </ul>
 */
@Service
public class EndUserRoleService {

    /** 控制台成员身份与项目权威路由入口。 */
    private final ProjectService projectService;
    /** 租户级终端用户身份仓储。 */
    private final AppUserRepository appUserRepository;
    /** 项目级终端用户角色仓储。 */
    private final AppUserRoleRepository appUserRoleRepository;
    /** 负责在角色停用事务内关闭本项目有效设备关系，禁止跨模块直写表。 */
    private final AppUserDeviceRepository appUserDeviceRepository;
    /** S12-2a1e 以项目权威路由建立完整事务局部 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** 运行访问冻结§4.1：项目共享许可先于用户互斥，避免与归档或清理交错写角色。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /**
     * 创建终端用户项目角色服务。
     *
     * @param projectService 控制台成员身份与项目权威路由入口
     * @param appUserRepository 租户级终端用户身份仓储
     * @param appUserRoleRepository 项目级终端用户角色仓储
     * @param appUserDeviceRepository 项目设备关系关闭仓储
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     * @param lifecycleAccessService 原事务项目ACTIVE共享许可
     */
    public EndUserRoleService(ProjectService projectService,
                              AppUserRepository appUserRepository,
                              AppUserRoleRepository appUserRoleRepository,
                              AppUserDeviceRepository appUserDeviceRepository,
                              TransactionLocalRlsScope transactionLocalRlsScope,
                              ProjectLifecycleAccessService lifecycleAccessService) {
        this.projectService = projectService;
        this.appUserRepository = appUserRepository;
        this.appUserRoleRepository = appUserRoleRepository;
        this.appUserDeviceRepository = appUserDeviceRepository;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.lifecycleAccessService = lifecycleAccessService;
    }

    /**
     * 给一个已存在的终端用户分配本项目角色。
     *
     * @param projectId 目标项目
     * @param appUserId 终端用户 ID
     * @param role      项目角色
     * @throws BusinessException 无权、用户不存在（跨租户）、或已拥有角色
     */
    @Transactional
    public void assign(UUID projectId, UUID appUserId, EndUserRole role) {
        UUID owningTenantId = lockTargetUser(projectId, appUserId, EndUserErrorCode.END_USER_NOT_FOUND);

        try {
            appUserRoleRepository.assign(new AppUserRole(
                    Uuid7.generate(), owningTenantId, projectId, appUserId, role,
                    AppUserRole.Status.ACTIVE, Instant.now()));
        } catch (DuplicateKeyException e) {
            // (project_id, app_user_id) 唯一索引仲裁，不先查后插。
            throw new BusinessException(EndUserErrorCode.END_USER_ROLE_ALREADY_ASSIGNED);
        }
    }

    /**
     * 修改某终端用户在本项目中的角色（S11-1b）。
     *
     * <p>与 {@link #assign} 分离：assign 建行，本方法改已存在行的 {@code role} 列。目标
     * 用户在本项目没有角色时返回 60005 —— 与「用户跨租户不存在」合并为同一结果，因为
     * 两种情况都命中 0 行，且都不应泄露「这个 ID 在别的租户是否存在」。不需要先
     * 单独读取用户快照；S12-2a3a1在项目共享许可后锁定稳定用户行，串行角色与grant写入。
     * 用户缺失或没有角色仍统一60005；不能把锁前角色快照当作锁后有效授权。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @param role      目标项目角色
     * @throws BusinessException 无权，或该用户在本项目没有角色
     */
    @Transactional
    public void updateRole(UUID projectId, UUID appUserId, EndUserRole role) {
        lockTargetUser(projectId, appUserId, EndUserErrorCode.END_USER_ROLE_NOT_FOUND);

        if (appUserRoleRepository.updateRole(projectId, appUserId, role) == 0) {
            throw new BusinessException(EndUserErrorCode.END_USER_ROLE_NOT_FOUND);
        }
    }

    /**
     * 停用某终端用户在本项目的角色，并关闭其本项目全部有效设备关系。
     *
     * <p>两步位于同一事务：角色不存在时不触碰设备关系；设备关系关闭失败时角色状态
     * 同步回滚。恢复角色也不会恢复旧关系，后续必须重新走绑定流程（ADR 0035、D-041）。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @throws BusinessException 无权，或该用户在本项目没有角色
     */
    @Transactional
    public void suspend(UUID projectId, UUID appUserId) {
        lockTargetUser(projectId, appUserId, EndUserErrorCode.END_USER_ROLE_NOT_FOUND);

        if (appUserRoleRepository.updateStatus(projectId, appUserId, AppUserRole.Status.DISABLED) == 0) {
            throw new BusinessException(EndUserErrorCode.END_USER_ROLE_NOT_FOUND);
        }
        // 发送前授权复核只能兜住运行时访问；在事实层关闭关系才能避免停用后留下 ACTIVE 授权。
        appUserDeviceRepository.closeActiveByProjectAndUser(projectId, appUserId);
    }

    /**
     * 恢复某终端用户在本项目已停用的角色。
     *
     * @param projectId 项目 ID
     * @param appUserId 终端用户 ID
     * @throws BusinessException 无权，或该用户在本项目没有角色
     */
    @Transactional
    public void restore(UUID projectId, UUID appUserId) {
        lockTargetUser(projectId, appUserId, EndUserErrorCode.END_USER_ROLE_NOT_FOUND);

        if (appUserRoleRepository.updateStatus(projectId, appUserId, AppUserRole.Status.ACTIVE) == 0) {
            throw new BusinessException(EndUserErrorCode.END_USER_ROLE_NOT_FOUND);
        }
    }

    /**
     * 运行访问冻结§4.1及ADR0097：四个角色写入口共用项目→用户锁序，持锁至原事务结束。
     * 项目锁后复核Console角色，防止首次身份查询与等待期间角色变化交错；用户锁后再写项目角色。
     * 不校验租户级ACTIVE：项目管理员仍可管理被平台锁定用户的项目角色，且不能改其登录状态。
     * @param projectId 已授权目标项目
     * @param appUserId 目标用户稳定身份
     * @param missing 原入口的用户/角色不可见分类，避免新增枚举信息
     * @return 权威项目归属租户
     */
    private UUID lockTargetUser(UUID projectId, UUID appUserId, EndUserErrorCode missing) {
        requireEndUserManager(projectId);
        ProjectRoutingContext routing = projectService.requireRoutingContext(projectId);
        transactionLocalRlsScope.establish(routing.tenantId(), projectId);
        lifecycleAccessService.requireActiveForWrite(routing.tenantId(), projectId);
        requireEndUserManager(projectId);
        if (appUserRepository.lockByIdAndTenant(routing.tenantId(), appUserId).isEmpty()) {
            throw new BusinessException(missing);
        }
        return routing.tenantId();
    }

    /** 要求调用者是项目 OWNER / ADMIN。非成员返回 404，成员但角色不足返回 403。 */
    private void requireEndUserManager(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (!role.canManageMembers()) {
            throw new BusinessException(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        }
    }

}
