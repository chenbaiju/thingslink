package com.things.link.integration.domain;
import java.time.Instant;
import java.util.*;
/** 不可变 Webhook 订阅快照，含生命周期代次、修订版本及签名代次；输出隐藏目标地址。 */
public record WebhookSubscription(UUID id,UUID tenant,UUID project,long generation,UUID createdBy,Instant createdAt,long revision,String status,Instant updatedAt,
    String name,String target,List<String> eventTypes,List<UUID> deviceIds,UUID authorizedBy,String signingKeyId,UUID signingGeneration) {
    public WebhookSubscription{eventTypes=List.copyOf(eventTypes);deviceIds=List.copyOf(deviceIds);}
    @Override public String toString(){return "WebhookSubscription[id="+id+",revision="+revision+",target=REDACTED]";}
}
