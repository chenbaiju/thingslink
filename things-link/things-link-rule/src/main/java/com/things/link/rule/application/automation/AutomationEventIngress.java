package com.things.link.rule.application.automation;

import com.things.link.rule.domain.*;
import com.things.link.project.application.AutomationQuotaService;
import com.things.link.project.application.BackgroundProjectActorAccess;
import com.things.link.device.application.DeviceAutomationAccess;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.support.json.AutomationCanonicalJson;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** ADR0154专用原始记录入口：解析/可信范围/首次计划/额度/执行同事务，只有提交后消费者才返回成功。 */
@Service
public class AutomationEventIngress {
    private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AutomationEventJournal journal;
    private final AutomationEventReceiptRepository receipts;
    private final AutomationRepository store;
    private final BackgroundProjectActorAccess actors;
    private final DeviceAutomationAccess devices;
    private final AutomationQuotaService quota;
    private final TransactionLocalRlsScope rls;
    public AutomationEventIngress(AutomationEventJournal journal,AutomationEventReceiptRepository receipts,AutomationRepository store,
            BackgroundProjectActorAccess actors,DeviceAutomationAccess devices,AutomationQuotaService quota,TransactionLocalRlsScope rls){
        this.journal=journal;this.receipts=receipts;this.store=store;this.actors=actors;this.devices=devices;this.quota=quota;this.rls=rls;
    }
    @Transactional public void accept(byte[] key,byte[] bytes,int partition,long offset){
        byte[] rawKey=key==null?new byte[0]:key,raw=bytes==null?new byte[0]:bytes;
        String rawDigest=AutomationCanonicalJson.digestBytes(ByteBuffer.allocate(4+rawKey.length+raw.length).putInt(rawKey.length).put(rawKey).put(raw).array());
        var transport=new AutomationEventJournal.Transport(partition,offset,rawDigest);
        AutomationPropertyAccepted event;
        try{event=json.readValue(raw,AutomationPropertyAccepted.class);if(event==null)throw new IllegalArgumentException();}
        catch(tools.jackson.core.JacksonException|IllegalArgumentException failure){reject(transport,"ENVELOPE_INVALID");return;}
        if(!Arrays.equals(key,event.deviceId().toString().getBytes(StandardCharsets.UTF_8))){reject(transport,"KEY_MISMATCH");return;}
        var projectState=actors.lockProject(event.tenantId(),event.projectId());
        if(projectState==BackgroundProjectActorAccess.State.MISSING){reject(transport,"SCOPE_MISMATCH");return;}
        rls.establish(event.tenantId(),event.projectId());receipts.lockProject(event.projectId());
        var deviceState=devices.lock(event.tenantId(),event.projectId(),event.deviceId());
        if(deviceState==DeviceAutomationAccess.State.MISSING){reject(transport,"SCOPE_MISMATCH");return;}
        var selected=new AtomicReference<List<AutomationVersion>>(List.of());
        var outcome=journal.freeze(event,transport,()->{
            if(projectState!=BackgroundProjectActorAccess.State.ACTIVE)return AutomationEventJournal.Decision.reject("PROJECT_READ_ONLY");
            if(deviceState!=DeviceAutomationAccess.State.AVAILABLE)return AutomationEventJournal.Decision.reject("DEVICE_UNAVAILABLE");
            var versions=store.matching(event.projectId(),event.deviceId());selected.set(versions);
            return AutomationEventJournal.Decision.accept(versions.stream().map(AutomationVersion::id).toList());
        });
        if(!outcome.first()||outcome.receipt().result()!=AutomationEventReceipt.Result.ACCEPTED)return;
        var validActors=new HashSet<UUID>();
        selected.get().stream().map(AutomationVersion::createdBy).distinct().sorted().forEach(id->{
            if(actors.lockManager(event.projectId(),id))validActors.add(id);
        });
        int pending=store.countPending(event.projectId());
        var input=json.valueToTree(event.payload());String inputDigest=AutomationCanonicalJson.digest(input);
        for(var version:selected.get()){
            UUID execution=Uuid7.generate();String reason=null;
            if(!validActors.contains(version.createdBy()))reason="AUTH_REVOKED";
            else if(pending>=1000)reason="CAPACITY";
            else{
                var reserved=quota.reserve(event.tenantId(),event.projectId(),execution);
                if(reserved==AutomationQuotaService.Reservation.RESERVED)pending++;
                else if(reserved==AutomationQuotaService.Reservation.NOT_ENABLED||reserved==AutomationQuotaService.Reservation.QUOTA_EXCEEDED)reason="QUOTA";
                else throw new IllegalStateException("首次执行预留与可信范围不一致");
            }
            store.admit(execution,version,event.sourceEventId(),event.deviceId(),input,inputDigest,event.traceId(),reason,outcome.receipt().admittedAt(),event.occurredAt(),event.acceptedAt());
        }
    }
    private void reject(AutomationEventJournal.Transport transport,String reason){
        receipts.rejectTransport(Uuid7.generate(),transport.partition(),transport.offset(),transport.digest(),reason);
    }
}
