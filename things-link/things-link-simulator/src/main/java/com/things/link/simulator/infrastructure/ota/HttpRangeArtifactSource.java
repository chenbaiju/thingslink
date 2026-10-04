package com.things.link.simulator.infrastructure.ota;

import com.things.link.simulator.application.ota.OtaArtifactException;
import com.things.link.simulator.application.ota.OtaArtifactSource;
import com.things.link.simulator.application.ota.OtaDownloadAssignment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 基于 {@code java.net.http.HttpClient} 的 Range 下载实现，不引入新依赖。
 *
 * <p><b>为什么必须显式识别「服务器忽略了 Range」：</b>断点续传的前提是响应字节确实从请求偏移开始。
 * 若服务器不支持 Range 而回了 {@code 200} 并把整个对象从 0 发回，设备把这段字节写进续传位置就会
 * 静默写坏镜像。因此本实现只在 {@code 206 + Content-Range} 起点精确等于请求偏移时接受续传；
 * 请求偏移大于 0 却收到 {@code 200} 时直接以 {@link OtaArtifactException.Kind#RESUME_REJECTED}
 * 报告，绝不假装续传成功。</p>
 *
 * <p><b>为什么传输层不校验摘要：</b>摘要与「镜像是否可信」属于状态机的 {@code VERIFYING} 安全裁决，
 * 传输层只负责如实报告服务器给出的长度与范围。把摘要校验混进传输层会让「HTTP 成功」被误读成
 * 「镜像可信」。</p>
 *
 * <p>本类不做 MQTT 发布、不持有签名私钥，也不需要 Spring 装配：它由调用方显式构造，属于纯设备侧端口实现。</p>
 */
public final class HttpRangeArtifactSource implements OtaArtifactSource {

    /** 分片读取默认上限；状态机通过连续 fetch 覆盖整个 artifact。 */
    private static final long DEFAULT_MAX_CHUNK_BYTES = 1L << 20;

    /** 允许的最大 artifact 尺寸；与 ADR0131 的 artifactSize 上限一致，超限直接拒绝。 */
    private static final long DEFAULT_MAX_ARTIFACT_BYTES = OtaDownloadAssignment.MAX_ARTIFACT_SIZE;

    /** 建连超时；真正的整体期限由每次请求的 budget 控制。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 读取缓冲。 */
    private static final int READ_BUFFER_BYTES = 8192;

    /** 单次 fetch 允许返回的最大字节数；服务器可能回整个尾部，这里按分片上限截断。 */
    private final long maxChunkBytes;

    /** 允许的 artifact 最大尺寸。 */
    private final long maxArtifactBytes;

    /** 复用的 HTTP 客户端。 */
    private final HttpClient client;

    /**
     * 使用默认分片与尺寸上限创建实现。
     */
    public HttpRangeArtifactSource() {
        this(DEFAULT_MAX_CHUNK_BYTES, DEFAULT_MAX_ARTIFACT_BYTES);
    }

    /**
     * 创建实现。
     *
     * @param maxChunkBytes 单次 fetch 最多返回的字节数，必须为正
     * @param maxArtifactBytes 允许的 artifact 最大字节数，必须位于 1..{@link OtaDownloadAssignment#MAX_ARTIFACT_SIZE}
     */
    public HttpRangeArtifactSource(long maxChunkBytes, long maxArtifactBytes) {
        if (maxChunkBytes <= 0L) {
            throw new IllegalArgumentException("maxChunkBytes 必须为正");
        }
        if (maxArtifactBytes <= 0L || maxArtifactBytes > OtaDownloadAssignment.MAX_ARTIFACT_SIZE) {
            throw new IllegalArgumentException(
                    "maxArtifactBytes 必须位于 1.." + OtaDownloadAssignment.MAX_ARTIFACT_SIZE);
        }
        this.maxChunkBytes = maxChunkBytes;
        this.maxArtifactBytes = maxArtifactBytes;
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 请求 {@code Range: bytes=<start>-} 并返回有界分片。
     *
     * <p>顺序固定为先裁决状态码与 Content-Range，再读取有界字节：任何「服务器没有按请求偏移返回」
     * 的情况都在写入暂存区之前被拒绝。</p>
     *
     * @param uri 短期下载地址
     * @param startOffset 本次请求的起始偏移
     * @param expectedLength 预期 artifact 总长度
     * @param budget 本次请求的时间预算
     * @return 收到的有界分片与服务器观察到的范围信息
     * @throws OtaArtifactException 服务器拒绝续传、artifact 超限、长度不符、超时或传输失败时
     */
    @Override
    public Chunk fetch(String uri, long startOffset, long expectedLength, Duration budget) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(budget, "budget");
        if (startOffset < 0L) {
            throw new IllegalArgumentException("startOffset 不能为负数");
        }
        if (expectedLength < 1L || expectedLength > maxArtifactBytes) {
            throw new OtaArtifactException(OtaArtifactException.Kind.ARTIFACT_OVERSIZED,
                    "预期 artifact 尺寸超限");
        }
        if (budget.isZero() || budget.isNegative()) {
            throw new OtaArtifactException(OtaArtifactException.Kind.DEADLINE, "请求预算已耗尽");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(uri))
                    .header("Range", "bytes=" + startOffset + "-")
                    .timeout(budget)
                    .GET()
                    .build();
        } catch (IllegalArgumentException failure) {
            throw new OtaArtifactException(OtaArtifactException.Kind.TRANSPORT, "下载地址不合法", failure);
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException failure) {
            throw new OtaArtifactException(OtaArtifactException.Kind.DEADLINE, "请求超出预算", failure);
        } catch (IOException failure) {
            throw new OtaArtifactException(OtaArtifactException.Kind.TRANSPORT, "下载请求失败", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new OtaArtifactException(OtaArtifactException.Kind.TRANSPORT, "下载请求被中断", failure);
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 206) {
                return partialContent(response, body, startOffset, expectedLength);
            }
            if (status == 200) {
                return fullContent(response, body, startOffset, expectedLength);
            }
            body.transferTo(java.io.OutputStream.nullOutputStream());
            throw new OtaArtifactException(OtaArtifactException.Kind.HTTP_STATUS, "下载响应状态=" + status);
        } catch (IOException failure) {
            throw new OtaArtifactException(OtaArtifactException.Kind.TRANSPORT, "读取下载响应失败", failure);
        }
    }

    /** 处理 206：Content-Range 必须存在且起点精确等于请求偏移。 */
    private Chunk partialContent(HttpResponse<InputStream> response, InputStream body, long startOffset,
                                 long expectedLength) throws IOException {
        long[] range = parseContentRange(response.headers().firstValue("Content-Range").orElse(null));
        if (range == null || range[0] != startOffset) {
            throw new OtaArtifactException(OtaArtifactException.Kind.RESUME_REJECTED,
                    "服务器未按请求偏移返回 Content-Range");
        }
        long total = range[2];
        if (total < 0L) {
            throw new OtaArtifactException(OtaArtifactException.Kind.ARTIFACT_SIZE_MISMATCH,
                    "服务器未声明 artifact 总长度");
        }
        if (total > maxArtifactBytes) {
            throw new OtaArtifactException(OtaArtifactException.Kind.ARTIFACT_OVERSIZED,
                    "artifact 总长度超限");
        }
        if (total != expectedLength || range[1] >= total) {
            throw new OtaArtifactException(OtaArtifactException.Kind.ARTIFACT_SIZE_MISMATCH,
                    "artifact 总长度与预期不一致");
        }
        return new Chunk(readAtMost(body, maxChunkBytes), true, range[0], total);
    }

    /** 处理 200：Range 未被采纳；偏移为 0 时可当作整包下载，偏移大于 0 时必须显式拒绝。 */
    private Chunk fullContent(HttpResponse<InputStream> response, InputStream body, long startOffset,
                              long expectedLength) throws IOException {
        if (startOffset > 0L) {
            throw new OtaArtifactException(OtaArtifactException.Kind.RESUME_REJECTED,
                    "服务器忽略 Range 并从 0 返回，续传被拒绝");
        }
        OptionalLong declared = response.headers().firstValueAsLong("Content-Length");
        if (declared.isPresent() && declared.getAsLong() > maxArtifactBytes) {
            throw new OtaArtifactException(OtaArtifactException.Kind.ARTIFACT_OVERSIZED,
                    "artifact 总长度超限");
        }
        byte[] payload = readAtMost(body, maxArtifactBytes + 1L);
        if (payload.length > maxArtifactBytes) {
            throw new OtaArtifactException(OtaArtifactException.Kind.ARTIFACT_OVERSIZED,
                    "artifact 总长度超限");
        }
        long total = declared.isPresent() ? declared.getAsLong() : payload.length;
        if (total != expectedLength || payload.length != expectedLength) {
            throw new OtaArtifactException(OtaArtifactException.Kind.ARTIFACT_SIZE_MISMATCH,
                    "artifact 总长度与预期不一致");
        }
        return new Chunk(payload, false, 0L, total);
    }

    /**
     * 读取至多 {@code limit} 字节，不等待其余内容。
     *
     * @param body 响应体
     * @param limit 上限，必须为正且不超过 64MiB + 1
     * @return 读到的字节
     * @throws IOException 读取失败时
     */
    private static byte[] readAtMost(InputStream body, long limit) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long remaining = limit;
        while (remaining > 0L) {
            int read = body.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            collected.write(buffer, 0, read);
            remaining -= read;
        }
        return collected.toByteArray();
    }

    /**
     * 解析 {@code Content-Range: bytes <start>-<end>/<total>}。
     *
     * @param header 原始头值，可为 {@code null}
     * @return {@code [start, end, total]}；total 未知时为 -1；格式非法时为 {@code null}
     */
    private static long[] parseContentRange(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        String prefix = "bytes ";
        if (!value.startsWith(prefix)) {
            return null;
        }
        String[] parts = value.substring(prefix.length()).split("/", -1);
        if (parts.length != 2) {
            return null;
        }
        String[] span = parts[0].split("-", -1);
        if (span.length != 2) {
            return null;
        }
        try {
            long start = Long.parseLong(span[0].trim());
            long end = Long.parseLong(span[1].trim());
            String totalText = parts[1].trim();
            long total = "*".equals(totalText) ? -1L : Long.parseLong(totalText);
            if (start < 0L || end < start) {
                return null;
            }
            return new long[] {start, end, total};
        } catch (NumberFormatException failure) {
            return null;
        }
    }
}
