package com.things.link.issuer.application;

import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.issuer.application.EnrollmentReviewRepository.Attestation;
import com.things.link.issuer.application.EnrollmentReviewRepository.RequestFact;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一名授权审核者留下必要核验；第二名可选，但必须与首份事实一致。 */
@Service
public class EnrollmentReviewService {
    private final ShcReviewerAccessService reviewers;
    private final EnrollmentReviewRepository repository;
    private final AuditLogService audit;
    private final ApprovedSelfHostedRevision revision;

    public EnrollmentReviewService(ShcReviewerAccessService reviewers,
                                   EnrollmentReviewRepository repository, AuditLogService audit)
            throws GeneralSecurityException {
        this.reviewers = reviewers;
        this.repository = repository;
        this.audit = audit;
        this.revision = ApprovedSelfHostedRevision.loadApproved();
    }

    @Transactional
    public ReviewDetail detail(UUID requestId) {
        UUID actor = reviewers.requireReviewer();
        RequestFact request = requireRequest(repository.request(requestId));
        List<Attestation> existing = repository.attestations(requestId);
        ensureConsistent(request, existing);
        audit.record(new AuditLogEntry(null, null, actor, "shc_enrollment_review", requestId,
                "shc.enrollment.review.viewed", Map.of("attestationCount", existing.size())));
        return new ReviewDetail(request.requestId(), request.deploymentId(), request.tenantId(),
                HexFormat.of().formatHex(request.publicKeySha256()),
                HexFormat.of().formatHex(request.requestSha256()), revision.revisionId(),
                HexFormat.of().formatHex(revision.sha256()), existing.size());
    }

    @Transactional
    public ReviewProgress attest(UUID requestId, ReviewInput input) {
        UUID actor = reviewers.requireReviewer();
        if (requestId == null || input == null || input.deploymentId() == null
                || input.tenantId() == null || input.tier() == null
                || !revision.revisionId().equals(input.revisionId())
                || input.organizationReference() == null
                || !input.organizationReference().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException("核验输入无效");
        }
        byte[] expectedPublic = parseDigest(input.publicKeySha256());
        byte[] expectedRequest = parseDigest(input.requestSha256());
        byte[] evidenceDigest = parseDigest(input.evidenceSha256());
        if (!MessageDigest.isEqual(revision.sha256(), parseDigest(input.revisionSha256()))) {
            throw new EnrollmentReviewConflictException();
        }
        try {
            revision.tier(input.tier());
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("套餐档位无效", invalid);
        }
        RequestFact request = requireRequest(repository.requestForUpdate(requestId));
        if (!"PENDING".equals(request.status())
                || !request.deploymentId().equals(input.deploymentId())
                || !request.tenantId().equals(input.tenantId())
                || !MessageDigest.isEqual(request.publicKeySha256(), expectedPublic)
                || !MessageDigest.isEqual(request.requestSha256(), expectedRequest)) {
            throw new EnrollmentReviewConflictException();
        }
        Attestation proposed = new Attestation(requestId, actor, request.deploymentId(), request.tenantId(),
                request.publicKeySha256(), request.requestSha256(), input.organizationReference(),
                evidenceDigest, input.tier(), revision.revisionId(), revision.sha256(), Instant.now());
        List<Attestation> existing = repository.attestations(requestId);
        ensureConsistent(request, existing);
        Attestation own = existing.stream().filter(row -> row.reviewerAccountId().equals(actor))
                .findFirst().orElse(null);
        if (own != null) {
            if (!sameDecision(own, proposed)) throw new EnrollmentReviewConflictException();
        } else {
            if (existing.size() >= 2 || existing.stream().anyMatch(row -> !sameDecision(row, proposed))) {
                throw new EnrollmentReviewConflictException();
            }
            repository.insert(proposed);
        }
        int count = existing.size() + (own == null ? 1 : 0);
        audit.record(new AuditLogEntry(null, null, actor, "shc_enrollment_review", requestId,
                "shc.enrollment.review.attested", Map.of(
                "attestationCount", count, "tier", input.tier(), "replay", own != null)));
        return new ReviewProgress(requestId, count, count >= 1);
    }

    private void ensureConsistent(RequestFact request, List<Attestation> existing) {
        if (existing.size() > 2) throw new EnrollmentReviewConflictException();
        for (Attestation row : existing) {
            if (!row.requestId().equals(request.requestId())
                    || !row.deploymentId().equals(request.deploymentId())
                    || !row.tenantId().equals(request.tenantId())
                    || !MessageDigest.isEqual(row.publicKeySha256(), request.publicKeySha256())
                    || !MessageDigest.isEqual(row.requestSha256(), request.requestSha256())
                    || !row.revisionId().equals(revision.revisionId())
                    || !MessageDigest.isEqual(row.revisionSha256(), revision.sha256())) {
                throw new EnrollmentReviewConflictException();
            }
        }
        if (existing.size() == 2 && !sameDecision(existing.get(0), existing.get(1))) {
            throw new EnrollmentReviewConflictException();
        }
    }

    private static RequestFact requireRequest(RequestFact request) {
        if (request == null) throw new EnrollmentReviewNotFoundException();
        return request;
    }

    private static boolean sameDecision(Attestation left, Attestation right) {
        return left.deploymentId().equals(right.deploymentId())
                && left.tenantId().equals(right.tenantId())
                && MessageDigest.isEqual(left.publicKeySha256(), right.publicKeySha256())
                && MessageDigest.isEqual(left.requestSha256(), right.requestSha256())
                && left.organizationReference().equals(right.organizationReference())
                && MessageDigest.isEqual(left.evidenceSha256(), right.evidenceSha256())
                && left.tier().equals(right.tier())
                && left.revisionId().equals(right.revisionId())
                && MessageDigest.isEqual(left.revisionSha256(), right.revisionSha256());
    }

    private static byte[] parseDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("摘要必须为 64 位小写十六进制");
        }
        return HexFormat.of().parseHex(value);
    }

    public record ReviewInput(UUID deploymentId, UUID tenantId, String publicKeySha256,
                              String requestSha256, String organizationReference,
                              String evidenceSha256, String tier, String revisionId,
                              String revisionSha256) { }

    public record ReviewDetail(UUID requestId, UUID deploymentId, UUID claimedTenantId,
                               String publicKeySha256, String requestSha256,
                               String revisionId, String revisionSha256, int attestationCount) { }

    public record ReviewProgress(UUID requestId, int attestationCount,
                                 boolean readyForIssuanceReview) { }
}
