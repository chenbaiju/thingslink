package com.things.link.project.application;

import com.things.link.shared.authz.ProjectRole;
import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectMembership;
import com.things.link.project.domain.ProjectRegionRepository;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.project.domain.plan.EffectivePlanQuota;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 项目用例：创建、编辑、删除项目，以及列出我参与的项目。
 *
 * <h2>为什么这两个接口不需要权限点</h2>
 * 「所有用户都可以创建项目」是产品定义（ADR 0012 校准）。而「列出我参与的项目」
 * 天然只返回自己的东西。
 *
 * <p>更关键的是：这两个操作发生在<b>选定项目之前</b>，那时还没有项目角色可言 ——
 * 用项目权限点去守它们是循环的。项目角色从下一个接口开始才有意义。
 *
 * <h2>当前账号从 TenantContext 取，不从 JWT 取</h2>
 * project 模块<b>不能依赖 iam</b>（iam 已经依赖 project，反过来就是循环）。
 * 而 {@link TenantContext} 在 shared 里，由 iam 的过滤器在认证之后填充 ——
 * 这是两个模块之间唯一不产生依赖的传递方式。
 */
@Service
public class ProjectService {

    /** 大陆产品首期默认时区；客户端省略时区时仍得到确定的 cron 解释。 */
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    /** 项目仓储。项目与成员关系必须在同一事务里写入，避免留下孤儿项目。 */
    private final ProjectRepository projectRepository;
    /** 项目区域目录。创建项目时必须查它，避免前后端各维护一份区域白名单。 */
    private final ProjectRegionRepository projectRegionRepository;
    /** ADR0064决策2/5：改名与删除在原事务持项目排他许可，再判断当前OWNER及活跃成员数。 */
    private final ProjectManagementWriteGuard managementWriteGuard;
    /** S14-2b：租户有效套餐的冻结 PROJECTS_MAX 判据；不可用时返回有限安全默认而不是臆造上限。 */
    private final EffectiveQuotaPolicyProvider quotaPolicyProvider;
    /** S14-3c：宽限期禁止扩大类动作（P4）。 */
    private final SubscriptionExpansionGuard expansionGuard;
    private final DeploymentEntitlementPolicy entitlementPolicy;

    /**
     * 创建项目用例。
     *
     * @param projectRepository       项目仓储
     * @param projectRegionRepository 项目区域目录仓储
     * @param managementWriteGuard 原事务项目排他锁与锁后OWNER复核
     * @param quotaPolicyProvider 租户有效套餐的冻结配额读取端口
     * @param expansionGuard 宽限期扩大类动作门禁
     */
    @Autowired
    public ProjectService(
            ProjectRepository projectRepository,
            ProjectRegionRepository projectRegionRepository,
            ProjectManagementWriteGuard managementWriteGuard,
            EffectiveQuotaPolicyProvider quotaPolicyProvider,
            SubscriptionExpansionGuard expansionGuard,
            DeploymentEntitlementPolicy entitlementPolicy) {
        this.projectRepository = projectRepository;
        this.projectRegionRepository = projectRegionRepository;
        this.managementWriteGuard = managementWriteGuard;
        this.quotaPolicyProvider = quotaPolicyProvider;
        this.expansionGuard = expansionGuard;
        this.entitlementPolicy = entitlementPolicy;
    }

    /** 保留非 Spring 单测的原构造入口；生产始终注入部署权益模式。 */
    public ProjectService(ProjectRepository projectRepository, ProjectRegionRepository projectRegionRepository,
            ProjectManagementWriteGuard managementWriteGuard, EffectiveQuotaPolicyProvider quotaPolicyProvider,
            SubscriptionExpansionGuard expansionGuard) {
        this(projectRepository, projectRegionRepository, managementWriteGuard, quotaPolicyProvider,
                expansionGuard, DeploymentEntitlementPolicy.commercial());
    }

