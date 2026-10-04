package com.things.link.project.application;

import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.entitlement.application.GrantV1Verification;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 已签 V1 授权的可复用原子导入内核。此类不自动装配、不读取环境变量或测试公钥；
 * 调用方负责提供已确权部署身份和来自发行物信任域的公钥。
 */
public final class SelfHostedGrantImportService {
    private final SelfHostedGrantRepository repository;
    private final TransactionTemplate transactions;
    private final TrustedIssuer trustedIssuer;
    private final ApprovedSelfHostedRevision approvedRevision;
    private final Clock clock;

    public SelfHostedGrantImportService(SelfHostedGrantRepository repository,
                                        PlatformTransactionManager transactionManager,
                                        TrustedIssuer trustedIssuer) throws GeneralSecurityException {
        this(repository, transactionManager, trustedIssuer, Clock.systemUTC());
    }

    public SelfHostedGrantImportService(SelfHostedGrantRepository repository,
                                        PlatformTransactionManager transactionManager,
                                        TrustedIssuer trustedIssuer, Clock clock) throws GeneralSecurityException {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.trustedIssuer = Objects.requireNonNull(trustedIssuer, "trustedIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.approvedRevision = ApprovedSelfHostedRevision.loadApproved();
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public ImportResult importGrant(byte[] envelope, Installation identity) throws GeneralSecurityException {
        Objects.requireNonNull(identity, "identity");
        GrantV1Verification.VerifiedGrant verified = verify(envelope, identity);
        requireCurrentTerm(verified);
        byte[] copy = envelope.clone();
        SelfHostedGrantRepository.StoredGrant candidate = new SelfHostedGrantRepository.StoredGrant(
                identity.deploymentId(), identity.tenantId(), identity.deploymentPublicKeySha256(),
                verified.grantId(), verified.sequence(), copy,
                MessageDigest.getInstance("SHA-256").digest(copy),
                verified.quotas().get("DEVICES_MAX"),
                verified.quotas().get("UPLINK_MESSAGE_DAILY"),
                verified.quotas().get("DOWNLINK_MESSAGE_DAILY"));
        ImportResult result = transactions.execute(status -> importLocked(candidate, verified, identity));
        if (result == null) throw new IllegalStateException("自部署授权事务未返回导入结果");
        return result;
    }

    /** 每次从共享数据库重新读取；此验签事实本身不是业务准入或期限状态决策。 */
    public Optional<GrantV1Verification.VerifiedGrant> readCurrent(Installation identity) {
        Objects.requireNonNull(identity, "identity");
        return repository.current().map(current -> requireStoredValid(current, identity));
    }

    private ImportResult importLocked(SelfHostedGrantRepository.StoredGrant candidate,
                                      GrantV1Verification.VerifiedGrant verified,
                                      Installation identity) {
        // 首次导入和注册 INSERT 持有相冲突的表锁，避免数完一个租户后并发注册第二个。
        repository.lockTenantRegistration();
        // 等待数据库锁期间可能跨越授权终点，落库前再核对一次时钟。
        requireCurrentTerm(verified);
        if (!repository.allTenantIds().equals(java.util.List.of(identity.tenantId()))) {
            throw new IllegalStateException("自部署数据库必须且只能有已确权的一个计费租户");
        }
        Optional<SelfHostedGrantRepository.StoredGrant> previous = repository.lockCurrent();
        if (previous.isPresent()) {
            SelfHostedGrantRepository.StoredGrant old = previous.get();
            GrantV1Verification.VerifiedGrant oldVerified = requireStoredValid(old, identity, true);
            if (candidate.sequence() == old.sequence()
                    && Arrays.equals(candidate.envelope(), old.envelope())) {
                // 已发布 0120 的旧实验行在 0130 升级后没有投影；同字节重验签才允许补齐。
                if (!old.hasQuotaProjection()) repository.replace(candidate);
                return new ImportResult(Outcome.ALREADY_IMPORTED, old.grantId(), old.sequence());
            }
            if (candidate.sequence() <= old.sequence()) {
                throw new IllegalStateException("自部署授权序号不是已导入授权的后继");
            }
            // SHC-6a 尚未冻结续期/换档：本地实验只允许重签同一首期，绝不由更高序号重置起点。
            if (!verified.startsAt().equals(oldVerified.startsAt())
                    || !Objects.equals(verified.endsAt(), oldVerified.endsAt())) {
                throw new IllegalStateException("本地实验授权不得改变首期期限");
            }
            if (verified.issuedAt().isBefore(oldVerified.issuedAt())) {
                throw new IllegalStateException("后继授权签发时间不能早于当前授权");
            }
            repository.replace(candidate);
        } else {
            requireFirstTerm(verified);
            repository.insert(candidate);
        }
        repository.appendImportAudit(candidate);
        return new ImportResult(Outcome.IMPORTED, candidate.grantId(), candidate.sequence());
    }

    private GrantV1Verification.VerifiedGrant requireStoredValid(
            SelfHostedGrantRepository.StoredGrant stored, Installation identity) {
        return requireStoredValid(stored, identity, false);
    }

    private GrantV1Verification.VerifiedGrant requireStoredValid(
            SelfHostedGrantRepository.StoredGrant stored, Installation identity,
            boolean allowLegacyMissingProjection) {
        try {
            if (!identity.deploymentId().equals(stored.deploymentId())
                    || !identity.tenantId().equals(stored.tenantId())
                    || !MessageDigest.isEqual(identity.deploymentPublicKeySha256(),
                    stored.deploymentPublicKeySha256())
                    || !MessageDigest.isEqual(stored.envelopeSha256(),
                    MessageDigest.getInstance("SHA-256").digest(stored.envelope()))) {
                throw new GeneralSecurityException("已导入授权的持久身份或摘要不一致");
            }
            GrantV1Verification.VerifiedGrant grant = verify(stored.envelope(), identity);
            if (grant.sequence() != stored.sequence() || !grant.grantId().equals(stored.grantId())) {
                throw new GeneralSecurityException("已导入授权的持久投影不一致");
            }
            if (stored.hasQuotaProjection()) {
                if (!Objects.equals(stored.devicesMax(), grant.quotas().get("DEVICES_MAX"))
                        || !Objects.equals(stored.uplinkMessageDaily(), grant.quotas().get("UPLINK_MESSAGE_DAILY"))
                        || !Objects.equals(stored.downlinkMessageDaily(), grant.quotas().get("DOWNLINK_MESSAGE_DAILY"))) {
                    throw new GeneralSecurityException("已导入授权的额度投影与签名封套不一致");
                }
            } else if (!allowLegacyMissingProjection) {
                throw new GeneralSecurityException("已导入授权的额度投影缺失，须重新导入同一封套");
            }
            return grant;
        } catch (GeneralSecurityException invalid) {
            throw new IllegalStateException("已导入授权损坏，拒绝继续使用或覆盖", invalid);
        }
    }

    private GrantV1Verification.VerifiedGrant verify(byte[] envelope, Installation identity)
            throws GeneralSecurityException {
        GrantV1Verification.VerifiedGrant verified = GrantV1Verification.verify(
                envelope, trustedIssuer.keys(), trustedIssuer.issuerId(),
                identity.deploymentId(), identity.tenantId());
        approvedRevision.requireMatches(verified);
        return verified;
    }

    private static void requireFirstTerm(GrantV1Verification.VerifiedGrant candidate) {
        if (!candidate.issuedAt().equals(candidate.startsAt())) {
            throw new IllegalStateException("首份授权起点必须等于发行方签发时刻");
        }
        Instant end = candidate.endsAt();
        if ("FREE".equals(candidate.tier())) {
            if (end != null) throw new IllegalStateException("FREE 授权不能有终点");
        } else if (end == null || !end.equals(candidate.startsAt().atOffset(ZoneOffset.UTC)
                .plusYears(1).toInstant())) {
            throw new IllegalStateException("首份付费授权必须按 UTC 公历周年到期");
        }
    }

    private void requireCurrentTerm(GrantV1Verification.VerifiedGrant candidate) {
        Instant now = clock.instant();
        if (now.isBefore(candidate.issuedAt()) || now.isBefore(candidate.startsAt())
                || candidate.endsAt() != null && !now.isBefore(candidate.endsAt())) {
            throw new IllegalStateException("授权尚未生效或已过期，不能导入本地实验运行时");
        }
    }

    public record Installation(UUID deploymentId, UUID tenantId, byte[] deploymentPublicKeySha256) {
        public Installation {
            Objects.requireNonNull(deploymentId, "deploymentId");
            Objects.requireNonNull(tenantId, "tenantId");
            if (deploymentPublicKeySha256 == null || deploymentPublicKeySha256.length != 32) {
                throw new IllegalArgumentException("部署身份公钥摘要必须为 32 字节");
            }
            deploymentPublicKeySha256 = deploymentPublicKeySha256.clone();
            if (deploymentId.equals(new UUID(0, 0)) || tenantId.equals(new UUID(0, 0))) {
                throw new IllegalArgumentException("自部署身份不能是空 UUID");
            }
        }

        @Override public byte[] deploymentPublicKeySha256() { return deploymentPublicKeySha256.clone(); }
    }

    /** 信任根由发行物装配方提供，不从请求、数据库、环境变量或本模块资源中取得。 */
    public record TrustedIssuer(String issuerId, Map<String, PublicKey> keys) {
        public TrustedIssuer {
            if (issuerId == null || issuerId.isBlank() || keys == null || keys.isEmpty()
                    || keys.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("自部署发行方信任根无效");
            }
            keys = Map.copyOf(keys);
        }
    }

    public enum Outcome { IMPORTED, ALREADY_IMPORTED }

    public record ImportResult(Outcome outcome, UUID grantId, long sequence) { }
}
