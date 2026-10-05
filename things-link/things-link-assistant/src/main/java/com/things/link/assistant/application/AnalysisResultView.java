package com.things.link.assistant.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 首次受权释放的公开瞬时正文；所有文字按纯文本使用，不代表语义质量已通过。 */
@Schema(name="AssistantAnalysisResult",requiredProperties={"model","promptVersion","summary","findings","limitations","usage"},
        additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record AnalysisResultView(
        @Schema(allowableValues="deepseek-flash") String model,
        @Schema(allowableValues="thingslink-agent-single-analysis-v1") String promptVersion,
        @Schema(minLength=1) String summary, List<Finding> findings, List<String> limitations, Usage usage) {
    /** 公开诊断条目；引用编号仅通过本次范围检查，不证明陈述正确。 */
    @Schema(name="AssistantAnalysisFinding",requiredProperties={"kind","statement","evidenceIds"},
            additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Finding(@Schema(allowableValues={"FACT","HYPOTHESIS","RECOMMENDATION"}) String kind,
            @Schema(minLength=1) String statement, List<String> evidenceIds) {
        public Finding { evidenceIds = List.copyOf(evidenceIds); }
        @Override public String toString() { return "公开诊断条目[内容已隐藏]"; }
    }
    /** 已核验的单次用量，不提供费用或余额推断。 */
    @Schema(name="AssistantAnalysisUsage",requiredProperties={"promptTokens","completionTokens","totalTokens","cacheHitTokens","cacheMissTokens"},
            additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Usage(@Schema(minimum="1",maximum="4096") int promptTokens,
            @Schema(minimum="0",maximum="1024") int completionTokens,
            @Schema(minimum="1",maximum="5120") int totalTokens,
            @Schema(minimum="0",maximum="4096") int cacheHitTokens,
            @Schema(minimum="0",maximum="4096") int cacheMissTokens) {}

    /** 保留不可变集合，后续展示不能修改本次已释放内容。 */
    public AnalysisResultView { findings = List.copyOf(findings); limitations = List.copyOf(limitations); }

    /**
     * 仅从最终确权并提交后的应用结果映射，不接收内部原始响应或前端正文。
     * @param released 首次成功提交的瞬时结果
     * @return 不含凭证、请求摘要或真实设备标识的公开字段
     */
    static AnalysisResultView from(ReleasedAnalysisResult released) {
        var content = released.content(); var usage = released.usage();
        return new AnalysisResultView(released.model(),released.promptVersion(),content.summary(),
                content.findings().stream().map(f -> new Finding(f.kind().name(),f.statement(),f.evidenceIds())).toList(),
                content.limitations(),new Usage(usage.promptTokens(),usage.completionTokens(),usage.totalTokens(),
                        usage.cacheHitTokens(),usage.cacheMissTokens()));
    }
    @Override public String toString() { return "公开瞬时分析结果[内容已隐藏]"; }
}
