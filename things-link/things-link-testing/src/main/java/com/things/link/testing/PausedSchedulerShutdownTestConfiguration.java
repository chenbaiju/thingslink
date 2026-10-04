package com.things.link.testing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Q13 的测试上下文关闭接缝，不进入业务模块生产依赖树。
 *
 * <p>Spring 7 的 cache pause 可能使已出队任务停在 beforeExecute。先用公共 stop(callback)
 * 等待已经准入的任务完成，再对精确目标进入 native STOP；JDK 21 的 ScheduledFutureTask
 * 在 STOP 下自行取消，因此不需要反射枚举已出队任务，也不覆盖原 Metrics 装饰器。
 * 约束与验证边界见 docs/progress/G2.md 的 G2-A4c-Q13 第二段。
 */
@TestConfiguration(proxyBeanMethods = false)
public class PausedSchedulerShutdownTestConfiguration {

    /** 仅测试环境使用；不是修改生产 scheduler 的默认等待参数。 */
    public static final String DEADLINE_PROPERTY = "things-link.testing.scheduler-close-deadline-millis";

    /** 全部受管池共享 30 秒总预算，禁止每池重新获得一个完整预算。 */
    public static final long DEFAULT_DEADLINE_MILLIS = 30_000;

    /** 仅显式启用的资格运行写直接证据；普通测试未配置时不创建文件。 */
    public static final String EVIDENCE_DIRECTORY_PROPERTY = "things-link.testing.scheduler-evidence-dir";
    /** 必须与独占目录的外部执行计划一致，不能在退出时临时生成批次身份。 */
    public static final String EVIDENCE_RUN_PROPERTY = "things-link.testing.scheduler-evidence-run-id";

    /** 名字来自 SchedulingIsolationConfiguration；mail 和其他 executor 不在本片所有权内。 */
    public static final List<String> SCHEDULER_NAMES = List.of("outboxTriggerScheduler", "commandLifecycleScheduler",
            "modbusLifecycleScheduler", "notificationLifecycleScheduler", "taskTriggerScheduler",
            "exportLifecycleScheduler", "otaUploadScheduler", "maintenanceScheduler", "automationLifecycleScheduler");

    /** @param context 当前测试上下文 @return 不解析父 context 的精确关闭监听 */
    @Bean
    public ShutdownMonitor pausedSchedulerShutdownMonitor(ConfigurableApplicationContext context) {
        long millis = context.getEnvironment().getProperty(DEADLINE_PROPERTY, Long.class, DEFAULT_DEADLINE_MILLIS);
        return new ShutdownMonitor(context, Duration.ofMillis(millis));
    }

    /** 单调时间线仅包含身份和资源状态，不包含配置原值、任务正文或凭据。 */
    public record CloseEvent(String scheduler, String phase, long elapsedNanos, String thread) { }

    /** 冻结时的单池结果；晚到清理可以记录新事件，但不得改写这份结果。 */
    public record PoolResult(String scheduler, int beanIdentity, String phase, boolean shutdown,
            boolean terminated, int activeCount, int queueSize, int cancelledQueueTasks) { }

    /** Spring 会捕获关闭监听异常，因此提供可显式断言且不会被晚到回调改绿的终态。 */
    public record CloseResult(String contextId, String status, String firstFailure, long elapsedNanos,
            long deadlineMillis, List<PoolResult> pools, List<CloseEvent> events) { }

    /** 外部覆盖计划使用的实例键；不能用可重复的 context 显示名替代。 */
    public record ExpectedContext(String module, long pid, String instanceId) { }

    /** 唯一的文件系统故障接缝；正常路径只允许同目录 ATOMIC_MOVE，不降级为非原子复制。 */
    @FunctionalInterface
    public interface AtomicPublication {
        /** @param temporary 已完整写入并 force 的0600临时文件 @param destination 唯一终态路径 */
        void move(Path temporary, Path destination) throws IOException;
    }

    /**
     * 证据专用权限边界：POSIX 使用0700/0600，NTFS 使用仅当前用户的显式ACL。
     * 必须在创建时设置，不先用宽权限创建再补救；两类能力均不具备时拒绝，不跳过测试。
     */
    public static final class PrivateFiles {
        /** 不允许实例化为可变的全局文件系统策略。 */
        private PrivateFiles() { }

