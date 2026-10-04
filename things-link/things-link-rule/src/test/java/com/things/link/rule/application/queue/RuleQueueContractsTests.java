package com.things.link.rule.application.queue;

import com.things.link.rule.application.engine.RuleMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** S8-2B 规则队列可靠性纯契约测试。 */
class RuleQueueContractsTests {

    /** 重试生成的新信封必须保留源消息、规则版本和 trace 身份。 */
    @Test
    void retryEnvelopePreservesOriginalExecutionIdentity() throws Exception {
        RuleExecutionEnvelope first = envelope(1);

        RuleExecutionEnvelope retry = first.nextAttempt(Instant.parse("2026-08-13T00:01:00Z"));

        assertThat(retry.key()).isEqualTo(first.key());
        assertThat(retry.tenantId()).isEqualTo(first.tenantId());
        assertThat(retry.message().messageId()).isEqualTo(first.message().messageId());
        assertThat(retry.message().traceId()).isEqualTo(first.message().traceId());
        assertThat(retry.attempt()).isEqualTo(2);
    }

    /** 队列键和可信消息任一身份不一致都必须拒绝，不能在重放链路中修正。 */
    @Test
    void rejectsEnvelopeWithMismatchedTrustedIdentity() throws Exception {
        RuleExecutionEnvelope source = envelope(1);
        RuleExecutionKey wrongKey = new RuleExecutionKey(source.key().projectId(), UUID.randomUUID(),
                source.key().ruleId(), source.key().ruleVersionId());

        assertThatIllegalArgumentException().isThrownBy(() -> new RuleExecutionEnvelope(
                wrongKey, source.tenantId(), source.message(), 1, source.enqueuedAt()))
                .withMessageContaining("身份");
    }

    /** 失败分类必须明确表达是否可重试，不能由异常文本或异常类名猜测。 */
    @Test
    void failureClassificationIsClosedAndExplicit() {
        assertThat(RuleExecutionFailure.DATABASE_TRANSIENT.retryable()).isTrue();
        assertThat(RuleExecutionFailure.SANDBOX_UNAVAILABLE.retryable()).isTrue();
        assertThat(RuleExecutionFailure.SCRIPT_FAILURE.retryable()).isFalse();
        assertThat(new RuleExecutionException(RuleExecutionFailure.CONTRACT_INVALID, "stable").failure())
                .isEqualTo(RuleExecutionFailure.CONTRACT_INVALID);
    }

    /** 首次和第二次失败只能进入 1m/5m，第三次或永久失败必须进入 DLQ。 */
    @Test
    void retryPolicyUsesOnlyTwoDelaysAndStopsAtThirdAttempt() throws Exception {
        RuleRetryPolicy policy = new RuleRetryPolicy();

        assertThat(policy.decide(envelope(1), RuleExecutionFailure.DATABASE_TRANSIENT))
                .isEqualTo(new RuleRetryDecision(RuleRetryDecision.Disposition.RETRY, Duration.ofMinutes(1)));
        assertThat(policy.decide(envelope(2), RuleExecutionFailure.QUEUE_SATURATED))
                .isEqualTo(new RuleRetryDecision(RuleRetryDecision.Disposition.RETRY, Duration.ofMinutes(5)));
        assertThat(policy.decide(envelope(3), RuleExecutionFailure.DATABASE_TRANSIENT).retry()).isFalse();
        assertThat(policy.decide(envelope(1), RuleExecutionFailure.SCRIPT_FAILURE).retry()).isFalse();
    }

    /** 指标仅可出现预注册的固定标签，且拒绝任意延迟档位。 */
    @Test
    void metricsUseOnlyFixedLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuleQueueMetrics metrics = new RuleQueueMetrics(registry);

        metrics.recordSubmission(RuleQueueMetrics.SubmissionStage.ADMISSION,
                RuleQueueMetrics.SubmissionResult.ACCEPTED);
        metrics.setPending(RuleQueueMetrics.QueueScope.PROJECT, 3);
        metrics.recordExecution(RuleQueueMetrics.ExecutionStage.ENGINE,
                RuleQueueMetrics.ExecutionResult.SUCCESS, Duration.ofMillis(12));
        metrics.recordRetry(Duration.ofMinutes(1), RuleQueueMetrics.RetryResult.PUBLISHED);
        metrics.recordDeadLetterPublished(RuleExecutionFailure.SCRIPT_FAILURE);

        assertThat(registry.get(RuleQueueMetrics.SUBMISSION)
                .tags("stage", "admission", "result", "accepted").counter().count()).isEqualTo(1D);
        assertThat(registry.get(RuleQueueMetrics.PENDING).tag("scope", "project").gauge().value()).isEqualTo(3D);
        assertThat(registry.get(RuleQueueMetrics.RETRY)
                .tags("delay", "one_minute", "result", "published").counter().count()).isEqualTo(1D);
        assertThat(registry.get(RuleQueueMetrics.DEAD_LETTER)
                .tags("reason", "script_failure", "result", "published").counter().count()).isEqualTo(1D);
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.recordRetry(
                Duration.ofSeconds(30), RuleQueueMetrics.RetryResult.SCHEDULED));
        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .anyMatch(key -> key.equals("tenantId") || key.equals("projectId") || key.equals("ruleId")))
                .isFalse();
    }

    /** 创建包含彼此匹配的可信消息与不可变规则版本键。 */
    private static RuleExecutionEnvelope envelope(int attempt) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        RuleMessage message = new RuleMessage(messageId, tenantId, projectId, UUID.randomUUID(),
                "trace-s8-2b", Instant.parse("2026-08-13T00:00:00Z"), "PROPERTY_REPORT",
                JsonMapper.builder().build().readTree("{\"value\":1}"), Map.of("source", "test"));
        return new RuleExecutionEnvelope(new RuleExecutionKey(projectId, messageId, UUID.randomUUID(),
                UUID.randomUUID()), tenantId, message, attempt, Instant.parse("2026-08-13T00:00:00Z"));
    }
}
