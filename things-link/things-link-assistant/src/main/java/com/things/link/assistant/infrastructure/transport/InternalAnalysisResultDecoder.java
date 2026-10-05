package com.things.link.assistant.infrastructure.transport;

import com.things.link.assistant.domain.AnalysisContent;
import com.things.link.assistant.domain.AnalysisContent.Finding;
import com.things.link.assistant.domain.AnalysisContent.Kind;
import com.things.link.assistant.domain.AnalysisUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 内部结果的闭合解码；不认证用户、不装配网络组件、不授予业务成功资格。 */
public final class InternalAnalysisResultDecoder {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS = Set.of("version", "callId", "configurationRevision", "inputSha256",
            "requestSha256", "counterSha256", "executionCandidateSha256", "model", "promptVersion", "qualification", "status", "rejectionCode",
            "providerFingerprintSha256", "usage", "content");
    private static final Set<String> ERRORS = Set.of("INVALID_MODEL_RESPONSE", "MODEL_RESPONSE_TOO_LARGE",
            "INPUT_COUNT_MISMATCH", "OUTPUT_TRUNCATED", "RESULT_TOO_LARGE", "INVALID_RESULT_JSON",
            "INVALID_RESULT_STRUCTURE", "INVALID_EVIDENCE_REFERENCES");
    private InternalAnalysisResultDecoder() {}

    /** 内部回执只用于后续编排；资格保持未准入，默认打印隐藏内容和关联摘要。 */
    public record Result(String requestSha256, String counterSha256, String executionCandidateSha256, String status, String rejectionCode,
            String providerFingerprintSha256, AnalysisUsage usage, AnalysisContent content, String qualification, String releaseReviewSha256) {
        @Override public String toString() { return "内部分析回执[未准入," + status + "]"; }
    }

    /**
     * 对受认证内部通道的字节重验调用、配置、输入及计数候选绑定。
     * @param raw 最多二十四千字节的内部JSON报文，不是供应商原始响应
     * @param callId 当前调用的原始标识，不从响应建立权限
     * @param revision 当前调用绑定的配置版本
     * @param inputSha256 实际发送的原受控证据摘要
     * @param counterSha256 受审发布候选的计数指纹，不能信任响应自报值
     * @param executionCandidateSha256 固定发布配置中的执行源码及依赖指纹，不从回执获取期望值
     * @param requestSha256 Java按固定提示词和原证据字节独立重建的完整模型请求摘要
     * @param evidenceIds 本次投影产生的引用允许集
     * @return 不可变未准入回执；不能直接映射为业务成功
     */
    public static Result decode(byte[] raw, UUID callId, long revision, String inputSha256,
            String counterSha256, String executionCandidateSha256, String requestSha256, Set<String> evidenceIds) {
        return decode(raw, callId, revision, inputSha256, counterSha256, executionCandidateSha256, requestSha256, evidenceIds, null);
    }

