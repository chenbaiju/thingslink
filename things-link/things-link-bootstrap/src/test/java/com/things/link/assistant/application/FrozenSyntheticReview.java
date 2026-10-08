package com.things.link.assistant.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 仅供测试白名单类路径的合成审批；不修改发布包裁决，不提供真实供应商资格。 */
public final class FrozenSyntheticReview {
    private static final String RESOURCE = "/com/things/link/assistant/analysis-release-review.json";
    private static final String RESOURCE_SHA = "55225fd4f2ac755e2db727f4e551fc855482cb10ba9e4e1269e5856709fa261d";
    private static final String CANDIDATE = "030929991aa0f2d08fe2945303d1566537c1f3437c7d3410ec66f760a3af2458";
    private static final String COUNTER = "27c279a98839b12b442bd67eceb2739a4e2386684a8049cf4593764f9a23482d";
    private static final String MANIFEST = "6fc27a092c104528af2cd6b0349bcc849c607da366b28a1452ee7e6d5b2fe1cd";
    private static final String SYNTHETIC_PROVIDER = "a".repeat(64);
    private final AnalysisReleaseReview.Approval approval;

    private FrozenSyntheticReview(AnalysisReleaseReview.Approval approval) { this.approval = approval; }

    /** 只在启动时调用一次；资格于原启动窗口一小时后到期，任何读取都不能续期。 */
    public static FrozenSyntheticReview createOnce(Instant now) {
        byte[] original = null, approved = null, attachment = null;
        try (var stream = FrozenSyntheticReview.class.getResourceAsStream(RESOURCE)) {
            require(stream != null);
            original = stream.readNBytes(16 * 1024 + 1);
            require(original.length <= 16 * 1024 && RESOURCE_SHA.equals(sha(original)));
            var json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            var document = (ObjectNode) json.readTree(original);
            require("PENDING".equals(document.path("decision").asString())
                    && CANDIDATE.equals(document.path("executionCandidateSha256").asString())
                    && COUNTER.equals(document.path("counterSha256").asString())
                    && MANIFEST.equals(document.path("evidenceManifestSha256").asString()));
            Instant start = now.truncatedTo(ChronoUnit.SECONDS);
            attachment = "SYNTHETIC_REVIEW_ONLY_NOT_PROVIDER_EVIDENCE".getBytes(StandardCharsets.UTF_8);
            document.put("decision", "APPROVED").put("providerFingerprintSha256", SYNTHETIC_PROVIDER)
                    .put("reviewedAt", start.minusSeconds(5).toString()).put("expiresAt", start.plusSeconds(3600).toString());
            for (var check : document.get("checks")) {
                ((ObjectNode) check).put("decision", "ACCEPTED").put("evidenceSha256", sha(attachment));
            }
            approved = json.writeValueAsBytes(document);
            byte[] fixedAttachment = attachment;
            // 包内纯验证入口，不覆写资源、不反射构造资格，也不读取供应商附件或项目秘密。
            var result = AnalysisReleaseReview.verify(approved, sha(approved), CANDIDATE, COUNTER,
                    MANIFEST, start, id -> fixedAttachment).orElseThrow();
            return new FrozenSyntheticReview(result);
        } catch (Exception ignored) {
            throw new IllegalStateException("INVALID_SYNTHETIC_REVIEW");
        } finally {
            clear(original); clear(approved); clear(attachment);
        }
    }

    /** @return 启动时固定的测试资格，读取不续期 */
    public AnalysisReleaseReview.Approval approval() { return approval; }
    /** @return 包内执行候选摘要 */
    public String candidateSha256() { return CANDIDATE; }
    /** @return 包内计数候选摘要，不认领真实计数质量 */
    public String counterSha256() { return COUNTER; }
    /** @return 明确合成的供应商占位摘要 */
    public String providerFingerprintSha256() { return SYNTHETIC_PROVIDER; }
    /** @return 原启动窗口的固定截止时间 */
    public Instant expiresAt() { return approval.expiresAt; }
    /** @return 仅合成交互验证的资格标签 */
    public String qualification() { return "SYNTHETIC_REVIEWED_INTERACTION"; }
    private static void require(boolean value) { if (!value) throw new IllegalStateException(); }
    private static void clear(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @Override public String toString() { return "合成审查夹具[非供应商批准]"; }
}
