package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** Durable claim/attempt facts; authority locks precede delivery locks in claim and outbound. */
@Service
public class WebhookDeliveryState {
    private final WebhookDeliveryRepository deliveries;private final WebhookSubscriptionRepository subscriptions;private final WebhookOutboundAuthority authority;private final TransactionLocalRlsScope rls;
    public WebhookDeliveryState(WebhookDeliveryRepository deliveries,WebhookSubscriptionRepository subscriptions,WebhookOutboundAuthority authority,TransactionLocalRlsScope rls){this.deliveries=deliveries;this.subscriptions=subscriptions;this.authority=authority;this.rls=rls;}
    @Transactional public List<WebhookDeliveryRepository.Candidate> candidates(){return deliveries.candidates(100);}
    @Transactional(timeout=10) public Optional<WebhookDeliveryRepository.Delivery> claim(WebhookDeliveryRepository.Candidate candidate,boolean sendingEnabled){
        rls.establish(candidate.tenant(),candidate.project());
        var snapshot=deliveries.find(candidate.id());if(snapshot.isEmpty())return Optional.empty();var subscription=authority.lock(candidate,snapshot.get());var found=deliveries.lock(candidate.id());if(found.isEmpty())return Optional.empty();var d=found.get();var now=subscriptions.now();
        if(!Set.of("READY","IN_FLIGHT").contains(d.status())||("IN_FLIGHT".equals(d.status())?d.leaseUntil().isAfter(now):d.nextAttempt().isAfter(now)))return Optional.empty();
        if(d.token()!=null)deliveries.completeAttempt(d.id(),d.token(),"UNKNOWN",null,null,"LEASE_EXPIRED");
        if(subscription.isEmpty()){
            deliveries.terminate(d.id(),"CANCELLED","AUTH_REJECTED");return Optional.empty();}
        if(!d.deadlineAt().isAfter(now)||d.roundAttempts()>=5){deliveries.terminate(d.id(),"DEAD",d.roundAttempts()>=5?"ATTEMPTS_EXHAUSTED":"DEADLINE_EXCEEDED");return Optional.empty();}
        if(!sendingEnabled)return Optional.empty();return Optional.of(deliveries.claim(d.id(),UUID.randomUUID()));
    }
    @Transactional(timeout=10) public boolean finish(WebhookDeliveryRepository.Candidate candidate,UUID token,Outcome outcome,Integer httpStatus,long elapsed){
        return finish(candidate,token,outcome,httpStatus,elapsed,outcome==null?null:outcome.name());
    }
    @Transactional(timeout=10) public boolean finish(WebhookDeliveryRepository.Candidate candidate,UUID token,Outcome outcome,Integer httpStatus,long elapsed,String reason){
        if(reason==null||!reason.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid webhook result reason");
        if(outcome==null||elapsed<0||httpStatus!=null&&(httpStatus<100||httpStatus>599))throw new IllegalArgumentException("Invalid webhook attempt result");rls.establish(candidate.tenant(),candidate.project());
        var found=deliveries.lock(candidate.id());if(found.isEmpty())return false;var d=found.get();var now=subscriptions.now();if(!d.status().equals("IN_FLIGHT")||!Objects.equals(d.token(),token)||!d.leaseUntil().isAfter(now))return false;
        boolean retry=outcome==Outcome.RETRYABLE||outcome==Outcome.UNKNOWN;String status=outcome==Outcome.SUCCEEDED?"SUCCEEDED":retry&&d.roundAttempts()<5&&d.deadlineAt().isAfter(now)?"READY":"DEAD";
        deliveries.completeAttempt(d.id(),token,outcome.name(),httpStatus,elapsed,reason);if(!deliveries.finish(d.id(),token,status,reason,1<<(d.roundAttempts()-1)))throw new IllegalStateException("Webhook finish lease expired");return true;
    }
    @Transactional(timeout=10) public boolean cancel(WebhookDeliveryRepository.Candidate candidate,UUID token,String reason){
        if(reason==null||!reason.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid webhook cancellation");rls.establish(candidate.tenant(),candidate.project());var found=deliveries.lock(candidate.id());if(found.isEmpty())return false;var d=found.get();
        if(!d.status().equals("IN_FLIGHT")||!Objects.equals(d.token(),token)||!d.leaseUntil().isAfter(subscriptions.now()))return false;
        deliveries.completeAttempt(d.id(),token,"CANCELLED",null,null,reason);if(!deliveries.finish(d.id(),token,"CANCELLED",reason,0))throw new IllegalStateException("Webhook cancel lease expired");return true;
    }
    public enum Outcome {SUCCEEDED,RETRYABLE,PERMANENT,UNKNOWN}
}
