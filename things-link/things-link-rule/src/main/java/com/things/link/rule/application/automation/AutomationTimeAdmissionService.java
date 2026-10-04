package com.things.link.rule.application.automation;

import com.things.link.rule.domain.*;
import com.things.link.project.application.*;
import com.things.link.device.application.DeviceAutomationAccess;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.json.AutomationCanonicalJson;
import com.things.link.support.scheduling.TenantWorkSlotRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** 到期领取和受理是两个短事务；末尾游标fence失效时额度/执行全部回滚。 */
@Service
@Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
public class AutomationTimeAdmissionService {
    private final AutomationScheduleRepository schedules;
    private final AutomationRepository definitions;
    private final AutomationEventReceiptRepository clock;
    private final BackgroundProjectActorAccess actors;
    private final DeviceAutomationAccess devices;
    private final AutomationQuotaService quota;
    private final TenantWorkSlotRepository slots;
    private final TransactionLocalRlsScope rls;
    private final AutomationTimePolicy policy;
    public AutomationTimeAdmissionService(AutomationScheduleRepository schedules,AutomationRepository definitions,
            AutomationEventReceiptRepository clock,BackgroundProjectActorAccess actors,DeviceAutomationAccess devices,
            AutomationQuotaService quota,TenantWorkSlotRepository slots,TransactionLocalRlsScope rls,AutomationTimePolicy policy){
        this.schedules=schedules;this.definitions=definitions;this.clock=clock;this.actors=actors;this.devices=devices;
        this.quota=quota;this.slots=slots;this.rls=rls;this.policy=policy;
    }
    public Optional<AutomationScheduleRepository.Claim> claim(AutomationScheduleRepository.Candidate c,TenantWorkSlotRepository.Lease lease){
        if(scope(c)==BackgroundProjectActorAccess.State.MISSING)return Optional.empty();
        var definition=definitions.find(c.projectId(),c.automationId(),true).orElse(null);
        if(definition==null||definition.status()!=AutomationDefinition.Status.ACTIVE||!validSlot(c,lease))return Optional.empty();
        return schedules.claim(c);
    }
    public boolean accept(AutomationScheduleRepository.Claim claimed,TenantWorkSlotRepository.Lease lease){
        var c=claimed.candidate();var projectState=scope(c);
        if(projectState==BackgroundProjectActorAccess.State.MISSING)return false;
        var definition=definitions.find(c.projectId(),c.automationId(),true).orElse(null);
        var current=schedules.lock(c).orElse(null);var now=clock.databaseNow();
        if(definition==null||definition.status()!=AutomationDefinition.Status.ACTIVE||!Objects.equals(definition.activeVersionId(),claimed.versionId())
                ||current==null||current.token()!=claimed.token()||!Objects.equals(current.versionId(),claimed.versionId())
                ||!Objects.equals(current.fireAt(),claimed.fireAt())||current.fireAt()==null||current.fireAt().isAfter(now)
                ||current.leaseUntil()==null||!current.leaseUntil().isAfter(now)||!validSlot(c,lease))return false;
        var version=definitions.version(c.projectId(),c.automationId(),claimed.versionId()).orElseThrow();
        UUID device=UUID.fromString(version.triggerConfig().path("deviceId").asString());
        String reason=null;
        if(projectState!=BackgroundProjectActorAccess.State.ACTIVE)reason="PROJECT_READ_ONLY";
        else if(!actors.lockManager(c.projectId(),version.createdBy()))reason="AUTH_REVOKED";
        else if(devices.lock(c.tenantId(),c.projectId(),device)!=DeviceAutomationAccess.State.AVAILABLE)reason="DEVICE_UNAVAILABLE";
        else if(definitions.countPending(c.projectId())>=1000)reason="CAPACITY";
        UUID execution=Uuid7.generate();
        if(reason==null){
            var result=quota.reserve(c.tenantId(),c.projectId(),execution);
            if(result==AutomationQuotaService.Reservation.NOT_ENABLED||result==AutomationQuotaService.Reservation.QUOTA_EXCEEDED)reason="QUOTA";
            else if(result!=AutomationQuotaService.Reservation.RESERVED)throw new IllegalStateException("首次时间执行额度身份不一致");
        }
        var input=version.triggerConfig().path("payload");
        definitions.admitTime(execution,version,current.fireAt(),device,input,AutomationCanonicalJson.digest(input),reason,now);
        boolean once=version.triggerType().equals("ONE_SHOT");
        var next=once?null:policy.next(version.triggerConfig(),now.isAfter(current.fireAt())?now:current.fireAt());
        if(!validSlot(c,lease)||!schedules.advance(claimed,next,once))throw new StaleScheduleLeaseException();
        return true;
    }
    private BackgroundProjectActorAccess.State scope(AutomationScheduleRepository.Candidate c){
        schedules.boundedWait();var state=actors.lockProject(c.tenantId(),c.projectId());
        if(state!=BackgroundProjectActorAccess.State.MISSING){rls.establish(c.tenantId(),c.projectId());clock.lockProject(c.projectId());}
        return state;
    }
    private boolean validSlot(AutomationScheduleRepository.Candidate c,TenantWorkSlotRepository.Lease lease){return lease!=null&&lease.workType()==TenantWorkSlotRepository.WorkType.AUTOMATION&&lease.tenantId().equals(c.tenantId())&&slots.fence(lease);}
    public static final class StaleScheduleLeaseException extends RuntimeException {}
}
