package com.things.link.project.application;

import com.things.link.project.domain.FreeSubscriptionSnapshot;
import com.things.link.project.domain.TenantSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 新租户的 FREE 订阅与有效策略绑定（S14-2a，架构文档 §3.2）。
 *
 * <p>架构文档 §3.2 要求新租户在<b>同一个业务事务</b>里拿到四样东西：租户事实、当前 FREE 产品修订版的
 * 免费订阅、FREE 有效配额策略绑定、以及绑定版本初值。本服务负责后三样，由
 * {@link TenantProvisioning#createTenant(String)} 在租户行落库后调用，因此注册事务的任何一步失败
 * 都会把订阅与指针一起回滚，不会留下「有租户没有订阅」或「订阅说 FREE 而指针指向旧模板」的半成品。
 *
 * <h2>为什么复用 QuotaPolicyAssignmentService 而不是 UPDATE sys_tenant</h2>
 * 运行时指针的写入协议是 S7 冻结的：CAS 绑定版本 + 提交后本机标 stale + Redis 广播。
 * 注册路径与将来的运营切换必须走同一条路径，否则会多出第二套失效协议，而漏掉的一半
 * （跨实例广播）不会有任何本地症状，只会在多实例部署时表现为部分节点继续用旧策略。
 *
 * <h2>为什么幂等键是订阅行本身</h2>
 * 重放（注册重试、补建步骤重跑）必须先看订阅是否已存在：只有<b>本次真正插入</b>了 ACTIVE 订阅才推进
 * 绑定版本，因此重放既不会建出第二行，也不会把版本从 2 抬到 3。这一条比「先查租户再决定」可靠，
 * 因为它与 {@code sys_tenant_subscription_active_tenant_uk} 表达的是同一条不变式。
 */
@Service
public class TenantSubscriptionProvisioning {

    /**
     * 新租户的配额策略绑定版本初值。
     *
     * <p>它与 {@code V20260811_0120} 给 {@code sys_tenant.quota_policy_assignment_version} 的
     * {@code DEFAULT 1} 一致：租户行先按默认值落库，随后 FREE 绑定把它从 1 递增到 2，
     * 因此「新租户恰好发生一次绑定递增」。注册路径之外没有任何地方能新建租户，
     * 所以这里不需要读回当前版本。
     */
    private static final long INITIAL_QUOTA_POLICY_ASSIGNMENT_VERSION = 1L;

    /** FREE 订阅事实的读写端口。 */
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    /** S7 冻结的运行时策略 CAS 绑定用例；注册路径必须复用它。 */
    private final QuotaPolicyAssignmentService quotaPolicyAssignmentService;

    /**
     * @param tenantSubscriptionRepository FREE 订阅事实的读写端口
     * @param quotaPolicyAssignmentService S7 运行时策略 CAS 绑定用例
     */
    public TenantSubscriptionProvisioning(TenantSubscriptionRepository tenantSubscriptionRepository,
                                          QuotaPolicyAssignmentService quotaPolicyAssignmentService) {
        this.tenantSubscriptionRepository = tenantSubscriptionRepository;
        this.quotaPolicyAssignmentService = quotaPolicyAssignmentService;
    }

    /**
     * 幂等补齐一名租户的 FREE 订阅与 {@code PLAN_R1_FREE} 有效策略绑定。
     *
     * <p>{@code MANDATORY} 与 {@link TenantProvisioning} 同一理由：补建步骤必须并入调用方事务，
     * 忘了开事务时直接抛异常，而不是静默地各自提交。
     *
     * @param tenantId 刚创建的租户 ID
     * @throws IllegalStateException FREE 产品修订版或 {@code PLAN_R1_FREE} 模板缺失（目录半迁移状态）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void provisionFreeSubscription(UUID tenantId) {
        FreeSubscriptionSnapshot snapshot = tenantSubscriptionRepository.findFreeSubscriptionSnapshot()
                .orElseThrow(() -> new IllegalStateException(
                        "FREE 产品修订版或 PLAN_R1_FREE 配额模板缺失，无法为新租户创建免费订阅"));
        if (!tenantSubscriptionRepository.createFreeSubscriptionIfAbsent(tenantId, snapshot)) {
            // 已有 ACTIVE 订阅：这是重放，不是新建。既不重复插入，也不再推进绑定版本。
            return;
        }
        quotaPolicyAssignmentService.assign(
                tenantId, snapshot.quotaPolicyId(), INITIAL_QUOTA_POLICY_ASSIGNMENT_VERSION);
    }
}
