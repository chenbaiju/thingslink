package com.things.link.issuer.application;

import com.things.link.entitlement.GrantV1;
import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.entitlement.application.GrantV1Verification;
import com.things.link.issuer.infrastructure.keys.LocalControlledIssuerKey;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 独立发行作业的首期签发事务；不作为普通平台进程的 Spring Bean 或 HTTP 入口。 */
public final class ControlledGrantIssuance {
    private final DataSource issuerDataSource;
    private final ApprovedSelfHostedRevision approved;

    public ControlledGrantIssuance(DataSource issuerDataSource) throws GeneralSecurityException {
        this.issuerDataSource = issuerDataSource;
        this.approved = ApprovedSelfHostedRevision.loadApproved();
    }

    public Issued issue(UUID requestId, UUID grantId, Path keyDirectory, char[] password) throws Exception {
        if (requestId == null || grantId == null) throw new IllegalArgumentException("必须指定申请和授权标识");
        try (Connection connection = issuerDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lock(connection, requestId)) throw new GeneralSecurityException("待审申请不存在");
                Issued prior = find(connection, grantId);
                if (prior != null) {
                    if (!prior.requestId().equals(requestId)) {
                        throw new GeneralSecurityException("签发幂等键已用于另一申请");
                    }
                    verifyRecorded(prior);
                    connection.commit();
                    return prior;
                }
                Source source = source(connection, requestId);
                if (source == null) throw new GeneralSecurityException("缺少受控客户归属记录");
                List<Review> reviews = reviews(connection, requestId);
                String tierName = requireResponsibleReview(source, reviews);
                var tier = approved.tier(tierName);
                var identity = LocalControlledIssuerKey.publicIdentity(keyDirectory, password);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM public.sys_shc_signed_grant WHERE deployment_id=?")) {
                    statement.setObject(1, source.deploymentId());
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        if (result.getLong(1) != 0) {
                            throw new GeneralSecurityException("后续续期或换档须使用单独批准路径");
                        }
                    }
                }
                Instant now;
                try (PreparedStatement statement = connection.prepareStatement("SELECT clock_timestamp()");
                     ResultSet result = statement.executeQuery()) {
                    result.next();
                    now = result.getTimestamp(1).toInstant();
                }
                Instant end = tierName.equals("FREE") ? null
                        : now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant();
                GrantV1 grant = new GrantV1(identity.issuerId(), grantId, source.deploymentId(),
                        source.tenantId(), tierName, approved.revisionId(), approved.sha256(),
                        1, now, now, end, identity.keyId(), tier.quotas(), tier.capabilities());
                byte[] envelope = LocalControlledIssuerKey.sign(keyDirectory, password, grant);
                PublicKey issuerPublic = KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(identity.publicKeySpki()));
                var verified = GrantV1Verification.verify(envelope,
                        Map.of(identity.keyId(), issuerPublic), identity.issuerId(),
                        source.deploymentId(), source.tenantId());
                approved.requireMatches(verified);
                if (!verified.grantId().equals(grantId) || verified.sequence() != 1
                        || !verified.issuedAt().equals(now) || !verified.startsAt().equals(now)
                        || !java.util.Objects.equals(verified.endsAt(), end)) {
                    throw new GeneralSecurityException("签名文件与发行事务不一致");
                }
                record(connection, grant, source, identity.publicKeySpki(), envelope);
                Issued issued = find(connection, grantId);
                if (issued == null) throw new GeneralSecurityException("签发事实未能读回");
                verifyRecorded(issued);
                connection.commit();
                return issued;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public Issued recover(UUID requestId, UUID grantId) throws Exception {
        if (requestId == null || grantId == null) throw new IllegalArgumentException("必须指定恢复键");
        try (Connection connection = issuerDataSource.getConnection()) {
            Issued prior = find(connection, grantId);
            if (prior == null) return null;
            if (!prior.requestId().equals(requestId)) {
                throw new GeneralSecurityException("恢复申请与原签发事实不符");
            }
            verifyRecorded(prior);
            return prior;
        }
    }

    private boolean lock(Connection connection, UUID requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT public.shc_issuance_lock(?)")) {
            statement.setObject(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private Source source(Connection connection, UUID requestId) throws SQLException, GeneralSecurityException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.deployment_id, r.tenant_id, r.public_key_sha256, r.request_sha256,
                       b.deployment_id, b.tenant_id, b.public_key_sha256, b.request_sha256,
                       b.organization_ref, b.evidence_sha256
                  FROM public.sys_shc_enrollment_request r
                  JOIN public.sys_shc_customer_binding b ON b.request_id=r.request_id
                 WHERE r.request_id=? AND r.status='PENDING'
                """)) {
            statement.setObject(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                UUID deployment = result.getObject(1, UUID.class), tenant = result.getObject(2, UUID.class);
                byte[] keyDigest = result.getBytes(3), requestDigest = result.getBytes(4);
                if (!deployment.equals(result.getObject(5, UUID.class))
                        || !tenant.equals(result.getObject(6, UUID.class))
                        || !MessageDigest.isEqual(keyDigest, result.getBytes(7))
                        || !MessageDigest.isEqual(requestDigest, result.getBytes(8))) {
                    throw new GeneralSecurityException("客户归属与申请身份不符");
                }
                return new Source(requestId, deployment, tenant, keyDigest, requestDigest,
                        result.getString(9), result.getBytes(10));
            }
        }
    }

    private List<Review> reviews(Connection connection, UUID requestId) throws SQLException {
        List<Review> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reviewer_account_id, deployment_id, tenant_id, public_key_sha256,
                       request_sha256, organization_ref, evidence_sha256,
                       tier, revision_id, revision_sha256
                  FROM public.sys_shc_review_attestation WHERE request_id=?
                """)) {
            statement.setObject(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Review(result.getObject(1, UUID.class),
                            result.getObject(2, UUID.class), result.getObject(3, UUID.class),
                            result.getBytes(4), result.getBytes(5), result.getString(6),
                            result.getBytes(7), result.getString(8), result.getString(9),
                            result.getBytes(10)));
                }
            }
        }
        return rows;
    }

    private String requireResponsibleReview(Source source, List<Review> reviews)
            throws GeneralSecurityException {
        if (reviews.isEmpty() || reviews.size() > 2
                || (reviews.size() == 2 && reviews.get(0).reviewer().equals(reviews.get(1).reviewer()))) {
            throw new GeneralSecurityException("必须有一名授权审核者的归属核验记录");
        }
        String tier = reviews.get(0).tier();
        for (Review review : reviews) {
            if (!source.deploymentId().equals(review.deploymentId())
                    || !source.tenantId().equals(review.tenantId())
                    || !MessageDigest.isEqual(source.publicKeySha256(), review.publicKeySha256())
                    || !MessageDigest.isEqual(source.requestSha256(), review.requestSha256())
                    || !source.organizationRef().equals(review.organizationRef())
                    || !MessageDigest.isEqual(source.evidenceSha256(), review.evidenceSha256())
                    || !tier.equals(review.tier())
                    || !approved.revisionId().equals(review.revisionId())
                    || !MessageDigest.isEqual(approved.sha256(), review.revisionSha256())) {
                throw new GeneralSecurityException("审核证明与申请、归属或产品修订不一致");
            }
        }
        return tier;
    }

    private void record(Connection connection, GrantV1 grant, Source source,
                        byte[] publicSpki, byte[] envelope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT public.shc_issuance_record(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setObject(1, grant.grantId());
            statement.setObject(2, source.requestId());
            statement.setObject(3, grant.deploymentId());
            statement.setObject(4, grant.tenantId());
            statement.setString(5, source.organizationRef());
            statement.setBytes(6, source.evidenceSha256());
            statement.setBytes(7, source.requestSha256());
            statement.setString(8, grant.tier());
            statement.setString(9, grant.revisionId());
            statement.setBytes(10, grant.revisionSha256());
            statement.setString(11, grant.issuerId());
            statement.setString(12, grant.keyId());
            statement.setBytes(13, publicSpki);
            statement.setLong(14, grant.sequence());
            statement.setTimestamp(15, Timestamp.from(grant.issuedAt()));
            statement.setTimestamp(16, Timestamp.from(grant.startsAt()));
            statement.setTimestamp(17, grant.endsAt() == null ? null : Timestamp.from(grant.endsAt()));
            statement.setBytes(18, envelope);
            statement.executeQuery().close();
        }
    }

    private Issued find(Connection connection, UUID grantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT grant_id, request_id, deployment_id, tenant_id, tier, revision_id,
                       revision_sha256, issuer_id, key_id, public_key_spki, public_key_sha256,
                       sequence_no, issued_at, starts_at, ends_at, envelope, envelope_sha256
                  FROM public.sys_shc_signed_grant WHERE grant_id=?
                """)) {
            statement.setObject(1, grantId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                Timestamp end = result.getTimestamp(15);
                return new Issued(result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                        result.getObject(3, UUID.class), result.getObject(4, UUID.class),
                        result.getString(5), result.getString(6), result.getBytes(7),
                        result.getString(8), result.getString(9), result.getBytes(10),
                        result.getBytes(11), result.getLong(12), result.getTimestamp(13).toInstant(),
                        result.getTimestamp(14).toInstant(), end == null ? null : end.toInstant(),
                        result.getBytes(16), result.getBytes(17));
            }
        }
    }

    private void verifyRecorded(Issued recorded) throws GeneralSecurityException {
        if (!MessageDigest.isEqual(recorded.envelopeSha256(),
                MessageDigest.getInstance("SHA-256").digest(recorded.envelope()))
                || !MessageDigest.isEqual(recorded.publicKeySha256(),
                MessageDigest.getInstance("SHA-256").digest(recorded.publicKeySpki()))) {
            throw new GeneralSecurityException("发行事实摘要损坏");
        }
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(recorded.publicKeySpki()));
        var verified = GrantV1Verification.verify(recorded.envelope(),
                Map.of(recorded.keyId(), key), recorded.issuerId(),
                recorded.deploymentId(), recorded.tenantId());
        approved.requireMatches(verified);
        if (!verified.grantId().equals(recorded.grantId())
                || !verified.tier().equals(recorded.tier())
                || verified.sequence() != recorded.sequence()
                || !verified.issuedAt().equals(recorded.issuedAt())
                || !verified.startsAt().equals(recorded.startsAt())
                || !java.util.Objects.equals(verified.endsAt(), recorded.endsAt())
                || !verified.revisionId().equals(recorded.revisionId())
                || !MessageDigest.isEqual(verified.revisionSha256(), recorded.revisionSha256())) {
            throw new GeneralSecurityException("发行事实与已签文件不符");
        }
    }

    private record Source(UUID requestId, UUID deploymentId, UUID tenantId,
                          byte[] publicKeySha256, byte[] requestSha256,
                          String organizationRef, byte[] evidenceSha256) { }

    private record Review(UUID reviewer, UUID deploymentId, UUID tenantId,
                          byte[] publicKeySha256, byte[] requestSha256,
                          String organizationRef, byte[] evidenceSha256, String tier,
                          String revisionId, byte[] revisionSha256) { }

    public record Issued(UUID grantId, UUID requestId, UUID deploymentId, UUID tenantId,
                         String tier, String revisionId, byte[] revisionSha256,
                         String issuerId, String keyId, byte[] publicKeySpki,
                         byte[] publicKeySha256, long sequence, Instant issuedAt,
                         Instant startsAt, Instant endsAt, byte[] envelope, byte[] envelopeSha256) {
        public Issued {
            revisionSha256 = revisionSha256.clone();
            publicKeySpki = publicKeySpki.clone();
            publicKeySha256 = publicKeySha256.clone();
            envelope = envelope.clone();
            envelopeSha256 = envelopeSha256.clone();
        }
        @Override public byte[] revisionSha256() { return revisionSha256.clone(); }
        @Override public byte[] publicKeySpki() { return publicKeySpki.clone(); }
        @Override public byte[] publicKeySha256() { return publicKeySha256.clone(); }
        @Override public byte[] envelope() { return envelope.clone(); }
        @Override public byte[] envelopeSha256() { return envelopeSha256.clone(); }
        public String envelopeSha256Hex() { return HexFormat.of().formatHex(envelopeSha256); }
    }
}
