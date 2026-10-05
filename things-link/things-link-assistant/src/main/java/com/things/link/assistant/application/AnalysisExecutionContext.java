package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.ModelCredential;

/** 已提交执行认领的内部上下文；只含密文，不可进入HTTP、日志、图状态或长期缓存。 */
public final class AnalysisExecutionContext {
    private final AnalysisCall call;
    private final ModelCredential credential;

    /**
     * 仅由应用层在持久认领提交时构造，不提供浏览器反序列化入口。
     * @param call 已认领的原始已派发记录
     * @param credential 同事务核验的启用密文，归属与配置版本必须匹配
     */
    AnalysisExecutionContext(AnalysisCall call, ModelCredential credential) {
        if (call == null || credential == null || call.status() != AnalysisCall.Status.DISPATCHED
                || !call.tenantId().equals(credential.tenantId()) || !call.projectId().equals(credential.projectId())
                || !credential.enabled() || credential.ciphertext() == null
                || call.configurationRevision() != credential.revision())
            throw new IllegalArgumentException("INVALID_ANALYSIS_EXECUTION_CONTEXT");
        this.call = call;
        this.credential = credential;
    }

    /** @return 原始持久调用，不修改原期限、身份或请求摘要 */
    public AnalysisCall call() { return call; }

    /** @return 本次密文，只能经单次同步交付器在事务外解密；不能缓存或直接序列化 */
    public ModelCredential credential() { return credential; }

    @Override public String toString() { return "内部分析执行上下文[内容已隐藏]"; }
}
