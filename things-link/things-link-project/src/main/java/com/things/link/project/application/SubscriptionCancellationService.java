package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.SubscriptionCancellationReceipt;
import com.things.link.project.domain.SubscriptionCancellationRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.project.domain.TenantSubscriptionRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** ADR0166 内部模拟执行的状态步骤；调用方先锁来源订单，本组件加入同一事务，不提供HTTP退款。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SubscriptionCancellationService {
    /** 不可变回执与取消写入。 */
    private final SubscriptionCancellationRepository cancellations;
    /** 租户互斥与当前订阅。 */
    private final TenantSubscriptionLifecycleRepository lifecycle;
    /** 真实FREE目录和创建。 */
    private final TenantSubscriptionRepository subscriptions;
    /** 完整来源核验。 */
    private final SubscriptionProvenanceVerificationService provenance;
    /** 绑定CAS及提交后缓存失效。 */
    private final QuotaPolicyAssignmentService assignment;
    /** 预约撤销原子步骤。 */
    private final TenantSubscriptionChangeService changes;
    /** 额度内项目限制与恢复。 */
    private final CommercialProjectAccessService projects;
    /** 不可篡改审计。 */
    private final AuditLogService audit;

    /** @param cancellations 回执 @param lifecycle 租户互斥 @param subscriptions FREE目录 @param provenance 来源
     * @param assignment 绑定CAS @param changes 预约 @param projects 项目强制 @param audit 审计 */
    public SubscriptionCancellationService(SubscriptionCancellationRepository cancellations,
            TenantSubscriptionLifecycleRepository lifecycle,TenantSubscriptionRepository subscriptions,
            SubscriptionProvenanceVerificationService provenance,QuotaPolicyAssignmentService assignment,
            TenantSubscriptionChangeService changes,CommercialProjectAccessService projects,AuditLogService audit) {
        this.cancellations=cancellations; this.lifecycle=lifecycle; this.subscriptions=subscriptions;
        this.provenance=provenance; this.assignment=assignment; this.changes=changes; this.projects=projects; this.audit=audit;
    }

    /**
     * 终止当前来源链的现行订阅并回落FREE，旧SUPERSEDED节点不变。
     * @param tenant 已确权租户 @param subscription 预期当前订阅 @param operation 幂等操作身份 @param reason 终止原因
     * @return 原始取消回执；金额执行由后续执行器负责
     */
    public SubscriptionCancellationReceipt cancel(UUID tenant,UUID subscription,UUID operation,String reason) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(subscription); Objects.requireNonNull(operation);
        String normalized=reason==null?"":reason.strip();
        if (normalized.isEmpty() || normalized.length()>500) throw conflict();
        var prior=cancellations.find(tenant,operation);
        if (prior.isPresent()) return replay(prior.get(),subscription,normalized);
        long version=lifecycle.lockTenantAndReadAssignmentVersion(tenant);
        prior=cancellations.find(tenant,operation);
        if (prior.isPresent()) return replay(prior.get(),subscription,normalized);
        var current=lifecycle.lockActiveSubscription(tenant).orElseThrow(SubscriptionCancellationService::conflict);
        if (!current.id().equals(subscription) || current.endsAt()==null
                || !current.endsAt().isAfter(cancellations.currentTime())) throw conflict();
        var chain=provenance.verifyAndBackfill(tenant,subscription);
        if (chain.stream().anyMatch(node -> lifecycle.findState(node.subscriptionId())
                .map(state -> state.status()!=(node.subscriptionId().equals(subscription)
                        ? SubscriptionStatus.ACTIVE : SubscriptionStatus.SUPERSEDED)).orElse(true))) throw conflict();
        var now=cancellations.currentTime();
        if (chain.isEmpty() || !current.endsAt().isAfter(now) || chain.stream().anyMatch(n -> n.paidAt().isAfter(now))) {
            throw conflict();
        }
        var free=subscriptions.findFreeSubscriptionSnapshot().orElseThrow(SubscriptionCancellationService::conflict);
        if (free.priceCents()!=0 || !"NONE".equals(free.billingPeriod()) || !"CNY".equals(free.currency())) throw conflict();
        if (!cancellations.cancelActive(tenant,subscription,now)) throw conflict();
        changes.cancelPendingForSubscriptionCancellation(tenant,operation);
        if (!subscriptions.createFreeSubscriptionIfAbsent(tenant,free)) throw conflict();
        UUID freeId=lifecycle.lockActiveSubscription(tenant).orElseThrow(SubscriptionCancellationService::conflict).id();
        assignment.assign(tenant,free.quotaPolicyId(),version);
        projects.restrictToCurrentLimit(tenant,subscription,now,"SUBSCRIPTION_CANCELLED");
        projects.restoreEligible(tenant,now);
        var receipt=new SubscriptionCancellationReceipt(operation,tenant,subscription,freeId,now,normalized,version,
                Math.addExact(version,1));
        try { cancellations.record(receipt); }
        catch (DuplicateKeyException duplicate) { throw conflict(); }
        audit.record(new AuditLogEntry(tenant,null,null,"tenant_subscription",subscription,"commercial.subscription.cancelled",
                Map.of("operationId",operation.toString(),"freeSubscriptionId",freeId.toString(),"cancelledAt",now.toString(),
                        "reason",normalized,"assignmentVersionBefore",version,"assignmentVersionAfter",receipt.assignmentVersionAfter(),
                        "settlement","SIMULATED_STATE_STEP")));
        return receipt;
    }

    /** 回执重放只核对原请求，不能重新修改当前权益。 */
    private SubscriptionCancellationReceipt replay(SubscriptionCancellationReceipt receipt,UUID subscription,String reason) {
        if (!receipt.subscriptionId().equals(subscription) || !receipt.reason().equals(reason)) throw conflict();
        return receipt;
    }

    /** 状态或幂等冲突不暴露其他租户细节。 */
    private static BusinessException conflict() { return new BusinessException(ProjectErrorCode.SUBSCRIPTION_CANCELLATION_CONFLICT); }
}
