package com.things.link.simulator.application.ota;

import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接验证真实 HTTP Range 端口契约：206 起点、200 忽略 Range、错位 Content-Range、超限与长度不符。
 *
 * <p>这些断言刻意停留在传输层，证明「服务器没按请求偏移返回」不会被伪装成续传成功。</p>
 */
class HttpRangeArtifactSourceTests {

    /** 测试用 artifact。 */
    private static final byte[] ARTIFACT = OtaTestFixtures.artifact(3072);

    /** 单次请求预算。 */
    private static final Duration BUDGET = Duration.ofSeconds(10);

    /**
     * 206 且 Content-Range 起点等于请求偏移 → 接受，并如实回报总长度。
     */
    @Test
    void acceptsPartialContentAtRequestedOffset() throws Exception {
        try (OtaRangeServer server = new OtaRangeServer(ARTIFACT)) {
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(1024L,
                    OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            OtaArtifactSource.Chunk chunk = source.fetch(server.artifactUri().toString(), 512L,
                    ARTIFACT.length, BUDGET);

            assertThat(chunk.contentRangeAccepted()).isTrue();
            assertThat(chunk.contentRangeStart()).isEqualTo(512L);
            assertThat(chunk.totalLength()).isEqualTo(ARTIFACT.length);
            assertThat(chunk.payload()).hasSize(1024);
            assertThat(chunk.payload()).isEqualTo(Arrays.copyOfRange(ARTIFACT, 512, 1536));
        }
    }

    /**
     * 服务器忽略 Range 并回 200；请求偏移大于 0 时必须显式报告续传被拒。
     */
    @Test
    void reportsIgnoredRangeAsResumeRejected() throws Exception {
        try (OtaRangeServer server = new OtaRangeServer(ARTIFACT)) {
            server.ignoreRange();
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(1024L,
                    OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            assertThat(kindOf(() -> source.fetch(server.artifactUri().toString(), 512L, ARTIFACT.length, BUDGET)))
                    .isEqualTo(OtaArtifactException.Kind.RESUME_REJECTED);
        }
    }

    /**
     * 偏移为 0 时服务器回 200 是可以接受的整包下载，但必须如实标记 Content-Range 未被采纳。
     */
    @Test
    void acceptsFullBodyWhenRangeIgnoredAtZero() throws Exception {
        try (OtaRangeServer server = new OtaRangeServer(ARTIFACT)) {
            server.ignoreRange();
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(1024L,
                    OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            OtaArtifactSource.Chunk chunk = source.fetch(server.artifactUri().toString(), 0L,
                    ARTIFACT.length, BUDGET);

            assertThat(chunk.contentRangeAccepted()).isFalse();
            assertThat(chunk.contentRangeStart()).isZero();
            assertThat(chunk.totalLength()).isEqualTo(ARTIFACT.length);
            assertThat(chunk.payload()).isEqualTo(ARTIFACT);
        }
    }

    /**
     * 206 的 Content-Range 起点与请求偏移不符时必须拒绝，不能按偏移写入。
     */
    @Test
    void rejectsMismatchedContentRangeStart() throws Exception {
        try (OtaRangeServer server = new OtaRangeServer(ARTIFACT)) {
            server.misreportStart();
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(1024L,
                    OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            assertThat(kindOf(() -> source.fetch(server.artifactUri().toString(), 512L, ARTIFACT.length, BUDGET)))
                    .isEqualTo(OtaArtifactException.Kind.RESUME_REJECTED);
        }
    }

    /**
     * 服务器声明的总长度超过实现上限 → ARTIFACT_OVERSIZED。
     */
    @Test
    void rejectsOversizedDeclaredArtifact() throws Exception {
        try (OtaRangeServer server = new OtaRangeServer(OtaTestFixtures.artifact(8192))) {
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(1024L, 4096L);

            assertThat(kindOf(() -> source.fetch(server.artifactUri().toString(), 0L, 4096L, BUDGET)))
                    .isEqualTo(OtaArtifactException.Kind.ARTIFACT_OVERSIZED);
        }
    }

    /**
     * 服务器声明的总长度与预期不符 → ARTIFACT_SIZE_MISMATCH。
     */
    @Test
    void rejectsDeclaredTotalMismatch() throws Exception {
        try (OtaRangeServer server = new OtaRangeServer(ARTIFACT)) {
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(1024L,
                    OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            assertThat(kindOf(() -> source.fetch(server.artifactUri().toString(), 0L, 2048L, BUDGET)))
                    .isEqualTo(OtaArtifactException.Kind.ARTIFACT_SIZE_MISMATCH);
        }
    }

    /**
     * 单次分片字节数受 {@code maxChunkBytes} 限制，服务器发送整个尾部也不会一次读满。
     */
    @Test
    void boundsSingleChunkSize() throws Exception {
        try (OtaRangeServer server = new OtaRangeServer(ARTIFACT)) {
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(100L,
                    OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            OtaArtifactSource.Chunk chunk = source.fetch(server.artifactUri().toString(), 0L,
                    ARTIFACT.length, BUDGET);

            assertThat(chunk.payload()).hasSize(100);
            assertThat(chunk.totalLength()).isEqualTo(ARTIFACT.length);
        }
    }

    /** 捕获并返回失败类别，避免依赖断言库的泛型推断差异。 */
    private static OtaArtifactException.Kind kindOf(ThrowingCallable callable) {
        try {
            callable.call();
        } catch (OtaArtifactException failure) {
            return failure.kind();
        } catch (Throwable other) {
            throw new AssertionError("期望 OtaArtifactException，实际为 " + other, other);
        }
        throw new AssertionError("期望抛出 OtaArtifactException，但没有异常");
    }
}
