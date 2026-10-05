package com.things.link.assistant.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 仅测试类路径使用的合成批准，不进入生产资源或授予真实出站资格。 */
public final class ReviewedTransportFixture {
    private ReviewedTransportFixture() {}
    public static AnalysisReleaseReview.Approval approval(String counter, String candidate, String provider) {
        try {
            var document = AnalysisReleaseReviewTests.approvedDocument();
            var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            document.put("reviewedAt", now.minusSeconds(10).toString());
            document.put("expiresAt", now.plusSeconds(3600).toString());
            document.put("executionCandidateSha256", candidate);
            document.put("counterSha256", counter);
            document.put("providerFingerprintSha256", provider);
            byte[] raw = AnalysisReleaseReviewTests.JSON.writeValueAsBytes(document);
            return AnalysisReleaseReview.verify(raw, AnalysisReleaseReviewTests.sha(raw), candidate, counter,
                    AnalysisReleaseReviewTests.MANIFEST, now, id -> AnalysisReleaseReviewTests.ATTACHMENT.clone()).orElseThrow();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
