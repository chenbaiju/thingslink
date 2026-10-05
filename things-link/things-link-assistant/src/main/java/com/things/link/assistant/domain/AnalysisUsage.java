package com.things.link.assistant.domain;

/** 有界单次用量；缺失时整个对象为空，不得用全零对象代替未知消费。 */
public record AnalysisUsage(int promptTokens, int completionTokens, int totalTokens,
        int cacheHitTokens, int cacheMissTokens) {
    /**
     * 验证单次上限和计数一致性；来源与输入计数匹配仍须由调用边界核验。
     * @param promptTokens 输入数量，范围一至四千零九十六
     * @param completionTokens 输出数量，范围零至一千零二十四
     * @param totalTokens 输入与输出总量
     * @param cacheHitTokens 缓存命中输入数量
     * @param cacheMissTokens 未命中输入数量
     */
    public AnalysisUsage {
        if (promptTokens < 1 || promptTokens > 4096 || completionTokens < 0 || completionTokens > 1024
                || cacheHitTokens < 0 || cacheMissTokens < 0
                || (long) totalTokens != (long) promptTokens + completionTokens
                || (long) promptTokens != (long) cacheHitTokens + cacheMissTokens)
            throw new IllegalArgumentException("INVALID_ANALYSIS_USAGE");
    }
}
