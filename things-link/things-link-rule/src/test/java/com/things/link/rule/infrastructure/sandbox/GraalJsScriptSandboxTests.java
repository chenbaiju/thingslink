package com.things.link.rule.infrastructure.sandbox;

import com.things.link.rule.application.ScriptExecutionRequest;
import com.things.link.rule.application.ScriptExecutionResult;
import com.things.link.rule.application.ScriptExecutionStatus;
import com.things.link.rule.application.ScriptKind;
import com.things.link.rule.application.ScriptValidationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 GraalJS isolate 验证 S8-1A 的五项硬边界，禁止把 mock runtime 当作安全证据。 */
class GraalJsScriptSandboxTests {

    /** 规则保存只解析函数而不调用正文；无限循环正文仍应快速验证通过，语法错误应固定失败。 */
    @Test
    void validatesFunctionWithoutExecutingBody() {
        GraalJsScriptSandbox sandbox = sandbox(defaults(), new SimpleMeterRegistry());
        try {
            ScriptValidationResult valid = sandbox.validate(
                    ScriptKind.RULE, "input => { while (true) {} }");
            ScriptValidationResult invalid = sandbox.validate(
                    ScriptKind.RULE, "input => ({");

            assertThat(valid.valid()).isTrue();
            assertThat(valid.duration()).isLessThan(Duration.ofSeconds(5));
            assertThat(invalid.valid()).isFalse();
            assertThat(invalid.errorCode()).isEqualTo("SANDBOX_SCRIPT_FAILURE");
        } finally {
            sandbox.close();
        }
    }

    /** 源码表达式本身也可能执行恶意 IIFE；即使 READY 尚未发出，也必须由启动墙钟终止 Worker。 */
    @Test
    void boundsMaliciousSourceEvaluationByStartupTimeout() {
        ScriptSandboxProperties shortStartup = new ScriptSandboxProperties(
                Duration.ofMillis(50), Duration.ofMillis(500), Duration.ofSeconds(3),
                DataSize.ofMegabytes(64), DataSize.ofMegabytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(4), 2, 10);
        GraalJsScriptSandbox sandbox = sandbox(shortStartup, new SimpleMeterRegistry());
        try {
            ScriptValidationResult result = sandbox.validate(
                    ScriptKind.RULE, "(() => { while (true) {} })()");

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isIn("SANDBOX_WORKER_STARTUP", "SANDBOX_WALL_TIMEOUT");
            assertThat(result.duration()).isLessThan(Duration.ofSeconds(5));
            assertThat(ProcessHandle.current().descendants()
                    .filter(ProcessHandle::isAlive)
                    .noneMatch(process -> process.info().commandLine()
                            .orElse("").contains(ScriptWorkerMain.class.getName())))
                    .as("超时返回后不得遗留脚本 Worker")
                    .isTrue();
        } finally {
            sandbox.close();
        }
    }

