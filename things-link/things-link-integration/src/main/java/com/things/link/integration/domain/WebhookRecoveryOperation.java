package com.things.link.integration.domain;
import java.time.Instant;
import java.util.UUID;
/** Immutable recovery receipt, independent of the seven-day delivery/body retention. */
public record WebhookRecoveryOperation(UUID tenant,UUID project,UUID operation,UUID actor,String digest,UUID delivery,int resultRound,Instant completedAt){}
