package com.things.link.project.application;

import com.things.link.project.domain.PaymentProvider;
import com.things.link.project.domain.SubscriptionRefundProration;
import com.things.link.project.domain.SubscriptionProvenanceChain;
import com.things.link.project.domain.TenantOrder;
import com.things.link.project.domain.TenantOrderStatus;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.RefundStatus;
import com.things.link.project.domain.SubscriptionCancellationRepository;
import com.things.link.project.domain.SubscriptionRefundExecution;
import com.things.link.project.domain.SubscriptionRefundExecutionRepository;
import com.things.link.project.domain.SubscriptionRefundQuote;
import com.things.link.project.domain.SubscriptionRefundQuoteRepository;
import com.things.link.project.domain.TenantOrderRepository;
import com.things.link.project.domain.TenantRefundRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** ADR0166 内部模拟执行器；只按保存报价结算，没有HTTP资金入口。 */
@Service
public class SubscriptionRefundExecutionService {
    /** 已保存报价，不接受调用方金额。 */
    private final SubscriptionRefundQuoteRepository quotes;
    /** 原成功结果与重放。 */
    private final SubscriptionRefundExecutionRepository executions;
    /** 稳定顺序订单锁和退款累计CAS。 */
    private final TenantOrderRepository orders;
    /** 原模拟资金事实。 */
    private final TenantRefundRepository refunds;
    /** 订单锁之后的租户互斥。 */
    private final TenantSubscriptionLifecycleRepository lifecycle;
    /** 来源复验。 */
    private final SubscriptionProvenanceVerificationService provenance;
    /** 当前完整快照。 */
    private final SubscriptionRefundQuoteService quoteService;
    /** 实际时间和独立取消操作检测。 */
    private final SubscriptionCancellationRepository cancellationFacts;
    /** 同事务取消、FREE和项目状态步骤。 */
    private final SubscriptionCancellationService cancellations;
    /** 同事务资金审计。 */
    private final AuditLogService audit;
    /** 精确JSON比较。 */
    private final ObjectMapper json;

    /** @param quotes 报价 @param executions 成功结果 @param orders 订单 @param refunds 退款 @param lifecycle 租户互斥
     * @param provenance 来源 @param quoteService 快照 @param cancellationFacts 时间 @param cancellations 状态步骤
     * @param audit 审计 @param json JSON */
    public SubscriptionRefundExecutionService(SubscriptionRefundQuoteRepository quotes,SubscriptionRefundExecutionRepository executions,
            TenantOrderRepository orders,TenantRefundRepository refunds,TenantSubscriptionLifecycleRepository lifecycle,
            SubscriptionProvenanceVerificationService provenance,SubscriptionRefundQuoteService quoteService,
            SubscriptionCancellationRepository cancellationFacts,SubscriptionCancellationService cancellations,AuditLogService audit,ObjectMapper json) {
        this.quotes=quotes; this.executions=executions; this.orders=orders; this.refunds=refunds; this.lifecycle=lifecycle;
        this.provenance=provenance; this.quoteService=quoteService; this.cancellationFacts=cancellationFacts;
        this.cancellations=cancellations; this.audit=audit; this.json=json;
    }

