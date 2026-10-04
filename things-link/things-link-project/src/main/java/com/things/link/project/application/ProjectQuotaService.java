package com.things.link.project.application;

import com.things.link.project.domain.PlanSummaryRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectQuotaOverview;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.project.domain.QuotaMetric;
import com.things.link.project.domain.QuotaMetricUsage;
import com.things.link.project.domain.QuotaOverviewRepository;
import com.things.link.project.domain.TenantPlanSummary;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 项目设置页的配额和 UTC 日用量只读用例。
 *
 * <p>本服务不承担运行时限流；S7-3 才会将入口限流接入同一策略。S7-2 只读取 PostgreSQL
 * 的权威事实，明确把“当前项目贡献”与“租户共享池”分开，避免 UI 暗示项目自有套餐。
 */
@Service
public class ProjectQuotaService {

    /** 项目成员关系与成员计数的唯一事实端口。 */
    private final ProjectRepository projectRepository;
    /** 当前项目的设备存量贡献端口；由 device 模块实现以维护表所有权。 */
    private final ObjectProvider<ProjectDeviceQuotaContributor> deviceContributorProvider;
    /** 共享池最小投影端口；内部调用受限 SECURITY DEFINER 函数。 */
    private final QuotaOverviewRepository quotaOverviewRepository;
    /** 当前项目归属租户的套餐摘要端口（S14-2c）；只在同一租户成员读取时返回事实。 */
    private final PlanSummaryRepository planSummaryRepository;
    /** S14-3c：宽限期禁止新增设备（P4「禁止新增扩大类动作」）。 */
    private final SubscriptionExpansionGuard expansionGuard;
    private final DeploymentEntitlementPolicy entitlementPolicy;

    /**
     * @param projectRepository 项目成员与成员数事实端口
     * @param deviceContributorProvider 设备存量贡献端口提供器；允许 project 模块独立启动测试上下文
     * @param quotaOverviewRepository 租户共享池投影端口
     * @param planSummaryRepository 租户套餐摘要端口；SQL 自身要求调用者属于项目归属租户
     * @param expansionGuard 宽限期扩大类动作门禁
     */
    @Autowired
    public ProjectQuotaService(ProjectRepository projectRepository,
                               ObjectProvider<ProjectDeviceQuotaContributor> deviceContributorProvider,
                               QuotaOverviewRepository quotaOverviewRepository,
                               PlanSummaryRepository planSummaryRepository,
                               SubscriptionExpansionGuard expansionGuard,
                               DeploymentEntitlementPolicy entitlementPolicy) {
        this.projectRepository = projectRepository;
        this.deviceContributorProvider = deviceContributorProvider;
        this.quotaOverviewRepository = quotaOverviewRepository;
        this.planSummaryRepository = planSummaryRepository;
        this.expansionGuard = expansionGuard;
        this.entitlementPolicy = entitlementPolicy;
    }

    /** 保留非 Spring 单测原构造入口。 */
    public ProjectQuotaService(ProjectRepository projectRepository,
            ObjectProvider<ProjectDeviceQuotaContributor> deviceContributorProvider,
            QuotaOverviewRepository quotaOverviewRepository, PlanSummaryRepository planSummaryRepository,
            SubscriptionExpansionGuard expansionGuard) {
        this(projectRepository, deviceContributorProvider, quotaOverviewRepository, planSummaryRepository,
                expansionGuard, DeploymentEntitlementPolicy.commercial());
    }

    /**
     * 读取当前已选项目的配额概览，并附带租户套餐摘要。
     *
     * <p>路径参数不是授权依据：必须等于 JWT 的已选项目，才能让 SQL 函数由
     * {@code app_current_project()} 安全派生租户。不同项目统一返回 404，不泄露该项目
     * 是否存在，也不接受任何客户端传来的 tenantId。
     *
     * @param projectId 路径中的项目 ID
     * @return 当前 UTC 日的真实配额和用量投影，以及同一租户成员可见的套餐摘要
     */
    @Transactional(readOnly = true)
    public ProjectQuotaOverview get(UUID projectId) {
        return load(projectId, true);
    }

