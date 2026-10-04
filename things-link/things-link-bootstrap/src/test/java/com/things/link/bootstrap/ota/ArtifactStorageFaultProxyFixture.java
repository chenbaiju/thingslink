package com.things.link.bootstrap.ota;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅回环测试代理，保留S3签名Host；强制每连接一个HTTP请求以精确观测取消。 */
final class ArtifactStorageFaultProxyFixture implements AutoCloseable {
    /** 故障发生在真实MinIO已经处理请求之后。 */
    enum Mode {
        /** 读取并丢弃写入成功响应，造成真实结果未知。 */ DROP_RESPONSE,
        /** 已完成真实写入后改为500，检测SDK状态码重试。 */ ERROR_RESPONSE,
        /** 完成multipart后替换响应，检查完成请求不隐式重发。 */ ERROR_COMPLETE,
        /** 只丢弃首次真实versioning读取响应，后续同GET正常通过。 */ DROP_FIRST_VERSIONING_RESPONSE,
        /** 每次真实versioning读取都丢响应，验证只读恢复有界。 */ DROP_ALL_VERSIONING_RESPONSES,
        /** 转发头部后阻塞正文，覆盖阻塞read取消。 */ STALL_BODY
    }
    /** 只监听本机随机端口，不暴露代理到外部。 */
    private final ServerSocket listener;
    /** 每任务虚拟线程，关闭代理前先关闭全部socket解锁阻塞。 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 测试容器真实host。 */
    private final String backendHost;
    /** 测试容器随机端口。 */
    private final int backendPort;
    /** 精确对象路径，不影响versioning/policy前置请求或其他对象。 */
    private final String target;
    /** 本例唯一故障类型。 */
    private final Mode mode;
    /** 所有连接均由该夹具拥有，关闭时不能留下后台桥接。 */
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    /** 已收到目标响应头，证明调用确实进入正文而非前置超时。 */
    private final CountDownLatch targetReached = new CountDownLatch(1);
    /** 客户端真实EOF或socket断开，不能用Future取消代替此信号。 */
    private final CountDownLatch clientClosed = new CountDownLatch(1);
    /** 类末释放故意阻塞的下行线程。 */
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    /** 可独立放行的第二条正文路径，用于证明取消时另一个Call仍活跃。 */
    private volatile String companionTarget;
    /** 第二条连接已收到部分正文。 */
    private final CountDownLatch companionReached = new CountDownLatch(1);
    /** 测试显式放行第二条正文，不依赖定时自动恢复。 */
    private final CountDownLatch releaseCompanion = new CountDownLatch(1);
    /** 记录目标请求次数，检测客户端偷偷重试写入。 */
    private final AtomicInteger targets = new AtomicInteger();
    /** 全部PUT计数，资格失败不得进入对象写入。 */
    private final AtomicInteger puts = new AtomicInteger();
    /** 关闭状态用于停止accept循环。 */
    private volatile boolean closed;

    /** 启动只服务本例容器的随机端口代理。 */
    ArtifactStorageFaultProxyFixture(String backendHost, int backendPort, String target, Mode mode) throws IOException {
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.target = target;
        this.mode = mode;
        listener = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        executor.submit(() -> {
            while (!closed) {
                try {
                    Socket client = listener.accept();
                    sockets.add(client);
                    executor.submit(() -> bridge(client));
                } catch (IOException exception) {
                    if (!closed) { throw new IllegalStateException("测试代理监听失败", exception); }
                }
            }
        });
    }

    /** 返回SDK使用的回环入口，不改写S3签名中的Host。 */
    String endpoint() { return "http://localhost:" + listener.getLocalPort(); }
    /** 当前目标调用次数。 */
    int targetRequests() { return targets.get(); }
    /** 精确记录真实PUT，不把只读恢复解释为写重试。 */
    int putRequests() { return puts.get(); }
    /** 等待真实响应头。 */
    boolean awaitTarget(long count, TimeUnit unit) throws InterruptedException { return targetReached.await(count, unit); }
    /** 等待真实客户端连接关闭。 */
    boolean awaitClientClosed(long count, TimeUnit unit) throws InterruptedException { return clientClosed.await(count, unit); }

    /** 登记第二条需要保持活跃的精确路径，在发起对应请求前调用。 */
    void holdCompanion(String path) { companionTarget = path; }
    /** 等待第二条真实连接已转发部分正文。 */
    boolean awaitCompanion(long count, TimeUnit unit) throws InterruptedException {
        return companionReached.await(count, unit);
    }
    /** 仅放行第二条请求，第一条故障仍保持到取消或夹具关闭。 */
    void releaseCompanion() { releaseCompanion.countDown(); }

