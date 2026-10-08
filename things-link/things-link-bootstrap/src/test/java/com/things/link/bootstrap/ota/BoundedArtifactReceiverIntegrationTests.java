package com.things.link.bootstrap.ota;

import com.things.link.support.storage.BoundedArtifactReceiver;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.util.EnumSet;
import java.util.List;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 独立真实 Tomcat 验证非阻塞正文接收，不依赖业务数据库或模拟 Servlet 流。 */
class BoundedArtifactReceiverIntegrationTests {
    /** 容器独占工作目录。 */
    @TempDir Path containerDirectory;
    /** 真实接收组件。 */
    private BoundedArtifactReceiver receiver;
    /** 独立嵌入容器。 */
    private Tomcat tomcat;
    /** 真实 HTTP 客户端。 */
    private HttpClient client;
    /** 每个请求显式关联自己的测试工作。 */
    private final Map<String, TestWork> workItems = new ConcurrentHashMap<>();
    /** 容器随机端口。 */
    private int port;

    /** 每例创建无 Spring、无数据库的异步 Servlet 容器。 */
    @BeforeEach
    void start() throws Exception {
        receiver = new BoundedArtifactReceiver(Duration.ofSeconds(2), containerDirectory.resolve("receiver-root"));
        tomcat = new Tomcat();
        tomcat.setBaseDir(containerDirectory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", containerDirectory.toString());
        Wrapper servlet = Tomcat.addServlet(context, "receiver", new ReceiverServlet());
        servlet.setAsyncSupported(true);
        context.addServletMappingDecoded("/upload/*", "receiver");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** 先释放测试阻挡，再关闭组件和容器，防止失败断言遗留工作。 */
    @AfterEach
    void stop() throws Exception {
        workItems.values().forEach(work -> work.release.countDown());
        try { if (receiver != null) { receiver.close(); } }
        finally {
            if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
            if (client != null) { client.close(); }
        }
    }

    /** 正常正文精确交接，文件和父目录权限最小化，完成后实际删除文件。 */
    @Test
    void receivesVerifiedBytesWithPrivatePermissionsAndRemovesFile() throws Exception {
        byte[] bytes = "固件\u0000body".getBytes(StandardCharsets.UTF_8);
        TestWork work = register(bytes.length, sha(bytes), false);
        assertThat(send(work, bytes).get(8, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(work.bytes).containsExactly(bytes);
        assertThat(work.filePermissions).isEqualTo("rw-------");
        assertThat(work.directoryPermissions).isEqualTo("rwx------");
        await(() -> !Files.exists(work.path));
        assertThat(work.completed.get()).isEqualTo(1);
        assertThat(work.failed.get()).isZero();
    }

    /** 64MiB整边界使用64KiB固定生成缓冲与流式验真，避免验收自身双倍加载大正文。 */
    @Test
    void acceptsExactlySixtyFourMebibytesAndRejectsLargerDeclaration() throws Exception {
        receiver.close();
        receiver = new BoundedArtifactReceiver(Duration.ofSeconds(120), containerDirectory.resolve("receiver-root"));
        long limit = 64L * 1024 * 1024;
        // Python hashlib独立固定向量：1024次更新65536个ASCII Z，不复用生产摘要结果。
        String expected = "103f23a15401a701b73587902f16e3b5b3bf38a039d5c94b675a9a8e84dbd5b5";
        TestWork work = register(limit, expected, false);
        work.streamOnly = true;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload/" + work.id))
                .timeout(Duration.ofSeconds(150))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new RepeatingBodyInputStream(limit))).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(work.actualLength).isEqualTo(limit);
        assertThat(work.actualSha256).isEqualTo(expected);
        assertThat(work.bytes).isNull();
        assertThat(work.completed.get()).isEqualTo(1);
        assertThat(work.failed.get()).isZero();
        await(() -> !Files.exists(work.path));
        TestWork oversized = register(limit + 1, expected, false);
        assertThat(send(oversized, new byte[] {1}).get(5, TimeUnit.SECONDS).body()).isEqualTo("INVALID_INPUT");
        assertThat(oversized.completed.get()).isZero();
        assertThat(oversized.failed.get()).isEqualTo(1);
    }

    /** HTTP 完整短正文、超长正文和错误摘要均不得进入业务工作。 */
    @Test
    void rejectsShortLongAndWrongDigestExactlyOnce() throws Exception {
        for (TestWork work : new TestWork[] {
                register(3, sha(new byte[] {1, 2, 3}), false),
                register(1, sha(new byte[] {1}), false),
                register(2, sha(new byte[] {9, 9}), false)}) {
            HttpResponse<String> response = send(work, new byte[] {1, 2}).get(8, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isIn("LENGTH_MISMATCH", "DIGEST_MISMATCH");
            assertThat(work.failed.get()).isEqualTo(1);
            assertThat(work.completed.get()).isZero();
        }
    }

    /** 故意永不发送 chunked 终块，截止必须主动回包并物理关闭连接。 */
    @Test
    void slowChunkedBodyTimesOutAndClosesSocketWithoutClientRelease() throws Exception {
        TestWork work = register(4, sha(new byte[] {1, 2, 3, 4}), false);
        long started = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(7_000);
            String headers = "POST /upload/" + work.id + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n1\r\nx\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            // readAllBytes 只有真正 EOF 才返回；此处不 shutdownOutput，也不主动释放慢正文。
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response).contains("TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7));
            assertThat(work.failed.get()).isEqualTo(1);
            assertThat(work.completed.get()).isZero();
        }
        receiver.close();
        assertThat(work.failed.get()).isEqualTo(1);
    }

    /** 客户端截断仍未完成的正文后，断连和关闭不得产生重复失败回调。 */
    @org.junit.jupiter.api.RepeatedTest(20)
    void disconnectedBodyFailsOnlyOnceIncludingSubsequentClose() throws Exception {
        TestWork work = register(4, sha(new byte[] {1, 2, 3, 4}), false);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String headers = "POST /upload/" + work.id + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Length: 4\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            // 先确认 Servlet 已登记真实接收工作，避免连接在分派前截断而只由容器处理。
            assertThat(work.accepted.await(3, TimeUnit.SECONDS)).isTrue();
            socket.getOutputStream().write('x');
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            await(() -> work.failed.get() == 1);
        }
        try { receiver.close(); }
        catch (RuntimeException failure) {
            // 仅失败时保存真实线程与持锁关系，不延长关闭预算或跳过资源断言。
            StringBuilder diagnostics=new StringBuilder();
            for(var thread:java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true,true)) {
                diagnostics.append(thread).append("\n");
                for(var frame:thread.getStackTrace()) diagnostics.append("    at ").append(frame).append("\n");
            }
            failure.addSuppressed(new IllegalStateException(diagnostics.toString()));
            throw failure;
        }
        assertThat(work.failed.get()).isEqualTo(1);
        assertThat(work.completed.get()).isZero();
        // 断连后必须真正删去正文及实例目录，不能仅把回调计数当作资源已经回收。
        try(var entries=Files.list(containerDirectory.resolve("receiver-root"))) {
            assertThat(entries.map(path->path.getFileName().toString())).containsExactly("maintenance.lock");
        }
    }

