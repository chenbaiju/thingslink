package com.things.link.support.fault;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * G1-C4b 专用的一次性进程强杀屏障。
 *
 * <p>该组件没有网络入口，且默认完全禁用。只有显式启用、UUIDv7 runId、冻结场景与 checkpoint
 * 四项同时匹配时才会落盘并阻塞；因此生产默认配置不会引入等待分支。外部 runner 观察到
 * {@code REACHED} 后精确强杀当前 SUT，重启进程看到 once 标记会直接越过，避免无限强杀循环。</p>
 */
@Component
public final class FaultInjectionCheckpoint {

    /** 冻结的九个 Worker 注入点；数据库停机场景复用 Outbox 领取后注入点而不新增生产分支。 */
    public enum Checkpoint {
        /** Outbox 领取事务开始前。 */
        OUTBOX_BEFORE_CLAIM,
        /** Outbox 领取事务已经提交。 */
        OUTBOX_AFTER_CLAIM_COMMIT,
        /** Kafka 已确认、Outbox 发布终态尚未写入。 */
        OUTBOX_AFTER_KAFKA_ACK,
        /** Outbox 发布终态已经提交。 */
        OUTBOX_AFTER_MARK_PUBLISHED_COMMIT,
        /** 规则持久回执抢占前。 */
        RULE_BEFORE_RECEIPT_CLAIM,
        /** 规则持久回执抢占后、脚本执行前。 */
        RULE_AFTER_RECEIPT_CLAIM,
        /** processed continuation 已获 Kafka ACK。 */
        RULE_AFTER_CONTINUATION_ACK,
        /** 规则回执与副作用事务已经提交。 */
        RULE_AFTER_RECEIPT_COMMIT,
        /** retry Topic 已获 Kafka ACK、旧回执尚未释放。 */
        RULE_AFTER_RETRY_ACK
    }

    /** checkpoint 到唯一允许场景的映射；DB-01 是唯一复用 OB-02 注入点的场景。 */
    private static final Map<Checkpoint, java.util.Set<String>> ALLOWED_SCENARIOS = Map.of(
            Checkpoint.OUTBOX_BEFORE_CLAIM, java.util.Set.of("OB-01"),
            Checkpoint.OUTBOX_AFTER_CLAIM_COMMIT, java.util.Set.of("OB-02", "DB-01"),
            Checkpoint.OUTBOX_AFTER_KAFKA_ACK, java.util.Set.of("OB-03"),
            Checkpoint.OUTBOX_AFTER_MARK_PUBLISHED_COMMIT, java.util.Set.of("OB-04"),
            Checkpoint.RULE_BEFORE_RECEIPT_CLAIM, java.util.Set.of("RW-01"),
            Checkpoint.RULE_AFTER_RECEIPT_CLAIM, java.util.Set.of("RW-02"),
            Checkpoint.RULE_AFTER_CONTINUATION_ACK, java.util.Set.of("RW-03"),
            Checkpoint.RULE_AFTER_RECEIPT_COMMIT, java.util.Set.of("RW-04"),
            Checkpoint.RULE_AFTER_RETRY_ACK, java.util.Set.of("RW-05"));

    /** 等待 release 文件的轮询间隔；没有超时越过，进程只能被 runner 释放或强杀。 */
    private static final long RELEASE_POLL_NANOS = 50_000_000L;

    /** 是否启用；禁用实例的 {@link #reach(Checkpoint, String)} 是纯 no-op。 */
    private final boolean enabled;
    /** 本次资格运行 UUIDv7。 */
    private final UUID runId;
    /** 本次唯一场景。 */
    private final String scenario;
    /** 本次唯一注入点。 */
    private final Checkpoint configuredCheckpoint;
    /** runner 预建并独占的控制目录真实路径。 */
    private final Path controlDirectory;

    /**
     * Spring 生产装配入口；四项参数只有启用时才要求非空并校验。
     *
     * @param enabled 是否启用故障注入
     * @param runId 本次 UUIDv7
     * @param scenario 冻结场景 ID
     * @param checkpoint 冻结 checkpoint 名
     * @param controlDirectory runner 预建的绝对控制目录
     */
    @Autowired
    public FaultInjectionCheckpoint(
            @Value("${things-link.fault-injection.enabled:false}") boolean enabled,
            @Value("${things-link.fault-injection.run-id:}") String runId,
            @Value("${things-link.fault-injection.scenario:}") String scenario,
            @Value("${things-link.fault-injection.checkpoint:}") String checkpoint,
            @Value("${things-link.fault-injection.control-dir:}") String controlDirectory) {
        this(enabled, runId, scenario, checkpoint,
                controlDirectory == null || controlDirectory.isBlank() ? null : Path.of(controlDirectory));
    }

    /**
     * 可测试构造器，不读取环境变量或系统属性。
     *
     * @param enabled 是否启用
     * @param runId UUIDv7 文本
     * @param scenario 场景 ID
     * @param checkpoint checkpoint 名
     * @param controlDirectory 控制目录
     */
    FaultInjectionCheckpoint(boolean enabled, String runId, String scenario,
                             String checkpoint, Path controlDirectory) {
        this.enabled = enabled;
        if (!enabled) {
            this.runId = null;
            this.scenario = null;
            this.configuredCheckpoint = null;
            this.controlDirectory = null;
            return;
        }
        this.runId = parseUuidV7(runId);
        this.scenario = requireText(scenario, "故障场景");
        this.configuredCheckpoint = parseCheckpoint(checkpoint);
        if (!ALLOWED_SCENARIOS.get(this.configuredCheckpoint).contains(this.scenario)) {
            throw new IllegalArgumentException("故障场景与 checkpoint 不匹配");
        }
        this.controlDirectory = validateControlDirectory(controlDirectory);
    }

