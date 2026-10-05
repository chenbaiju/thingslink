package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisContent;
import com.things.link.assistant.domain.AnalysisUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** 独立审查资格下的一次结果签发组件；不联网，不建立用户权限，也不认领真实出站授权。 */
public final class AnalysisResultIssuer {
    /** 实际发送前独立计算的绑定，不能从收到的模型或内部回执反填期望。 */
    public record Binding(String inputSha256, String requestSha256, String counterSha256,
            String executionCandidateSha256, Set<String> evidenceIds) {
        public Binding {
            try {
                require(digest(inputSha256) && digest(requestSha256) && digest(counterSha256)
                        && digest(executionCandidateSha256) && evidenceIds != null
                        && evidenceIds.containsAll(Set.of("e-device", "e-alarm"))
                        && evidenceIds.stream().allMatch(id -> id != null
                            && id.matches("e-(device|alarm|property-([1-9]|10))")));
                evidenceIds = Set.copyOf(evidenceIds);
            } catch (RuntimeException ignored) { throw invalid(); }
        }
        @Override public String toString() { return "原分析绑定[内容已隐藏]"; }
    }

    /** 受认证通道的封闭回执值；构造值本身没有准入资格，必须与独立裁决及原绑定共同验证。 */
    public record Receipt(UUID callId, long configurationRevision, String inputSha256, String requestSha256,
            String counterSha256, String executionCandidateSha256, String reviewSha256,
            String qualification, String status, String providerFingerprintSha256,
            AnalysisContent content, AnalysisUsage usage) {
        @Override public String toString() { return "待核验分析回执[内容已隐藏]"; }
    }

    private final AnalysisCall original;
    private final Binding binding;
    private final AnalysisReleaseReview.Approval approval;
    private final Clock clock;
    private final LongSupplier ticker;
    private final long started, budget;
    private final AtomicBoolean used = new AtomicBoolean();

    /**
     * 绑定已持久认领的原调用与独立期望；资格来自固定审查器，不接受布尔放行。
     * @param call 原始派发调用，最终返回仍需接收侧重新确权
     * @param binding 实际证据、请求与执行候选的独立绑定
     * @param approval 仍在有效期内的包内审查资格
     * @return 本次调用专用签发对象，失败也消费一次使用机会
     */
    public static AnalysisResultIssuer bind(AnalysisCall call, Binding binding, AnalysisReleaseReview.Approval approval) {
        return new AnalysisResultIssuer(call, binding, approval, Clock.systemUTC());
    }

    /** 包内时钟注入仅供确定性期限测试，生产使用协调世界时且不延长原窗口。 */
    AnalysisResultIssuer(AnalysisCall call, Binding binding, AnalysisReleaseReview.Approval approval, Clock clock) {
        this(call, binding, approval, clock, System::nanoTime);
    }

    /** 原时窗仅转为一次单调预算；墙钟回拨不增加可签发时长。 */
    AnalysisResultIssuer(AnalysisCall call, Binding binding, AnalysisReleaseReview.Approval approval,
            Clock clock, LongSupplier ticker) {
        require(call != null && binding != null && approval != null && clock != null && ticker != null);
        this.original = call; this.binding = binding; this.approval = approval; this.clock = clock;
        this.ticker = ticker; this.started = ticker.getAsLong();
        Instant now = clock.instant();
        verifyTimeAndBinding(now);
        Instant expires = call.deadline().isBefore(approval.expiresAt) ? call.deadline() : approval.expiresAt;
        try { this.budget = Duration.between(now, expires).toNanos(); }
        catch (RuntimeException ignored) { throw invalid(); }
        require(budget > 0 && budget <= Duration.ofSeconds(60).toNanos());
        verifyBudget();
    }

    /**
     * 消费对象并严格比对本次回执，离线资格、未知用量及错绑定都不能签发。
     * @param receipt 经受认证内部通道解码的回执，不能由公开请求提供
     * @return 有效期不晚于原调用和原审查窗口的既有不透明释放凭证
     */
    public AnalysisResultPermit issue(Receipt receipt) {
        if (!used.compareAndSet(false, true)) throw invalid();
        try {
            verifyBudget();
            verifyTimeAndBinding(clock.instant());
            require(receipt != null && original.id().equals(receipt.callId())
                    && original.configurationRevision() == receipt.configurationRevision()
                    && binding.inputSha256().equals(receipt.inputSha256())
                    && binding.requestSha256().equals(receipt.requestSha256())
                    && binding.counterSha256().equals(receipt.counterSha256())
                    && binding.executionCandidateSha256().equals(receipt.executionCandidateSha256())
                    && approval.reviewSha256.equals(receipt.reviewSha256())
                    && "REVIEWED_EXECUTION".equals(receipt.qualification())
                    && "VALIDATED".equals(receipt.status())
                    && approval.providerFingerprintSha256.equals(receipt.providerFingerprintSha256())
                    && receipt.content() != null && receipt.usage() != null);
            Instant expires = original.deadline().isBefore(approval.expiresAt) ? original.deadline() : approval.expiresAt;
            AnalysisResultPermit permit = new AnalysisResultPermit(original, receipt.content(), receipt.usage(),
                    binding.evidenceIds(), expires, "deepseek-flash", "thingslink-agent-single-analysis-v1");
            verifyBudget();
            verifyTimeAndBinding(clock.instant());
            return permit;
        } catch (RuntimeException ignored) { throw invalid(); }
    }

    /** 原调用、原候选及原审查期限逐次复核；不允许先构造对象再跨窗口签发。 */
    private void verifyTimeAndBinding(Instant now) {
        require(approval.validAt(now) && original.id() != null && original.id().version() == 7
                && original.id().variant() == 2 && original.status() == AnalysisCall.Status.DISPATCHED
                && original.configurationRevision() > 0 && original.finishedAt() == null
                && original.createdAt() != null && original.dispatchedAt() != null
                && original.deadline() != null && original.expiresAt() != null
                && !original.dispatchedAt().isBefore(original.createdAt())
                && !now.isBefore(original.dispatchedAt()) && now.isBefore(original.deadline())
                && !original.deadline().isAfter(original.expiresAt())
                && approval.counterSha256.equals(binding.counterSha256())
                && approval.candidateSha256.equals(binding.executionCandidateSha256()));
    }
    /** 单调时钟溢出或执行预算耗尽均拒绝，不重新从墙钟计算预算。 */
    private void verifyBudget() {
        long elapsed = ticker.getAsLong() - started;
        require(elapsed >= 0 && elapsed < budget);
    }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void require(boolean value) { if (!value) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("ANALYSIS_RELEASE_NOT_ADMITTED"); }
    @Override public String toString() { return "单次分析签发对象[内容已隐藏]"; }
}