    /** 两个完成正文但仍处理文件的工作持续占名额，第三个请求明确忙。 */
    @Test
    void retainsTwoSlotsThroughBusinessWork() throws Exception {
        byte[] bytes = {1};
        TestWork first = register(1, sha(bytes), true);
        TestWork second = register(1, sha(bytes), true);
        CompletableFuture<HttpResponse<String>> one = send(first, bytes);
        CompletableFuture<HttpResponse<String>> two = send(second, bytes);
        assertThat(first.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(second.entered.await(5, TimeUnit.SECONDS)).isTrue();
        TestWork third = register(1, sha(bytes), false);
        assertThat(send(third, bytes).get(5, TimeUnit.SECONDS).body()).isEqualTo("BUSY");
        assertThat(third.failed.get()).isEqualTo(1);
        first.release.countDown();
        second.release.countDown();
        assertThat(one.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(two.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    /** 租约取消只通知正在读取文件的工作，不能并发删除其文件。 */
    @Test
    void cancellationRetainsFileUntilWorkExits() throws Exception {
        TestWork work = register(1, sha(new byte[] {1}), true);
        CompletableFuture<HttpResponse<String>> response = send(work, new byte[] {1});
        assertThat(work.entered.await(5, TimeUnit.SECONDS)).isTrue();
        work.cancelled.set(true);
        await(() -> work.cancellation.getAsBoolean());
        assertThat(Files.readAllBytes(work.path)).containsExactly((byte) 1);
        assertThat(work.failed.get()).isZero();
        work.release.countDown();
        response.get(5, TimeUnit.SECONDS);
        await(() -> !Files.exists(work.path));
        assertThat(work.completed.get()).isEqualTo(1);
        assertThat(work.failed.get()).isZero();
    }

    /** 活跃工作观察关闭信号退出，组件回收文件和目录且不补发 failed。 */
    @Test
    void closingComponentCancelsActiveWorkAndRemovesOwnedDirectory() throws Exception {
        TestWork work = register(1, sha(new byte[] {1}), true);
        work.exitOnCancellation = true;
        CompletableFuture<HttpResponse<String>> response = send(work, new byte[] {1});
        assertThat(work.entered.await(5, TimeUnit.SECONDS)).isTrue();
        Path directory = work.path.getParent();
        long started = System.nanoTime();
        receiver.close();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        response.get(5, TimeUnit.SECONDS);
        assertThat(Files.exists(directory)).isFalse();
        assertThat(work.cancellation.getAsBoolean()).isTrue();
        assertThat(work.failed.get()).isZero();
        receiver.close();
    }

    /** 第二组件启动不能回收第一组件仍持锁且被工作读取的文件。 */
    @Test
    void startupPreservesAnotherActiveInstance() throws Exception {
        TestWork work = register(1, sha(new byte[] {1}), true);
        CompletableFuture<HttpResponse<String>> response = send(work, new byte[] {1});
        assertThat(work.entered.await(5, TimeUnit.SECONDS)).isTrue();
        try (BoundedArtifactReceiver other = new BoundedArtifactReceiver(Duration.ofSeconds(2),
                containerDirectory.resolve("receiver-root"))) {
            assertThat(Files.readAllBytes(work.path)).containsExactly((byte) 1);
        }
        assertThat(Files.exists(work.path)).isTrue();
        work.release.countDown();
        response.get(5, TimeUnit.SECONDS);
    }

    /** 独立进程持锁时保留，强制终止进程后由下一实例精准回收崩溃正文。 */
    @Test
    void startupReclaimsOnlyAfterOwnerProcessDies() throws Exception {
        Path root = containerDirectory.resolve("receiver-root");
        Path abandoned = createPrivateEntry(root.resolve("instance-" + UUID.randomUUID()), true);
        Path lock = createPrivateEntry(abandoned.resolve("owner.lock"), false);
        Path body = createPrivateEntry(abandoned.resolve("body-123.part"), false);
        Files.write(body, new byte[] {1, 2, 3});
        Path diagnostics = containerDirectory.resolve("lock-owner.stderr.log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LockOwnerProcess.class.getName(), lock.toString());
        // 启动诊断属于stderr，stdout只承载持锁握手；每轮显式制造JVM提示反例。
        builder.environment().merge("JAVA_TOOL_OPTIONS", "-Dthingslink.test.lock.stdout-separation=true",
                (existing, probe) -> existing + " " + probe);
        Process process = builder.redirectErrorStream(false).redirectError(diagnostics.toFile()).start();
        try {
            CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
                try { return process.inputReader().readLine(); }
                catch (IOException exception) { throw new IllegalStateException(exception); }
            });
            assertThat(ready.get(5, TimeUnit.SECONDS)).isEqualTo("LOCKED");
            try (BoundedArtifactReceiver other = new BoundedArtifactReceiver(Duration.ofSeconds(2), root)) {
                assertThat(Files.exists(body)).isTrue();
            }
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.readString(diagnostics)).contains("Picked up JAVA_TOOL_OPTIONS");
            try (BoundedArtifactReceiver other = new BoundedArtifactReceiver(Duration.ofSeconds(2), root)) {
                assertThat(Files.exists(abandoned)).isFalse();
            }
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** 未知文件不被递归删除，维护明确失败且目标文件保持原样。 */
    @Test
    void startupRefusesUnknownContentsWithoutDeletingTargets() throws Exception {
        Path root = containerDirectory.resolve("receiver-root");
        Path abandoned = createPrivateEntry(root.resolve("instance-" + UUID.randomUUID()), true);
        createPrivateEntry(abandoned.resolve("owner.lock"), false);
        Path unknown = Files.writeString(abandoned.resolve("unrecognized"), "keep too");
        assertThatThrownBy(() -> new BoundedArtifactReceiver(Duration.ofSeconds(2), root))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(unknown)).isEqualTo("keep too");
    }

    /** Windows 无符号链接特权时仅跳过此项；其余目录维护测试照常执行。 */
    @Test
    void startupRefusesLinkedContentsWithoutDeletingTargets() throws Exception {
        Path root = containerDirectory.resolve("receiver-root");
        Path abandoned = createPrivateEntry(root.resolve("instance-" + UUID.randomUUID()), true);
        createPrivateEntry(abandoned.resolve("owner.lock"), false);
        Path outside = Files.writeString(containerDirectory.resolve("unrelated"), "keep");
        Path link;
        try { link = Files.createSymbolicLink(abandoned.resolve("body-123.part"), outside); }
        catch (java.nio.file.FileSystemException exception) {
            if (!outside.getFileSystem().supportedFileAttributeViews().contains("acl")) { throw exception; }
            org.junit.jupiter.api.Assumptions.abort("Windows cannot create test symlink: " + exception.getReason());
            return;
        }
        assertThatThrownBy(() -> new BoundedArtifactReceiver(Duration.ofSeconds(2), root))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(outside)).isEqualTo("keep");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        Files.delete(link);
    }

    /** 超过启动维护预算即失败，不通过无界遍历或部分递归删除逃避上限。 */
    @Test
    void startupRejectsExcessiveInstanceInventory() throws Exception {
        Path root = containerDirectory.resolve("over-budget-root");
        createPrivateEntry(root, true);
        for (int index = 0; index < 129; index++) {
            createPrivateEntry(root.resolve("instance-" + UUID.randomUUID()), true);
        }
        assertThatThrownBy(() -> new BoundedArtifactReceiver(Duration.ofSeconds(2), root))
                .isInstanceOf(IllegalStateException.class);
        try (var entries = Files.list(root)) {
            assertThat(entries.filter(path -> path.getFileName().toString().startsWith("instance-")).count())
                    .isEqualTo(129);
        }
    }

    /** 测试夹具独立设置平台原生权限。 */
    private static Path createPrivateEntry(Path path, boolean directory) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return directory ? Files.createDirectory(path, permissions) : Files.createFile(path, permissions);
        }
        var owner = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        var entries = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
        FileAttribute<List<AclEntry>> permissions = new FileAttribute<>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<AclEntry> value() { return entries; }
        };
        // Windows 的继承 ACL 可能不授予 WRITE_OWNER；创建时先取得私有 ACL，再设置所有者。
        if (directory) { Files.createDirectory(path, permissions); } else { Files.createFile(path, permissions); }
        Files.setOwner(path, owner);
        verifiedPermissions(path, directory);
        return path;
    }

    /** 检查真实 ACL 或 POSIX 权限后返回统一语义，不能在 Windows 跳过权限断言。 */
    private static String verifiedPermissions(Path path, boolean directory) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        }
        var owner = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        assertThat(Files.getOwner(path)).isEqualTo(owner);
        var entries = Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().type()).isEqualTo(AclEntryType.ALLOW);
        assertThat(entries.getFirst().principal()).isEqualTo(owner);
        assertThat(entries.getFirst().flags()).isEmpty();
        assertThat(entries.getFirst().permissions()).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(AclEntryPermission.class));
        return directory ? "rwx------" : "rw-------";
    }

    /** 启动失败必须保留底层原因，便于定位目录权限和维护错误。 */
    @Test
    void startupRetainsUnderlyingFailure() throws Exception {
        Path file = Files.writeString(containerDirectory.resolve("not-a-directory"), "keep");
        assertThatThrownBy(() -> new BoundedArtifactReceiver(Duration.ofSeconds(2), file))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo("keep");
    }

    /** 已有目录的宽松权限必须拒绝，不能为了跨平台启动静默放宽隔离。 */
    @Test
    void startupRejectsUnsafeExistingPermissions() throws Exception {
        Path root = createPrivateEntry(containerDirectory.resolve("unsafe-root"), true);
        if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        } else {
            Files.getFileAttributeView(root, AclFileAttributeView.class).setAcl(
                    Files.getFileAttributeView(containerDirectory, AclFileAttributeView.class).getAcl());
        }
        assertThatThrownBy(() -> new BoundedArtifactReceiver(Duration.ofSeconds(2), root))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IOException.class);
        assertThat(Files.isDirectory(root)).isTrue();
    }

    /** 真实 Spring Bean 生命周期完成初始化和销毁，第二次启动可以复用根目录。 */
    @Test
    void springContextStartsAndRestarts() throws Exception {
        Path root = containerDirectory.resolve("spring-root");
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.registerBean(BoundedArtifactReceiver.class,
                        () -> new BoundedArtifactReceiver(Duration.ofSeconds(2), root));
                context.refresh();
                assertThat(context.getBean(BoundedArtifactReceiver.class)).isNotNull();
            }
            try (var entries = Files.list(root)) {
                assertThat(entries.map(path -> path.getFileName().toString())).containsExactly("maintenance.lock");
            }
        }
    }

    /** 独立进程锁持有者，仅测试OS进程死亡自动释放文件锁这一事实。 */
    public static final class LockOwnerProcess {
        /** 持锁直到父进程强制终止，不自行释放以避免伪造崩溃证据。 */
        public static void main(String[] arguments) throws Exception {
            try (FileChannel channel = FileChannel.open(Path.of(arguments[0]), StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                System.out.println("LOCKED");
                System.out.flush();
                System.in.read();
            }
        }
    }

    /** 注册不会继承调用线程状态的显式测试工作。 */
    private TestWork register(long length, String sha, boolean block) {
        TestWork work = new TestWork(length, sha, block);
        workItems.put(work.id, work);
        return work;
    }

    /** 向真实随机端口提交字节正文。 */
    private CompletableFuture<HttpResponse<String>> send(TestWork work, byte[] bytes) {
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/upload/" + work.id)).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 计算独立期望摘要。 */
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 有界等待异步资源收束，不靠固定长暂停掩盖竞态。 */
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** 只负责测试路由和技术接收器接线。 */
    private final class ReceiverServlet extends HttpServlet {
        /** Servlet 序列化标识。 */
        private static final long serialVersionUID = 1L;
        /** 将独立工作绑定到本次响应，生产认证不在本组件专项范围内。 */
        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) {
            TestWork work = workItems.get(request.getPathInfo().substring(1));
            work.response = response;
            receiver.receive(request, response, work.length, work.sha, work);
            work.accepted.countDown();
        }
    }

    /** 固定64KiB重复生成流，不创建与64MiB声明长度等大的内存数组。 */
    private static final class RepeatingBodyInputStream extends InputStream {
        /** 一次最多复制的固定内容块。 */
        private final byte[] block = new byte[65_536];
        /** 尚未生成的准确正文长度。 */
        private long remaining;
        /** 配置固定大正文长度与ASCII Z内容。 */
        RepeatingBodyInputStream(long length) { remaining = length; Arrays.fill(block, (byte) 'Z'); }
        /** 支持标准单字节读，不越过声明的生成终点。 */
        @Override public int read() {
            if (remaining == 0) { return -1; }
            remaining--;
            return 'Z';
        }
        /** 每次最多复制一个固定块，供HTTP BodyPublisher流式拉取。 */
        @Override public int read(byte[] target, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) { return 0; }
            if (remaining == 0) { return -1; }
            int count = (int) Math.min(Math.min(length, block.length), remaining);
            System.arraycopy(block, 0, target, offset, count);
            remaining -= count;
            return count;
        }
    }

    /** 可控工作以真实文件和锁存器证明资源所有权及取消边界。 */
    private static final class TestWork implements BoundedArtifactReceiver.Work {
        /** 唯一路由标识。 */
        private final String id = UUID.randomUUID().toString();
        /** 期望长度。 */
        private final long length;
        /** 期望摘要。 */
        private final String sha;
        /** 后续工作入口。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** Servlet 已将请求交给真实接收组件。 */
        private final CountDownLatch accepted = new CountDownLatch(1);
        /** 人工释放工作，仅由测试控制。 */
        private final CountDownLatch release;
        /** 外部租约取消信号。 */
        private final AtomicBoolean cancelled = new AtomicBoolean();
        /** 成功交接次数。 */
        private final AtomicInteger completed = new AtomicInteger();
        /** 失败回调次数。 */
        private final AtomicInteger failed = new AtomicInteger();
        /** 已交接文件路径。 */
        private volatile Path path;
        /** 在工作持有文件期间读到的内容。 */
        private volatile byte[] bytes;
        /** 大正文例只流式核验，不保存完整数组。 */
        private volatile boolean streamOnly;
        /** 文件实际长度。 */
        private volatile long actualLength;
        /** 独立流式读取所得摘要。 */
        private volatile String actualSha256;
        /** 文件实际权限。 */
        private volatile String filePermissions;
        /** 目录实际权限。 */
        private volatile String directoryPermissions;
        /** 组件工作取消信号。 */
        private volatile BooleanSupplier cancellation;
        /** 关闭测试允许工作自行响应取消退出。 */
        private volatile boolean exitOnCancellation;
        /** 显式传入的本次 HTTP 响应。 */
        private HttpServletResponse response;
        /** 配置字节合同及人工阻挡。 */
        TestWork(long length, String sha, boolean block) {
            this.length = length;
            this.sha = sha;
            release = new CountDownLatch(block ? 1 : 0);
        }
        /** 验证文件处于受限权限，并保持读权直到允许工作退出。 */
        @Override
        public void completed(Path source, BooleanSupplier cancellation) throws Exception {
            this.path = source;
            this.cancellation = cancellation;
            if (streamOnly) {
                actualLength = Files.size(source);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(source)) {
                    byte[] buffer = new byte[65_536];
                    int count;
                    while ((count = input.read(buffer)) >= 0) { digest.update(buffer, 0, count); }
                }
                actualSha256 = HexFormat.of().formatHex(digest.digest());
            } else { bytes = Files.readAllBytes(source); }
            filePermissions = verifiedPermissions(source, false);
            directoryPermissions = verifiedPermissions(source.getParent(), true);
            completed.incrementAndGet();
            entered.countDown();
            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                if (exitOnCancellation && cancellation.getAsBoolean()) { break; }
            }
            response.setStatus(200);
            response.getWriter().write("OK");
        }
        /** 一次固定失败响应，不由回调关闭容器网络流。 */
        @Override
        public void failed(BoundedArtifactReceiver.Failure failure) throws IOException {
            failed.incrementAndGet();
            response.setStatus(400);
            response.getWriter().write(failure.reason().name());
        }
        /** 非阻塞读取外部取消，不访问网络或数据库。 */
        @Override
        public boolean cancelled() { return cancelled.get(); }
    }
}