    /**
     * 本次持有独立审查资格时才允许受审回执；参数不替代实际签发者的资格核验。
     * @param raw 受认证内部报文字节
     * @param callId 原调用标识
     * @param revision 原配置版本
     * @param inputSha256 实际输入独立摘要
     * @param counterSha256 固定计数候选
     * @param executionCandidateSha256 固定执行候选
     * @param requestSha256 完整模型请求独立摘要
     * @param evidenceIds 本次引用允许集
     * @param reviewSha256 已获审查资格的固定裁决摘要；为空只接受离线分支
     * @return 闭合解码结果，仍须独立签发及最终确权
     */
    public static Result decode(byte[] raw, UUID callId, long revision, String inputSha256,
            String counterSha256, String executionCandidateSha256, String requestSha256, Set<String> evidenceIds,
            String reviewSha256) {
        Result result = null;
        try {
            require(raw != null && raw.length > 0 && raw.length <= 24 * 1024);
            require(callId != null && callId.version() == 7 && callId.variant() == 2 && revision > 0);
            require(digest(inputSha256) && digest(counterSha256) && digest(executionCandidateSha256) && digest(requestSha256) && evidenceIds != null
                    && evidenceIds.stream().allMatch(id -> id != null && !id.isEmpty()));
            JsonNode body = JSON.readTree(raw);
            boolean reviewed = reviewSha256 != null;
            var expectedFields = new java.util.HashSet<>(FIELDS);
            if (reviewed) { require(digest(reviewSha256)); expectedFields.add("releaseReviewSha256"); }
            fields(body, expectedFields);
            require((reviewed ? "agent-internal-analysis-result-v3" : "agent-internal-analysis-result-v2").equals(text(body.get("version"))));
            if (reviewed) require(reviewSha256.equals(text(body.get("releaseReviewSha256"))));
            require(callId.toString().equals(text(body.get("callId"))));
            require(body.get("configurationRevision").isIntegralNumber()
                    && body.get("configurationRevision").canConvertToLong()
                    && body.get("configurationRevision").asLong() == revision);
            require(inputSha256.equals(text(body.get("inputSha256")))
                    && counterSha256.equals(text(body.get("counterSha256")))
                    && executionCandidateSha256.equals(text(body.get("executionCandidateSha256"))));
            String request = text(body.get("requestSha256"));
            require(requestSha256.equals(request));
            require("deepseek-flash".equals(text(body.get("model")))
                    && "thingslink-agent-single-analysis-v1".equals(text(body.get("promptVersion")))
                    && (reviewed ? "REVIEWED_EXECUTION" : "OFFLINE_UNQUALIFIED").equals(text(body.get("qualification"))));
            String status = text(body.get("status"));
            String rejection = nullableText(body.get("rejectionCode"));
            String fingerprint = nullableText(body.get("providerFingerprintSha256"));
            AnalysisUsage usage = body.get("usage").isNull() ? null : usage(body.get("usage"));
            require(usage == null ? fingerprint == null : digest(fingerprint));
            AnalysisContent content = body.get("content").isNull() ? null : content(body.get("content"), evidenceIds);
            require(("VALIDATED".equals(status) && rejection == null && usage != null && content != null)
                    || ("REJECTED".equals(status) && ERRORS.contains(rejection) && content == null));
            result = new Result(request, counterSha256, executionCandidateSha256, status, rejection, fingerprint, usage, content, text(body.get("qualification")), reviewSha256);
        } catch (Exception ignored) {
            // 固定错误不保留原响应、解析路径或异常原因，失败不得变成零消费。
        }
        if (result == null) throw new IllegalArgumentException("INVALID_INTERNAL_ANALYSIS_RESULT");
        return result;
    }

    private static AnalysisUsage usage(JsonNode value) {
        fields(value, Set.of("promptTokens", "completionTokens", "totalTokens", "cacheHitTokens", "cacheMissTokens"));
        int prompt = count(value.get("promptTokens")), completion = count(value.get("completionTokens"));
        int total = count(value.get("totalTokens")), hit = count(value.get("cacheHitTokens")), miss = count(value.get("cacheMissTokens"));
        return new AnalysisUsage(prompt, completion, total, hit, miss);
    }

    private static AnalysisContent content(JsonNode value, Set<String> scope) {
        require(JSON.writeValueAsBytes(value).length <= 16 * 1024);
        fields(value, Set.of("summary", "findings", "limitations"));
        String summary = text(value.get("summary"));
        require(value.get("findings").isArray() && value.get("limitations").isArray());
        var findings = new ArrayList<Finding>();
        for (JsonNode item : value.get("findings")) {
            fields(item, Set.of("kind", "statement", "evidenceIds"));
            String kind = text(item.get("kind"));
            var ids = texts(item.get("evidenceIds"));
            require(!ids.isEmpty() && scope.containsAll(ids));
            findings.add(new Finding(Kind.valueOf(kind), text(item.get("statement")), ids));
        }
        return new AnalysisContent(summary, findings, texts(value.get("limitations")));
    }

    private static List<String> texts(JsonNode value) {
        require(value != null && value.isArray());
        var values = new ArrayList<String>();
        for (JsonNode item : value) values.add(text(item));
        return values;
    }

    private static int count(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0);
        return value.asInt();
    }
    private static String nullableText(JsonNode value) { return value.isNull() ? null : text(value); }
    private static String text(JsonNode value) {
        require(value != null && value.isString() && !value.asString().isEmpty());
        return value.asString();
    }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void fields(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject() && value.propertyNames().equals(expected));
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException(); }
}
