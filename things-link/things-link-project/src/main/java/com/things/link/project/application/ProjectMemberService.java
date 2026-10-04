package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectMember;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 项目成员管理用例：查看、邀请、改角色、移出（S1 切片 5d）。
 *
 * <h2>这是四个项目角色第一次真正产生差异的地方</h2>
 * 在此之前四个角色的权限集合完全相同（平台只有 {@code dashboard:read} 一个权限点），
 * 分级机制接通了但看不出效果。从这里开始 OWNER/ADMIN 与 OPERATOR/VIEWER 分道：
 * 前两者能改成员，后两者只能看。
 *
 * <h2>授权判定在这一层，不在 Controller</h2>
 * 架构文档 7.2：禁止仅靠前端隐藏菜单实现授权。而只在 Controller 判也不够 ——
 * 将来一定会出现第二个调用方（定时任务、内部接口、批量导入），那时判定要么被复制
 * 一份、要么被漏掉。放在用例这一层，所有入口共用同一段代码。
 *
 * <h2>每个操作都从当前成员事实重新判定角色</h2>
 * 不复用令牌里的信息 —— 令牌不带角色，正是因为它会过期（ADR 0012 校准）。
 * 刚被降级的人手里的令牌在剩余有效期内仍然有效，若照它判定，降级要等到令牌过期
 * 才生效。ADR0064决策2/5下，五个成员写入口先预检各自权限，再在原事务取得项目排他锁并重新授权；
 * 锁保持到成员变更及审计提交。列表仍只观察成员快照，主动退出使用锁后角色判断OWNER限制。
 */
