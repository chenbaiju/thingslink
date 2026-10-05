package com.things.link.integration.infrastructure.webhook;

import com.things.link.integration.application.WebhookDeliveryState.Outcome;
import com.things.link.integration.application.WebhookEventCodec;
import com.things.link.integration.application.WebhookSigningKeys;
import com.things.link.integration.domain.WebhookSubscription;
import com.things.link.support.notification.delivery.PinnedWebhookTransport;
import com.things.link.support.notification.delivery.WebhookSignatures;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 仅提供签名适配；调用方必须在整个调用期间持有当前权限锁与租约隔离保护。 */
@Component
public class PublicWebhookSender {
    private final WebhookSigningKeys keys;
    private final PinnedWebhookTransport transport;
    public PublicWebhookSender(WebhookSigningKeys keys,PinnedWebhookTransport transport){this.keys=keys;this.transport=transport;}
    public Result send(WebhookSubscription subscription,UUID delivery,String eventText){
        if(!keys.available(subscription.signingKeyId()))return new Result(Outcome.PERMANENT,null,0,"KEY_UNAVAILABLE");
        byte[] body=WebhookEventCodec.body(delivery,eventText).getBytes(StandardCharsets.UTF_8);
        byte[] secret=keys.derive(subscription.signingKeyId(),subscription.tenant(),subscription.project(),subscription.id(),subscription.signingGeneration());
        String nonce=UUID.randomUUID().toString();long time=Instant.now().getEpochSecond();String signature;
        try{signature=WebhookSignatures.sign(secret,time,nonce,delivery,body);}finally{Arrays.fill(secret,(byte)0);}
        Map<String,String> headers=Map.of("X-ThingsLink-Delivery-Id",delivery.toString(),"X-ThingsLink-Timestamp",Long.toString(time),
            "X-ThingsLink-Nonce",nonce,"X-ThingsLink-Signature",signature,"X-ThingsLink-Key-Id",subscription.signingKeyId());
        var result=transport.post(URI.create(subscription.target()),headers,body);
        if(result.failure()!=PinnedWebhookTransport.Failure.NONE)return new Result(result.failure()==PinnedWebhookTransport.Failure.INVALID_TARGET?Outcome.PERMANENT:Outcome.UNKNOWN,null,result.elapsedMillis(),result.failure().name());
        int status=result.httpStatus();Outcome outcome=status>=200&&status<300?Outcome.SUCCEEDED:status==408||status==429||status>=500?Outcome.RETRYABLE:Outcome.PERMANENT;
        return new Result(outcome,status,result.elapsedMillis(),"HTTP_"+status);
    }
    public record Result(Outcome outcome,Integer httpStatus,long elapsedMillis,String reason){}
}
