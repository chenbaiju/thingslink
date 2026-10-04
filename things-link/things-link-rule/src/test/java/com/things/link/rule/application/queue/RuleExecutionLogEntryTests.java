package com.things.link.rule.application.queue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 生产日志在进入持久层前拒绝无界 attempt、负计量和自由文本结果。 */
class RuleExecutionLogEntryTests {

    /** 结果码不能成为异常正文或租户数据的自由文本通道。 */
    @Test
    void rejectsFreeTextResultCode() {
        assertThatThrownBy(() -> entry(1, "script failed: device-secret", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("封闭大写标识");
    }

    /** 第四次尝试和负字节数均违反 S8-2B 的有限重试边界。 */
    @Test
    void rejectsOutOfRangeAttemptAndMeasurements() {
        assertThatThrownBy(() -> entry(4, "SUCCESS", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry(1, "SUCCESS", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** @return 指定边界值的日志夹具 */
    private static RuleExecutionLogEntry entry(int attempt, String code, int inputBytes) {
        UUID projectId = UUID.randomUUID();
        return new RuleExecutionLogEntry(
                UUID.randomUUID(),
                new RuleExecutionKey(projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                attempt, RuleExecutionLogEntry.Status.SUCCESS, code, Duration.ZERO,
                inputBytes, 0, Instant.now());
    }
}
