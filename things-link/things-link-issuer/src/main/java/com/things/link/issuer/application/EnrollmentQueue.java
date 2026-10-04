package com.things.link.issuer.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 只暴露待审事实的有界查询端口，不返回申请公钥或摘要。 */
public interface EnrollmentQueue {
    List<Pending> page(int limit, Instant beforeReceivedAt, UUID beforeRequestId);

    record Pending(UUID requestId, UUID deploymentId, UUID claimedTenantId,
                   EnrollmentRegistry.Channel firstChannel, Instant receivedAt) { }
}
