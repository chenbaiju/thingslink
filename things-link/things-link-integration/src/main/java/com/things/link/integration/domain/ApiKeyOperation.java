package com.things.link.integration.domain;
import java.time.Instant;
import java.util.UUID;
/** 不可变恢复身份，不含秘密或凭据摘要。 */
public record ApiKeyOperation(UUID tenant, UUID project, UUID operation, UUID actor, String kind,
                              String requestDigest, UUID target, UUID result, Instant completedAt) {}