    /**
     * 创建项目。创建者自动成为该项目的 OWNER。
     *
     * <p>两步必须在<b>同一个事务</b>里：只建项目不建成员关系的话，会留下一个
     * 谁也进不去的项目 —— 没有成员，就没有任何人能在列表里看到它，
     * 也就没有入口能删掉它。与注册时的「孤儿租户」是同一类问题。
     *
     * <p>S14-2b：创建前在租户级事务锁下按有效套餐的冻结 {@code projects_max} 计数。FREE 为 1，
     * 因此第二个<b>自有</b>项目被 50020 拒绝；外部协作者身份属于他人租户，不占本租户名额。
     *
     * @param name 项目名称
     * @param region 区域编码
     * @param timezone IANA 项目时区；空值采用 {@link #DEFAULT_TIMEZONE}
     * @return 新建项目及创建者的角色
     */
    @Transactional
    public ProjectMembership create(String name, String region, String timezone) {
        TenantScope scope = currentScope();
        String normalizedRegion = region.trim();
        String normalizedTimezone = normalizeTimezone(timezone);
        if (projectRegionRepository.findProjectCreatable(normalizedRegion).isEmpty()) {
            // 区域决定后续设备接入域名与数据落点，不能接受前端随便传一个未知值。
            // 这里查平台目录而不是代码白名单：前端展示、后端校验与未来运营后台
            // 必须共用同一份事实，否则「页面可选但接口拒绝」会成为常态
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "项目区域不可用");
        }
        enforceOwnedProjectQuota(scope);

        String projectKey = generateProjectKey();
        Project project = new Project(
                Uuid7.generate(),
                // 归属创建者的租户。它决定账单算在谁头上，**不决定谁能访问**
                scope.tenantId(),
                name.trim(),
                normalizedRegion,
                normalizedTimezone,
                projectKey,
                Project.Status.ACTIVE,
                java.time.Instant.now());

        projectRepository.create(project);
        projectRepository.addMember(
                Uuid7.generate(), project.id(), scope.accountId(), ProjectRole.OWNER);

        log.info("创建项目 projectId={} tenantId={} accountId={} region={} timezone={}",
                project.id(), scope.tenantId(), scope.accountId(), normalizedRegion, normalizedTimezone);

