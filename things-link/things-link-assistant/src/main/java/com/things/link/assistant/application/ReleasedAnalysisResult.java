package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisContent;
import com.things.link.assistant.domain.AnalysisUsage;

/** 仅首次最终确权成功的瞬时结果；不持久化、不重放，当前尚未接入公开控制器。 */
public final class ReleasedAnalysisResult {
    private final AnalysisCallView call;
    private final AnalysisContent content;
    private final AnalysisUsage usage;
    private final String model;
    private final String promptVersion;

    /**
     * 仅由结果释放事务提交后的应用流程组装，不提供反序列化构造器。
     * @param call 已成功提交的本人调用元数据
     * @param content 本次不可变正文
     * @param usage 本次已核验用量
     * @param model 本次凭证绑定的模型
     * @param promptVersion 本次凭证绑定的提示词版本
     */
    ReleasedAnalysisResult(AnalysisCallView call, AnalysisContent content, AnalysisUsage usage, String model, String promptVersion) {
        this.call = call; this.content = content; this.usage = usage;
        this.model = model; this.promptVersion = promptVersion;
    }
    /** @return 本次调用元数据；后续只读查询仍只能取得元数据 */
    public AnalysisCallView call() { return call; }
    /** @return 本次瞬时正文，不能从历史调用查询恢复 */
    public AnalysisContent content() { return content; }
    /** @return 本次核验用量，未知用量不能走成功释放 */
    public AnalysisUsage usage() { return usage; }
    /** @return 本次已核验模型标识 */
    public String model() { return model; }
    /** @return 本次已核验提示词版本 */
    public String promptVersion() { return promptVersion; }
    @Override public String toString() { return "瞬时分析结果[内容已隐藏]"; }
}
