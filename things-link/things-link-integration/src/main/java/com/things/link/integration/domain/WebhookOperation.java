package com.things.link.integration.domain;
import java.time.Instant;
import java.util.UUID;
/** 订阅操作的持久幂等回执，绑定操作者、请求摘要、结果标识及结果版本。 */
public record WebhookOperation(UUID tenant,UUID project,UUID operation,UUID actor,String kind,String digest,UUID result,long revision,Instant completedAt) {}
