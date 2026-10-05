package com.things.link.assistant.application;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 随发布包固定的独立审查裁决；材料完整不自动变成批准，不读取环境开关或调用方路径。 */
public final class AnalysisReleaseReview {
    private static final String RESOURCE = "/com/things/link/assistant/analysis-release-review.json";
    private static final String RESOURCE_SHA256 = "c945bc81b430484ba0aa5bff877b7f6cb178e7b264f5095d03c51bc59a30ce6b";
    private static final String MATERIAL_MANIFEST = "a1469ccb2412beda70e938852bb4eafd431f3efed91684be467a29b79361444a";
    private static final String REQUEST_CONTRACT = "10f0d15475b1beb590311b2d0c0ed3da883226419353360256e22df74243c4e2";
    private static final Map<String, String> ATTACHMENTS = Map.of(
            "ONLINE_COUNTER_CONTRACT", "/com/things/link/assistant/review/online-counter.receipt",
            "SUPPLIER_DATA_HANDLING", "/com/things/link/assistant/review/supplier-data-handling.receipt");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS = Set.of("version", "decision", "model", "modelVersion", "promptVersion",
            "executionCandidateSha256", "counterSha256", "requestContractSha256", "evidenceManifestSha256",
            "providerFingerprintSha256", "reviewedAt", "expiresAt", "checks");
    private AnalysisReleaseReview() {}

    /** 只有固定裁决校验器能创建的资格；不包含当前项目权限或付费测试授权。 */
    public static final class Approval {
        final String reviewSha256, candidateSha256, counterSha256, manifestSha256, providerFingerprintSha256;
        final Instant reviewedAt, expiresAt;
        private Approval(String review, String candidate, String counter, String manifest, String provider,
                Instant start, Instant end) {
            reviewSha256 = review; candidateSha256 = candidate; counterSha256 = counter;
            manifestSha256 = manifest; providerFingerprintSha256 = provider; reviewedAt = start; expiresAt = end;
        }
        /** @return 固定裁决摘要，仅用于已受认证内部通道的本次绑定，不是独立授权令牌 */
        public String reviewSha256() { return reviewSha256; }
        /** @return 此刻是否仍在原审查窗口内；状态提示不能替代实际签发时的原期限复核 */
        public boolean isCurrent() { return validAt(Instant.now()); }
        /** 当前时间仍在原审查窗口内才有效，不因创建对象而延长有效期。 */
        boolean validAt(Instant now) { return now != null && !now.isBefore(reviewedAt) && now.isBefore(expiresAt); }
        @Override public String toString() { return "分析审查资格[内容已隐藏]"; }
    }

    /**
     * 从固定包内资源读取裁决并核对服务端预期；待审或拒绝返回空，不得当作可用。
     * @param candidate 实际装配的执行候选摘要
     * @param counter 实际装配的计数候选摘要
     * @param manifest 实际装配的材料清单摘要，不从内部响应取期望
     * @param now 当前受控时钟
     * @return 仅明确批准且附件及绑定全部通过时返回不透明资格
     */
    public static Optional<Approval> load(String candidate, String counter, String manifest, Instant now) {
        return verify(readResource(RESOURCE, 16 * 1024), RESOURCE_SHA256, candidate, counter, manifest, now,
                id -> readResource(ATTACHMENTS.get(id), 64 * 1024));
    }

