package com.things.link.support.storage;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 非阻塞接收固件正文并有界交接工作，不携带调用线程认证上下文或业务发布语义。 */
@Component
public final class BoundedArtifactReceiver implements AutoCloseable {
    /** 原始正文最大64MiB。 */
    private static final long MAX_LENGTH = 64L * 1024 * 1024;
    /** 接收和后续处理共享两个名额，不因EOF提前释放。 */
    private final Semaphore slots = new Semaphore(2);
    /** 本组件仍持有名额的全部接收或处理任务。 */
    private final Set<Transfer> transfers = ConcurrentHashMap.newKeySet();
    /** 后续存储等工作最多两个线程，队列也有固定上限。 */
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> Thread.ofPlatform().name("artifact-receiver-work")
                    .daemon(true).inheritInheritableThreadLocals(false).unstarted(runnable),
            new ThreadPoolExecutor.AbortPolicy());
    /** 只运行单调截止和非阻塞租约取消信号，不执行正文读取。 */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().name("artifact-receiver-cancellation").daemon(true)
                    .inheritInheritableThreadLocals(false).unstarted(runnable));
    /** 每次接收自开始计算的纳秒预算，不覆盖交接后的业务处理预算。 */
    private final long timeoutNanos;
    /** 独占用户私有实例目录（POSIX 0700 或 Windows ACL），启动仅维护本组件专属根目录。 */
    private final Path directory;
    /** 持有操作系统实例存活锁的通道。 */
    private final FileChannel ownerChannel;
    /** 直到文件读者退出且目录删除才释放的实例存活锁。 */
    private final FileLock ownerLock;
    /** 组件关闭后禁止取得新名额。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Spring唯一默认装配入口，接收最多120秒。 */
    @Autowired
    public BoundedArtifactReceiver() { this(Duration.ofSeconds(120)); }

    /** 使用专属进程用户目录，并允许测试缩短接收截止。 */
    public BoundedArtifactReceiver(Duration timeout) {
        this(timeout, Path.of(System.getProperty("java.io.tmpdir"),
                "thingslink-artifact-receiver-" + System.getProperty("user.name")));
    }

    /** 注入组件专属根目录用于隔离测试；目录必须同用户所有且仅该用户可访问。 */
    public BoundedArtifactReceiver(Duration timeout, Path privateRoot) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(privateRoot, "privateRoot");
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("制品接收时限不合法");
        }
        timeoutNanos = timeout.toNanos();
        try {
            UserPrincipal owner = privateRoot.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            try { createPrivateDirectory(privateRoot, owner); }
            catch (java.nio.file.FileAlreadyExistsException ignored) { /* 已有目录仍须重新校验。 */ }
            requirePrivate(privateRoot, true, owner);
            Path maintenance = privateRoot.resolve("maintenance.lock");
            try (FileChannel channel = openLock(maintenance, owner); FileLock lock = tryLock(channel)) {
                if (lock == null) { throw new IOException("maintenance busy"); }
                reapAbandoned(privateRoot, owner);
                directory = createPrivateDirectory(privateRoot.resolve("instance-" + UUID.randomUUID()), owner);
                ownerChannel = openLock(directory.resolve("owner.lock"), owner);
                FileLock acquired = tryLock(ownerChannel);
                if (acquired == null) { ownerChannel.close(); throw new IOException("owner busy"); }
                ownerLock = acquired;
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法建立或维护制品接收私有目录", exception);
        }
    }

    /** 不跟随符号链接，验证当前用户及准确的最小权限。 */
    private static void requirePrivate(Path path, boolean directory, UserPrincipal owner) throws IOException {
        if (!(directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)
                || !hasPrivatePermissions(path, directory, owner)) {
            throw new IOException("private entry rejected");
        }
    }

    /** 根据文件系统能力原子设置初始权限，Windows 使用仅当前用户可访问的 ACL。 */
    private static FileAttribute<?> privatePermissions(Path path, boolean directory, UserPrincipal owner) {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
        if (!path.getFileSystem().supportedFileAttributeViews().contains("acl")) {
            throw new UnsupportedOperationException("File system requires POSIX or ACL permissions");
        }
        List<AclEntry> entries = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(owner).setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class)).build());
        return new FileAttribute<List<AclEntry>>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<AclEntry> value() { return entries; }
        };
    }

    /** Windows 管理员创建对象可能默认归管理员组所有，显式归属当前进程用户。 */
    private static Path createPrivateDirectory(Path path, UserPrincipal owner) throws IOException {
        Files.createDirectory(path, privatePermissions(path, true, owner));
        Files.setOwner(path, owner);
        requirePrivate(path, true, owner);
        return path;
    }

    /** ACL 不允许其他主体获得访问权，也不接受空 ACL 或继承专用授权。 */
    private static boolean hasPrivatePermissions(Path path, boolean directory, UserPrincipal owner)
            throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) { return false; }
        List<AclEntry> entries = view.getAcl();
        return entries.size() == 1 && entries.getFirst().type() == AclEntryType.ALLOW
                && entries.getFirst().principal().equals(owner) && entries.getFirst().flags().isEmpty()
                && entries.getFirst().permissions().equals(java.util.EnumSet.allOf(AclEntryPermission.class));
    }

    /** 只创建或打开本组件固定锁文件，不允许链接跳转。 */
    private static FileChannel openLock(Path path, UserPrincipal owner) throws IOException {
        try {
            Files.createFile(path, privatePermissions(path, false, owner));
            Files.setOwner(path, owner);
        } catch (java.nio.file.FileAlreadyExistsException ignored) { /* 已有文件必须校验，不能重置权限。 */ }
        requirePrivate(path, false, owner);
        FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
        try { requirePrivate(path, false, owner); return channel; }
        catch (IOException | RuntimeException exception) { channel.close(); throw exception; }
    }

    /** 跨进程和同JVM竞争都以未取得锁表达，不等待不确定时长。 */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); }
        catch (OverlappingFileLockException exception) { return null; }
    }

    /** 仅扫描专属根目录，最多128个实例；超预算或未知内容保留并阻止启动。 */
    private static void reapAbandoned(Path root, UserPrincipal owner) throws IOException {
        List<Path> instances = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().equals("maintenance.lock")) { continue; }
                if (!entry.getFileName().toString().matches("instance-[0-9a-f-]{36}")
                        || instances.size() >= 128) { throw new IOException("maintenance boundary"); }
                requirePrivate(entry, true, owner);
                instances.add(entry);
            }
        }
        for (Path instance : instances) {
            Path lockPath = instance.resolve("owner.lock");
            // 创建与回收共享维护锁：缺少owner锁只可能是创建前崩溃的空目录。
            if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.deleteIfExists(instance); }
                catch (java.nio.file.DirectoryNotEmptyException exception) {
                    throw new IOException("unknown abandoned contents");
                }
                continue;
            }
            try (FileChannel channel = openLock(lockPath, owner); FileLock lock = tryLock(channel)) {
                if (lock == null) { continue; }
                List<Path> bodies = new ArrayList<>();
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(instance)) {
                    for (Path entry : entries) {
                        if (entry.equals(lockPath)) { continue; }
                        if (bodies.size() >= 2 || !entry.getFileName().toString().matches("body-[0-9]+\\.part")) {
                            throw new IOException("unknown abandoned contents");
                        }
                        requirePrivate(entry, false, owner);
                        bodies.add(entry);
                    }
                }
                for (Path body : bodies) { Files.delete(body); }
            }
            // 外层维护锁仍持有；Windows 必须先关闭文件句柄才能删除锁文件。
            Files.delete(lockPath);
            Files.delete(instance);
        }
    }

    /**
     * 启动真实Servlet非阻塞接收；拒绝时只调用failed一次，成功交接后只调用completed一次。
     * 调用方必须自行在回调中恢复所需认证范围，不能假定容器线程持有原线程上下文。
     */
    public void receive(HttpServletRequest request, HttpServletResponse response, long expectedLength,
                        String expectedSha256, Work work) {
        Objects.requireNonNull(work, "work");
        if (request == null || response == null || expectedLength < 1 || expectedLength > MAX_LENGTH
                || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                || !request.isAsyncSupported() || request.isAsyncStarted()) {
            reject(work, Reason.INVALID_INPUT);
            return;
        }
        Transfer transfer = null;
        Reason rejection = null;
        synchronized (this) {
            if (closed.get()) { rejection = Reason.CANCELLED; }
            else if (!slots.tryAcquire()) { rejection = Reason.BUSY; }
            else {
                try {
                    transfer = new Transfer(request, response, expectedLength, expectedSha256, work);
                    transfers.add(transfer);
                } catch (Exception exception) {
                    slots.release();
                    rejection = Reason.IO;
                }
            }
        }
        // 调用方failed可能访问数据库，不允许其持组件锁而阻塞关闭或另一请求取得名额。
        if (rejection != null) { reject(work, rejection); return; }
        transfer.start();
    }

    /** 仅暴露给同包技术测试检查目录权限和资源收束，不作为业务API。 */
    Path temporaryDirectory() { return directory; }

    /** 接收前拒绝只产生一次固定回调，不透出异常正文。 */
    private static void reject(Work work, Reason reason) {
        try { work.failed(new Failure(reason)); }
        catch (Exception ignored) { /* 调用方连接可能已关闭，不能重复回调。 */ }
    }

    /** 关闭只取消本组件任务；仍在读临时文件的业务工作必须自行响应取消后退出。 */
    @PreDestroy
    @Override
    public void close() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) { return; }
        }
        transfers.forEach(transfer -> transfer.fail(Reason.CANCELLED));
        watchdog.shutdownNow();
        workers.shutdown();
        try {
            boolean finished = workers.awaitTermination(5, TimeUnit.SECONDS);
            boolean watcherStopped = watchdog.awaitTermination(5, TimeUnit.SECONDS);
            if (!finished || !watcherStopped || !transfers.isEmpty()) {
                workers.shutdownNow();
                throw new IllegalStateException("制品接收工作未响应取消，保留仍被使用的临时文件");
            }
            UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
            try (FileChannel maintenance = openLock(directory.getParent().resolve("maintenance.lock"), owner);
                 FileLock lock = maintenance.lock()) {
                ownerLock.release();
                ownerChannel.close();
                Files.deleteIfExists(directory.resolve("owner.lock"));
                Files.deleteIfExists(directory);
            }
        } catch (InterruptedException exception) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("制品接收关闭被中断");
        } catch (IOException exception) {
            throw new IllegalStateException("制品接收目录未完成清理", exception);
        }
    }

    /** 不继承ThreadLocal的显式工作合同，cancelled必须只读非阻塞信号。 */
    public interface Work {
        /** 原始正文已核验；后续存储/事务/响应由调用方执行，必须持续响应cancelled。 */
        void completed(Path source, BooleanSupplier cancelled) throws Exception;
        /** 仅接收失败调用一次；已进入completed后不会并发调用。 */
        void failed(Failure failure) throws Exception;
        /** 返回上传会话取消或失租状态，禁止阻塞和访问远端服务。 */
        boolean cancelled();
    }
    /**
     * 固定接收失败，不携带原始正文、路径或底层异常。
     * @param reason 有界原因码
     */
    public record Failure(Reason reason) { }
    /** 接收层固定失败类别，业务HTTP映射由调用方提供。 */
    public enum Reason {
        /** 本实例两个名额均占用。 */ BUSY,
        /** 输入合同或Servlet异步能力不符合。 */ INVALID_INPUT,
        /** 实际字节与声明长度不同。 */ LENGTH_MISMATCH,
        /** 完整正文摘要不匹配。 */ DIGEST_MISMATCH,
        /** 接收单调截止耗尽。 */ TIMEOUT,
        /** 容器报告连接错误或中断。 */ DISCONNECTED,
        /** 会话失租、取消或组件关闭。 */ CANCELLED,
        /** 本地临时文件或容器接收失败。 */ IO
    }
    /** 单次传输所有权阶段，避免交接后清理线程与存储读文件竞争。 */
    private enum Phase {
        /** 接收非阻塞正文。 */ RECEIVING,
        /** 正文核验完成，等待有界工作线程。 */ READY,
        /** 业务工作拥有临时文件读权。 */ WORK,
        /** 资源已收束。 */ FINISHED
    }

    /** 一个持名额的Servlet传输，网络读取只发生在ReadListener的isReady区间。 */
    private final class Transfer implements ReadListener, AsyncListener {
        /** 原始Servlet请求，认证范围由外层回调处理。 */
        private final HttpServletRequest request;
        /** 响应仅用于生命周期及避免接收失败保持连接。 */
        private final HttpServletResponse response;
        /** 精确长度上限。 */
        private final long expectedLength;
        /** 精确SHA256期望。 */
        private final String expectedSha256;
        /** 调用方业务回调。 */
        private final Work work;
        /** 单调接收起点。 */
        private final long started = System.nanoTime();
        /** 独占普通临时文件，使用 POSIX 0600 或仅当前用户可访问的 Windows ACL。 */
        private final Path file;
        /** 固定64KiB读取缓冲，不随声明长度扩容。 */
        private final byte[] buffer = new byte[65_536];
        /** 原始正文增量SHA256。 */
        private final MessageDigest digest;
        /** 仅在接收阶段持有的本地输出流。 */
        private OutputStream output;
        /** 容器非阻塞输入流。 */
        private ServletInputStream input;
        /** 异步请求生命周期，所有终态均尝试complete。 */
        private AsyncContext async;
        /** 已读取正文长度。 */
        private long length;
        /** 所有权状态由本Transfer锁保护。 */
        private Phase phase = Phase.RECEIVING;
        /** 后续工作随时可观察的取消信号。 */
        private volatile boolean cancelled;
        /** 工作/接收期间的非阻塞租约检查任务。 */
        private ScheduledFuture<?> timer;

        /** 创建私有文件，若输出流建立失败立即删除本次文件。 */
        Transfer(HttpServletRequest request, HttpServletResponse response, long expectedLength,
                 String expectedSha256, Work work) throws Exception {
            this.request = request;
            this.response = response;
            this.expectedLength = expectedLength;
            this.expectedSha256 = expectedSha256;
            this.work = work;
            digest = MessageDigest.getInstance("SHA-256");
            UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
            file = Files.createTempFile(directory, "body-", ".part", privatePermissions(directory, false, owner));
            try {
                Files.setOwner(file, owner);
                requirePrivate(file, false, owner);
                output = Files.newOutputStream(file);
            }
            catch (IOException exception) { Files.deleteIfExists(file); throw exception; }
        }
        /** 登记异步监听后启动非阻塞读取，不在线程池里执行阻塞read。 */
        void start() {
            try {
                synchronized (this) {
                    if (phase == Phase.FINISHED) { return; }
                    async = request.startAsync(request, response);
                    // 单调watchdog统一接收截止；禁用容器默认超时误伤后续业务工作。
                    async.setTimeout(0);
                    async.addListener(this);
                    input = request.getInputStream();
                    timer = watchdog.scheduleAtFixedRate(this::poll, 0, 20, TimeUnit.MILLISECONDS);
                    input.setReadListener(this);
                }
            } catch (Exception exception) { fail(Reason.IO); }
        }
        /** 就绪时最多读固定缓冲，达到声明长度后仍等EOF，拒绝多余字节。 */
        @Override
        public void onDataAvailable() {
            try {
                synchronized (this) {
                    while (phase == Phase.RECEIVING && input.isReady() && !input.isFinished()) {
                        if (checkCancellation()) { return; }
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, expectedLength - length + 1));
                        if (read < 0) { break; }
                        if (read == 0) { break; }
                        if (read > expectedLength - length) { fail(Reason.LENGTH_MISMATCH); return; }
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        length += read;
                    }
                }
            } catch (IOException exception) { fail(Reason.DISCONNECTED); }
            catch (RuntimeException exception) { fail(Reason.IO); }
        }
        /** EOF后先关闭写句柄和验真，再把临时文件所有权交给工作线程。 */
        @Override
        public void onAllDataRead() {
            try {
                synchronized (this) {
                    if (phase != Phase.RECEIVING || checkCancellation()) { return; }
                    if (length != expectedLength) { fail(Reason.LENGTH_MISMATCH); return; }
                    if (!HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
                        fail(Reason.DIGEST_MISMATCH);
                        return;
                    }
                    output.close();
                    output = null;
                    phase = Phase.READY;
                    workers.execute(this::runWork);
                }
            } catch (Exception exception) { fail(Reason.IO); }
        }
        /** 容器输入错误仅结束接收；业务已持文件时只发取消信号。 */
        @Override public void onError(Throwable error) { fail(Reason.DISCONNECTED); }
        /** 业务开始前原子取得文件读权，之后任何错误都不能并发删除文件。 */
        private void runWork() {
            synchronized (this) {
                if (phase != Phase.READY) { return; }
                if (checkCancellation()) { return; }
                phase = Phase.WORK;
            }
            try { work.completed(file, () -> cancelled); }
            catch (Exception exception) {
                cancelled = true;
                if (!response.isCommitted()) { response.setStatus(500); }
            } finally {
                synchronized (this) { finish(); }
            }
        }
        /** 接收和后续工作均观察失租信号，只有接收受120秒预算约束。 */
        private void poll() {
            synchronized (this) {
                if (phase != Phase.FINISHED) { checkCancellation(); }
            }
        }
        /** 非阻塞检查根维护的租约信号及本组件状态。 */
        private boolean checkCancellation() {
            try {
                if (closed.get() || work.cancelled()) { fail(Reason.CANCELLED); return true; }
            } catch (RuntimeException exception) { fail(Reason.CANCELLED); return true; }
            if ((phase == Phase.RECEIVING || phase == Phase.READY) && System.nanoTime() - started >= timeoutNanos) {
                fail(Reason.TIMEOUT);
                return true;
            }
            return cancelled;
        }
        /** failed只在交接前调用一次，WORK阶段只置cancel交给业务finally收束。 */
        private synchronized void fail(Reason reason) {
            if (phase == Phase.FINISHED) { return; }
            cancelled = true;
            if (phase == Phase.WORK) { return; }
            phase = Phase.FINISHED;
            if (timer != null) { timer.cancel(false); }
            // failed可能要记录业务事实，不能占用单线程watchdog而阻塞另一传输的取消。
            Runnable failure = () -> {
                try {
                    if (!response.isCommitted()) { response.setHeader("Connection", "close"); }
                    reject(work, reason);
                } finally {
                    synchronized (Transfer.this) { finish(); }
                }
            };
            try { workers.execute(failure); }
            catch (java.util.concurrent.RejectedExecutionException exception) {
                // 仅可能与组件关闭竞争；仍必须回调一次并完成本次资源收束。
                failure.run();
            }
        }
        /** 持文件所有权的线程关闭句柄、删除自身文件、complete并归还名额。 */
        private void finish() {
            phase = Phase.FINISHED;
            if (timer != null) { timer.cancel(false); }
            try {
                if (output != null) { output.close(); output = null; }
            } catch (IOException ignored) { /* 仍继续尝试删除文件并释放异步请求。 */ }
            try { if (input != null) { input.close(); } }
            catch (IOException ignored) { /* 已断开连接不阻止其余资源收束。 */ }
            try { Files.deleteIfExists(file); }
            catch (IOException exception) { throw new IllegalStateException("制品接收临时文件清理失败"); }
            finally {
                try { if (async != null) { async.complete(); } }
                catch (IllegalStateException ignored) { /* 容器可能已因断连完成请求。 */ }
                finally {
                    if (transfers.remove(this)) { slots.release(); }
                }
            }
        }
        /** 外层提前完成请求时，活跃业务仅接受取消，不被异步清理线程删文件。 */
        @Override public void onComplete(AsyncEvent event) { fail(Reason.DISCONNECTED); }
        /** 容器明确超时与单调watchdog归为同一固定原因。 */
        @Override public void onTimeout(AsyncEvent event) { fail(Reason.TIMEOUT); }
        /** 容器连接错误统一取消本传输。 */
        @Override public void onError(AsyncEvent event) { fail(Reason.DISCONNECTED); }
        /** 组件从不dispatch或再次startAsync，若外层重启则取消旧工作。 */
        @Override public void onStartAsync(AsyncEvent event) { fail(Reason.CANCELLED); }
    }
}
