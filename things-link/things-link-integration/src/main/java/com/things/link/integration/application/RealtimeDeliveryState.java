package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class RealtimeDeliveryState {
    private final RealtimeDeliveryRepository deliveries;private final RealtimeTicketRepository tickets;
    private final TransactionLocalRlsScope rls;private final ProjectLifecycleAccessService projects;
    public RealtimeDeliveryState(RealtimeDeliveryRepository deliveries,RealtimeTicketRepository tickets,TransactionLocalRlsScope rls,ProjectLifecycleAccessService projects){this.deliveries=deliveries;this.tickets=tickets;this.rls=rls;this.projects=projects;}
    @Transactional public List<RealtimeDeliveryRepository.Candidate> candidates(){return deliveries.candidates(100);}
    @Transactional(timeout=10) public Optional<RealtimeDeliveryRepository.Delivery> claim(RealtimeDeliveryRepository.Candidate candidate,boolean sendingEnabled){
        rls.establish(candidate.tenant(),candidate.project());
        var generation=projects.lockReadableGeneration(candidate.tenant(),candidate.project());
        // No ticket write while holding delivery lock: admission uses ticket -> delivery.
        var found=deliveries.lock(candidate.id());if(found.isEmpty())return Optional.empty();var d=found.get();var now=tickets.now();
        if(!Set.of("READY","IN_FLIGHT").contains(d.status())||("IN_FLIGHT".equals(d.status())?d.leaseUntil().isAfter(now):d.nextAttempt().isAfter(now)))return Optional.empty();
        var t=tickets.find(d.ticket());
        if(generation.isEmpty()||t.isEmpty()||!t.get().status().equals("CONNECTED")||!t.get().expiresAt().isAfter(now)||t.get().identity().generation()!=generation.getAsLong()){
            deliveries.terminate(d.id(),"CANCELLED","AUTH_REJECTED");return Optional.empty();
        }
        if(t.get().request().protocol()!=RealtimeTicketRequest.Protocol.MQTT)return Optional.empty();
        if(!sendingEnabled)return Optional.empty();
        if(d.attempts()>=5){deliveries.terminate(d.id(),"FAILED","ATTEMPTS_EXHAUSTED");return Optional.empty();}
        return Optional.of(deliveries.claim(d.id(),UUID.randomUUID()));
    }
    @Transactional(timeout=10) public void cancel(RealtimeDeliveryRepository.Candidate candidate,RealtimeDeliveryRepository.Delivery claim,String reason){
        rls.establish(candidate.tenant(),candidate.project());projects.lockReadableGeneration(candidate.tenant(),candidate.project());
        // Ticket before delivery matches ingress and avoids a lock upgrade in outbound admission.
        tickets.lockForTermination(claim.ticket());
        deliveries.lock(candidate.id()).ifPresent(d->{if(claim.token().equals(d.token())&&d.leaseUntil().isAfter(tickets.now())){
            if("RESYNC_REQUIRED".equals(reason))tickets.close(claim.ticket(),reason);
            deliveries.finish(d.id(),claim.token(),"CANCELLED",reason,0);
        }});
    }
}
