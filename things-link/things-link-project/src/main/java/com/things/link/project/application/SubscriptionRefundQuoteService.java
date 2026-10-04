package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionCancellationRepository;
import com.things.link.project.domain.SubscriptionProvenanceChain;
import com.things.link.project.domain.SubscriptionProvenanceRepository;
import com.things.link.project.domain.SubscriptionRefundProration;
import com.things.link.project.domain.SubscriptionRefundQuote;
import com.things.link.project.domain.SubscriptionRefundQuoteRepository;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantSubscriptionChangeRepository;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** ADR0166 内部持久报价；只冻结已核验事实，不接受客户端价格，不改变当前权益。 */
@Service
public class SubscriptionRefundQuoteService {
    /** 不可变报价及租户事实。 */
    private final SubscriptionRefundQuoteRepository quotes;
    /** 订单与订阅来源核验。 */
    private final SubscriptionProvenanceVerificationService provenance;
    /** 原始事实快照。 */
    private final SubscriptionProvenanceRepository sources;
    /** 原锁序及当前身份。 */
    private final TenantSubscriptionLifecycleRepository lifecycle;
    /** 现行预约，变化会使报价陈旧。 */
    private final TenantSubscriptionChangeRepository changes;
    /** 数据库实际时间及已占用取消操作。 */
    private final SubscriptionCancellationRepository cancellations;
    /** 精确JSON结构比较。 */
    private final ObjectMapper json;
    /** 同事务报价审计。 */
    private final AuditLogService audit;

    /** @param quotes 报价 @param provenance 核验 @param sources 原始事实 @param lifecycle 互斥
     * @param changes 预约 @param cancellations 时间及取消 @param json JSON @param audit 审计 */
    public SubscriptionRefundQuoteService(SubscriptionRefundQuoteRepository quotes,SubscriptionProvenanceVerificationService provenance,
            SubscriptionProvenanceRepository sources,TenantSubscriptionLifecycleRepository lifecycle,
            TenantSubscriptionChangeRepository changes,SubscriptionCancellationRepository cancellations,ObjectMapper json,AuditLogService audit) {
        this.quotes=quotes; this.provenance=provenance; this.sources=sources; this.lifecycle=lifecycle;
        this.changes=changes; this.cancellations=cancellations; this.json=json; this.audit=audit;
    }