@Service
public class ProjectMemberService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMemberService.class);

    private final ProjectRepository projectRepository;

    /**
     * 审计日志。成员与权限变更必须持久记录（架构文档 7.3），不能只靠 log.info。
     */
    private final AuditLogService auditLogService;

    /**
     * 账号目录。实现在 iam，由 Spring 在运行期注入 —— 编译期 project 只认识接口
     * （见 {@link AccountDirectory} 里关于依赖反转的说明）。
     */
    private final AccountDirectory accountDirectory;

    /** ADR0064决策2/5：成员写入口按各自角色要求共用原事务排他许可，防止冻结或等待期间失权后继续写。 */
    private final ProjectManagementWriteGuard managementWriteGuard;

    /** S14-R4d：持久owner租户的宽限/席位与目标账号50外部项目安全上限。 */
    private final CollaborationAdmissionService collaborationAdmission;

    /**
     * 构造项目成员用例服务。
     *
     * @param projectRepository 项目仓储
     * @param accountDirectory  账号目录端口
     * @param auditLogService   审计日志写入服务
     * @param managementWriteGuard 原事务内项目排他锁与锁后管理角色复核
     * @param collaborationAdmission 所属租户席位与目标账号安全准入
     */
    public ProjectMemberService(ProjectRepository projectRepository,
                                AccountDirectory accountDirectory,
                                AuditLogService auditLogService,
                                ProjectManagementWriteGuard managementWriteGuard,
                                CollaborationAdmissionService collaborationAdmission) {
        this.projectRepository = projectRepository;
        this.accountDirectory = accountDirectory;
        this.auditLogService = auditLogService;
        this.managementWriteGuard = managementWriteGuard;
        this.collaborationAdmission = collaborationAdmission;
    }

    /**
     * 列出项目成员。
     *
     * <p>四个角色都能看：知道「这个项目里有谁」是协作的前提，藏起来只会让人
     * 去问管理员，而管理员会截图发群里 —— 保护效果为零，摩擦是真的。
     *
     * @param projectId 项目 ID
     * @return 成员列表，按加入时间正序
     * @throws BusinessException 当前账号不是该项目成员（返回 404，不泄露项目是否存在）
     */
    @Transactional(readOnly = true)
    public List<ProjectMemberView> list(UUID projectId) {
        requireMembership(projectId);

        List<ProjectMember> members = projectRepository.findMembers(projectId);

        // 一次批量查回全部账号信息，而不是在循环里逐个查（N+1）
        Map<UUID, AccountRef> accounts = accountDirectory
                .findByIds(members.stream().map(ProjectMember::accountId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(AccountRef::id, Function.identity()));

        return members.stream()
                .map(member -> {
                    AccountRef account = accounts.get(member.accountId());
                    if (account == null) {
                        // 账号被软删除但成员行还在。理论上不该出现（目前没有删账号的入口），
                        // 但真出现时整个列表不该 500 —— 那会让管理员连「把这条清掉」
                        // 都做不到。降级显示，并留下日志便于排查
                        log.warn("成员对应的账号已不存在 projectId={} accountId={}",
                                projectId, member.accountId());
                        return new ProjectMemberView(member.accountId(),
                                "(账号已注销)", "(账号已注销)", member.role(), member.createdAt());
                    }
                    return new ProjectMemberView(account.id(), account.email(),
                            account.displayName(), member.role(), member.createdAt());
                })
                .toList();
    }

    /**
     * 按邮箱邀请一个已注册账号加入项目。
     *
     * <p>目前是<b>直接加入</b>，没有「待接受」状态：被邀请人下次进项目列表就能看到
     * 这个项目。G3-LOCAL-11d明确保留直接添加语义，未注册邀请/确认/撤回不在当前合同内，
     * 邮件设施存在不等于邀请功能已完成。当前「立即生效」的语义是明确的 ——
     * 界面上写的是「添加成员」而不是「邀请」，不会让人以为对方还要点什么。
     *
     * @param projectId 项目 ID
     * @param email     被邀请人的注册邮箱，大小写不敏感
     * @param role      分配的角色，不能是 OWNER
     * @return 新成员
     * @throws BusinessException 无权、邮箱未注册、已是成员，或试图分配 OWNER
     */
    @Transactional
    public ProjectMemberView invite(UUID projectId, String email, ProjectRole role) {
        TenantScope scope = requireMemberManager(projectId);
        // HTTP 入参校验只保证「像邮箱」，不保证调用方一定像控制台一样先 trim。
        // 邀请是按账号表里的邮箱查人，多一个尾随空格不该把已注册账号误判为不存在。
        String normalizedEmail = email.trim();

        if (role == ProjectRole.OWNER) {
            // 每个项目只有一个 OWNER，由创建项目产生。转让所有权是另一个操作
            throw new BusinessException(ProjectErrorCode.OWNER_NOT_ASSIGNABLE);
        }

        AccountRef invitee = accountDirectory.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.INVITEE_NOT_REGISTERED));

        if (invitee.id().equals(scope.accountId())) {
            // 自己邀请自己。不拦的话会撞唯一索引，报出来的是 ALREADY_MEMBER，
            // 提示是「该账号已经是本项目成员」—— 正确但让人困惑
            throw new BusinessException(ProjectErrorCode.CANNOT_TARGET_SELF);
        }

        collaborationAdmission.requireAdmission(projectId, invitee.id());
        try {
            projectRepository.addMember(Uuid7.generate(), projectId, invitee.id(), role);
        } catch (DuplicateKeyException e) {
            // 唯一索引仲裁，不先查后插：并发的两次邀请会同时查到「还不是成员」，
            // 然后一个成功一个抛未处理的 500（与注册的邮箱冲突是同一套处理）
            throw new BusinessException(ProjectErrorCode.ALREADY_MEMBER);
        }

        log.info("邀请成员 projectId={} inviteeId={} role={} byAccountId={}",
                projectId, invitee.id(), role, scope.accountId());
        auditMemberChange(scope, projectId, invitee.id(), "project.member.invited", Map.of(
                "email", invitee.email(),
                "role", role.name()));

        return new ProjectMemberView(invitee.id(), invitee.email(), invitee.displayName(),
                role, java.time.Instant.now());
    }

    /**
     * 改成员在本项目中的角色。
     *
     * @param projectId 项目 ID
     * @param accountId 目标账号 ID
     * @param role      新角色，不能是 OWNER
     * @throws BusinessException 无权、目标是 OWNER、目标是自己，或目标已不是成员
     */
    @Transactional
    public void updateRole(UUID projectId, UUID accountId, ProjectRole role) {
        TenantScope scope = requireMemberManager(projectId);
        ProjectRole oldRole = requireModifiableTarget(projectId, accountId, scope);

        if (role == ProjectRole.OWNER) {
            throw new BusinessException(ProjectErrorCode.OWNER_NOT_ASSIGNABLE);
        }

        if (projectRepository.updateMemberRole(projectId, accountId, role) == 0) {
            // 0 行 = 目标在这两步之间被别人移出了项目。
            // 静默成功会让界面显示「已修改」，而实际上什么都没发生
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND,
                    "该成员已不在本项目中");
        }

        log.info("修改成员角色 projectId={} accountId={} newRole={} byAccountId={}",
                projectId, accountId, role, scope.accountId());
        auditMemberChange(scope, projectId, accountId, "project.member.role_updated", Map.of(
                "oldRole", oldRole.name(),
                "newRole", role.name()));
    }

    /**
     * 把某个成员移出项目。
     *
     * <p>只删除关联，<b>账号本身完全不受影响</b>：他还能登录，还能看到自己参与的
     * 其他项目，只是这个项目从他的列表里消失。这正是需求里
     * 「删除的用户失去与该项目的关联，账户并没有删除」。
     *
     * <p><b>被移出的人手里的令牌不会立刻失效</b>，但也不会有实际权限：菜单接口每次
     * 都重新查角色（查不到即按「未选项目」处理），项目列表按成员关系过滤。
     * 令牌本身无法撤销 —— 这是 JWT 的固有代价，已在 ADR 0010 记录。
     *
     * @param projectId 项目 ID
     * @param accountId 目标账号 ID
     * @throws BusinessException 无权、目标是 OWNER、目标是自己，或目标本就不是成员
     */
    @Transactional
    public void remove(UUID projectId, UUID accountId) {
        TenantScope scope = requireMemberManager(projectId);
        ProjectRole oldRole = requireModifiableTarget(projectId, accountId, scope);

        if (projectRepository.removeMember(projectId, accountId) == 0) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND,
                    "该成员已不在本项目中");
        }

        log.info("移除成员 projectId={} accountId={} byAccountId={}",
                projectId, accountId, scope.accountId());
        auditMemberChange(scope, projectId, accountId, "project.member.removed", Map.of(
                "oldRole", oldRole.name()));
    }

    /**
     * 转让项目所有权。
     *
     * <p>只有当前 OWNER 能执行。目标必须是项目内的另一个成员；转让后目标成为 OWNER，
     * 原 OWNER 降为 ADMIN。选择 ADMIN 而不是 VIEWER，是因为“交出所有权”不等于退出项目，
     * 原所有者通常仍要继续协作和处理迁移后的收尾。
     *
     * @param projectId     项目 ID
     * @param newOwnerId    新 OWNER 账号 ID
     * @throws BusinessException 当前账号不是 OWNER、目标不是成员、或目标是自己
     */
    @Transactional
    public void transferOwnership(UUID projectId, UUID newOwnerId) {
        TenantScope scope = currentScope();
        // ADR0064决策2/5：先持项目排他锁并复验当前OWNER，再读取目标，双角色更新与审计沿原事务提交。
        managementWriteGuard.requireOwner(projectId, scope.accountId());
        if (newOwnerId.equals(scope.accountId())) {
            throw new BusinessException(ProjectErrorCode.CANNOT_TARGET_SELF);
        }

        ProjectRole targetRole = projectRepository.findRole(projectId, newOwnerId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND,
                        "该成员不在本项目中"));
        if (targetRole == ProjectRole.OWNER) {
            throw new BusinessException(ProjectErrorCode.OWNER_NOT_ASSIGNABLE);
        }

        if (!projectRepository.transferOwnership(projectId, scope.accountId(), newOwnerId)) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND,
                    "项目所有权转让失败，请刷新成员列表后重试");
        }

        log.info("转让项目所有权 projectId={} oldOwnerId={} newOwnerId={}",
                projectId, scope.accountId(), newOwnerId);
        auditMemberChange(scope, projectId, newOwnerId, "project.member.ownership_transferred", Map.of(
                "oldOwnerId", scope.accountId().toString(),
                "oldOwnerNewRole", ProjectRole.ADMIN.name(),
                "newOwnerOldRole", targetRole.name(),
                "newOwnerNewRole", ProjectRole.OWNER.name()));
    }

    /**
     * 当前账号主动退出项目。
     *
     * <p>这和 OWNER / ADMIN 的“移出成员”不同：它只作用于自己，不需要成员管理权限。
     * 但 OWNER 不能退出，因为项目必须始终保留一个所有者；所有者应先转让或删除项目。
     *
     * @param projectId 项目 ID
     * @throws BusinessException 当前账号不是成员，或当前账号是 OWNER
     */
    @Transactional
    public void leave(UUID projectId) {
        TenantScope scope = currentScope();
        // 使用锁后角色判断OWNER，不能沿锁前旧角色退出；归档有效成员由许可先拒绝50017。
        ProjectRole oldRole = managementWriteGuard.requireMember(projectId, scope.accountId());

        if (oldRole == ProjectRole.OWNER) {
            throw new BusinessException(ProjectErrorCode.OWNER_CANNOT_LEAVE);
        }

        if (projectRepository.removeMember(projectId, scope.accountId()) == 0) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND,
                    "你已不在本项目中");
        }

        log.info("成员主动退出项目 projectId={} accountId={}", projectId, scope.accountId());
        auditMemberChange(scope, projectId, scope.accountId(), "project.member.left", Map.of(
                "oldRole", oldRole.name()));
    }

    /**
     * 要求当前账号是该项目成员，并返回他的角色。
     *
     * <p>非成员返回 404 而不是 403：403 等于确认「这个项目存在」，
     * 攻击者拿一份 UUID 逐个试就能枚举出平台上有哪些项目。
     *
     * @param projectId 项目 ID
     * @return 当前账号在该项目中的角色
     */
    private ProjectRole requireMembership(UUID projectId) {
        return projectRepository.findRole(projectId, currentScope().accountId())
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    /**
     * 要求当前账号有权管理该项目的成员。
     *
     * <p>ADR0064决策2/5：先角色授权预检，再取得项目排他锁并重新授权及分类状态。
     * 许可先于账号目录、目标成员和审计；显式传项目与当前账号，跨租户协作者的tenant不充当项目归属。
     *
     * @param projectId 项目 ID
     * @return 当前请求的范围
     */
    private TenantScope requireMemberManager(UUID projectId) {
        TenantScope scope = currentScope();
        managementWriteGuard.requireMemberManager(projectId, scope.accountId());
        return scope;
    }

    /**
     * 校验目标成员可以被改动。
     *
     * <p>两条规则，各挡一类无法挽回的结局：
     * <ul>
     *   <li><b>不能动 OWNER</b> —— 他是项目最后的管理入口。ADMIN 若能移除 OWNER，
     *       一个被入侵的 ADMIN 账号就能把项目彻底夺走</li>
     *   <li><b>不能动自己</b> —— ADMIN 把自己降成 VIEWER 或移出项目后，
     *       就再没有入口能撤销这个动作了</li>
     * </ul>
     *
     * <p>仓储层的 SQL 里还带了 {@code role <> 'OWNER'} 作为第二道保险：
     * 这一层的检查被绕过或写错时，数据库仍然不会把所有者删掉。
     *
     * @param projectId 项目 ID
     * @param accountId 目标账号 ID
     * @param scope     当前请求范围
     */
    private ProjectRole requireModifiableTarget(UUID projectId, UUID accountId, TenantScope scope) {
        if (accountId.equals(scope.accountId())) {
            throw new BusinessException(ProjectErrorCode.CANNOT_TARGET_SELF);
        }

        ProjectRole targetRole = projectRepository.findRole(projectId, accountId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND,
                        "该成员不在本项目中"));

        if (targetRole == ProjectRole.OWNER) {
            throw new BusinessException(ProjectErrorCode.OWNER_NOT_REMOVABLE);
        }

        return targetRole;
    }

    /**
     * 记录项目成员变更审计。
     *
     * <p>targetId 使用目标账号 ID，而不是 {@code sys_project_member.id}。成员行在移出时会被
     * 物理删除；账号 ID 才是事后排查“谁被动过”的稳定标识。
     *
     * @param scope           当前请求范围
     * @param projectId       项目 ID
     * @param targetAccountId 被操作的账号 ID
     * @param action          动作编码
     * @param details         结构化详情
     */
    private void auditMemberChange(TenantScope scope, UUID projectId, UUID targetAccountId,
                                   String action, Map<String, ?> details) {
        auditLogService.record(new AuditLogEntry(
                scope.tenantId(),
                projectId,
                scope.accountId(),
                "project_member",
                targetAccountId,
                action,
                details));
    }

    /**
     * 取当前请求的范围。
     *
     * @return 租户范围
     * @throws IllegalStateException 没有租户上下文
     */
    private static TenantScope currentScope() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException(
                "没有租户上下文。本接口必须在已认证的请求中调用"));
    }

}
