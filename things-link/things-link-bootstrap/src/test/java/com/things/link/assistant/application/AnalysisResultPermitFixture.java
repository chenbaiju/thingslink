package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisContent;
import com.things.link.assistant.domain.AnalysisUsage;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Test-only trusted issuer; never packaged into a production module. */
public final class AnalysisResultPermitFixture {
    public static AnalysisResultPermit issue(AnalysisExecutionContext context) {
        return issue(context, context.call().deadline());
    }
    public static AnalysisResultPermit issue(AnalysisExecutionContext context, Instant expires) {
        return issue(context.call(),expires);
    }
    public static AnalysisResultPermit issue(com.things.link.assistant.domain.AnalysisCall call, Instant expires) {
        var content = new AnalysisContent("synthetic-private-summary",
                List.of(new AnalysisContent.Finding(AnalysisContent.Kind.HYPOTHESIS,
                        "synthetic-private-statement", List.of("e-device"))), List.of("synthetic-limitation"));
        return new AnalysisResultPermit(call, content, new AnalysisUsage(10, 2, 12, 0, 10),
                Set.of("e-device"), expires,"deepseek-flash","thingslink-agent-single-analysis-v1");
    }

    /** 合成独立批准，仅测试类路径使用；没有供应商事实或真实调用授权。 */
    public static AnalysisReleaseReview.Approval review(java.time.Instant now) {
        try {
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            byte[] attachment="synthetic-review-only".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.util.function.Function<byte[],String> sha=bytes->{try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            }catch(Exception e){throw new AssertionError(e);}};
            try(var stream=AnalysisResultPermitFixture.class.getResourceAsStream("/com/things/link/assistant/analysis-release-review.json")) {
                var document=(tools.jackson.databind.node.ObjectNode)json.readTree(stream.readAllBytes());
                now=now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                document.put("decision","APPROVED").put("providerFingerprintSha256","a".repeat(64))
                    .put("reviewedAt",now.minusSeconds(5).toString()).put("expiresAt",now.plusSeconds(3600).toString());
                for(var check:document.get("checks"))((tools.jackson.databind.node.ObjectNode)check)
                    .put("decision","ACCEPTED").put("evidenceSha256",sha.apply(attachment));
                byte[] raw=json.writeValueAsBytes(document);
                return AnalysisReleaseReview.verify(raw,sha.apply(raw),document.get("executionCandidateSha256").asString(),
                    document.get("counterSha256").asString(),document.get("evidenceManifestSha256").asString(),now,id->attachment.clone()).orElseThrow();
            }
        }catch(Exception e){throw new AssertionError(e);}
    }
}
