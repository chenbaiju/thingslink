package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisContent;
import com.things.link.assistant.domain.AnalysisUsage;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.json.JsonMapper;

/** 应用内部的一次性结果释放凭证；当前生产尚无获批签发通道，不允许由模型或浏览器声明准入。 */
public final class AnalysisResultPermit {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AnalysisCall original;
    private final AnalysisContent content;
    private final AnalysisUsage usage;
    private final String model;
    private final String promptVersion;
    private final Instant expiresAt;
    private final AtomicBoolean used = new AtomicBoolean();

    /**
     * 仅供应用层受信签发者使用；构造前必须验证真实准入及全部候选绑定，离线回执不能签发。
     * @param original 原始已持久认领调用，不含正文或明文凭据
     * @param content 已通过本次结构与引用范围校验的瞬时正文
     * @param usage 已核验计数，不允许以空对象表示未知
     * @param evidenceIds 本次受控投影的引用允许集，不能从响应自行扩展
     * @param expiresAt 释放有效期，不晚于原调用期限
     * @param model 本次已核验固定模型，不能由展示层补猜
     * @param promptVersion 本次已核验提示词版本
     */
    AnalysisResultPermit(AnalysisCall original, AnalysisContent content, AnalysisUsage usage,
            Set<String> evidenceIds, Instant expiresAt, String model, String promptVersion) {
        boolean valid = false;
        try {
            valid = original != null && original.status() == AnalysisCall.Status.DISPATCHED
                    && original.dispatchedAt() != null && content != null && usage != null
                    && "deepseek-flash".equals(model) && "thingslink-agent-single-analysis-v1".equals(promptVersion)
                    && expiresAt != null && expiresAt.isAfter(original.createdAt())
                    && !expiresAt.isAfter(original.deadline()) && !expiresAt.isAfter(original.expiresAt())
                    && evidenceIds != null && evidenceIds.stream().allMatch(id -> id != null && !id.isEmpty())
                    && content.findings().stream().allMatch(f -> evidenceIds.containsAll(f.evidenceIds()))
                    && JSON.writeValueAsBytes(content).length <= 16 * 1024;
        } catch (RuntimeException ignored) { /* 固定错误不保留正文或序列化原因。 */ }
        if (!valid) throw invalid();
        this.original = original;
        this.content = content;
        this.usage = usage;
        this.model = model;
        this.promptVersion = promptVersion;
        this.expiresAt = expiresAt;
    }

    /**
     * 原调用最终确权后消费一次；回滚不恢复凭证，不能通过重复返回恢复正文。
     * @param execution 本次实际持久认领上下文
     * @param now 当前受控时钟，不延长原期限
     */
    void consume(AnalysisExecutionContext execution, Instant now) {
        verifyBinding(execution, now);
        if (!used.compareAndSet(false, true)) throw invalid();
    }

    /** 状态写入之后、提交之前重验期限；锁等待和写入耗时不能延长释放窗口。 */
    void verifyReturn(AnalysisExecutionContext execution, Instant now) {
        verifyBinding(execution, now);
        if (!used.get()) throw invalid();
    }

    /** 只接受同一原调用且尚未到期的凭证，不以值对象本身建立用户权限。 */
    private void verifyBinding(AnalysisExecutionContext execution, Instant now) {
        if (execution == null || !original.equals(execution.call()) || now == null
                || !now.isBefore(expiresAt)) throw invalid();
    }

    /** @return 已消费凭证中的瞬时正文，仅最终提交成功后组装返回 */
    AnalysisContent content() { return content; }
    /** @return 已核验单次用量，不代表语义质量或用户余额 */
    AnalysisUsage usage() { return usage; }
    /** @return 凭证绑定的固定模型，不从正文推测 */
    String model() { return model; }
    /** @return 凭证绑定的固定提示词版本 */
    String promptVersion() { return promptVersion; }
    @Override public String toString() { return "结果释放凭证[内容已隐藏]"; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_ANALYSIS_RESULT_PERMIT"); }
}
