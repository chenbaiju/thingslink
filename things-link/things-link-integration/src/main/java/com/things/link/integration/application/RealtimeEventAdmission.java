package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 已受理索引及固定匹配计划同事务；实际发送仍须逐次确权。 */
@Service
public class RealtimeEventAdmission {
    private final RealtimeEventRepository events;private final RealtimeTicketRepository tickets;
    private final RealtimeEventCodec codec;private final TransactionLocalRlsScope rls;private final ProjectLifecycleAccessService projects;
    public RealtimeEventAdmission(RealtimeEventRepository events,RealtimeTicketRepository tickets,RealtimeEventCodec codec,TransactionLocalRlsScope rls,ProjectLifecycleAccessService projects){
        this.events=events;this.tickets=tickets;this.codec=codec;this.rls=rls;this.projects=projects;
    }
    @Transactional
    public void accept(DeviceRealtimeUpdate update){
        rls.establish(update.tenantId(),update.projectId());var generation=projects.lockReadableGeneration(update.tenantId(),update.projectId());
        if(generation.isEmpty())return;
        var result=events.admit(update,codec.sourceHash(update));
        if(result==RealtimeEventRepository.Admission.CONFLICT)throw new IllegalArgumentException("REALTIME_EVENT_ID_CONFLICT");
        if(result==RealtimeEventRepository.Admission.DUPLICATE)return;
        for(var ticket:tickets.lockConnected(update.projectId())){
            if(generation.getAsLong()!=ticket.identity().generation()){tickets.close(ticket.id(),"AUTH_REJECTED");continue;}
            var scope=ticket.request().devices().stream().filter(d->d.deviceId().equals(update.deviceId())).findFirst();if(scope.isEmpty())continue;
            if(!scope.get().expectedModelVersionId().equals(update.thingModelVersionId())){tickets.close(ticket.id(),"RESYNC_REQUIRED");continue;}
            var envelope=codec.envelope(update,scope.get());if(envelope.isEmpty())continue;
            if(codec.oversized(envelope.get())||events.pending(ticket.id())>=256){tickets.close(ticket.id(),"RESYNC_REQUIRED");continue;}
            events.enqueue(ticket,update,envelope.get());
        }
    }
}
