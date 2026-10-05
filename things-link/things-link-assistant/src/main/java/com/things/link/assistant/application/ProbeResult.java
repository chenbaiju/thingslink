package com.things.link.assistant.application;

/**
 * 仅包含有界的合成探针用量元数据，不包含生成文本或供应商身份。
 *
 * @param sampleIndex 固定合成样本序号，取值为 1 至 3
 * @param manifestSha256 本次授权绑定的样本清单摘要
 * @param outcome 探针终态，限成功、失败或结果未知对应的枚举名称
 * @param category 固定结果分类码，不包含原始异常或供应商响应文本
 * @param usage 成功调用的用量及计数证据；其他终态为空
 */
public record ProbeResult(int sampleIndex,String manifestSha256,String outcome,String category,Usage usage) {
    /**
     * 单次成功探针的计数及摘要证据；成功不代表输入计数已获得业务出站资格。
     *
     * @param localInputTokens 本地候选计数器计算的完整消息输入数量
     * @param promptTokens 供应商返回的实际输入数量
     * @param completionTokens 供应商返回的输出数量，最多 128
     * @param totalTokens 实际输入与输出数量之和
     * @param cacheHitTokens 输入中命中供应商缓存的数量
     * @param cacheMissTokens 输入中未命中供应商缓存的数量
     * @param delta 实际输入数量减去本地输入数量，可为负数
     * @param finishReason 固定结束原因，限正常结束或达到长度上限
     * @param implementationSha256 候选计数器实现摘要
     * @param assetsSha256 候选计数资源摘要
     * @param requestSha256 固定模型请求摘要
     * @param messagesSha256 完整模型消息摘要
     * @param backendFingerprintSha256 后端指纹摘要，不返回原始指纹
     */
    @io.swagger.v3.oas.annotations.media.Schema(name="AssistantProbeUsage",additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Usage(int localInputTokens,int promptTokens,int completionTokens,int totalTokens,
        int cacheHitTokens,int cacheMissTokens,int delta,String finishReason,String implementationSha256,
        String assetsSha256,String requestSha256,String messagesSha256,String backendFingerprintSha256) {}
}
