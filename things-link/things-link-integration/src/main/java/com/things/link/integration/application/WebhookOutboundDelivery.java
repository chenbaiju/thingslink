package com.things.link.integration.application;

import com.things.link.integration.domain.*;
import com.things.link.integration.infrastructure.webhook.PublicWebhookSender;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 与已提交的 STARTED 回执分属不同事务；权限锁贯穿 HTTP 调用及完成写入。 */
@Service
public class WebhookOutboundDelivery {
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookOutboundAuthority authority;
    private final WebhookDeliveryState state;
    private final PublicWebhookSender sender;
    private final TransactionLocalRlsScope rls;
    private final boolean enabled;
    public WebhookOutboundDelivery(WebhookDeliveryRepository deliveries,WebhookSubscriptionRepository subscriptions,
            WebhookOutboundAuthority authority,WebhookDeliveryState state,PublicWebhookSender sender,
            TransactionLocalRlsScope rls,@Value("${things-link.integration.webhook.enabled:false}") boolean enabled){
        this.deliveries=deliveries;this.subscriptions=subscriptions;this.authority=authority;
        this.state=state;this.sender=sender;this.rls=rls;this.enabled=enabled;
    }
    @Transactional(timeout=10)
    public boolean send(WebhookDeliveryRepository.Candidate candidate,UUID token){
        if(!enabled)return false;
        rls.establish(candidate.tenant(),candidate.project());
        var snapshot=deliveries.find(candidate.id());if(snapshot.isEmpty())return false;
        var subscription=authority.lock(candidate,snapshot.get());
        var found=deliveries.lock(candidate.id());if(found.isEmpty())return false;
        var d=found.get();var now=subscriptions.now();
        if(!d.status().equals("IN_FLIGHT")||!java.util.Objects.equals(d.token(),token)||!d.leaseUntil().isAfter(now))return false;
        if(subscription.isEmpty())return state.cancel(candidate,token,"AUTH_REJECTED");
        // 完整网络预算必须落在归属租约内；不确定的任务留待租约恢复处理。
        if(!d.leaseUntil().isAfter(now.plusSeconds(5)))return false;
        if(!d.deadlineAt().isAfter(now.plusSeconds(5)))return state.finish(candidate,token,WebhookDeliveryState.Outcome.PERMANENT,null,0,"DEADLINE_EXCEEDED");
        String body=deliveries.eventText(d.id());
        if(body==null)return state.finish(candidate,token,WebhookDeliveryState.Outcome.PERMANENT,null,0,"BODY_UNAVAILABLE");
        var result=sender.send(subscription.get(),d.id(),body);
        if(!state.finish(candidate,token,result.outcome(),result.httpStatus(),result.elapsedMillis(),result.reason()))
            throw new IllegalStateException("Webhook completion lost ownership");
        return true;
    }
}