        return new ProjectMembership(project, ProjectRole.OWNER);
    }

    /**
     * S14-2b：在租户级事务锁下，按有效套餐的冻结 {@code projects_max} 拒绝超限的自有项目创建。
     *
     * <p>锁序固定为「取租户锁 → 读有效策略 → 数自有项目 → 比较」。若先数后锁，两个并发事务会
     * 同时读到「未到上限」并各自插入，上限形同不存在；因此计数必须与创建设置在同一把锁的临界区内。
     *
     * <p>没有冻结配额投影（{@code planQuota == null}）时不臆造上限：S7 既有运行时模板没有
     * {@code PROJECTS_MAX} 这一事实，按「未知」处理而非按「FREE=1」处理。S14-2a 之后的新租户
     * 默认绑定 {@code PLAN_R1_FREE}，必然拿到判据；策略读取在依赖故障时由提供者回落到有限安全默认，
     * 此时同样没有冻结投影，按ADR0159拒绝新增并返回503/50047；不改动既有项目或猜测额度。
     *
     * @param scope 已认证请求的租户范围
     * @throws BusinessException 自有项目数已达到有效套餐上限
     */
    private void enforceOwnedProjectQuota(TenantScope scope) {
        // S14-3c：宽限期内一律禁止新建项目（P4「禁止新增扩大类动作」），即使额度还有富余。
        // 放在额度判定之前：错误码要指向真正的原因（宽限禁扩大 50033，而不是「额度已满」50020）。
        expansionGuard.requireExpansionAllowed(scope.tenantId());
        projectRepository.lockTenantProjectQuota(scope.tenantId());
        long projectsMax;
        if (entitlementPolicy.nonCommercial()) {
            projectsMax = entitlementPolicy.capacity(DeploymentEntitlementPolicy.Capacity.PROJECTS);
        } else {
            EffectivePlanQuota planQuota = quotaPolicyProvider.resolveTrustedTenant(scope.tenantId()).planQuota();
            if (planQuota == null) {
                throw new BusinessException(ProjectErrorCode.PROJECT_QUOTA_UNAVAILABLE);
            }
            projectsMax = planQuota.projectsMax();
        }
        long owned = projectRepository.countOwnedProjects(scope.tenantId());
        if (owned >= projectsMax) {
            log.info("拒绝超限创建自有项目 tenantId={} accountId={} owned={} projectsMax={}",
                    scope.tenantId(), scope.accountId(), owned, projectsMax);
            throw new BusinessException(ProjectErrorCode.PROJECT_QUOTA_EXCEEDED);
        }
    }

    /**
     * 列出当前账号参与的全部项目。
     *
     * <p>结果<b>跨租户</b>：既有自己创建的，也有被邀请加入的、属于别人租户的项目。
     *
     * @return 项目及我在其中的角色
     */
    @Transactional(readOnly = true)
    public List<ProjectMembership> listMine() {
        return projectRepository.findMembershipsByAccount(currentScope().accountId());
    }

    /**
     * 修改项目名称。
     *
     * <p>只有 OWNER 可以改。项目名称虽然不是安全边界，但它是协作者共同看到的项目
     * 标识；ADMIN 若能随意改名，会造成误操作与审计追溯混乱。
     *
     * <p>区域不在请求体里，也不在仓储方法里。区域一经创建不可变更（架构文档 7.1），
     * 后续真要跨区域，只能走导出-导入或迁移任务。
     *
     * @param projectId 项目 ID
     * @param name      新项目名称
     * @return 修改后的项目及当前账号角色
     */
    @Transactional
    public ProjectMembership updateName(UUID projectId, String name) {
        TenantScope scope = currentScope();
        managementWriteGuard.requireOwner(projectId, scope.accountId());

        if (projectRepository.updateName(projectId, name.trim()) == 0) {
            // 合规并发写已由项目排他锁排序；零行仍防御异常数据或旁路修改，不泄露内部不一致。
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }

        log.info("修改项目名称 projectId={} accountId={}", projectId, scope.accountId());
        return projectRepository.findMembership(projectId, scope.accountId())
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    /**
     * 删除项目。
     *
     * <p>删除只允许 OWNER，且项目里只能剩 OWNER 一名活跃成员。若还有其他活跃成员，必须先到
     * 成员管理里逐个移出 —— 那个动作明确表达“解除成员与项目绑定”，并且后续会进入
     * 审计；直接删项目会让所有协作者突然失去入口。
     *
     * <p>ADR0064决策2/5：项目排他锁后重新核验OWNER及活跃成员数；同事务置DELETING/deleted_at，
     * 保留全部成员原角色/状态作为权威历史事实。普通授权依项目状态拒绝，保留行不产生现时访问资格。
     * 删除短事务只改项目，不跨域清理设备、轮询或时序，也不从历史缺失成员重建OWNER。
     *
     * @param projectId 项目 ID
     */
    @Transactional
    public void delete(UUID projectId) {
        TenantScope scope = currentScope();
        managementWriteGuard.requireOwner(projectId, scope.accountId());

        int memberCount = projectRepository.countActiveMembers(projectId);
        if (memberCount > 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_HAS_MEMBERS);
        }
        if (memberCount == 0) {
            // 正常项目至少有OWNER；锁后为零仍按不存在拒绝，避免历史异常或旁路数据暴露内部不一致。
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }

        if (projectRepository.softDelete(projectId) == 0) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }

        log.info("删除项目 projectId={} accountId={}", projectId, scope.accountId());
    }

    /**
     * 取当前请求的范围。
     *
     * @return 租户范围
     * @throws IllegalStateException 没有租户上下文
     */
    private static TenantScope currentScope() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException(
                // 走到这里说明接口没有被 Security 保护，或者 TenantScopeFilter 没生效。
                // 抛异常而不是返回空列表：后者会把「认证链路坏了」伪装成「你没有项目」
                "没有租户上下文。本接口必须在已认证的请求中调用"));
    }

    /** @return 随机 8 字符全局唯一 projectKey，仅包含小写字母和数字 */
    private static String generateProjectKey() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(8);
        java.security.SecureRandom rng = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }

    /**
     * 取当前账号在指定项目中的角色。
     *
     * <p>供后续的授权判定与项目切换使用。查不到时返回空 —— 调用方应当把它当作
     * <b>项目不存在</b>处理，而不是「无权限」：后者会泄露该项目是否存在。
     *
     * @param projectId 项目 ID
     * @return 角色；非成员时为空
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ProjectRole> roleInProject(UUID projectId) {
        return projectRepository.findRole(projectId, currentScope().accountId());
    }

    /**
     * 取当前账号的项目角色，不是成员时按项目不存在处理。
     *
     * <p>跨模块调用方使用本方法即可获得项目域统一的 50001，同时不需要依赖 project.domain 内部包。</p>
     *
     * @param projectId 项目 ID
     * @return 当前账号角色
     */
    @Transactional(readOnly = true)
    public ProjectRole requireRoleInProject(UUID projectId) {
        return roleInProject(projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    /**
     * ADR0070决策1：为已授权控制台写入取得持久项目归属，不能沿用跨租户协作者自己的tenant。
     * 本查询保留ARCHIVED供后续持续许可分类为只读；返回值只是身份，不代表写许可或锁后角色。
     * @param projectId 当前账号参与的项目
     * @return 项目真实归属租户；不存在、删除或非成员沿用50001
     */
    @Transactional(readOnly = true)
    public UUID requireProjectTenant(UUID projectId) {
        return projectRepository.findMembership(projectId, currentScope().accountId())
                .map(membership -> membership.project().tenantId())
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    /**
     * 按 MQTT projectKey 返回设备接入所需的最小项目范围。
     *
     * <p>公开注册入口没有控制台账号，不能走成员授权；本方法只返回隔离 ID，调用方仍须验证产品密钥。
     * 不存在时返回空，接入模块必须统一拒绝，不能泄露项目是否存在。</p>
     *
     * @param projectKey 项目 MQTT 标识
     * @return 项目隔离范围
     */
    @Transactional(readOnly = true)
    public java.util.Optional<DeviceAccessScope> findDeviceAccessScope(String projectKey) {
        return projectRepository.findByProjectKey(projectKey)
                .map(project -> new DeviceAccessScope(project.id(), project.tenantId()));
    }

    /**
     * 为已通过设备域归属校验的数据面调用返回稳定 MQTT projectKey。
     *
     * <p>该方法不做账号成员授权，不能用于 HTTP 入口；它只给跨模块数据面端口补齐 Topic 路由字段。</p>
     *
     * @param projectId 已确权项目 ID
     * @return 项目路由投影
     */
    @Transactional(readOnly = true)
    public ProjectRoutingContext requireRoutingContext(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        return new ProjectRoutingContext(project.tenantId(), project.projectKey());
    }

    /** @param projectId 项目 ID @param tenantId 归属租户 ID */
    public record DeviceAccessScope(UUID projectId, UUID tenantId) {
    }

    /** @param tenantId 项目归属租户 @param projectKey MQTT Topic 稳定项目键 */
    public record ProjectRoutingContext(UUID tenantId, String projectKey) {
    }

    /**
     * 为已从任务事实确权的后台调度返回项目所有者与权威时区。
     *
     * <p>该端口不做控制台成员授权，不能直接用于 HTTP；任务模块必须先在自己的项目 RLS 范围内取得 job，
     * 再用其中的 projectId 调用。这样后台线程没有账号上下文时仍能恢复 owner tenant 和 cron 解释时区。</p>
     *
     * @param projectId 已由任务事实确权的项目 ID
     * @return 任务调度所需的最小项目投影
     */
    @Transactional(readOnly = true)
    public ProjectSchedulingContext requireSchedulingContext(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        return new ProjectSchedulingContext(project.tenantId(), project.timezone());
    }

    /** @param tenantId 项目所有者租户 @param timezone 权威 IANA 项目时区 */
    public record ProjectSchedulingContext(UUID tenantId, String timezone) {
    }

    /**
     * 规整并用 JDK 时区库验证 IANA 标识，拒绝仅看起来像时区的未知字符串。
     *
     * @param timezone 客户端可省略的时区
     * @return 规范化时区 ID
     */
    private static String normalizeTimezone(String timezone) {
        String candidate = timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.strip();
        if (candidate.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "项目时区不合法");
        }
        try {
            return ZoneId.of(candidate).getId();
        } catch (DateTimeException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "项目时区不合法");
        }
    }

}
