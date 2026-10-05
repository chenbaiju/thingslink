package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
/** 公开 MQTT 实时交付编排；认领后重新准入，在租约内发送并写入持久结果。 */
@Service
public class RealtimeDeliveryDispatcher {
    private final RealtimeDeliveryState state;private final RealtimeOutboundAdmission admission;
    private final RealtimeDeliveryRepository deliveries;private final RealtimeTicketRepository tickets;private final RealtimeMqttPublisher publisher;
    public RealtimeDeliveryDispatcher(RealtimeDeliveryState state,RealtimeOutboundAdmission admission,RealtimeDeliveryRepository deliveries,RealtimeTicketRepository tickets,RealtimeMqttPublisher publisher){this.state=state;this.admission=admission;this.deliveries=deliveries;this.tickets=tickets;this.publisher=publisher;}
    public void dispatch(RealtimeDeliveryRepository.Candidate candidate){
        state.claim(candidate,publisher.configured()).ifPresent(claim->send(candidate,claim));
    }
    public void send(RealtimeDeliveryRepository.Candidate candidate,RealtimeDeliveryRepository.Delivery claim){
        byte[] payload=claim.envelope().getBytes(StandardCharsets.UTF_8);
        if(RealtimeMqttPublisher.packetBytes(claim.ticket(),payload)>32768){state.cancel(candidate,claim,"RESYNC_REQUIRED");return;}
        try{
            admission.withAdmission(new RealtimeTicketRepository.Candidate(candidate.tenant(),candidate.project(),claim.ticket()),RealtimeTicketRequest.Protocol.MQTT,t->{
                var current=deliveries.lock(candidate.id());
                if(current.isEmpty()||!"IN_FLIGHT".equals(current.get().status())||!claim.token().equals(current.get().token())||!current.get().leaseUntil().isAfter(tickets.now().plusSeconds(5)))return false;
                var outcome=publisher.publish(t.id(),payload,Duration.ofSeconds(5));
                boolean accepted=outcome==RealtimeMqttPublisher.Outcome.BROKER_ACCEPTED;
                String status=accepted?"DELIVERED":claim.attempts()>=5?"FAILED":"READY";
                return deliveries.finish(claim.id(),claim.token(),status,accepted?"BROKER_ACCEPTED":outcome.name(),1<<(claim.attempts()-1));
            });
        }catch(BusinessException rejected){if(rejected.errorCode()==IntegrationErrorCode.REALTIME_UNAVAILABLE)throw rejected;state.cancel(candidate,claim,"AUTH_REJECTED");}
    }
}
