package com.things.link.support.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** G1-C4b 一次性故障屏障的安全与跨重启语义测试。 */
class FaultInjectionCheckpointTests {

    /** JUnit 提供的独立控制目录根。 */
    @TempDir
    Path temporaryDirectory;

    /** 仅导入目标组件，验证命令行属性名确实能经过 Spring {@code @Value} 装配。 */
    @Configuration(proxyBeanMethods = false)
    @Import(FaultInjectionCheckpoint.class)
    static class CheckpointConfiguration {
    }

    /** 禁用实例必须是纯 no-op，不能要求配置或产生文件。 */
    @Test
    void disabledCheckpointDoesNothing() {
        FaultInjectionCheckpoint.disabled().reach(
                FaultInjectionCheckpoint.Checkpoint.OUTBOX_BEFORE_CLAIM, "secret-payload");

        assertThat(temporaryDirectory).isEmptyDirectory();
    }

    /** 启用时只接受 UUIDv7、冻结组合与预建安全目录。 */
    @Test
    void rejectsInvalidRunAndScenarioCheckpointPair() throws Exception {
        Path control = controlDirectory();

        assertThatIllegalArgumentException().isThrownBy(() -> new FaultInjectionCheckpoint(
                true, "550e8400-e29b-41d4-a716-446655440000", "OB-01",
                "OUTBOX_BEFORE_CLAIM", control));
        assertThatIllegalArgumentException().isThrownBy(() -> new FaultInjectionCheckpoint(
                true, "019d2c58-7c6d-7000-8000-000000000001", "RW-05",
                "OUTBOX_BEFORE_CLAIM", control));
    }

    /** runner 冻结的五个参数必须能创建真实 Spring Bean，错误键名不能被测试替身掩盖。 */
    @Test
    void bindsRunnerArgumentsThroughSpringAssembly() throws Exception {
        Path control = controlDirectory();
        new ApplicationContextRunner()
                .withUserConfiguration(CheckpointConfiguration.class)
                .withPropertyValues(
                        "things-link.fault-injection.enabled=true",
                        "things-link.fault-injection.run-id=019d2c58-7c6d-7000-8000-000000000001",
                        "things-link.fault-injection.scenario=OB-01",
                        "things-link.fault-injection.checkpoint=OUTBOX_BEFORE_CLAIM",
                        "things-link.fault-injection.control-dir=" + control)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FaultInjectionCheckpoint.class);
                });
    }

    /** 非目标 checkpoint 不能创建 once 或 evidence，防止正常请求被错误阻塞。 */
    @Test
    void ignoresNonConfiguredCheckpoint() throws Exception {
        Path control = controlDirectory();
        FaultInjectionCheckpoint checkpoint = enabled(control);

        checkpoint.reach(FaultInjectionCheckpoint.Checkpoint.OUTBOX_AFTER_KAFKA_ACK, "event-1");

        assertThat(control.resolve("events.jsonl")).doesNotExist();
        assertThat(control.resolve("once")).isEmptyDirectory();
    }

    /** 首次到达持久化脱敏证据并等待 release，第二次调用由 once 标记直接越过。 */
    @Test
    void persistsReachedWaitsForReleaseAndRunsOnlyOnce() throws Exception {
        Path control = controlDirectory();
        FaultInjectionCheckpoint checkpoint = enabled(control);
        CompletableFuture<Void> blocked = CompletableFuture.runAsync(() -> checkpoint.reach(
                FaultInjectionCheckpoint.Checkpoint.OUTBOX_BEFORE_CLAIM, "raw-secret-identity"));

        awaitExists(control.resolve("events.jsonl"));
        assertThat(blocked).isNotDone();
        String evidence = Files.readString(control.resolve("events.jsonl"));
        assertThat(evidence).contains("\"event\":\"REACHED\"")
                .contains("\"scenario\":\"OB-01\"")
                .contains("\"checkpoint\":\"OUTBOX_BEFORE_CLAIM\"")
                .endsWith("\n")
                .doesNotContain("raw-secret-identity");
        assertThat(control.resolve("events.jsonl.pending")).doesNotExist();

        Files.writeString(control.resolve("release/outbox_before_claim.release"), "release");
        blocked.get(2, TimeUnit.SECONDS);
        checkpoint.reach(FaultInjectionCheckpoint.Checkpoint.OUTBOX_BEFORE_CLAIM, "another-value");

        assertThat(Files.readAllLines(control.resolve("events.jsonl"))).hasSize(1);
    }

    /** 证据无法落盘时必须抛错，不能因 release 存在而越过故障点继续业务。 */
    @Test
    void failsClosedWhenReachedEvidenceCannotBeWritten() throws Exception {
        Path control = controlDirectory();
        Files.createDirectory(control.resolve("events.jsonl"));
        Files.writeString(control.resolve("release/outbox_before_claim.release"), "release");

        assertThatIllegalStateException().isThrownBy(() -> enabled(control).reach(
                FaultInjectionCheckpoint.Checkpoint.OUTBOX_BEFORE_CLAIM, "event-1"));

        assertThat(control.resolve("once/outbox_before_claim.once")).exists();
    }

    /** @return 已预建 once/release 的安全控制目录。 */
    private Path controlDirectory() throws Exception {
        Path control = temporaryDirectory.resolve("control").toAbsolutePath();
        Files.createDirectories(control.resolve("once"));
        Files.createDirectories(control.resolve("release"));
        return control;
    }

    /** @return 目标为 OB-01 的启用屏障。 */
    private static FaultInjectionCheckpoint enabled(Path control) {
        return new FaultInjectionCheckpoint(true, "019d2c58-7c6d-7000-8000-000000000001",
                "OB-01", "OUTBOX_BEFORE_CLAIM", control);
    }

    /** 等待异步线程写入证据；两秒内未出现即由断言失败。 */
    private static void awaitExists(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(file).exists();
    }
}
