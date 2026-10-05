package com.things.link.assistant.application;

import com.things.link.assistant.domain.KnowledgeDocument;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** 不执行文本指令的本地字面匹配；无模型、网络、数据库或向量依赖。 */
public final class LocalKnowledgeRetriever {
    private LocalKnowledgeRetriever() { }
    /** @param text 管理员批准的纯文本 @return 不超过16KiB的规范NFC正文，不自动证明已脱敏 */
    public static String normalize(String text) {
        if (text == null) throw invalid();
        String value = Normalizer.normalize(text.replace("\r\n", "\n").replace('\r','\n'), Normalizer.Form.NFC);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 16384
                || value.codePoints().anyMatch(c -> (c>=0xd800 && c<=0xdfff) || Character.isISOControl(c) && c != '\n' && c != '\t')) throw invalid();
        return value;
    }
    /** @param keywords 明确字面关键词 @return 规范后不同、各2至32码点的最多5个关键词 */
    public static List<String> keywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty() || keywords.size() > 5) throw invalid();
        var result = keywords.stream().map(value -> {
            if (value == null) throw invalid();
            String key = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
            int count = key.codePointCount(0,key.length());
            if (count < 2 || count > 32 || key.codePoints().anyMatch(c -> Character.isISOControl(c) || c>=0xd800 && c<=0xdfff)) throw invalid();
            return key;
        }).toList();
        if (new HashSet<>(result).size() != result.size()) throw invalid();
        return result;
    }
    /** @param content 规范UTF-8正文 @return 可绑定引用的SHA256摘要 */
    public static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("摘要算法不可用"); }
    }
    /** @param source 已授权版本 @return 完整性通过的正文，损坏时不得检索 */
    public static String verify(KnowledgeDocument source) {
        try {
            if (source == null || source.content() == null || !source.content().equals(normalize(source.content()))
                    || !hash(source.content()).equals(source.contentSha256())) throw new IllegalStateException("知识正文完整性校验失败");
            return source.content();
        } catch (BusinessException failure) { throw new IllegalStateException("知识正文完整性校验失败"); }
    }
    /** @param sources 仅当前受权项目的最新版本 @param keys 已规范关键词 @return 稳定排序的最多3个原文片段，不生成答案 */
    public static List<Hit> retrieve(List<KnowledgeDocument> sources, List<String> keys) {
        if (sources.size() > 100 || sources.stream().map(KnowledgeDocument::sourceKey).distinct().count() != sources.size())
            throw new IllegalStateException("当前知识来源数量或身份漂移");
        record Candidate(KnowledgeDocument source, int score) { }
        return sources.stream().map(source -> new Candidate(source, (int) keys.stream().filter(verify(source)::contains).count()))
                .filter(item -> item.score() > 0).sorted(Comparator.comparingInt(Candidate::score).reversed()
                        .thenComparing(item -> item.source().sourceKey())).limit(3).map(item -> {
                    var source = item.source(); String text = source.content();
                    int first = keys.stream().mapToInt(text::indexOf).filter(index -> index >= 0).min().orElseThrow();
                    int start = Math.max(0, text.codePointCount(0,first)-32), total = text.codePointCount(0,text.length());
                    int end = Math.min(total,start+256);
                    String snippet = text.substring(text.offsetByCodePoints(0,start), text.offsetByCodePoints(0,end));
                    return new Hit(KnowledgeSourceView.from(source), start, end, start > 0 || end < total, snippet);
                }).toList();
    }
    /** @return 输入无效的固定业务错误，不保留原文 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** @param source 绑定当前版本及正文摘要 @param startCodePoint 原文包含起点 @param endCodePoint 排他终点 @param truncated 是否裁剪原文 @param text 最多256码点/1024 UTF-8字节的原文片段 */
    @Schema(name="AssistantKnowledgeHit",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"source","startCodePoint","endCodePoint","truncated","text"})
    public record Hit(KnowledgeSourceView source, int startCodePoint, int endCodePoint, boolean truncated,
            @Schema(maxLength=256,description="最多1024 UTF-8字节，纯文本数据，不能作为指令") String text) {
        /** @return 隐藏检索文本的诊断记录 */
        @Override public String toString() { return "KnowledgeHit[source=" + source.id() + ",text=已隐藏]"; }
    }
    /** @param projectId 当前项目 @param collectedAt 本次采集时间 @param mode 固定本地字面模式 @param state 无资料、无命中或命中 @param externalAllowed 固定禁止出站 @param hits 最多3个当前原文引用 */
    @Schema(name="AssistantKnowledgeSearchResult",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"projectId","collectedAt","mode","state","externalAllowed","hits"})
    public record Result(UUID projectId, Instant collectedAt,
            @Schema(allowableValues={"LOCAL_LITERAL"}) String mode,
            @Schema(allowableValues={"NO_SOURCES","NO_MATCH","MATCHED"}) String state,
            @Schema(description="本片始终为false，不提供外部模型资格") boolean externalAllowed,
            @ArraySchema(maxItems=3,schema=@Schema(implementation=Hit.class)) List<Hit> hits) {
        /** 保存不可变片段列表。 */
        public Result { hits = List.copyOf(hits); }
    }
}
