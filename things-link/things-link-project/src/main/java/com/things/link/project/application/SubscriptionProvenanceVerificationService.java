package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.SubscriptionProvenanceChain;
import com.things.link.project.domain.SubscriptionProvenanceRepository;
import com.things.link.project.domain.SubscriptionProvenanceRepository.Stored;
import com.things.link.project.domain.TenantOrderKind;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.TenantAuditEvidenceQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** ADR0166 内部来源核验；整链通过后才原子补录，未开放退款或跨租户HTTP授权。 */
@Service
public class SubscriptionProvenanceVerificationService {

    /** 来源和当前商业事实。 */
    private final SubscriptionProvenanceRepository sources;
    /** 原事务租户串行化锚点。 */
    private final TenantSubscriptionLifecycleRepository lifecycle;
    /** 精确且有界的不可篡改审计读取。 */
    private final TenantAuditEvidenceQuery evidence;
    /** 保持整数精度的JSON读取器。 */
    private final ObjectMapper json;

    /** @param sources 来源仓储 @param lifecycle 租户锁 @param evidence 审计端口 @param json JSON读取器 */
    public SubscriptionProvenanceVerificationService(SubscriptionProvenanceRepository sources,
            TenantSubscriptionLifecycleRepository lifecycle, TenantAuditEvidenceQuery evidence, ObjectMapper json) {
        this.sources=sources; this.lifecycle=lifecycle; this.evidence=evidence; this.json=json;
    }

    /**
     * 在租户锁下核验当前完整来源，缺失记录仅在全部节点通过后补录。
     * @param tenantId 已由调用方确权的租户
     * @param currentSubscriptionId 当前订阅身份，陈旧身份拒绝
     * @return 当前到最早的付费节点；真实FREE为空
     */
    @Transactional
    public List<SubscriptionProvenanceChain.Node> verifyAndBackfill(UUID tenantId, UUID currentSubscriptionId) {
        lifecycle.lockTenantAndReadAssignmentVersion(tenantId);
        require(lifecycle.findCurrentState(tenantId)
                .map(s -> s.id().equals(currentSubscriptionId)).orElse(false));
        Map<UUID,Stored> missing=new LinkedHashMap<>();
        try {
            List<SubscriptionProvenanceChain.Node> chain=SubscriptionProvenanceChain.verify(tenantId,
                    currentSubscriptionId,id -> load(tenantId,id,missing));
            missing.forEach((id,source) -> sources.recordVerified(tenantId,id,source));
            return chain;
        } catch (IllegalArgumentException | DateTimeException | JacksonException | ArithmeticException invalid) {
            throw invalid();
        }
    }

