package com.things.link.rule.application.automation;

import com.things.link.rule.domain.AutomationEventReceipt;
import com.things.link.rule.domain.AutomationEventReceiptRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.support.json.AutomationCanonicalJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** ADR0154原事务首次结果；调用者先确权/取得项目许可并建立RLS，本组件不是未经授权的消费入口。 */
@Service
public class AutomationEventJournal {
    private static final Set<String> REJECTIONS=Set.of("EVENT_EXPIRED","EVENT_TIME_INVALID","INPUT_LIMIT",
            "PROJECT_READ_ONLY","DEVICE_UNAVAILABLE");
    private final AutomationEventReceiptRepository repository;
    private final ObjectMapper json;
    public AutomationEventJournal(AutomationEventReceiptRepository repository,ObjectMapper json) {
        this.repository=repository; this.json=json;
    }
    /** 重投不调用目录/计划回调；回调只冻结目录；调用方在首次收据返回后、同事务内创建全部执行，失败整笔回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Outcome freeze(AutomationPropertyAccepted event, Transport transport, Supplier<Decision> firstPlan) {
        Objects.requireNonNull(event); Objects.requireNonNull(transport); Objects.requireNonNull(firstPlan);
        repository.lockProject(event.projectId());
        String digest=AutomationCanonicalJson.digest(json.valueToTree(event));
        var previous=repository.find(event.projectId(),event.sourceEventId());
        if(previous.isPresent()) {
            if(!previous.get().sourceDigest().equals(digest)) {
                repository.rejectTransport(Uuid7.generate(),transport.partition(),transport.offset(),transport.digest(),"EVENT_CONFLICT");
                return new Outcome(null,true,false);
            }
            return new Outcome(previous.get(),false,false);
        }
        var now=repository.databaseNow();
        Decision decision;
        if(event.acceptedAt().isBefore(now.minus(Duration.ofDays(7)))) decision=Decision.reject("EVENT_EXPIRED");
        else if(event.acceptedAt().isAfter(now.plusSeconds(30))) decision=Decision.reject("EVENT_TIME_INVALID");
        else if(json.writeValueAsString(event.payload()).getBytes(StandardCharsets.UTF_8).length>16384)
            decision=Decision.reject("INPUT_LIMIT");
        else decision=Objects.requireNonNull(firstPlan.get());
        var receipt=new AutomationEventReceipt(Uuid7.generate(),event.tenantId(),event.projectId(),event.sourceEventId(),
                event.deviceId(),event.acceptedAt(),now,digest,decision.plan(),
                decision.reason()==null?AutomationEventReceipt.Result.ACCEPTED:AutomationEventReceipt.Result.REJECTED,
                decision.reason(),now.plus(Duration.ofDays(30)));
        repository.insert(receipt);
        return new Outcome(receipt,false,true);
    }
    /** 来自broker元数据及实际记录字节；禁止从JSON载荷取partition/offset。 */
    public record Transport(int partition,long offset,String digest) {
        public Transport {
            if(partition<0||offset<0||digest==null||!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("非法传输位置");
        }
    }
    /** 仅首次回调提供计划；每一版本的执行创建仍与收据在同事务。 */
    public record Decision(List<UUID> plan,String reason) {
        public Decision {
            plan=List.copyOf(plan);
            if(plan.size()>10||plan.stream().distinct().count()!=plan.size()
                    ||(reason!=null&&(!REJECTIONS.contains(reason)||!plan.isEmpty()))) throw new IllegalArgumentException("非法首次结果");
        }
        public static Decision accept(List<UUID> plan) { return new Decision(plan,null); }
        public static Decision reject(String reason) { return new Decision(List.of(),Objects.requireNonNull(reason)); }
    }
    /** 冲突时不返回或改写原计划，消费者只在本事务提交后正常返回。 */
    public record Outcome(AutomationEventReceipt receipt,boolean conflict,boolean first) { }
}