    /**
     * 为受信业务写入口返回同一份 PostgreSQL 权威配额概览。
     *
     * <p>该别名避免设备等跨模块调用依赖 Controller 的“get”语义；授权、项目一致性与共享池
     * 聚合仍完全复用本服务，不开放第二套查询口径。数据面不需要套餐摘要，因此这里不额外
     * 查询订阅事实。</p>
     *
     * @param projectId 已授权且已选中的项目 ID
     * @return 当前 UTC 日配额与用量概览
     */
    @Transactional(readOnly = true)
    public ProjectQuotaOverview overview(UUID projectId) {
        return load(projectId, false);
    }

    /**
     * 为设备写入口返回 application 层的存量配额等级，避免跨模块泄露概览领域模型。
     *
     * @param projectId 已授权且已选中的项目 ID
     * @return 当前租户共享设备存量等级
     */
    @Transactional(readOnly = true)
    public QuotaStatus deviceQuotaStatus(UUID projectId) {
        QuotaStatus status = QuotaStatus.valueOf(load(projectId, false).deviceUsage().status().name());
        // S14-3c：授权与项目一致性已由 load 确认，之后才判宽限，避免向未授权调用方泄露订阅状态。
        UUID ownerTenant = projectRepository.findById(projectId)
                .map(project -> project.tenantId())
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        expansionGuard.requireExpansionAllowed(ownerTenant);
        return status;
    }

    /**
     * 为没有HTTP账号上下文的受信设备入口返回owner租户共享设备存量等级。
     *
     * <p>策略和存量均读取本次事务的PostgreSQL权威事实；无效二元组或查询故障向外抛出，
     * 由调用入口回滚消息及业务事实。调用方必须先持有owner tenant设备配额锁，才能让
     * “读取已用量→创建设备”在所有合作入口之间保持串行。</p>
     *
     * @param trustedTenantId 已认证项目的owner租户ID
     * @param trustedProjectId 已认证项目ID
     * @return 当前租户共享设备存量等级
     */
    @Transactional(readOnly = true)
    public QuotaStatus deviceQuotaStatus(UUID trustedTenantId, UUID trustedProjectId) {
        QuotaOverviewRepository.DeviceQuotaPolicyRow policy = quotaOverviewRepository
                .findDeviceQuotaPolicy(trustedTenantId, trustedProjectId)
                .orElseThrow(() -> new IllegalArgumentException("设备确权租户与项目归属不匹配或策略无效"));
        // S14-3c：二元组已由策略行确权，宽限期内禁止新增设备；既有设备连接与上行不经过本方法。
        expansionGuard.requireExpansionAllowed(trustedTenantId);
        ProjectDeviceQuotaContributor deviceContributor = deviceContributorProvider.getIfAvailable(() -> {
            throw new IllegalStateException("设备模块未装配，不能读取设备存量配额");
        });
        ProjectDeviceQuotaContributor.ProjectDeviceQuotaUsage usage =
                deviceContributor.countActiveDevices(trustedTenantId, trustedProjectId);
        Long deviceLimit = entitlementPolicy.nonCommercial()
                ? Long.valueOf(entitlementPolicy.capacity(DeploymentEntitlementPolicy.Capacity.DEVICES))
                : policy.deviceLimit();
        QuotaMetricUsage metric = new QuotaMetricUsage(QuotaMetric.DEVICE_COUNT, deviceLimit,
                usage.projectUsed(), usage.tenantUsed(), policy.softLimitBasisPoints(), policy.degradeBasisPoints());
        return QuotaStatus.valueOf(metric.status().name());
    }