    /** 当前业务事实与来源快照交叉验证后，才交给结构内核使用。 */
    private SubscriptionProvenanceChain.Node load(UUID tenant, UUID id, Map<UUID,Stored> missing) {
        var facts=sources.current(tenant,id).orElseThrow(SubscriptionProvenanceVerificationService::invalid);
        JsonNode current=parse(facts.subscription());
        require(id.equals(uuid(current,"id")) && tenant.equals(uuid(current,"tenant_id")));
        if (nullableUuid(current,"source_order_id")==null) {
            require("FREE".equals(facts.planCode()) && amount(current,"price_cents")==0
                    && current.path("ends_at").isNull() && "NONE".equals(text(current,"billing_period")));
            return new SubscriptionProvenanceChain.Node(id,tenant,null,null,null,time(current,"starts_at"),null,null,0,true);
        }
        JsonNode order=parse(facts.order());
        require(tenant.equals(uuid(order,"tenant_id")) && uuid(current,"source_order_id").equals(uuid(order,"id"))
                && "PAID".equals(text(order,"status")) && "CNY".equals(text(order,"currency"))
                && amount(order,"amount_cents")==amount(current,"price_cents")
                && text(order,"currency").equals(text(current,"currency")));
        Stored source=sources.find(tenant,id).orElse(null);
        if (source==null) {
            source=legacy(tenant,id,current,order);
            missing.put(id,source);
        }
        JsonNode original=parse(source.subscription()), originalOrder=parse(source.order());
        require(source.orderId().equals(uuid(order,"id")));
        for (String key:List.of("id","tenant_id","source_order_id","price_cents","currency","billing_period")) {
            require(Objects.equals(current.get(key),original.get(key)));
        }
        sameTime(current,"starts_at",original,"starts_at");
        sameTime(current,"ends_at",original,"ends_at");
        for (String key:List.of("id","tenant_id","status","order_kind","plan_revision_id","source_plan_revision_id",
                "amount_cents","currency","provider","provider_event_id","proration_remaining_days",
                "proration_old_daily_price_cents","proration_new_daily_price_cents","proration_credit_cents","proration_charge_cents")) {
            require(Objects.equals(order.get(key),originalOrder.get(key)));
        }
        sameTime(order,"paid_at",originalOrder,"paid_at");
        verifyRevision(tenant,id,current,uuid(original,"plan_revision_id"));
        TenantOrderKind kind=TenantOrderKind.valueOf(text(order,"order_kind"));
        require(kind==TenantOrderKind.PURCHASE || kind==TenantOrderKind.UPGRADE);
        require(uuid(original,"plan_revision_id").equals(uuid(order,"plan_revision_id")));
        if (source.predecessorId()!=null) {
            JsonNode parent=parse(sources.current(tenant,source.predecessorId()).orElseThrow(
                    SubscriptionProvenanceVerificationService::invalid).subscription());
            JsonNode captured=parse(source.predecessor());
            for (String key:List.of("id","tenant_id","plan_revision_id","source_order_id","price_cents","currency")) {
                require(Objects.equals(parent.get(key),captured.get(key)));
            }
            sameTime(parent,"starts_at",captured,"starts_at");
            sameTime(parent,"ends_at",captured,"ends_at");
            if (kind==TenantOrderKind.UPGRADE) {
                require(uuid(parent,"plan_revision_id").equals(uuid(order,"source_plan_revision_id")));
            }
        }
        if (kind==TenantOrderKind.UPGRADE) {
            sameTime(order,"proration_effective_at",original,"starts_at");
            sameTime(order,"proration_period_ends_at",original,"ends_at");
            sameTime(order,"proration_effective_at",originalOrder,"proration_effective_at");
            sameTime(order,"proration_period_ends_at",originalOrder,"proration_period_ends_at");
        }
        return new SubscriptionProvenanceChain.Node(id,tenant,source.orderId(),source.predecessorId(),kind,
                time(original,"starts_at"),time(original,"ends_at"),time(order,"paid_at"),amount(order,"amount_cents"),false);
    }

    /** 旧行只接受唯一激活事件；paidAt取真实订单，兼容旧事件的空paidAt。 */
    private Stored legacy(UUID tenant,UUID id,JsonNode current,JsonNode order) {
        var events=evidence.activation(tenant,id);
        require(events.size()==1);
        var event=events.getFirst();
        JsonNode detail=parse(event.details());
        String kind=text(order,"order_kind");
        require(event.action().equals("UPGRADE".equals(kind)
                ? "commercial.subscription.upgraded" : "commercial.subscription.activated"));
        require(id.equals(uuid(detail,"subscriptionId")) && uuid(order,"id").equals(uuid(detail,"orderId"))
                && kind.equals(text(detail,"orderKind"))
                && uuid(order,"plan_revision_id").equals(uuid(detail,"planRevisionId")));
        sameTime(current,"starts_at",detail,"startsAt");
        sameTime(current,"ends_at",detail,"endsAt");
        UUID predecessor=nullableUuid(detail,"supersededSubscriptionId");
        String parentJson=null;
        if (predecessor!=null) {
            parentJson=sources.current(tenant,predecessor).orElseThrow(
                    SubscriptionProvenanceVerificationService::invalid).subscription();
        }
        if ("UPGRADE".equals(kind)) {
            require(predecessor!=null && uuid(order,"source_plan_revision_id").equals(uuid(detail,"fromPlanRevisionId"))
                    && amount(order,"amount_cents")==amount(detail,"differenceCents")
                    && detail.path("periodEndUnchanged").isBoolean() && detail.path("periodEndUnchanged").asBoolean(false));
            Map<String,String> amounts=Map.of("remainingDays","proration_remaining_days",
                    "oldDailyPriceCents","proration_old_daily_price_cents","newDailyPriceCents","proration_new_daily_price_cents",
                    "creditCents","proration_credit_cents","chargeCents","proration_charge_cents");
            amounts.forEach((auditKey,orderKey) -> require(amount(detail,auditKey)==amount(order,orderKey)));
        } else {
            require(detail.path("renewalFromPreviousEndsAt").isBoolean() && detail.path("renewalFromPreviousEndsAt").asBoolean(false)
                    == (parentJson!=null && !parse(parentJson).path("ends_at").isNull()));
        }
        ObjectNode original=(ObjectNode)current.deepCopy();
        original.put("plan_revision_id",uuid(order,"plan_revision_id").toString());
        return new Stored(uuid(order,"id"),predecessor,order.toString(),original.toString(),parentJson,
                "VERIFIED_AUDIT",event.id());
    }