        /** 最低层可直接核验非POSIX选择及ACL内容，不在macOS伪称运行了Windows文件系统。 */
        public static FileAttribute<?> initialAttribute(boolean directory, boolean posix, boolean acl, UserPrincipal owner) {
            if (posix) return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            if (!acl || owner == null) throw new IllegalStateException("Q13_PRIVATE_PERMISSIONS_UNSUPPORTED");
            List<AclEntry> entries = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
            return new FileAttribute<List<AclEntry>>() {
                /** Windows NIO支持的原子创建属性，不把POSIX属性传给ACL provider。 */
                @Override public String name() { return "acl:acl"; }
                /** 单一主体、无继承标志，避免从父目录扩散其他主体访问权。 */
                @Override public List<AclEntry> value() { return entries; }
            };
        }

        /** ACL读回也须严格匹配；只检查owner或只检查某个ALLOW都会漏掉其他授权。 */
        public static void requireOwnerOnlyAcl(UserPrincipal owner, List<AclEntry> entries) {
            if (owner == null || entries.size() != 1) throw new IllegalStateException("Q13_PRIVATE_ACL");
            AclEntry entry = entries.getFirst();
            if (entry.type() != AclEntryType.ALLOW || !entry.principal().equals(owner) || !entry.flags().isEmpty()
                    || !entry.permissions().equals(EnumSet.allOf(AclEntryPermission.class))) {
                throw new IllegalStateException("Q13_PRIVATE_ACL");
            }
        }

        /** 创建路径的父目录必须存在；不递归创建或改变外部目录权限。 */
        private static FileAttribute<?> attribute(Path parent, boolean directory) throws IOException {
            var store = Files.getFileStore(parent);
            boolean posix = store.supportsFileAttributeView("posix");
            boolean acl = store.supportsFileAttributeView("acl");
            if (!posix && !acl) throw new IllegalStateException("Q13_PRIVATE_PERMISSIONS_UNSUPPORTED");
            UserPrincipal owner = posix ? null : parent.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            return initialAttribute(directory, posix, acl, owner);
        }

        /** 唯一目录CREATE_NEW语义，旧运行目录不允许被当作本轮前置。 */
        public static Path createDirectory(Path path) throws IOException {
            Path created = Files.createDirectory(path, attribute(path.toAbsolutePath().getParent(), true));
            requirePrivate(created, true);
            return created;
        }

        /** 临时目录同样走安全创建属性；普通未归档测试不能无条件要求POSIX。 */
        public static Path createTempDirectory(Path parent, String prefix) throws IOException {
            Path created = Files.createTempDirectory(parent, prefix, attribute(parent, true));
            requirePrivate(created, true);
            return created;
        }

        /** 临时文件先限定权限，再写字节和原子发布。 */
        public static Path createTempFile(Path parent, String prefix, String suffix) throws IOException {
            Path created = Files.createTempFile(parent, prefix, suffix, attribute(parent, false));
            requirePrivate(created, false);
            return created;
        }