    /** @return 已认证请求的租户范围 */
    private static TenantScope currentScope() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException(
                "没有租户上下文。配额接口必须在已认证且已选项目的请求中调用"));
    }

    /**
     * 读取权威配额概览；套餐摘要只在控制面读取时附加。
     *
     * <p>授权顺序固定为「项目一致 → 成员关系 → 配额事实」，套餐摘要在成员关系确认之后读取。
     * 摘要端口自身的 SQL 还要求调用账号是项目归属租户的 ACTIVE 成员（架构文档 §6），因此跨租户
     * 协作者即使通过了项目成员校验也拿不到摘要。数据面（{@code includePlanSummary=false}）
     * 不承担这次额外查询，保持设备写入路径的查询开销与 S14-2c 之前一致。
     *
     * @param projectId 路径中的项目 ID
     * @param includePlanSummary 是否读取租户套餐摘要
     * @return 当前 UTC 日配额与用量概览
     */
    private ProjectQuotaOverview load(UUID projectId, boolean includePlanSummary) {
        TenantScope scope = currentScope();
        if (!projectId.equals(scope.projectId())) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }

        // 即使令牌尚未过期，成员关系也可能已被移除；每次读取重新查库，不能把 JWT 当权限事实。
        projectRepository.findRole(projectId, scope.accountId())
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));

        LocalDate usageDate = LocalDate.now(ZoneOffset.UTC);
        List<QuotaOverviewRepository.QuotaMetricUsageRow> rows = quotaOverviewRepository.findDailyUsage(usageDate);
        if (rows.isEmpty()) {
            // 选中的项目在函数里不可见只可能是并发删除或策略事实损坏；仍返回 404，避免暴露内部状态。
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }

        // project 模块的独立测试应用不会装配 device 模块；只允许上下文启动，真实接口调用仍必须有事实提供器。
        ProjectDeviceQuotaContributor deviceContributor = deviceContributorProvider.getIfAvailable(() -> {
            throw new IllegalStateException("设备模块未装配，不能读取项目配额概览");
        });
        ProjectDeviceQuotaContributor.ProjectDeviceQuotaUsage deviceUsage = deviceContributor.countActiveDevices(projectId);
        long memberCount = projectRepository.countActiveMembers(projectId);
        QuotaOverviewRepository.QuotaMetricUsageRow first = rows.getFirst();
        com.things.link.project.domain.QuotaMetricUsage deviceMetricUsage =
                new com.things.link.project.domain.QuotaMetricUsage(
                com.things.link.project.domain.QuotaMetric.DEVICE_COUNT,
                entitlementPolicy.nonCommercial()
                        ? Long.valueOf(entitlementPolicy.capacity(DeploymentEntitlementPolicy.Capacity.DEVICES))
                        : first.deviceLimit(),
                deviceUsage.projectUsed(), deviceUsage.tenantUsed(),
                first.softLimitBasisPoints(), first.degradeBasisPoints());

        List<com.things.link.project.domain.QuotaMetricUsage> dailyMetrics = rows.stream()
                .map(row -> new com.things.link.project.domain.QuotaMetricUsage(
                        row.metric(), entitlementPolicy.nonCommercial() && row.metric().dailyCounter()
                                ? Long.valueOf(entitlementPolicy.dailyLimit(row.metric())) : row.limit(),
                        row.projectUsed(), row.tenantUsed(),
                        row.softLimitBasisPoints(), row.degradeBasisPoints()))
                .sorted(Comparator.comparing(usage -> usage.metric().name()))
                .toList();

        TenantPlanSummary planSummary = includePlanSummary && !entitlementPolicy.nonCommercial()
                ? planSummaryRepository.findForCurrentProject(projectId, scope.accountId()).orElse(null)
                : null;

        return new ProjectQuotaOverview(projectId, usageDate,
                entitlementPolicy.nonCommercial() ? "NONCOMMERCIAL_TECHNICAL" : first.policyCode(),
                entitlementPolicy.nonCommercial() ? 1L : first.policyVersion(),
                deviceMetricUsage, memberCount, dailyMetrics, planSummary);
    }
}
