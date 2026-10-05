package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Consumer;
/** 公开 WebSocket 实时交付服务；发送前重验票据权限、实例归属及持久交付状态。 */
@Service
public class RealtimeWsDelivery {
    private final RealtimeDeliveryRepository deliveries;private final RealtimeTicketRepository tickets;private final RealtimeOutboundAdmission admission;private final RealtimeTicketService service;private final TransactionLocalRlsScope rls;
    public RealtimeWsDelivery(RealtimeDeliveryRepository deliveries,RealtimeTicketRepository tickets,RealtimeOutboundAdmission admission,RealtimeTicketService service,TransactionLocalRlsScope rls){this.deliveries=deliveries;this.tickets=tickets;this.admission=admission;this.service=service;this.rls=rls;}
    @Transactional(readOnly=true) public List<RealtimeDeliveryRepository.Candidate> pending(RealtimeTicketRepository.Candidate ticket){rls.establish(ticket.tenant(),ticket.project());return deliveries.wsCandidates(ticket.id(),256);}
    public void frame(RealtimeTicketRepository.Candidate ticket,Consumer<RealtimeTicket> action){admission.withAdmission(ticket,RealtimeTicketRequest.Protocol.WS,t->{if(!service.instanceId().equals(t.instanceId()))throw new IllegalStateException("WS owner changed");action.accept(t);return null;});}
    public void send(RealtimeTicketRepository.Candidate ticket,RealtimeDeliveryRepository.Candidate candidate,Consumer<String> wire){
        frame(ticket,t->{var found=deliveries.lock(candidate.id());if(found.isEmpty())return;var d=found.get();
            if(!d.ticket().equals(ticket.id())||!Set.of("READY","IN_FLIGHT").contains(d.status())||("IN_FLIGHT".equals(d.status())&&d.leaseUntil().isAfter(tickets.now())))return;
            var claim=deliveries.claim(d.id(),UUID.randomUUID());wire.accept(claim.envelope());
            if(!deliveries.finish(claim.id(),claim.token(),"DELIVERED","WS_ACCEPTED",0))throw new IllegalStateException("WS lease lost");
        });
    }
}
