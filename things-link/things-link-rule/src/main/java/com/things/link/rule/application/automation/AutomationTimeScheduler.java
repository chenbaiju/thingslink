package com.things.link.rule.application.automation;

import com.things.link.rule.domain.AutomationScheduleRepository;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.scheduling.TenantWorkSlotRepository;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.UUID;

/** 每轮至多100租户头部，游标翻页防止前部故障租户饥饿后部；与求值共享租户工作槽。 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.automation.time.enabled",havingValue="true")
public class AutomationTimeScheduler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AutomationTimeScheduler.class);
    private final AutomationScheduleRepository store;
    private final AutomationTimeAdmissionService service;
    private final TenantWorkSlotRepository slots;
    private UUID afterTenant;
    public AutomationTimeScheduler(AutomationScheduleRepository store,AutomationTimeAdmissionService service,TenantWorkSlotRepository slots){this.store=store;this.service=service;this.slots=slots;}
    @Scheduled(fixedDelayString="${things-link.automation.time.scan-delay-millis:1000}",scheduler="automationLifecycleScheduler")
    public void scan(){
        var previous=TenantContext.current();TenantContext.clear();
        try{
            var candidates=store.candidates(afterTenant);
            if(candidates.isEmpty()){afterTenant=null;return;}
            for(var c:candidates){afterTenant=c.tenantId();try{run(c);}catch(RuntimeException failure){LOG.warn("时间自动化本轮未完成，保留游标及租约；类型={}",failure.getClass().getSimpleName());}}
        }finally{TenantContext.clear();previous.ifPresent(TenantContext::set);}
    }
    public void run(AutomationScheduleRepository.Candidate c){
        var previous=TenantContext.current();TenantContext.clear();
        try{
            var acquired=slots.tryAcquire(TenantWorkSlotRepository.WorkType.AUTOMATION,c.tenantId(),Duration.ofSeconds(30));
            if(acquired.isEmpty())return;var lease=acquired.get();
            try{service.claim(c,lease).ifPresent(claim->service.accept(claim,lease));}finally{slots.release(lease);}
        }finally{TenantContext.clear();previous.ifPresent(TenantContext::set);}
    }
}