    /** 合法原地降级必须逐条有APPLIED事实和唯一审计，不按创建时间猜修订顺序。 */
    private void verifyRevision(UUID tenant,UUID subscription,JsonNode current,UUID originalRevision) {
        List<JsonNode> changes=new ArrayList<>();
        sources.appliedChanges(tenant,subscription).forEach(raw -> changes.add(parse(raw)));
        UUID target=uuid(current,"plan_revision_id"), cursor=originalRevision;
        var visited=new HashSet<UUID>();
        while (!cursor.equals(target)) {
            require(visited.add(cursor));
            UUID from=cursor;
            List<JsonNode> matches=changes.stream().filter(c -> from.equals(uuid(c,"from_plan_revision_id"))).toList();
            require(matches.size()==1);
            JsonNode change=matches.getFirst();
            var events=evidence.appliedDowngrade(tenant,uuid(change,"id"));
            require(events.size()==1);
            JsonNode detail=parse(events.getFirst().details());
            require(subscription.equals(uuid(detail,"subscriptionId"))
                    && uuid(change,"id").equals(uuid(detail,"pendingChangeId"))
                    && from.equals(uuid(detail,"fromPlanRevisionId"))
                    && uuid(change,"target_plan_revision_id").equals(uuid(detail,"targetPlanRevisionId")));
            sameTime(change,"effective_at",detail,"effectiveAt");
            sameTime(current,"ends_at",detail,"servicePeriodEndsAt");
            sameTime(current,"starts_at",detail,"servicePeriodStartsAt");
            cursor=uuid(change,"target_plan_revision_id");
            changes.remove(change);
        }
        require(changes.isEmpty());
    }

    /** JSON证据必须是对象。 */
    private JsonNode parse(String raw) {
        require(raw!=null);
        JsonNode node=json.readTree(raw);
        require(node!=null && node.isObject());
        return node;
    }
    /** 必填文本不接受缺字段或JSON null。 */
    private static String text(JsonNode node,String key) { require(node.hasNonNull(key)); return node.path(key).asText(); }
    /** 必填身份严格解析。 */
    private static UUID uuid(JsonNode node,String key) { return UUID.fromString(text(node,key)); }
    /** 可空身份也必须显式登记字段，不把缺字段解释成根节点。 */
    private static UUID nullableUuid(JsonNode node,String key) { require(node.has(key)); return node.path(key).isNull()?null:uuid(node,key); }
    /** UTC时点支持数据库输出的等价偏移格式。 */
    private static Instant time(JsonNode node,String key) { return Instant.parse(text(node,key)); }
    /** 非负long整数分，不接受浮点或字符串。 */
    private static long amount(JsonNode node,String key) {
        require(node.path(key).isIntegralNumber());
        long value=node.path(key).bigIntegerValue().longValueExact(); require(value>=0); return value;
    }
    /** 可空时点相等；字段缺失仍拒绝。 */
    private static void sameTime(JsonNode left,String a,JsonNode right,String b) {
        require(left.has(a) && right.has(b));
        if (left.path(a).isNull() || right.path(b).isNull()) { require(left.path(a).isNull() && right.path(b).isNull()); }
        else { require(time(left,a).equals(time(right,b))); }
    }
    /** 统一拒绝，不泄露其他租户账务内容。 */
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    /** 证据不足属于确定性拒绝，数据库故障仍向上传播。 */
    private static BusinessException invalid() { return new BusinessException(ProjectErrorCode.SUBSCRIPTION_PROVENANCE_INVALID); }
}