    /**
     * 原报价原子结算，失败零部分提交；成功重放优先于当前状态/报价期限检查。
     * @param tenant 已确权租户 @param operation 持久报价操作身份 @return 原始模拟成功结果
     */
    @Transactional
    public SubscriptionRefundExecution execute(UUID tenant,UUID operation) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(operation);
        var prior=executions.find(tenant,operation);
        if (prior.isPresent()) return prior.get();
        var quote=quotes.find(tenant,operation).orElseThrow(SubscriptionRefundExecutionService::conflict);
        if (!SubscriptionRefundQuote.ALGORITHM.equals(quote.algorithm())) throw conflict();
        var locked=new java.util.HashMap<UUID,TenantOrder>();
        for (var line:quote.lines().stream().sorted(Comparator.comparing(SubscriptionRefundQuote.Line::orderId)).toList()) {
            var order=orders.lockOrder(line.orderId()).filter(candidate -> candidate.tenantId().equals(tenant))
                    .orElseThrow(SubscriptionRefundExecutionService::conflict);
            locked.put(order.id(),order);
        }
        long version=lifecycle.lockTenantAndReadAssignmentVersion(tenant);
        prior=executions.find(tenant,operation);
        if (prior.isPresent()) return prior.get();
        if (version!=quote.assignmentVersion() || cancellationFacts.find(tenant,operation).isPresent()
                || !lifecycle.findCurrentState(tenant).map(s -> s.id().equals(quote.currentSubscriptionId())).orElse(false)) throw conflict();
        requireTime(quote,cancellationFacts.currentTime());
        var chain=provenance.verifyAndBackfill(tenant,quote.currentSubscriptionId());
        var actual=json.readTree(quoteService.captureSnapshot(tenant,chain));
        if (!json.readTree(quote.stateSnapshot()).equals(actual)) throw conflict();
        validateLines(quote,chain,locked,actual);
        var settled=new ArrayList<SubscriptionRefundExecution.Settlement>();
        for (var line:quote.lines()) {
            UUID refundId=null;
            if (line.refundableCents()>0) {
                String event="subscription:"+operation+":"+line.orderId();
                if (refunds.findByProviderRefundId(PaymentProvider.SIMULATED,event).isPresent()
                        || !orders.reserveRefund(line.orderId(),line.refundableCents())) throw conflict();
                try {
                    refundId=refunds.insert(tenant,line.orderId(),line.refundableCents(),"CNY",PaymentProvider.SIMULATED,event,
                            RefundStatus.SUCCEEDED,quote.reason());
                } catch (DuplicateKeyException duplicate) { throw conflict(); }
                audit.record(new AuditLogEntry(tenant,null,null,"tenant_refund",refundId,"commercial.refund.succeeded",
                        Map.of("operationId",operation.toString(),"orderId",line.orderId().toString(),
                                "subscriptionId",line.subscriptionId().toString(),"amountCents",line.refundableCents(),
                                "provider","SIMULATED","providerRefundId",event,"algorithm",quote.algorithm())));
            }
            settled.add(new SubscriptionRefundExecution.Settlement(line.orderId(),line.subscriptionId(),line.refundableCents(),refundId));
        }
        var cancelled=cancellations.cancel(tenant,quote.currentSubscriptionId(),operation,quote.reason());
        requireTime(quote,cancelled.cancelledAt());
        var result=new SubscriptionRefundExecution(operation,tenant,quote.currentSubscriptionId(),cancelled.freeSubscriptionId(),
                cancelled.cancelledAt(),quote.totalCents(),settled);
        try { executions.insert(result); }
        catch (DuplicateKeyException duplicate) { throw conflict(); }
        audit.record(new AuditLogEntry(tenant,null,null,"subscription_refund_execution",operation,"commercial.subscription.refund.executed",
                Map.of("subscriptionId",quote.currentSubscriptionId().toString(),"freeSubscriptionId",result.freeSubscriptionId().toString(),
                        "totalCents",result.totalCents(),"executedAt",result.executedAt().toString(),"provider","SIMULATED")));
        return result;
    }

    /** 逐单结果也必须能由已锁来源按冻结算法重算，不能只信保存的总额。 */
    private static void validateLines(SubscriptionRefundQuote quote,java.util.List<SubscriptionProvenanceChain.Node> chain,
            Map<UUID,TenantOrder> locked,tools.jackson.databind.JsonNode snapshot) {
        if (chain.size()!=quote.lines().size()) throw conflict();
        for (int i=0;i<chain.size();i++) {
            var node=chain.get(i);
            var line=quote.lines().get(i);
            var order=locked.get(node.orderId());
            if (order==null || order.status()!=TenantOrderStatus.PAID || order.provider()!=PaymentProvider.SIMULATED
                    || !line.orderId().equals(node.orderId()) || !line.subscriptionId().equals(node.subscriptionId())
                    || line.kind()!=node.kind() || !line.startsAt().equals(node.startsAt()) || !line.endsAt().equals(node.endsAt())
                    || line.amountCents()!=node.amountCents() || line.refundedCents()!=order.refundedCents()
                    || line.orderRevision()!=order.revision()
                    || line.subscriptionRevision()!=snapshot.path("nodes").get(i).path("subscription").path("revision").longValue()) throw conflict();
            var expected=SubscriptionRefundProration.between(node.startsAt(),node.endsAt(),quote.quotedAt(),node.amountCents(),order.refundedCents());
            if (expected.totalDays()!=line.totalDays() || expected.unusedDays()!=line.unusedDays()
                    || expected.refundableCents()!=line.refundableCents()) throw conflict();
        }
    }

    /** 截止点不包含，时钟回退不能按未来报价退款。 */
    private static void requireTime(SubscriptionRefundQuote quote,Instant now) {
        if (now.isBefore(quote.quotedAt()) || !now.isBefore(quote.expiresAt())) throw conflict();
    }
    /** 原报价不足以执行时必须重新核对，不自动换报价或另退一笔。 */
    private static BusinessException conflict() { return new BusinessException(ProjectErrorCode.SUBSCRIPTION_REFUND_QUOTE_CONFLICT); }
}
