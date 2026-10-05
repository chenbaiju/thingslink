package com.things.link.integration.domain;
import java.time.Instant;
import java.util.UUID;
/** 不可变恢复回执，不受投递记录及正文七天保留期限制。 */
public record WebhookRecoveryOperation(UUID tenant,UUID project,UUID operation,UUID actor,String digest,UUID delivery,int resultRound,Instant completedAt){}
