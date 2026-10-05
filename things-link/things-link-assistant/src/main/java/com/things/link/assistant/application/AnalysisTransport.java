package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;

/** 内部分析通道，不提供用户授权；受审回执仍须通过最终用户确权。 */
public interface AnalysisTransport {
    /** 封闭内部回执；返回类型本身不提供当前用户授权或语义质量资格。 */
    sealed interface Receipt {
        Receipt UNQUALIFIED = new Unqualified();
        /** 未准入完成确认，不表示成功或零消费。 */
        final class Unqualified implements Receipt { private Unqualified() {} }
        /** 只有受信签发者才能提供不透明凭证；随包裁决待审时不产生该凭证。 */
        record Releasable(AnalysisResultPermit permit) implements Receipt {
            public Releasable {
                if (permit == null) throw new IllegalArgumentException("INVALID_ANALYSIS_RECEIPT");
            }
            @Override public String toString() { return "内部结果回执[内容已隐藏]"; }
        }
    }

    /** @return 内部通道是否已完整装配；不代表供应商业务准入 */
    boolean ready();

    /** @return 固定发布材料产生的独立批准；默认空，不从通道装配或布尔配置提升资格 */
    default java.util.Optional<AnalysisReleaseReview.Approval> review() { return java.util.Optional.empty(); }

    /**
     * 同步执行一次，异常时远端状态未知，不能自动重试或提前释放共享槽。
     * @param call 已持久认领的原始调用，期限不能续期
     * @param input 本次受控证据投影，不含真实业务标识和自由文本
     * @param credential 单次可变凭据；必须消费后清零，不得缓存、日志或异步保存
     * @return 封闭内部确认；固定裁决待审时只返回未准入，带凭证结果仍须最终确权才能释放
     */
    Receipt execute(AnalysisCall call, PreparedModelEvidence.Input input, byte[] credential);
}