    /** 双向桥接真实S3请求，仅替换未参与签名的Connection头以便一连接一请求。 */
    private void bridge(Socket client) {
        Socket backend = null;
        boolean selected = false;
        try {
            byte[] header = header(client.getInputStream());
            String text = new String(header, StandardCharsets.ISO_8859_1);
            String[] requestLine = text.substring(0, text.indexOf("\r\n")).split(" ");
            String requestPath = requestLine[1].split("\\?", 2)[0];
            boolean versioningFault = mode == Mode.DROP_FIRST_VERSIONING_RESPONSE || mode == Mode.DROP_ALL_VERSIONING_RESPONSES;
            if (requestLine[0].equals("PUT")) { puts.incrementAndGet(); }
            selected = requestPath.equals(target)
                    && (versioningFault ? requestLine[0].equals("GET")
                        && requestLine[1].matches(".*[?&]versioning(?:=[^&]*)?(?:&.*)?$")
                        : mode == Mode.ERROR_COMPLETE
                        ? requestLine[0].equals("POST") && requestLine[1].contains("uploadId=")
                        : mode != Mode.STALL_BODY ? requestLine[0].equals("PUT") : requestLine[0].equals("GET"));
            boolean companion = requestPath.equals(companionTarget) && requestLine[0].equals("GET");
            int ordinal = selected ? targets.incrementAndGet() : 0;
            backend = new Socket(backendHost, backendPort);
            sockets.add(backend);
            backend.setSoTimeout(15_000);
            StringBuilder forwarded = new StringBuilder();
            for (String line : text.split("\r\n")) {
                if (!line.isEmpty() && !line.regionMatches(true, 0, "Connection:", 0, 11)) {
                    forwarded.append(line).append("\r\n");
                }
            }
            forwarded.append("Connection: close\r\n\r\n");
            backend.getOutputStream().write(forwarded.toString().getBytes(StandardCharsets.ISO_8859_1));
            backend.getOutputStream().flush();
            Socket connection = backend;
            boolean targetConnection = selected;
            executor.submit(() -> {
                try {
                    client.getInputStream().transferTo(connection.getOutputStream());
                } catch (IOException ignored) {
                    // 真实客户端断连可能表现为EOF或连接重置，两者都证明已不再持有下行。
                } finally {
                    if (targetConnection) { clientClosed.countDown(); }
                    closeSocket(connection);
                    closeSocket(client);
                }
            });
            byte[] responseHeader = header(backend.getInputStream());
            if (selected && mode != Mode.STALL_BODY
                    && (mode != Mode.DROP_FIRST_VERSIONING_RESPONSE || ordinal == 1)) {
                // 必须看见真实200，不能把上游认证失败冒充写入或资格读取成功。
                String first = new String(responseHeader, StandardCharsets.ISO_8859_1).split("\r\n", 2)[0];
                if (!first.startsWith("HTTP/1.1 200")) { throw new IOException("预期真实操作成功响应"); }
                targetReached.countDown();
                backend.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                if (mode == Mode.ERROR_RESPONSE || mode == Mode.ERROR_COMPLETE) {
                    byte[] error = "<Error><Code>InternalError</Code><Message>test response loss</Message></Error>"
                            .getBytes(StandardCharsets.UTF_8);
                    String failureHeader = "HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/xml\r\n"
                            + "Content-Length: " + error.length + "\r\nConnection: close\r\n\r\n";
                    client.getOutputStream().write(failureHeader.getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(error);
                    client.getOutputStream().flush();
                }
                return;
            }
            client.getOutputStream().write(responseHeader);
            client.getOutputStream().flush();
            if (selected && mode == Mode.STALL_BODY || companion) {
                // 先向客户端实际发送部分正文，再观察取消；不会把响应头等待冒充阻塞read。
                int firstByte = backend.getInputStream().read();
                if (firstByte < 0) { throw new IOException("故障对象缺少预期正文"); }
                client.getOutputStream().write(firstByte);
                client.getOutputStream().flush();
                if (companion) {
                    companionReached.countDown();
                    releaseCompanion.await();
                } else {
                    targetReached.countDown();
                    releaseBody.await();
                }
            }
            backend.getInputStream().transferTo(client.getOutputStream());
            client.getOutputStream().flush();
        } catch (IOException ignored) {
            // 取消测试刻意断开socket；实际行为由目标计数、EOF及客户端固定异常断言。
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            closeSocket(backend);
            closeSocket(client);
        }
    }

    /** 有界读取单个HTTP头，不提前消耗响应正文或将无限输入读入内存。 */
    private static byte[] header(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int suffix = 0;
        for (int count = 0; count < 65_536; count++) {
            int value = input.read();
            if (value < 0) { throw new IOException("测试HTTP头意外结束"); }
            bytes.write(value);
            suffix = (suffix << 8) | value;
            if (suffix == 0x0d0a0d0a) { return bytes.toByteArray(); }
        }
        throw new IOException("测试HTTP头超出预算");
    }

    /** 只关闭本夹具拥有的socket，重复关闭安全。 */
    private void closeSocket(Socket socket) {
        if (socket != null) {
            sockets.remove(socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** 先解锁故障和I/O，再收束线程；不等待默认连接池空闲超时。 */
    @Override
    public void close() throws Exception {
        closed = true;
        listener.close();
        releaseBody.countDown();
        releaseCompanion.countDown();
        for (Socket socket : sockets.toArray(Socket[]::new)) { closeSocket(socket); }
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            throw new IllegalStateException("测试代理线程没有退出");
        }
    }
}