    /**
     * 同身份返回原报价，过期后也不偷偷重新定价；新报价必须新操作ID。
     * @param tenant 已确权租户 @param subscription 当前订阅 @param operation 原操作身份 @param reason 原因
     * @return 已保存的不可变报价
     */
    @Transactional
    public SubscriptionRefundQuote quote(UUID tenant,UUID subscription,UUID operation,String reason) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(subscription); Objects.requireNonNull(operation);
        String normalized=reason==null?"":reason.strip();
        if (normalized.isEmpty() || normalized.length()>500) throw conflict();
        var prior=quotes.find(tenant,operation);
        if (prior.isPresent()) return replay(prior.get(),subscription,normalized);
        long version=lifecycle.lockTenantAndReadAssignmentVersion(tenant);
        prior=quotes.find(tenant,operation);
        if (prior.isPresent()) return replay(prior.get(),subscription,normalized);
        if (cancellations.find(tenant,operation).isPresent()) throw conflict();
        var current=lifecycle.lockActiveSubscription(tenant).orElseThrow(SubscriptionRefundQuoteService::conflict);
        if (!current.id().equals(subscription) || current.endsAt()==null
                || !current.endsAt().isAfter(cancellations.currentTime())) throw conflict();
        var chain=provenance.verifyAndBackfill(tenant,subscription);
        if (chain.isEmpty() || chain.stream().anyMatch(node -> lifecycle.findState(node.subscriptionId())
                .map(state -> state.status()!=(node.subscriptionId().equals(subscription)
                        ? SubscriptionStatus.ACTIVE : SubscriptionStatus.SUPERSEDED)).orElse(true))) throw conflict();
        String snapshot=captureSnapshot(tenant,chain);
        var now=cancellations.currentTime();
        if (!current.endsAt().isAfter(now) || chain.stream().anyMatch(node -> node.paidAt().isAfter(now))) throw conflict();
        var expiry=now.plusSeconds(300).isBefore(current.endsAt())?now.plusSeconds(300):current.endsAt();
        var frozen=json.readTree(snapshot).path("nodes");
        List<SubscriptionRefundQuote.Line> lines=new ArrayList<>();
        long total=0;
        for (int i=0;i<chain.size();i++) {
            var node=chain.get(i);
            var fact=frozen.get(i);
            long refunded=fact.path("order").path("refunded_cents").longValue();
            var amount=SubscriptionRefundProration.between(node.startsAt(),node.endsAt(),now,node.amountCents(),refunded);
            var line=new SubscriptionRefundQuote.Line(node.subscriptionId(),node.orderId(),node.kind(),node.startsAt(),node.endsAt(),
                    fact.path("subscription").path("revision").longValue(),fact.path("order").path("revision").longValue(),
                    node.amountCents(),refunded,amount.totalDays(),amount.unusedDays(),amount.refundableCents());
            lines.add(line);
            try { total=Math.addExact(total,line.refundableCents()); }
            catch (ArithmeticException overflow) { throw conflict(); }
        }
        var quote=new SubscriptionRefundQuote(operation,tenant,subscription,version,now,expiry,normalized,
                SubscriptionRefundQuote.ALGORITHM,total,snapshot,lines);
        try { quotes.insert(quote); }
        catch (DuplicateKeyException duplicate) { throw conflict(); }
        audit.record(new AuditLogEntry(tenant,null,null,"subscription_refund_quote",operation,"commercial.subscription.refund.quoted",
                Map.of("subscriptionId",subscription.toString(),"algorithm",quote.algorithm(),"totalCents",total,
                        "quotedAt",now.toString(),"expiresAt",expiry.toString(),"settlement","SIMULATED_QUOTE")));
        // PostgreSQL jsonb规范化字段顺序；初次与重放统一返回数据库保存的形式。
        return quotes.find(tenant,operation).orElseThrow(IllegalStateException::new);
    }

    /**
     * 调用方已持租户锁且来源链已核验；执行器在订单锁之后复用完整事实快照比较。
     * @param tenant 已锁租户 @param chain 完整付费链 @return 稳定顺序原始事实JSON
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String captureSnapshot(UUID tenant,List<SubscriptionProvenanceChain.Node> chain) {
        var root=json.createObjectNode();
        var tenantState=json.readTree(quotes.tenantSnapshot(tenant).orElseThrow(SubscriptionRefundQuoteService::conflict));
        if (!"ACTIVE".equals(tenantState.path("status").asText())) throw conflict();
        root.set("tenant",tenantState);
        var nodes=root.putArray("nodes");
        for (var node:chain) {
            var facts=sources.current(tenant,node.subscriptionId()).orElseThrow(SubscriptionRefundQuoteService::conflict);
            var entry=nodes.addObject();
            entry.set("subscription",normalizeDatabaseTimes(facts.subscription()));
            entry.set("order",normalizeDatabaseTimes(facts.order()));
        }
        root.set("pending",json.valueToTree(changes.findPending(tenant).orElse(null)));
        return root.toString();
    }

    /** 数据库时间文本受连接时区影响，比较前统一为UTC，其他事实原样保留。 */
    private tools.jackson.databind.JsonNode normalizeDatabaseTimes(String raw) {
        var node=(tools.jackson.databind.node.ObjectNode)json.readTree(raw);
        for (String key:List.copyOf(node.propertyNames())) {
            if (key.endsWith("_at") && node.path(key).isString()) {
                node.put(key,java.time.Instant.parse(node.path(key).asText()).toString());
            }
        }
        return node;
    }

    /** 同请求重放只返回历史报价，不触发任何新定价或权益变化。 */
    private SubscriptionRefundQuote replay(SubscriptionRefundQuote quote,UUID subscription,String reason) {
        if (!quote.currentSubscriptionId().equals(subscription) || !quote.reason().equals(reason)) throw conflict();
        return quote;
    }
    /** 不暴露其他租户身份。 */
    private static BusinessException conflict() { return new BusinessException(ProjectErrorCode.SUBSCRIPTION_REFUND_QUOTE_CONFLICT); }
}
