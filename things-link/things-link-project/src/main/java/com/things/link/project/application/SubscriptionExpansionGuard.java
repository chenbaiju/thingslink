package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionLifecycleState;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 宽限期的「禁止扩大」门禁（S14-3c，S14-0 P4）。
 *
 * <p>P4 冻结：宽限期内写（创建/修改）允许，但**禁止新增扩大类动作** —— 新建项目、添加设备、
 * 添加协作席位、购买扩容包以外的资源变更。判定只有一条：租户当前订阅状态是否为 {@code GRACE}。
 * 续费/升级回到 {@code ACTIVE} 后立即放行；切换到 {@code RESTRICTED_FREE} 后不再走本门禁，
 * 由 FREE 档额度（自有项目 50020、设备 30035）按真实额度接管。
 *
 * <p>为什么独立成服务而不是散落在各入口：扩大类入口分属 project/device 等模块，但它们都落在
 * 项目域的少数几个写入口上；把判据收敛成一个用例，可以让「宽限禁扩大」只有一处实现、
 * 一处错误码（已登记的 50033），避免每个调用方各自解释订阅状态。
 */
@Service
public class SubscriptionExpansionGuard {

    /** 当前订阅事实的读取端口。 */
    private final TenantSubscriptionLifecycleRepository lifecycleRepository;
    private final DeploymentEntitlementPolicy entitlementPolicy;

    /**
     * @param lifecycleRepository 当前订阅事实读取端口
     */
    @Autowired
    public SubscriptionExpansionGuard(TenantSubscriptionLifecycleRepository lifecycleRepository,
            DeploymentEntitlementPolicy entitlementPolicy) {
        this.lifecycleRepository = lifecycleRepository;
        this.entitlementPolicy = entitlementPolicy;
    }

    /** 保留非 Spring 单测原构造入口。 */
    public SubscriptionExpansionGuard(TenantSubscriptionLifecycleRepository lifecycleRepository) {
        this(lifecycleRepository, DeploymentEntitlementPolicy.commercial());
    }

    /**
     * 要求租户当前允许「扩大类动作」。
     *
     * <p>只有明确处于 {@code GRACE} 时才拒绝：没有订阅事实（存量直插租户）沿用既有放行语义，
     * 避免把一个缺失的订阅误判成宽限；{@code ACTIVE}/{@code RESTRICTED_FREE} 分别由本门禁与
     * 额度守卫处理。
     *
     * @param tenantId 已确权的租户 ID
     * @throws BusinessException 订阅处于宽限期
     */
    @Transactional(readOnly = true)
    public void requireExpansionAllowed(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("扩大类动作门禁必须提供租户 ID");
        }
        if (entitlementPolicy.nonCommercial()) return;
        SubscriptionLifecycleState current = lifecycleRepository.findCurrentState(tenantId).orElse(null);
        if (current != null && current.status() == SubscriptionStatus.GRACE) {
            throw new BusinessException(ProjectErrorCode.SUBSCRIPTION_GRACE_NO_EXPANSION);
        }
    }
}
