package com.things.link.dashboard.application.sharing;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 实际文件路径、严格事件投影与独立日志context，临时目录不冒称生产持久挂载。 */
class DashboardShareSecurityEventsTests {
    /** 单测试实际日志位置。 */ @TempDir Path directory;

    /** Spring最终显式目录承载真实事件，根正文恰好12字段且无actor账号和原始请求。 */
    @Test
    void writesOnlySafeFieldsToConfiguredDirectory() throws Exception {
        var metrics = new SimpleMeterRegistry();
        DashboardShareSecurityEvents events = new DashboardShareSecurityEvents(properties(), metrics);
        UUID share = UUID.randomUUID();
        try {
            events.record(DashboardShareSecurityEvents.Type.REQUEST, DashboardShareSecurityEvents.Outcome.ALLOWED,
                    DashboardShareSecurityEvents.Reason.SUCCESS, "trace_abc", DashboardShareSecurityEvents.Route.SCHEMA,
                    share, UUID.randomUUID(), 1234, 10);
        } finally { events.shutdown(); }
        String output = Files.readString(directory.resolve("share-security.log"));
        var json = JsonMapper.builder().build().readTree(output);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("at", "type", "outcome", "reason", "traceId",
                "routeTemplate", "actorType", "actorAccountId", "shareId", "projectId", "responseBytes", "durationMs");
        assertThat(json.get("actorAccountId").isNull()).isTrue();
        assertThat(json.get("actorType").asString()).isEqualTo("SHARE_CAPABILITY");
        assertThat(json.get("routeTemplate").asString()).isEqualTo("/api/v1/shares/{shareId}/schema");
        assertThat(json.get("shareId").asString()).isEqualTo(share.toString());
        assertThat(output.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2049);
        assertThat(output).doesNotContain("secret", "hash", "remoteAddr", "Authorization");
    }

    /** 主日志context重载不会卸载分享服务持有的独立context，也不会改到默认logs。 */
    @Test
    void independentContextSurvivesOtherLoggerContextReset() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, ResetProbe.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("reset-probe.log").toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(directory.resolve("reset-probe.log"))).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        assertThat(Files.readString(directory.resolve("share-security.log"))).contains("INVALID_CREDENTIAL");
    }

    /** 超长或非法trace不入事件，只记固定丢弃计数；失败不抛给HTTP业务。 */
    @Test
    void invalidDynamicTextIsDroppedWithLowCardinalityMetric() {
        var metrics = new SimpleMeterRegistry();
        var events = new DashboardShareSecurityEvents(properties(), metrics);
        try {
            events.record(DashboardShareSecurityEvents.Type.REQUEST, DashboardShareSecurityEvents.Outcome.ERROR,
                    DashboardShareSecurityEvents.Reason.INTERNAL_ERROR, "token=" + "x".repeat(3000),
                    DashboardShareSecurityEvents.Route.CONTEXT, null, null, 0, 1);
            assertThat(metrics.get("dashboard.share.security.events.dropped").tag("reason", "invalid_event").counter().count())
                    .isEqualTo(1);
        } finally { events.shutdown(); }
    }

    /** 在隔离JVM真实重置全局SLF4J LoggerContext，不破坏同fork其他测试日志配置。 */
    public static final class ResetProbe {
        /** 只允许测试启动器调用。 */ private ResetProbe() { }
        /** 事件服务已装配后，模拟主logback配置真实reset，之后仍须输出分享事件。 */
        public static void main(String[] arguments) {
            var events = new DashboardShareSecurityEvents(new DashboardShareRuntimeProperties(
                    true, "https://example.test", false, arguments[0]), new SimpleMeterRegistry());
            try {
                ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
                events.record(DashboardShareSecurityEvents.Type.REQUEST, DashboardShareSecurityEvents.Outcome.DENIED,
                        DashboardShareSecurityEvents.Reason.INVALID_CREDENTIAL, null,
                        DashboardShareSecurityEvents.Route.CONTEXT, null, null, 0, 1);
            } finally { events.shutdown(); }
        }
    }

    /** 启用属性使用真实临时目录。 */
    private DashboardShareRuntimeProperties properties() {
        return new DashboardShareRuntimeProperties(true, "https://example.test", false, directory.toString());
    }
}
