package com.things.link.issuer.application;

import com.things.link.entitlement.GrantV1;
import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.entitlement.application.GrantV1Verification;
import com.things.link.issuer.application.EnrollmentReviewRepository.Attestation;
import com.things.link.issuer.application.EnrollmentReviewRepository.RequestFact;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 只供源码树本地验收的测试发行服务。无 HTTP 入口、无密钥加载器、无生产 Bean；
 * 调用方提供仅在本机测试进程持有的签名函数和公钥。
 */
@Service
@Profile("shc-lab")
public class LocalTestGrantIssuer {
    public static final String TEST_ISSUER = "thingslink-local-test";
    private final EnrollmentReviewRepository reviews;
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;
    private final ApprovedSelfHostedRevision revision;

    public LocalTestGrantIssuer(EnrollmentReviewRepository reviews, JdbcTemplate jdbc,
                                AuditLogService audit) throws GeneralSecurityException {
        this.reviews = reviews;
        this.jdbc = jdbc;
        this.audit = audit;
        this.revision = ApprovedSelfHostedRevision.loadApproved();
    }

    @FunctionalInterface
    public interface EphemeralSigner {
        byte[] sign(GrantV1 grant) throws GeneralSecurityException;
    }

    /** 同一 grantId 重试返回持久原字节；不同 grantId 对同一部署分配更高序号。 */
    @Transactional(rollbackFor = Exception.class)
    public Issued issue(UUID requestId, UUID grantId, PublicKey testPublicKey,
                        EphemeralSigner signer) throws GeneralSecurityException {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(grantId, "grantId");
        Objects.requireNonNull(signer, "signer");
        if (!(testPublicKey instanceof EdECPublicKey edKey)
                || !"Ed25519".equals(edKey.getParams().getName())
                || testPublicKey.getEncoded() == null) {
            throw new GeneralSecurityException("仅允许临时 Ed25519 测试公钥");
        }
        byte[] spki = testPublicKey.getEncoded();
        byte[] keyDigest = sha256(spki);
        String keyId = "local-test." + HexFormat.of().formatHex(keyDigest, 0, 8);

        // 申请行锁同时串行化同一部署的序号分配。重试必须在拿锁后再次读取，
        // 否则并发相同 grantId 会被误判为新的签发。
        RequestFact request = reviews.requestForUpdate(requestId);
        if (request == null || !"PENDING".equals(request.status())) {
            throw new GeneralSecurityException("测试发行需要已登记待审申请");
        }
        Issued prior = find(grantId);
        if (prior != null) {
            if (!prior.requestId().equals(requestId)
                    || !MessageDigest.isEqual(prior.testPublicKeySha256(), keyDigest)) {
                throw new GeneralSecurityException("测试签发幂等键与原事实冲突");
            }
            return validateRecorded(prior, request);
        }
        List<Attestation> facts = reviews.attestations(requestId);
        Attestation first = requireTwoMatchingTestFacts(request, facts);
        ApprovedSelfHostedRevision.Tier tier = revision.tier(first.tier());
        Issued latest = latest(request.deploymentId());
        if (latest != null) {
            validateRecorded(latest, request);
            if (!latest.tier().equals(first.tier())) {
                throw new GeneralSecurityException("测试续发尚不支持未经冻结的换档期限");
            }
        }
        long previous = latest == null ? 0 : latest.sequence();
        if (previous == Long.MAX_VALUE) {
            throw new GeneralSecurityException("测试签发序号不可分配");
        }
        long sequence = previous + 1;
        Timestamp databaseTime = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        if (databaseTime == null) throw new GeneralSecurityException("发行方数据库时钟不可用");
        Instant now = databaseTime.toInstant();
        if (latest != null && now.isBefore(latest.issuedAt())) {
            throw new GeneralSecurityException("发行方时钟早于上一测试签发事实");
        }
        Instant startsAt = latest == null ? now : latest.startsAt();
        Instant endsAt = latest == null
                ? (first.tier().equals("FREE") ? null
                    : now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant())
                : latest.endsAt();
        GrantV1 grant = new GrantV1(TEST_ISSUER, grantId, request.deploymentId(),
                request.tenantId(), first.tier(), revision.revisionId(), revision.sha256(),
                sequence, now, startsAt, endsAt, keyId, tier.quotas(), tier.capabilities());
        byte[] envelope = Objects.requireNonNull(signer.sign(grant), "signed envelope");
        GrantV1Verification.VerifiedGrant verified = GrantV1Verification.verify(envelope,
                Map.of(keyId, testPublicKey), TEST_ISSUER, request.deploymentId(), request.tenantId());
        revision.requireMatches(verified);
        if (!verified.grantId().equals(grantId) || verified.sequence() != sequence
                || !verified.keyId().equals(keyId) || !verified.issuedAt().equals(now)
                || !verified.startsAt().equals(startsAt)
                || !Objects.equals(verified.endsAt(), endsAt)) {
            throw new GeneralSecurityException("测试签名器返回了不匹配的授权");
        }
        byte[] envelopeDigest = sha256(envelope);
        jdbc.update("""
                INSERT INTO public.sys_shc_lab_issuance
                    (grant_id, request_id, deployment_id, tenant_id, issuer_id, key_id,
                     test_public_key_spki, test_public_key_sha256, request_sha256,
                     organization_ref, evidence_sha256, tier, revision_id, revision_sha256,
                     sequence_no, issued_at, starts_at, ends_at, envelope, envelope_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, grantId, requestId, request.deploymentId(), request.tenantId(),
                TEST_ISSUER, keyId, spki, keyDigest, request.requestSha256(),
                first.organizationReference(), first.evidenceSha256(), first.tier(),
                revision.revisionId(), revision.sha256(), sequence, Timestamp.from(now),
                Timestamp.from(startsAt), endsAt == null ? null : Timestamp.from(endsAt),
                envelope, envelopeDigest);
        audit.record(new AuditLogEntry(null, null, null, "shc_lab_issuance", grantId,
                "shc.lab.grant.issued", Map.of("requestId", requestId.toString(),
                "deploymentId", request.deploymentId().toString(), "tier", first.tier(),
                "sequence", sequence, "envelopeSha256", HexFormat.of().formatHex(envelopeDigest))));
        return new Issued(grantId, requestId, request.deploymentId(), request.tenantId(),
                first.tier(), sequence, now, startsAt, endsAt, keyId, spki, keyDigest,
                request.requestSha256(), envelope,
                envelopeDigest);
    }

    /**
     * 调用方丢失临时私钥后，仍可按原申请和幂等键读回已提交的签名原字节与公钥。
     * 查无记录表示上次事务未提交；不能凭此方法创造新授权或猜测签发成功。
     */
    @Transactional(readOnly = true)
    public Optional<Issued> recover(UUID requestId, UUID grantId) throws GeneralSecurityException {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(grantId, "grantId");
        Issued recorded = find(grantId);
        if (recorded == null) return Optional.empty();
        if (!recorded.requestId().equals(requestId)) {
            throw new GeneralSecurityException("测试签发恢复申请与原事实不符");
        }
        RequestFact request = reviews.request(requestId);
        if (request == null || !"PENDING".equals(request.status())) {
            throw new GeneralSecurityException("测试签发恢复缺少原申请");
        }
        return Optional.of(validateRecorded(recorded, request));
    }

    private Issued validateRecorded(Issued recorded, RequestFact request)
            throws GeneralSecurityException {
        if (!recorded.requestId().equals(request.requestId())
                || !recorded.deploymentId().equals(request.deploymentId())
                || !recorded.tenantId().equals(request.tenantId())
                || !MessageDigest.isEqual(recorded.requestSha256(), request.requestSha256())
                || !MessageDigest.isEqual(sha256(recorded.envelope()), recorded.envelopeSha256())
                || !MessageDigest.isEqual(sha256(recorded.testPublicKeySpki()),
                        recorded.testPublicKeySha256())) {
            throw new GeneralSecurityException("测试签发持久事实与申请或摘要不符");
        }
        String expectedKeyId = "local-test." + HexFormat.of().formatHex(
                recorded.testPublicKeySha256(), 0, 8);
        if (!expectedKeyId.equals(recorded.keyId())) {
            throw new GeneralSecurityException("测试签发持久密钥标识不符");
        }
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(recorded.testPublicKeySpki()));
        if (!MessageDigest.isEqual(key.getEncoded(), recorded.testPublicKeySpki())) {
            throw new GeneralSecurityException("测试签发公钥不是规范编码");
        }
        GrantV1Verification.VerifiedGrant verified = GrantV1Verification.verify(recorded.envelope(),
                Map.of(recorded.keyId(), key), TEST_ISSUER, recorded.deploymentId(),
                recorded.tenantId());
        revision.requireMatches(verified);
        if (!verified.grantId().equals(recorded.grantId())
                || !verified.keyId().equals(recorded.keyId())
                || !verified.tier().equals(recorded.tier())
                || verified.sequence() != recorded.sequence()
                || !verified.issuedAt().equals(recorded.issuedAt())
                || !verified.startsAt().equals(recorded.startsAt())
                || !Objects.equals(verified.endsAt(), recorded.endsAt())) {
            throw new GeneralSecurityException("测试签发封套与持久发行事实不符");
        }
        return recorded;
    }

    private Issued find(UUID grantId) {
        List<Issued> rows = jdbc.query("""
                SELECT grant_id, request_id, deployment_id, tenant_id, tier, sequence_no,
                       issued_at, starts_at, ends_at, key_id, test_public_key_spki,
                       test_public_key_sha256, request_sha256, envelope, envelope_sha256
                  FROM public.sys_shc_lab_issuance WHERE grant_id = ?
                """, LocalTestGrantIssuer::map, grantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Issued latest(UUID deploymentId) {
        List<Issued> rows = jdbc.query("""
                SELECT grant_id, request_id, deployment_id, tenant_id, tier, sequence_no,
                       issued_at, starts_at, ends_at, key_id, test_public_key_spki,
                       test_public_key_sha256, request_sha256, envelope, envelope_sha256
                  FROM public.sys_shc_lab_issuance WHERE deployment_id = ?
                 ORDER BY sequence_no DESC LIMIT 1
                """, LocalTestGrantIssuer::map, deploymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Issued map(ResultSet rs, int ignored) throws SQLException {
        var end = rs.getTimestamp("ends_at");
        return new Issued(rs.getObject("grant_id", UUID.class),
                rs.getObject("request_id", UUID.class), rs.getObject("deployment_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getString("tier"), rs.getLong("sequence_no"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("starts_at").toInstant(), end == null ? null : end.toInstant(),
                rs.getString("key_id"), rs.getBytes("test_public_key_spki"),
                rs.getBytes("test_public_key_sha256"), rs.getBytes("request_sha256"),
                rs.getBytes("envelope"),
                rs.getBytes("envelope_sha256"));
    }

    private Attestation requireTwoMatchingTestFacts(RequestFact request, List<Attestation> facts)
            throws GeneralSecurityException {
        if (facts.size() != 2 || facts.get(0).reviewerAccountId().equals(facts.get(1).reviewerAccountId())) {
            throw new GeneralSecurityException("测试签发需要两名独立审核者的一致证明");
        }
        Attestation first = facts.get(0);
        for (Attestation fact : facts) {
            if (!fact.requestId().equals(request.requestId())
                    || !fact.deploymentId().equals(request.deploymentId())
                    || !fact.tenantId().equals(request.tenantId())
                    || !MessageDigest.isEqual(fact.publicKeySha256(), request.publicKeySha256())
                    || !MessageDigest.isEqual(fact.requestSha256(), request.requestSha256())
                    || !fact.organizationReference().startsWith("local-test:")
                    || !fact.organizationReference().equals(first.organizationReference())
                    || !MessageDigest.isEqual(fact.evidenceSha256(), first.evidenceSha256())
                    || !fact.tier().equals(first.tier())
                    || !fact.revisionId().equals(revision.revisionId())
                    || !MessageDigest.isEqual(fact.revisionSha256(), revision.sha256())) {
                throw new GeneralSecurityException("测试审核证明与申请或修订不一致");
            }
        }
        return first;
    }

    private static byte[] sha256(byte[] value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    public record Issued(UUID grantId, UUID requestId, UUID deploymentId, UUID tenantId,
                         String tier, long sequence, Instant issuedAt, Instant startsAt,
                         Instant endsAt,
                         String keyId, byte[] testPublicKeySpki, byte[] testPublicKeySha256,
                         byte[] requestSha256, byte[] envelope, byte[] envelopeSha256) {
        public Issued {
            testPublicKeySpki = testPublicKeySpki.clone();
            testPublicKeySha256 = testPublicKeySha256.clone();
            requestSha256 = requestSha256.clone();
            envelope = envelope.clone();
            envelopeSha256 = envelopeSha256.clone();
        }
        @Override public byte[] testPublicKeySpki() { return testPublicKeySpki.clone(); }
        @Override public byte[] testPublicKeySha256() { return testPublicKeySha256.clone(); }
        @Override public byte[] requestSha256() { return requestSha256.clone(); }
        @Override public byte[] envelope() { return envelope.clone(); }
        @Override public byte[] envelopeSha256() { return envelopeSha256.clone(); }
    }
}