    /** @return 不产生文件和等待的禁用实例，供非 Spring 单元测试构造生产类。 */
    public static FaultInjectionCheckpoint disabled() {
        return new FaultInjectionCheckpoint(false, null, null, null, (Path) null);
    }

    /**
     * 到达匹配注入点时先持久化一次性标记与脱敏证据，再等待 runner 释放或强杀。
     *
     * @param checkpoint 当前代码注入点
     * @param stableIdentity 业务稳定身份，仅保存 SHA-256，不得传入 payload 或凭据
     */
    public void reach(Checkpoint checkpoint, String stableIdentity) {
        if (!enabled || checkpoint != configuredCheckpoint) {
            return;
        }
        Path onceFile = controlDirectory.resolve("once").resolve(fileName(checkpoint, ".once"));
        if (!claimOnce(onceFile)) {
            return;
        }
        appendReached(checkpoint, stableIdentity);
        Path releaseFile = controlDirectory.resolve("release").resolve(fileName(checkpoint, ".release"));
        while (!Files.isRegularFile(releaseFile, LinkOption.NOFOLLOW_LINKS)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("故障屏障等待被中断");
            }
            LockSupport.parkNanos(RELEASE_POLL_NANOS);
        }
    }

    /** @return 当前 checkpoint 是否成功取得 CREATE_NEW 一次性标记。 */
    private boolean claimOnce(Path onceFile) {
        byte[] bytes = runId.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(onceFile,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            channel.force(true);
            return true;
        } catch (FileAlreadyExistsException ignored) {
            return false;
        } catch (IOException exception) {
            throw new IllegalStateException("故障屏障一次性标记写入失败", exception);
        }
    }

    /**
     * 先持久化同目录临时文件，再原子发布唯一 REACHED。
     *
     * <p>G1-C4b-F22 关闭 attempt 17 暴露的可见性窗口：若直接以 CREATE + APPEND 打开最终路径，
     * Windows runner 可能在首字节写入前看到空文件并误判。一次性标记已经保证本场景只有一个 writer，
     * 因此最终证据不是通用事件日志，不需要用追加协议换取多 writer 能力。</p>
     *
     * @param checkpoint 已取得一次性标记的注入点
     * @param stableIdentity 只用于生成脱敏摘要的业务稳定身份
     */
    private void appendReached(Checkpoint checkpoint, String stableIdentity) {
        String json = "{\"event\":\"REACHED\",\"runId\":\"" + runId
                + "\",\"scenario\":\"" + scenario
                + "\",\"checkpoint\":\"" + checkpoint.name()
                + "\",\"pid\":" + ProcessHandle.current().pid()
                + ",\"thread\":\"" + escape(Thread.currentThread().getName())
                + "\",\"stableIdSha256\":\"" + sha256(requireText(stableIdentity, "稳定身份"))
                + "\",\"reachedAt\":\"" + Instant.now() + "\"}\n";
        Path events = controlDirectory.resolve("events.jsonl");
        Path pending = controlDirectory.resolve("events.jsonl.pending");
        try (FileChannel channel = FileChannel.open(pending,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            writeFully(channel, StandardCharsets.UTF_8.encode(json));
            channel.force(true);
        } catch (IOException exception) {
            throw new IllegalStateException("故障屏障 REACHED 证据写入失败", exception);
        }
        try {
            // 同目录 ATOMIC_MOVE 是完成发布点；不允许降级为先暴露空最终文件的非原子复制。
            Files.move(pending, events, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("故障屏障 REACHED 证据原子发布失败", exception);
        }
    }

    /** FileChannel.write 允许短写，证据必须循环写完后才能 force。 */
    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** @return 只含冻结枚举的安全文件名。 */
    private static String fileName(Checkpoint checkpoint, String suffix) {
        return checkpoint.name().toLowerCase(java.util.Locale.ROOT) + suffix;
    }

    /** @return UUIDv7；其他 UUID 不能作为跨重启资格身份。 */
    private static UUID parseUuidV7(String value) {
        try {
            UUID parsed = UUID.fromString(requireText(value, "runId"));
            if (parsed.version() != 7) {
                throw new IllegalArgumentException("runId 必须是 UUIDv7");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("runId 必须是 UUIDv7", exception);
        }
    }

    /** @return 冻结 checkpoint 枚举。 */
    private static Checkpoint parseCheckpoint(String value) {
        try {
            return Checkpoint.valueOf(requireText(value, "checkpoint"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知故障 checkpoint", exception);
        }
    }

    /** @return 无符号链接且包含 once/release 子目录的真实绝对目录。 */
    private static Path validateControlDirectory(Path directory) {
        if (directory == null || !directory.isAbsolute() || Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException("故障控制目录必须是非符号链接绝对路径");
        }
        try {
            Path real = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path once = real.resolve("once");
            Path release = real.resolve("release");
            if (!Files.isDirectory(once, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(release, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(once) || Files.isSymbolicLink(release)) {
                throw new IllegalArgumentException("故障控制目录缺少安全的 once/release 子目录");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("故障控制目录不可读取", exception);
        }
    }

    /** @return 非空配置值。 */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    /** @return UTF-8 SHA-256 小写十六进制。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    /** @return JSON 字符串最小转义；线程名是唯一非冻结文本。 */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
