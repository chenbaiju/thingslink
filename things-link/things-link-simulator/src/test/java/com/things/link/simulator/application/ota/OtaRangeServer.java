package com.things.link.simulator.application.ota;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实本地 HTTP artifact 服务器（{@link HttpServer}），刻意用真实 socket 而不是桩：
 * 只有真实 HTTP 往返才能暴露「服务器忽略 Range」「Content-Range 起点不符」「503 抖动」
 * 这类只有传输层才能证伪的行为。
 *
 * <p>服务器默认支持 {@code Range: bytes=<start>-} 并返回精确的 {@code 206 Content-Range}；
 * {@link #ignoreRange()} 切换到「不支持 Range、总是回 200 整包」的变体，
 * {@link #misreportStart()} 返回错位的 Content-Range，{@link #failNextRequests(int)} 注入瞬时 503。</p>
 *
 * <p><b>为什么是 public：</b>真实 Broker 集成测试位于 {@code infrastructure.ota} 包，必须在这个
 * 「真实 socket」夹具上验证设备确实经 HTTP Range 取回了字节；把夹具暴露给跨包测试，比在第二个包里
 * 复制一份 HTTP 服务器更能保证两条链路测的是同一份传输行为。</p>
 */
public final class OtaRangeServer implements AutoCloseable {

    /** 被测 artifact 内容。 */
    private final byte[] artifact;

    /** 真实 HTTP 服务器，绑定回环随机端口。 */
    private final HttpServer server;

    /** 单线程守护执行器，避免测试进程因服务器线程无法退出。 */
    private final ExecutorService executor;

    /** 按到达顺序记录的 Range 头；没有该头时记录 {@code <none>}。 */
    private final List<String> rangeRequests = new CopyOnWriteArrayList<>();

    /** 按到达顺序记录的响应状态码。 */
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();

    /** 是否支持 Range；false 时总是回 200 整包。 */
    private volatile boolean honorRange = true;

    /** 是否故意返回错位的 Content-Range 起点。 */
    private volatile boolean misreportStart;

    /** 还要失败多少次（返回 503）。 */
    private final AtomicInteger remainingFailures = new AtomicInteger();

    /**
     * 启动服务器。
     *
     * @param artifact 固定 artifact 字节
     * @throws IOException 端口绑定失败时
     */
    public OtaRangeServer(byte[] artifact) throws IOException {
        this.artifact = artifact.clone();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/artifact", this::handle);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ota-range-test-server");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(executor);
        this.server.start();
    }

    /**
     * @return artifact 下载地址
     */
    public URI artifactUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/artifact");
    }

    /**
     * 让接下来的 N 次请求返回 503。
     *
     * @param count 失败次数
     */
    void failNextRequests(int count) {
        remainingFailures.set(count);
    }

    /** 关闭 Range 支持：总是回 200 整包。 */
    void ignoreRange() {
        honorRange = false;
    }

    /** 返回错位的 Content-Range 起点，用于验证实现不会把错位响应当成续传成功。 */
    void misreportStart() {
        misreportStart = true;
    }

    /**
     * @return 按到达顺序记录的 Range 头
     */
    public List<String> rangeRequests() {
        return List.copyOf(rangeRequests);
    }

    /**
     * @return 按到达顺序记录的响应状态码
     */
    List<Integer> statuses() {
        return List.copyOf(statuses);
    }

    /** 清空观测记录，便于把「第一次执行」和「恢复执行」的请求分开断言。 */
    void clearObservations() {
        rangeRequests.clear();
        statuses.clear();
    }

    /** 停止服务器并释放端口。 */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** 处理单次 GET；任何客户端提前断开导致的写失败都不应影响断言。 */
    private void handle(HttpExchange exchange) {
        String range = exchange.getRequestHeaders().getFirst("Range");
        rangeRequests.add(range == null ? "<none>" : range);
        if (consumeFailure()) {
            statuses.add(503);
            respondEmpty(exchange, 503);
            return;
        }
        try {
            if (!honorRange) {
                statuses.add(200);
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, artifact.length);
                write(exchange, artifact, 0, artifact.length);
                return;
            }
            long start = parseStart(range);
            if (start < 0L || start >= artifact.length) {
                statuses.add(416);
                respondEmpty(exchange, 416);
                return;
            }
            long reportedStart = misreportStart ? Math.min(start + 1L, artifact.length - 1L) : start;
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + reportedStart + "-" + (artifact.length - 1L) + "/" + artifact.length);
            statuses.add(206);
            exchange.sendResponseHeaders(206, artifact.length - start);
            write(exchange, artifact, (int) start, (int) (artifact.length - start));
        } catch (IOException ignored) {
            // 客户端按分片上限读满后会主动关闭连接；本地测试服务器忽略由此产生的写失败。
        } finally {
            exchange.close();
        }
    }

    /** 返回没有正文的响应。 */
    private static void respondEmpty(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1L);
        } catch (IOException ignored) {
            // 客户端已断开时无需再回空响应。
        }
        exchange.close();
    }

    /** 写正文并吞掉客户端提前断开产生的异常。 */
    private static void write(HttpExchange exchange, byte[] data, int offset, int length) throws IOException {
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(data, offset, length);
        }
    }

    /** 以 CAS 消费一次注入失败。 */
    private boolean consumeFailure() {
        int current;
        do {
            current = remainingFailures.get();
            if (current <= 0) {
                return false;
            }
        } while (!remainingFailures.compareAndSet(current, current - 1));
        return true;
    }

    /** 解析 {@code bytes=<start>-}；无法解析时返回 -1。 */
    private static long parseStart(String range) {
        if (range == null) {
            return 0L;
        }
        String value = range.trim();
        String prefix = "bytes=";
        if (!value.startsWith(prefix)) {
            return -1L;
        }
        String[] span = value.substring(prefix.length()).split("-", -1);
        try {
            return Long.parseLong(span[0].trim());
        } catch (RuntimeException failure) {
            return -1L;
        }
    }
}
