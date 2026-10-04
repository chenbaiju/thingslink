package com.things.link.integration.domain;
import java.time.Instant;
import java.util.*;
public record WebhookSubscription(UUID id,UUID tenant,UUID project,long generation,UUID createdBy,Instant createdAt,long revision,String status,Instant updatedAt,
    String name,String target,List<String> eventTypes,List<UUID> deviceIds,UUID authorizedBy,String signingKeyId,UUID signingGeneration) {
    public WebhookSubscription{eventTypes=List.copyOf(eventTypes);deviceIds=List.copyOf(deviceIds);}
    @Override public String toString(){return "WebhookSubscription[id="+id+",revision="+revision+",target=REDACTED]";}
}
