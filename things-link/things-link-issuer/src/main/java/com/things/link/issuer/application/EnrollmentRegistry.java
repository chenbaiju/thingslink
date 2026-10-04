package com.things.link.issuer.application;

import java.time.Instant;
import java.util.UUID;

/** 发行方待审申请登记端口；仅返回持久事实，不授予任何权益。 */
public interface EnrollmentRegistry {
    Registration register(byte[] envelope, Channel channel);

    enum Channel { ONLINE, OFFLINE }

    record Registration(UUID requestId, UUID deploymentId, UUID tenantId,
                        byte[] publicKeySha256, byte[] requestSha256,
                        Channel channel, String status, Instant receivedAt) {
        public Registration {
            publicKeySha256 = publicKeySha256.clone();
            requestSha256 = requestSha256.clone();
        }

        @Override public byte[] publicKeySha256() { return publicKeySha256.clone(); }
        @Override public byte[] requestSha256() { return requestSha256.clone(); }
    }
}