    /**
     * 内部通道读取与当前发布成对的材料清单；待审或配置不匹配时只保留离线拒绝路径。
     * @param candidate 服务端已冻结执行候选
     * @param counter 服务端已冻结计数候选
     * @return 当前有效的审查资格；固定资源损坏也不提升资格
     */
    public static Optional<Approval> forTransport(String candidate, String counter) {
        try { return load(candidate, counter, MATERIAL_MANIFEST, Instant.now()); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    /** 包内纯验证入口供合成反例使用；生产只能从固定摘要和固定附件路径调用。 */
    static Optional<Approval> verify(byte[] raw, String expectedSha, String candidate, String counter,
            String manifest, Instant now, Function<String, byte[]> attachments) {
        try {
            require(raw != null && raw.length > 0 && raw.length <= 16 * 1024 && digest(expectedSha)
                    && expectedSha.equals(sha(raw)) && digest(candidate) && digest(counter) && digest(manifest)
                    && now != null && attachments != null);
            JsonNode body = JSON.readTree(raw);
            fields(body, FIELDS);
            require("agent-release-review-v1".equals(text(body, "version"))
                    && "deepseek-flash".equals(text(body, "model"))
                    && "DeepSeek-V4.1-Flash".equals(text(body, "modelVersion"))
                    && "thingslink-agent-single-analysis-v1".equals(text(body, "promptVersion"))
                    && candidate.equals(text(body, "executionCandidateSha256"))
                    && counter.equals(text(body, "counterSha256"))
                    && REQUEST_CONTRACT.equals(text(body, "requestContractSha256"))
                    && manifest.equals(text(body, "evidenceManifestSha256")));
            Instant start = utc(text(body, "reviewedAt")), end = utc(text(body, "expiresAt"));
            require(start.isBefore(end) && Duration.between(start, end).compareTo(Duration.ofDays(7)) <= 0
                    && !now.isBefore(start) && now.isBefore(end));
            String decision = text(body, "decision");
            require(Set.of("PENDING", "APPROVED", "REJECTED").contains(decision));
            JsonNode checks = body.get("checks");
            require(checks.isArray() && checks.size() == ATTACHMENTS.size());
            var seen = new java.util.HashSet<String>();
            boolean accepted = true;
            for (JsonNode check : checks) {
                fields(check, Set.of("checkId", "decision", "evidenceSha256"));
                String id = text(check, "checkId"), state = text(check, "decision");
                require(ATTACHMENTS.containsKey(id) && seen.add(id)
                        && Set.of("PENDING", "ACCEPTED", "REJECTED").contains(state));
                if ("ACCEPTED".equals(state)) {
                    String hash = text(check, "evidenceSha256");
                    require(digest(hash));
                    byte[] attachment = attachments.apply(id);
                    require(attachment != null && attachment.length > 0 && attachment.length <= 64 * 1024
                            && hash.equals(sha(attachment)));
                } else {
                    require(check.get("evidenceSha256").isNull());
                    accepted = false;
                }
            }
            JsonNode provider = body.get("providerFingerprintSha256");
            require(provider.isNull() || (provider.isString() && digest(provider.asString())));
            if (!"APPROVED".equals(decision)) return Optional.empty();
            require(accepted && provider.isString());
            return Optional.of(new Approval(expectedSha, candidate, counter, manifest, provider.asString(), start, end));
        } catch (Exception ignored) {
            // 不保留材料内容、路径、摘要或解析异常作为错误原因。
            throw invalid();
        }
    }

    /** 只读取固定资源并限制大小，缺失不回退到网络或环境变量。 */
    private static byte[] readResource(String path, int maximum) {
        try (InputStream stream = AnalysisReleaseReview.class.getResourceAsStream(path)) {
            if (stream == null) throw invalid();
            byte[] raw = stream.readNBytes(maximum + 1);
            if (raw.length > maximum) throw invalid();
            return raw;
        } catch (Exception ignored) { throw invalid(); }
    }
    /** 仅接受完整秒的标准协调世界时，禁止容错归一无效日期。 */
    private static Instant utc(String value) {
        require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"));
        Instant parsed = Instant.parse(value);
        require(parsed.toString().equals(value));
        return parsed;
    }
    private static String text(JsonNode object, String name) {
        JsonNode node = object.get(name);
        require(node != null && node.isString() && !node.asString().isEmpty());
        return node.asString();
    }
    private static void fields(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject() && value.propertyNames().equals(expected));
    }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private static void require(boolean value) { if (!value) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_ANALYSIS_RELEASE_REVIEW"); }
}
