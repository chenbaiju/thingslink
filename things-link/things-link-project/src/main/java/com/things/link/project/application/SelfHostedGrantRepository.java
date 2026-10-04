package com.things.link.project.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 自部署授权的数据库事实端口；调用方须在同一事务中串行化租户和授权状态。 */
public interface SelfHostedGrantRepository {
    void lockTenantRegistration();

    List<UUID> allTenantIds();

    Optional<StoredGrant> lockCurrent();

    Optional<StoredGrant> current();

    void insert(StoredGrant grant);

    void replace(StoredGrant grant);

    void appendImportAudit(StoredGrant grant);

    record StoredGrant(UUID deploymentId, UUID tenantId, byte[] deploymentPublicKeySha256,
                       UUID grantId, long sequence, byte[] envelope, byte[] envelopeSha256,
                       Long devicesMax, Long uplinkMessageDaily, Long downlinkMessageDaily) {
        public StoredGrant {
            deploymentPublicKeySha256 = deploymentPublicKeySha256.clone();
            envelope = envelope.clone();
            envelopeSha256 = envelopeSha256.clone();
        }

        @Override public byte[] deploymentPublicKeySha256() { return deploymentPublicKeySha256.clone(); }
        @Override public byte[] envelope() { return envelope.clone(); }
        @Override public byte[] envelopeSha256() { return envelopeSha256.clone(); }

        public boolean hasQuotaProjection() {
            return devicesMax != null && uplinkMessageDaily != null && downlinkMessageDaily != null;
        }
    }
}
