package com.things.link.integration.domain;
import java.time.Instant;
import java.util.UUID;
public record WebhookOperation(UUID tenant,UUID project,UUID operation,UUID actor,String kind,String digest,UUID result,long revision,Instant completedAt) {}