        /** 拒绝符号链接/错误类型；ACL分支不能退化成“当前用户能读即可”。 */
        public static void requirePrivate(Path path, boolean directory) throws IOException {
            if (Files.isSymbolicLink(path) || !(directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw new IllegalStateException("Q13_EVIDENCE_PATH_TYPE");
            var store = Files.getFileStore(path);
            if (store.supportsFileAttributeView("posix")) {
                if (!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))) {
                    throw new IllegalStateException("Q13_EVIDENCE_PRIVATE_MODE");
                }
            } else if (store.supportsFileAttributeView("acl")) {
                UserPrincipal user = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
                AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null || !view.getOwner().equals(user)) throw new IllegalStateException("Q13_PRIVATE_ACL_OWNER");
                requireOwnerOnlyAcl(user, view.getAcl());
            } else throw new IllegalStateException("Q13_PRIVATE_PERMISSIONS_UNSUPPORTED");
        }
    }

    /**
     * Q13 专用的登记/终态出口，不依赖退出期日志或 Surefire 通道。
     * 目录须为0700或等价owner-only ACL；记录为0600或等价ACL，不遍历或删除其他运行资源。
     * 判读方必须另外提供非空预期实例集合；从已有登记反推完整覆盖会漏掉未接线的 context。
     */
    public static final class DirectEvidence {
        /** JDK同目录原子发布；不支持的平台拒绝启用，而不是静默降级。 */
        private static final AtomicPublication ATOMIC = (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        /** 每份记录允许的安全基础字段；显示名只存摘要，避免将配置原值写入制品。 */
        private static final Set<String> BASE_KEYS = Set.of("schema", "kind", "runId", "module", "pid", "instanceId", "displayNameSha256");
        /** 注册身份在构造时冻结，后续上下文显示名变化不改变实例键。 */
        private final Map<String, String> registration;
        /** 本轮唯一实例目录，不复用其他进程或历史路径。 */
        private final Path directory;
        /** 登记原文摘要同时绑定终态，防止错配或事后替换。 */
        private final String registrationSha256;
        /** 最小原子移动接缝只用于永久错误路径回归。 */
        private final AtomicPublication publication;
        /** 证据失败独立于关闭算法首因；后续清理/重复写不能清除它。 */
        private final AtomicReference<String> failure = new AtomicReference<>();
        /** 同一实例只允许尝试一次终态发布，包括第一次已失败的情况。 */
        private boolean terminalAttempted;
        /** 结果内存赋值早于文件发布；并发读取不得把进行中的写入视为成功。 */
        private volatile boolean terminalPublished;

        /** 正常调用使用固定原子发布，不暴露任意文件写入策略。 */
        public DirectEvidence(Path root, String runId, String module, String displayName) {
            this(root, runId, module, displayName, ATOMIC);
        }

        /** @param publication 仅为测试原子移动失败的最小接缝，不改变终态内容或成功判据 */
        public DirectEvidence(Path root, String runId, String module, String displayName, AtomicPublication publication) {
            try {
                require(UUID.fromString(runId).toString().equals(runId), "RUN_ID");
                require(module.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}"), "MODULE");
                require(root.isAbsolute(), "ABSOLUTE_DIRECTORY");
                requirePrivate(root, true);
                this.publication = publication;
                String instance = UUID.randomUUID().toString();
                long pid = ProcessHandle.current().pid();
                this.directory = root.resolve(module + "--" + pid + "--" + instance);
                PrivateFiles.createDirectory(this.directory);
                this.registration = Map.of("schema", "1", "kind", "REGISTRATION", "runId", runId,
                        "module", module, "pid", Long.toString(pid), "instanceId", instance,
                        "displayNameSha256", digest(displayName.getBytes(StandardCharsets.UTF_8)));
                byte[] bytes = encode(this.registration);
                this.registrationSha256 = digest(bytes);
                atomicWrite(this.directory.resolve("registration.properties"), bytes);
            } catch (IOException | RuntimeException error) {
                // 登记失败必须阻止该 monitor 创建成功；不能在资格结束时靠其他实例的登记掩盖遗漏。
                throw new IllegalStateException("Q13_EVIDENCE_REGISTRATION_FAILED");
            }
        }

        /** @return 外部预登记/父进程使用的精确实例身份，不是显示名 */
        public ExpectedContext identity() {
            return new ExpectedContext(this.registration.get("module"), Long.parseLong(this.registration.get("pid")), this.registration.get("instanceId"));
        }

        /** @return 仅本实例路径，供本轮证据核验和受控故障夹具定位 */
        public Path directory() { return this.directory; }

        /** @return 证据首败安全代码；不能以关闭算法PASS替代本值 */
        public String failure() { return this.failure.get(); }

        /** 配置必须成对出现；module来自Surefire实际模块目录，而非全仓共用手填常量。 */
        private static DirectEvidence configured(ConfigurableApplicationContext context) {
            String root = context.getEnvironment().getProperty(EVIDENCE_DIRECTORY_PROPERTY);
            String run = context.getEnvironment().getProperty(EVIDENCE_RUN_PROPERTY);
            if (root == null && run == null) return null;
            if (root == null || run == null) throw new IllegalStateException("Q13_EVIDENCE_CONFIGURATION_INCOMPLETE");
            String module = Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).getFileName().toString();
            return new DirectEvidence(Path.of(root), run, module, context.getId());
        }

        /** 先冻结算法结果再直接发布；证据写失败不改变原关闭首因，也不能对外签通过。 */
        public synchronized void publish(CloseResult result) {
            if (this.terminalAttempted) {
                recordFailure("DUPLICATE_TERMINAL_ATTEMPT");
                return;
            }
            this.terminalAttempted = true;
            try {
                Map<String, String> fields = new TreeMap<>(this.registration);
                fields.put("kind", "TERMINAL");
                fields.put("registrationSha256", this.registrationSha256);
                fields.put("status", result.status());
                fields.put("firstFailure", result.firstFailure() == null ? "NONE" : result.firstFailure());
                fields.put("elapsedNanos", Long.toString(result.elapsedNanos()));
                fields.put("deadlineMillis", Long.toString(result.deadlineMillis()));
                for (PoolResult pool : result.pools()) {
                    String prefix = "pool." + pool.scheduler() + ".";
                    fields.put(prefix + "phase", pool.phase());
                    fields.put(prefix + "beanIdentity", Integer.toString(pool.beanIdentity()));
                    fields.put(prefix + "shutdown", Boolean.toString(pool.shutdown()));
                    fields.put(prefix + "terminated", Boolean.toString(pool.terminated()));
                    fields.put(prefix + "activeCount", Integer.toString(pool.activeCount()));
                    fields.put(prefix + "queueSize", Integer.toString(pool.queueSize()));
                    fields.put(prefix + "cancelledQueueTasks", Integer.toString(pool.cancelledQueueTasks()));
                }
                atomicWrite(this.directory.resolve("terminal.properties"), encode(fields));
                this.terminalPublished = true;
            } catch (IOException | RuntimeException error) {
                recordFailure("TERMINAL_PUBLICATION_FAILED");
            }
        }

        /** 判读必须显式检查，不依赖Spring是否传播监听异常。 */
        public void assertSuccessful() {
            if (this.failure.get() != null) throw new IllegalStateException("Q13_EVIDENCE_" + this.failure.get());
            if (!this.terminalPublished) throw new IllegalStateException("Q13_EVIDENCE_TERMINAL_NOT_PUBLISHED");
        }

        /** 首败标志尽力直写；若目录本身不可写，缺终态/遗留part同样会被判读拒绝。 */
        private void recordFailure(String code) {
            if (!this.failure.compareAndSet(null, code)) return;
            try {
                atomicWrite(this.directory.resolve("failure.properties"), encode(Map.of("failure", code)));
            } catch (IOException | RuntimeException ignored) {
                // 不用二次采集异常覆盖首因；内存状态和缺失/多余文件均不能被视为完整成功。
            }
        }

        /** 完整字节force后同目录原子发布；唯一实例且同步调用，不覆盖既有记录。 */
        private void atomicWrite(Path target, byte[] bytes) throws IOException {
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "DUPLICATE_RECORD");
            Path temporary = PrivateFiles.createTempFile(this.directory, ".writing-", ".part");
            try (FileChannel stream = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) stream.write(buffer);
                stream.force(true);
            }
            this.publication.move(temporary, target);
        }

        /**
         * 关闭算法PASS与证据完整性是两个条件。expected必须来自独立计划/父进程登记，不得从本目录枚举后自证完整。
         * 零覆盖、额外/缺失实例、未发布/重复/错配、残余part或未终止都拒绝；普通未启用运行不应调用本资格判读。
         */
        public static void verify(Path root, String runId, Set<ExpectedContext> expected) {
            try {
                require(!expected.isEmpty(), "EMPTY_EXPECTATION");
                require(UUID.fromString(runId).toString().equals(runId), "RUN_ID");
                requirePrivate(root, true);
                Set<String> names = new HashSet<>();
                for (ExpectedContext item : expected) {
                    require(item.module().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}") && item.pid() > 0
                            && UUID.fromString(item.instanceId()).toString().equals(item.instanceId()), "EXPECTED_IDENTITY");
                    names.add(item.module() + "--" + item.pid() + "--" + item.instanceId());
                }
                try (var paths = Files.list(root)) {
                    require(paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(names), "COVERAGE_MISMATCH");
                }
                for (ExpectedContext item : expected) {
                    Path directory = root.resolve(item.module() + "--" + item.pid() + "--" + item.instanceId());
                    requirePrivate(directory, true);
                    try (var paths = Files.list(directory)) {
                        require(paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                                .equals(Set.of("registration.properties", "terminal.properties")), "RECORD_SET");
                    }
                    Map<String, String> registration = read(directory.resolve("registration.properties"));
                    Map<String, String> terminal = read(directory.resolve("terminal.properties"));
                    require(registration.keySet().equals(BASE_KEYS), "REGISTRATION_FIELDS");
                    require("1".equals(registration.get("schema")) && "REGISTRATION".equals(registration.get("kind")), "REGISTRATION_SCHEMA");
                    require(runId.equals(registration.get("runId")) && item.module().equals(registration.get("module"))
                            && Long.toString(item.pid()).equals(registration.get("pid")) && item.instanceId().equals(registration.get("instanceId")), "IDENTITY");
                    for (String key : BASE_KEYS) if (!key.equals("kind")) require(registration.get(key).equals(terminal.get(key)), "TERMINAL_IDENTITY");
                    require("TERMINAL".equals(terminal.get("kind")), "TERMINAL_SCHEMA");
                    require(digest(Files.readAllBytes(directory.resolve("registration.properties"))).equals(terminal.get("registrationSha256")), "REGISTRATION_SHA");
                    Set<String> keys = new HashSet<>(BASE_KEYS);
                    keys.addAll(Set.of("registrationSha256", "status", "firstFailure", "elapsedNanos", "deadlineMillis"));
                    require("PASS".equals(terminal.get("status")) && "NONE".equals(terminal.get("firstFailure")), "CLOSE_NOT_PASS");
                    long nanos = Long.parseLong(terminal.get("elapsedNanos"));
                    long deadline = Long.parseLong(terminal.get("deadlineMillis"));
                    require(nanos >= 0 && deadline > 0 && deadline <= DEFAULT_DEADLINE_MILLIS && nanos <= TimeUnit.MILLISECONDS.toNanos(deadline), "DEADLINE");
                    for (String scheduler : SCHEDULER_NAMES) {
                        String prefix = "pool." + scheduler + ".";
                        for (String field : List.of("phase", "beanIdentity", "shutdown", "terminated", "activeCount", "queueSize", "cancelledQueueTasks")) keys.add(prefix + field);
                        require("0".equals(terminal.get(prefix + "activeCount")) && "0".equals(terminal.get(prefix + "queueSize")), "POOL_BUSY");
                        require(Integer.parseInt(terminal.get(prefix + "cancelledQueueTasks")) >= 0, "CANCEL_COUNT");
                        String phase = terminal.get(prefix + "phase");
                        if ("NOT_CREATED_LOCAL".equals(phase)) {
                            require("0".equals(terminal.get(prefix + "beanIdentity")) && "false".equals(terminal.get(prefix + "shutdown"))
                                    && "false".equals(terminal.get(prefix + "terminated")), "ABSENT_POOL");
                        } else {
                            require(Set.of("NATIVE_STOP", "ALREADY_TERMINATED").contains(phase)
                                    && "true".equals(terminal.get(prefix + "shutdown")) && "true".equals(terminal.get(prefix + "terminated")), "POOL_NOT_TERMINATED");
                            Integer.parseInt(terminal.get(prefix + "beanIdentity"));
                        }
                    }
                    require(terminal.keySet().equals(keys), "TERMINAL_FIELDS");
                }
            } catch (IOException | RuntimeException error) {
                String message = error.getMessage();
                String code = message != null && message.startsWith("Q13_EVIDENCE_")
                        ? message.substring("Q13_EVIDENCE_".length()) : "IO_OR_PARSE";
                throw new IllegalStateException("Q13_EVIDENCE_REJECTED_" + code);
            }
        }

        /** POSIX与ACL都必须经过创建后读回，不能把平台分支当作权限豁免。 */
        private static void requirePrivate(Path path, boolean directory) throws IOException {
            PrivateFiles.requirePrivate(path, directory);
        }

        /** 严格小记录：拒绝重复键、未知行及过大载荷，不用Properties.load静默覆盖重复键。 */
        private static Map<String, String> read(Path path) throws IOException {
            requirePrivate(path, false);
            require(Files.size(path) <= 65_536, "RECORD_SIZE");
            Map<String, String> fields = new HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                require(split > 0 && fields.putIfAbsent(line.substring(0, split), line.substring(split + 1)) == null, "RECORD_LINE");
            }
            return fields;
        }

        /** 固定键排序的UTF-8安全投影，无凭据、环境、任务正文或原始显示名。 */
        private static byte[] encode(Map<String, String> fields) {
            StringBuilder text = new StringBuilder();
            new TreeMap<>(fields).forEach((key, value) -> {
                require(key.matches("[A-Za-z0-9.]+") && !value.contains("\n") && !value.contains("\r"), "UNSAFE_RECORD");
                text.append(key).append('=').append(value).append('\n');
            });
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** SHA-256只绑定本地原文，不作为身份之外的可信来源声明。 */
        private static String digest(byte[] value) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
            catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
        }

        /** 安全固定错误码，不将路径或不可信原值拼入异常。 */
        private static void require(boolean condition, String code) {
            if (!condition) throw new IllegalStateException("Q13_EVIDENCE_" + code);
        }
    }

    /** 当前 context 的同步优先监听；不注册全局状态，不恢复 context，不修改装饰器。 */
    public static final class ShutdownMonitor implements ApplicationListener<ContextClosedEvent>, Ordered {

        /** full 必须核验本记录与退出事实，不能仅依赖 Maven 的退出码。 */
        private static final Log LOGGER = LogFactory.getLog(ShutdownMonitor.class);
        /** 精确 context 身份，避免父子事件传播造成越界清理。 */
        private final ConfigurableApplicationContext context;
        /** 单次关闭共同使用的预算。 */
        private final Duration deadline;
        /** 未配置时为null；直接证据错误不与原关闭算法错误混淆。 */
        private final DirectEvidence evidence;
        /** 同一实例仅受理一次关闭，后续事件不得覆盖首因。 */
        private final AtomicBoolean started = new AtomicBoolean();
        /** 首个失败单赋值；清理成功不是本次失败的撤销。 */
        private final AtomicReference<String> firstFailure = new AtomicReference<>();
        /** 主线程与排空回调共同追加的安全事件。 */
        private final List<CloseEvent> events = new CopyOnWriteArrayList<>();
        /** 终态为不可变快照；测试必须在 close 之后显式读取。 */
        private volatile CloseResult result;
        /** 时间线使用同一单调基点，不用系统墙钟推算因果。 */
        private volatile long startedNanos;

        /** @param context 仅处理此实例 @param deadline 测试夹具 1 秒，通常 30 秒，不允许扩展预算 */
        public ShutdownMonitor(ConfigurableApplicationContext context, Duration deadline) {
            this(context, deadline, DirectEvidence.configured(context));
        }

        /** 最小文件发布故障夹具复用同一个真实关闭监听，不替换关闭算法。 */
        public ShutdownMonitor(ConfigurableApplicationContext context, Duration deadline, DirectEvidence evidence) {
            if (deadline.isZero() || deadline.isNegative() || deadline.compareTo(Duration.ofMillis(DEFAULT_DEADLINE_MILLIS)) > 0) {
                throw new IllegalArgumentException("测试关闭总预算必须在 0..30000 毫秒内且大于零");
            }
            this.context = context;
            this.deadline = deadline;
            this.evidence = evidence;
            this.result = new CloseResult(context.getId(), "NOT_RUN", null, 0, deadline.toMillis(), List.of(), List.of());
        }

        /** 必须早于 scheduler 接收 ContextClosedEvent 后设置 lateShutdown。 */
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        /** 即使 multicaster 配置了线程池，本监听也必须在发布线程内完成。 */
        @Override
        public boolean supportsAsyncExecution() {
            return false;
        }

        /** @return 不会随晚到回调变化的结果；NOT_RUN/RUNNING 都不能当作通过 */
        public CloseResult result() {
            return this.result;
        }

        /** @return 包含晚到清理事件的副本；与冻结结果分开使用 */
        public List<CloseEvent> events() {
            return List.copyOf(this.events);
        }

        /** @return 供外部独立覆盖登记使用的真实实例键；普通未配置模式返回null */
        public ExpectedContext evidenceIdentity() { return this.evidence == null ? null : this.evidence.identity(); }

        /** JUnit 可显式调用此方法；本方法不会自动在 JVM 关闭钩子中变成测试断言。 */
        public void assertSuccessful() {
            if (!"PASS".equals(this.result.status())) {
                throw new IllegalStateException("Q13 scheduler close未通过: " + this.result);
            }
            if (this.evidence != null) this.evidence.assertSuccessful();
        }

        /** 同步冻结全部受管池准入、等待排空并精确终止；此预算不包含 Spring 后续其他 bean 的销毁。 */
        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            if (event.getApplicationContext() != this.context || !this.started.compareAndSet(false, true)) {
                return;
            }
            this.startedNanos = System.nanoTime();
            long expires = this.startedNanos + this.deadline.toNanos();
            this.result = new CloseResult(this.context.getId(), "RUNNING", null, 0, this.deadline.toMillis(), List.of(), List.of());
            List<Target> targets = new ArrayList<>();
            List<PoolResult> absent = new ArrayList<>();
            try {
                for (String name : SCHEDULER_NAMES) {
                    // getSingleton 不创建 lazy bean，也不会从父 context 回退查找对象。
                    Object bean = this.context.getBeanFactory().getSingleton(name);
                    if (bean == null) {
                        absent.add(new PoolResult(name, 0, "NOT_CREATED_LOCAL", false, false, 0, 0, 0));
                    } else if (bean instanceof ThreadPoolTaskScheduler scheduler) {
                        try {
                            targets.add(new Target(name, scheduler));
                        } catch (RuntimeException error) {
                            fail(name, "TARGET_NOT_INITIALIZED");
                            absent.add(new PoolResult(name, System.identityHashCode(bean), "TARGET_NOT_INITIALIZED", false, false, 0, 0, 0));
                        }
                    } else {
                        fail(name, "TARGET_TYPE");
                        absent.add(new PoolResult(name, System.identityHashCode(bean), "TARGET_TYPE", false, false, 0, 0, 0));
                    }
                }
                // 先分别发出暂停请求，再等待；不能在第一个池等待期间仍让其他池继续准入。
                for (Target target : targets) {
                    if (target.executor.isTerminated()) {
                        target.phase = "ALREADY_TERMINATED";
                        target.drained.countDown();
                        continue;
                    }
                    if (target.executor.isShutdown()) {
                        fail(target.name, "ALREADY_SHUTDOWN_UNTERMINATED");
                        target.phase = "REJECTED_UNSAFE_STATE";
                        target.drained.countDown();
                        continue;
                    }
                    event(target.name, "STOP_REQUESTED");
                    try {
                        target.scheduler.stop(() -> stopDrainedTarget(target));
                        event(target.name, "PAUSED_ADMISSION");
                    } catch (RuntimeException error) {
                        fail(target.name, "STOP_CALLBACK_REQUEST_FAILED");
                        target.phase = "STOP_FAILED";
                        target.drained.countDown();
                    }
                }
                for (Target target : targets) {
                    long remaining = expires - System.nanoTime();
                    if (target.drained.getCount() != 0 && (remaining <= 0 || !target.drained.await(remaining, TimeUnit.NANOSECONDS))) {
                        fail(target.name, "DRAIN_TIMEOUT");
                        continue;
                    }
                    remaining = expires - System.nanoTime();
                    if (!target.executor.isTerminated() && (remaining <= 0 || !target.executor.awaitTermination(remaining, TimeUnit.NANOSECONDS))) {
                        fail(target.name, "TERMINATION_TIMEOUT");
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                fail("context", "CLOSE_INTERRUPTED");
            } catch (RuntimeException error) {
                fail("context", "CLOSE_OPERATION_FAILED");
            } finally {
                if (System.nanoTime() > expires) {
                    fail("context", "CLOSE_DEADLINE_EXCEEDED");
                }
                List<PoolResult> pools = new ArrayList<>(absent);
                for (Target target : targets) {
                    pools.add(target.snapshot());
                    if (!target.executor.isTerminated()) {
                        fail(target.name, "NOT_TERMINATED");
                    }
                }
                this.result = new CloseResult(this.context.getId(), this.firstFailure.get() == null ? "PASS" : "FAIL",
                        this.firstFailure.get(), System.nanoTime() - this.startedNanos, this.deadline.toMillis(),
                        List.copyOf(pools), List.copyOf(this.events));
                if (this.evidence != null) this.evidence.publish(this.result);
                LOGGER.info("Q13_SCHEDULER_CLOSE " + this.result);
            }
        }

        /**
         * 回调由 Spring 实际准入计数归零触发；不能在这里 await 当前 worker 自己。
         * STOP 先于唤醒：已出队 ScheduledFutureTask 随后依据 JDK STOP 状态自取消，而非执行业务。
         */
        private void stopDrainedTarget(Target target) {
            event(target.name, "DRAINED_CALLBACK");
            try {
                List<Runnable> queued = target.executor.shutdownNow();
                target.phase = "NATIVE_STOP";
                event(target.name, "NATIVE_STOP_REJECTS_SUBMISSION");
                for (Runnable task : queued) {
                    if (task instanceof Future<?> future) {
                        future.cancel(false);
                        target.cancelledQueueTasks++;
                    } else {
                        fail(target.name, "UNKNOWN_QUEUE_TASK");
                    }
                }
            } catch (RuntimeException error) {
                fail(target.name, "NATIVE_STOP_FAILED");
                target.phase = "NATIVE_STOP_FAILED";
            } finally {
                target.drained.countDown();
            }
        }

        /** 保存安全错误码；具体任务内容和环境不进入关闭日志。 */
        private void fail(String scheduler, String code) {
            this.firstFailure.compareAndSet(null, scheduler + ":" + code);
            event(scheduler, code);
        }

        /** 每个回调事件保留单调时点，晚到事件不改写已冻结的 FAIL。 */
        private void event(String scheduler, String phase) {
            CloseEvent event = new CloseEvent(scheduler, phase, System.nanoTime() - this.startedNanos, Thread.currentThread().getName());
            this.events.add(event);
            LOGGER.info("Q13_SCHEDULER_CLOSE_EVENT context=" + this.context.getId() + " " + event);
        }

        /** 仅保存已存在本地实例；不是全局 executor 注册器。 */
        private static final class Target {
            /** 冻结白名单中的 Bean 名。 */
            private final String name;
            /** 原始 scheduler，保留其 Metrics 装饰器和所有生产参数。 */
            private final ThreadPoolTaskScheduler scheduler;
            /** 原实例的 native executor，不创建替代线程池。 */
            private final ScheduledThreadPoolExecutor executor;
            /** 回调只发信号，等待发生在事件发布线程。 */
            private final CountDownLatch drained = new CountDownLatch(1);
            /** 允许晚到清理更新现场，但不更新已冻结的 CloseResult。 */
            private volatile String phase = "EXISTING_LOCAL";
            /** 明确区分返回队列取消与已出队任务的 JDK 自取消。 */
            private volatile int cancelledQueueTasks;

            /** @param name 白名单身份 @param scheduler 本地既有实例 */
            private Target(String name, ThreadPoolTaskScheduler scheduler) {
                this.name = name;
                this.scheduler = scheduler;
                this.executor = scheduler.getScheduledThreadPoolExecutor();
            }

            /** @return 不含任务内容的准确资源状态 */
            private PoolResult snapshot() {
                return new PoolResult(this.name, System.identityHashCode(this.scheduler), this.phase,
                        this.executor.isShutdown(), this.executor.isTerminated(), this.executor.getActiveCount(),
                        this.executor.getQueue().size(), this.cancelledQueueTasks);
            }
        }
    }
}
