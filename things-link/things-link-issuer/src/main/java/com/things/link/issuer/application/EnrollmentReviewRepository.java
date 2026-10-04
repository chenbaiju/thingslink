package com.things.link.issuer.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 发行方只追加核验事实端口；申请行锁串行化同申请的两次核验。 */
public interface EnrollmentReviewRepository {
    RequestFact requestForUpdate(UUID requestId);
    RequestFact request(UUID requestId);
    List<Attestation> attestations(UUID requestId);
    void insert(Attestation attestation);

    record RequestFact(UUID requestId, UUID deploymentId, UUID tenantId,
                       byte[] publicKeySha256, byte[] requestSha256, String status) {
        public RequestFact {
            publicKeySha256 = publicKeySha256.clone();
            requestSha256 = requestSha256.clone();
        }
        @Override public byte[] publicKeySha256() { return publicKeySha256.clone(); }
        @Override public byte[] requestSha256() { return requestSha256.clone(); }
    }

    record Attestation(UUID requestId, UUID reviewerAccountId, UUID deploymentId, UUID tenantId,
                       byte[] publicKeySha256, byte[] requestSha256,
                       String organizationReference, byte[] evidenceSha256, String tier,
                       String revisionId, byte[] revisionSha256, Instant attestedAt) {
        public Attestation {
            publicKeySha256 = publicKeySha256.clone();
            requestSha256 = requestSha256.clone();
            evidenceSha256 = evidenceSha256.clone();
            revisionSha256 = revisionSha256.clone();
        }
        @Override public byte[] publicKeySha256() { return publicKeySha256.clone(); }
        @Override public byte[] requestSha256() { return requestSha256.clone(); }
        @Override public byte[] evidenceSha256() { return evidenceSha256.clone(); }
        @Override public byte[] revisionSha256() { return revisionSha256.clone(); }
    }
}
