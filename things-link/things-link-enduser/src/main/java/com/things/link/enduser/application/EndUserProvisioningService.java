package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.PlanCapacityService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.SubscriptionExpansionGuard;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TenantTransactionLocalRlsScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 预置终端用户账号用例（S11-1a）。
 *
 * <h2>核心陷阱：写哪个租户</h2>
 * {@code app_user} 是租户级登录身份，受租户 RLS 保护，写入的 {@code tenant_id} 必须是
 * <b>项目归属租户</b>，而不是调用者自己的租户。调用者的 {@code TenantScope.tenantId} 是
 * 他自己登录控制台时所属的租户 —— 而协作者可能来自其他租户（ADR 0012）。因此：
 *
 * <ol>
 *   <li>用 {@code ProjectService.requireProjectTenant(projectId)} 从 RLS 豁免的
 *       {@code sys_project} 解析出项目的归属租户；</li>
 *   <li>用集中租户范围组件在当前事务连接建立该租户上下文；</li>
 *   <li>再写 {@code app_user} —— 租户 RLS 的 {@code WITH CHECK} 与复合外键共同保证
 *       写入的租户与项目归属一致。</li>
 * </ol>
 *
 * <p>这正是复合外键（ADR 0035）存在的理由：如果只靠 RLS「隐藏」跨租户脏数据，这里写错
 * 租户也只是「看不见」，脏数据仍在库里，审计与计费会把它算进错误的租户。
 *
 * <h2>授权判定在这一层</h2>
 * 项目 OWNER / ADMIN 可预置账号。判定读的是 {@code ProjectRole.canManageMembers()}，
 * 与 iam 权限点授予表读同一段代码，避免「按钮藏了但接口放行」。
 */
@Service
public class EndUserProvisioningService {

    /** 口令最短长度。与控制台 {@code PasswordPolicy} 分开：终端用户口令策略独立演进。 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final ProjectService projectService;
    private final AppUserRepository appUserRepository;
    private final PlanCapacityService planCapacityService;
    private final ProjectLifecycleAccessService lifecycleAccessService;
    private final SubscriptionExpansionGuard expansionGuard;
    private final PasswordEncoder passwordEncoder;
    /** 以项目持久归属建立租户级事务局部 RLS 范围。 */
    private final TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;

    /**
     * 创建终端用户预置服务。
     *
     * @param projectService 项目授权与持久路由端口
     * @param appUserRepository 终端用户仓储
     * @param passwordEncoder 口令哈希编码器
     * @param tenantTransactionLocalRlsScope 事务局部租户范围组件
     * @param planCapacityService 权威有效套餐容量
     * @param lifecycleAccessService 项目持续写许可
     * @param expansionGuard 宽限扩大门禁
     */
    public EndUserProvisioningService(ProjectService projectService,
                                      AppUserRepository appUserRepository,
                                      PasswordEncoder passwordEncoder,
                                      TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope,
                                      PlanCapacityService planCapacityService,
                                      ProjectLifecycleAccessService lifecycleAccessService,
                                      SubscriptionExpansionGuard expansionGuard) {
        this.projectService = projectService;
        this.planCapacityService = planCapacityService;
        this.lifecycleAccessService = lifecycleAccessService;
        this.expansionGuard = expansionGuard;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.tenantTransactionLocalRlsScope = tenantTransactionLocalRlsScope;
    }

    /**
     * 在项目归属租户下预置一个终端用户账号。
     *
     * <p>只创建账号，不分配项目角色 —— 角色分配是独立用例（{@link EndUserRoleService}），
     * 因为「创建账号」与「给账号项目角色」是两个可分别撤销的动作。
     *
     * @param projectId   当前项目。账号落在该项目的归属租户下
     * @param username    租户内用户名，将规范化为 trim + 小写
     * @param password    初始口令
     * @param displayName 显示名称，可空
     * @return 新账号的非敏感投影
     * @throws BusinessException 无权、用户名已存在，或入参不合法
     */
    @Transactional
    public AppUserReference provision(UUID projectId, String username, String password, String displayName) {
        requireEndUserManager(projectId);

        // 归属租户来自 RLS 豁免的 sys_project，而不是调用者的 TenantScope（见类注释）。
        UUID owningTenantId = projectService.requireProjectTenant(projectId);
        lifecycleAccessService.requireActiveForWrite(owningTenantId, projectId);
        requireEndUserManager(projectId);
        tenantTransactionLocalRlsScope.establish(owningTenantId);

        String normalizedUsername = normalizeUsername(username);
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "口令长度不足 " + MIN_PASSWORD_LENGTH + " 位");
        }

        appUserRepository.lockTenantCapacity(owningTenantId);
        // 同名仍保持既有唯一冲突，不把重复请求误报成额度耗尽。
        if (appUserRepository.findByTenantAndUsername(owningTenantId, normalizedUsername).isPresent()) {
            throw new BusinessException(EndUserErrorCode.END_USER_USERNAME_TAKEN);
        }
        expansionGuard.requireExpansionAllowed(owningTenantId);
        long limit = planCapacityService.endUsersLimit(owningTenantId, projectId);
        if (appUserRepository.countByTenant(owningTenantId) >= limit) {
            throw new BusinessException(EndUserErrorCode.END_USER_QUOTA_EXCEEDED);
        }

        AppUser user = new AppUser(
                Uuid7.generate(),
                owningTenantId,
                normalizedUsername,
                passwordEncoder.encode(password),
                normalizeDisplayName(displayName),
                AppUser.Status.ACTIVE,
                null,
                Instant.now());

        try {
            appUserRepository.create(user);
        } catch (DuplicateKeyException e) {
            // (tenant_id, username) 唯一索引仲裁，不先查后插 —— 并发的两次预置会同时
            // 查到「不存在」，然后一个成功一个抛未处理的 500。
            throw new BusinessException(EndUserErrorCode.END_USER_USERNAME_TAKEN);
        }

        return AppUserReference.of(user);
    }

    /** 要求调用者是项目 OWNER / ADMIN。非成员返回 404，成员但角色不足返回 403。 */
    private void requireEndUserManager(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (!role.canManageMembers()) {
            throw new BusinessException(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        }
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "用户名不能为空");
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "用户名过长");
        }
        return normalized;
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 128) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "显示名称过长");
        }
        return trimmed;
    }
}