    /** 正常函数只能看到 JSON 输入并返回 JSON，且真实成功指标必须产生。 */
    @Test
    void executesJsonFunctionAndRecordsMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GraalJsScriptSandbox sandbox = sandbox(defaults(), registry);
        try {
            ScriptExecutionResult result = sandbox.execute(new ScriptExecutionRequest(
                    ScriptKind.DEBUG,
                    "input => ({temperature: input.temperature + 1, ok: true})",
                    "{\"temperature\":26}"));

            assertThat(result.status()).as("完整结果 %s", result).isEqualTo(ScriptExecutionStatus.SUCCESS);
            assertThat(result.outputJson()).isEqualTo("{\"temperature\":27,\"ok\":true}");
            assertThat(registry.find("thingslink.script.execution")
                    .tag("kind", "debug").tag("result", "success").timer().count()).isEqualTo(1L);
        } finally {
            sandbox.close();
        }
    }

    /** Java 类、文件、反射、网络和线程能力在 guest 全局中都不可见。 */
    @Test
    void hidesHostFileNetworkReflectionAndThreadCapabilities() {
        GraalJsScriptSandbox sandbox = sandbox(defaults(), new SimpleMeterRegistry());
        try {
            ScriptExecutionResult globals = sandbox.execute(new ScriptExecutionRequest(
                    ScriptKind.RULE,
                    "input => ({java: typeof Java, require: typeof require, fetch: typeof fetch, worker: typeof Worker})",
                    "{}"));
            ScriptExecutionResult hostAttempt = sandbox.execute(new ScriptExecutionRequest(
                    ScriptKind.RULE,
                    "input => Java.type('java.nio.file.Files').readString('secret')",
                    "{}"));

            assertThat(globals.outputJson()).isEqualTo(
                    "{\"java\":\"object\",\"require\":\"undefined\",\"fetch\":\"undefined\",\"worker\":\"undefined\"}");
            assertThat(hostAttempt.status()).isEqualTo(ScriptExecutionStatus.FAILURE);
            assertThat(hostAttempt.errorCode()).isEqualTo("SANDBOX_SCRIPT_FAILURE");
        } finally {
            sandbox.close();
        }
    }

    /** 无限循环必须由 guest CPU 时间限制终止，而不是永久占用业务线程。 */
    @Test
    void stopsInfiniteLoopAtCpuLimit() {
        GraalJsScriptSandbox sandbox = sandbox(defaults(), new SimpleMeterRegistry());
        try {
            ScriptExecutionResult result = sandbox.execute(new ScriptExecutionRequest(
                    ScriptKind.FUNCTION, "input => { while (true) {} }", "{}"));

            assertThat(result.status()).isEqualTo(ScriptExecutionStatus.TIMEOUT);
            assertThat(result.errorCode()).isIn("SANDBOX_CPU_LIMIT", "SANDBOX_WALL_TIMEOUT");
            assertThat(result.duration()).isLessThan(Duration.ofSeconds(5));
        } finally {
            sandbox.close();
        }
    }

    /** 返回 JSON 使用 UTF-8 真实字节计数，不能用 Java 字符数让多字节文本绕过上限。 */
    @Test
    void rejectsOversizedUtf8Output() {
        ScriptSandboxProperties outputBoundary = new ScriptSandboxProperties(
                Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(8),
                DataSize.ofMegabytes(64), DataSize.ofMegabytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(4), 1, 2);
        GraalJsScriptSandbox sandbox = sandbox(outputBoundary, new SimpleMeterRegistry());
        try {
            ScriptExecutionResult result = sandbox.execute(new ScriptExecutionRequest(
                    ScriptKind.CODEC, "input => '汉'.repeat(30000)", "{}"));

            assertThat(result.status()).isEqualTo(ScriptExecutionStatus.RESOURCE_EXHAUSTED);
            assertThat(result.errorCode()).isIn("SANDBOX_OUTPUT_LIMIT", "SANDBOX_RESOURCE_LIMIT");
        } finally {
            sandbox.close();
        }
    }

    /** 恶意持续分配只能耗尽本次 Worker 堆，宿主测试 JVM 与后续执行必须继续存活。 */
    @Test
    void terminatesWorkerAtMemoryLimit() {
        ScriptSandboxProperties properties = new ScriptSandboxProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(8), Duration.ofSeconds(8),
                DataSize.ofMegabytes(32), DataSize.ofMegabytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(4), 1, 2);
        GraalJsScriptSandbox sandbox = sandbox(properties, new SimpleMeterRegistry());
        try {
            ScriptExecutionResult exhausted = sandbox.execute(new ScriptExecutionRequest(
                    ScriptKind.RULE,
                    "input => { const chunks = []; while (true) chunks.push(new Array(100000).fill(chunks.length)); }",
                    "{}"));
            ScriptExecutionResult next = sandbox.execute(new ScriptExecutionRequest(
                    ScriptKind.RULE, "input => ({alive: true})", "{}"));

            assertThat(exhausted.status()).as("完整结果 %s", exhausted)
                    .isEqualTo(ScriptExecutionStatus.RESOURCE_EXHAUSTED);
            assertThat(exhausted.errorCode()).isEqualTo("SANDBOX_MEMORY_LIMIT");
            assertThat(next.status()).isEqualTo(ScriptExecutionStatus.SUCCESS);
        } finally {
            sandbox.close();
        }
    }

    /** 固定线程与有界队列同时占满后，第三个请求必须明确拒绝且不得落到调用线程执行。 */
    @Test
    void rejectsWhenDedicatedPoolAndQueueAreFull() throws Exception {
        ScriptSandboxProperties properties = new ScriptSandboxProperties(
                Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(8),
                DataSize.ofMegabytes(64), DataSize.ofMegabytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(4), 1, 1);
        GraalJsScriptSandbox sandbox = sandbox(properties, new SimpleMeterRegistry());
        try {
            ScriptExecutionRequest blocking = new ScriptExecutionRequest(
                    ScriptKind.RULE, "input => { while (true) {} }", "{}");
            CompletableFuture<ScriptExecutionResult> first =
                    CompletableFuture.supplyAsync(() -> sandbox.execute(blocking));
            Thread.sleep(100);
            CompletableFuture<ScriptExecutionResult> second =
                    CompletableFuture.supplyAsync(() -> sandbox.execute(blocking));
            Thread.sleep(100);

            ScriptExecutionResult third = sandbox.execute(blocking);

            assertThat(third.status()).isEqualTo(ScriptExecutionStatus.REJECTED);
            assertThat(third.errorCode()).isEqualTo("SANDBOX_QUEUE_FULL");
            assertThat(first.get().status()).isEqualTo(ScriptExecutionStatus.TIMEOUT);
            assertThat(second.get().status()).isEqualTo(ScriptExecutionStatus.TIMEOUT);
        } finally {
            sandbox.close();
        }
    }

    /** @return 与生产默认相同、仅放宽测试墙钟启动时间的属性 */
    private static ScriptSandboxProperties defaults() {
        return new ScriptSandboxProperties(
                Duration.ofMillis(50), Duration.ofSeconds(5), Duration.ofSeconds(8),
                DataSize.ofMegabytes(64), DataSize.ofMegabytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(64),
                DataSize.ofKilobytes(64), DataSize.ofKilobytes(4), 2, 10);
    }

    /** 创建使用真实 isolate 的被测执行器。 */
    private static GraalJsScriptSandbox sandbox(
            ScriptSandboxProperties properties, SimpleMeterRegistry registry) {
        return new GraalJsScriptSandbox(properties, new ScriptExecutionMetrics(registry));
    }
}
