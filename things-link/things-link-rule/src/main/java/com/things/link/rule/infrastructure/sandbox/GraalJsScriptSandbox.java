package com.things.link.rule.infrastructure.sandbox;

import com.things.link.rule.application.ScriptExecutionRequest;
import com.things.link.rule.application.ScriptExecutionResult;
import com.things.link.rule.application.ScriptExecutionStatus;
import com.things.link.rule.application.ScriptSandbox;
import com.things.link.rule.application.ScriptKind;
import com.things.link.rule.application.ScriptValidationResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通过一次执行一个独立 Worker JVM 隔离 GraalJS Community 的脚本执行器。
 *
 * <p>当前工程使用 Eclipse Adoptium JDK；GraalVM UNTRUSTED 资源限制依赖 Oracle JDK 与 Enterprise 扩展，
 * 不能作为可移植基线。ADR 0026 因而选择独立进程：Worker 清空环境变量、关闭 Host/IO/线程/进程能力，
 * 以 {@code -Xmx} 和元空间限制单次内存，内部 CPU 看门狗超时直接终止整个进程。宿主业务 JVM 从不 eval。</p>
 */
@Component
public class GraalJsScriptSandbox implements ScriptSandbox {

    /** Worker 正常完成前先写入的协议魔数。 */
    static final int READY_MAGIC = 0x53425231;
    /** Worker 因 CPU 上限主动终止的进程码。 */
    static final int CPU_LIMIT_EXIT = 124;
    /** Worker 因堆耗尽主动终止的进程码。 */
    static final int MEMORY_LIMIT_EXIT = 125;
    /** 工作线程名前缀，用于线程转储中与 HTTP/Kafka 业务线程区分。 */
    private static final String THREAD_NAME_PREFIX = "rule-sandbox-";
    /** Java 可执行文件名按当前平台选择。 */
    private static final String JAVA_EXECUTABLE = System.getProperty("os.name")
            .toLowerCase().contains("win") ? "java.exe" : "java";
    /** 只记录 Worker 装配故障，不记录租户源码、输入或 guest 异常正文。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(GraalJsScriptSandbox.class);

    /** 全部资源硬边界。 */
    private final ScriptSandboxProperties properties;
    /** 真实成功、失败、超时和拒绝指标。 */
    private final ScriptExecutionMetrics metrics;
    /** 与主业务线程隔离的固定线程数、有界队列执行器。 */
    private final ThreadPoolExecutor executor;

    /**
     * @param properties 启动时已校验的资源边界
     * @param metrics 低基数执行指标
     */
    public GraalJsScriptSandbox(ScriptSandboxProperties properties, ScriptExecutionMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        this.executor = new ThreadPoolExecutor(
                properties.poolSize(), properties.poolSize(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), threadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ScriptValidationResult validate(ScriptKind kind, String source) {
        if (kind == null || source == null || source.isBlank()) {
            throw new IllegalArgumentException("脚本分类和源码不能为空");
        }
        long started = System.nanoTime();
        if (utf8Length(source) > properties.maxSourceBytes().toBytes()) {
            return ScriptValidationResult.invalid("SANDBOX_SOURCE_LIMIT", elapsed(started));
        }
        Future<WorkerResult> future;
        try {
            future = executor.submit(() -> executeInWorker(
                    new ScriptExecutionRequest(kind, source, "{}"), WorkerMode.VALIDATE));
        } catch (RejectedExecutionException exception) {
            return ScriptValidationResult.invalid("SANDBOX_QUEUE_FULL", elapsed(started));
        }
        try {
            Duration totalDeadline = properties.maxStartupTime().plus(properties.maxWallTime());
            WorkerResult worker = future.get(totalDeadline.toMillis(), TimeUnit.MILLISECONDS);
            return worker.success()
                    ? ScriptValidationResult.valid(elapsed(started))
                    : ScriptValidationResult.invalid(worker.errorCode(), elapsed(started));
        } catch (TimeoutException exception) {
            future.cancel(true);
            return ScriptValidationResult.invalid("SANDBOX_WALL_TIMEOUT", elapsed(started));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ScriptValidationResult.invalid("SANDBOX_CALLER_INTERRUPTED", elapsed(started));
        } catch (Exception exception) {
            LOGGER.error("脚本 Worker 验证调用失败，验证已 fail-closed", exception);
            return ScriptValidationResult.invalid("SANDBOX_INTERNAL_FAILURE", elapsed(started));
        }
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public ScriptExecutionResult execute(ScriptExecutionRequest request) {
        long started = System.nanoTime();
        ScriptExecutionResult invalid = validateSize(request, started);
        if (invalid != null) {
            metrics.record(request.kind(), invalid.status(), invalid.duration());
            return invalid;
        }

        Future<WorkerResult> future;
        try {
            future = executor.submit(() -> executeInWorker(request, WorkerMode.EXECUTE));
        } catch (RejectedExecutionException exception) {
            ScriptExecutionResult rejected = failed(
                    ScriptExecutionStatus.REJECTED, "SANDBOX_QUEUE_FULL", started);
            metrics.record(request.kind(), rejected.status(), rejected.duration());
            return rejected;
        }

        ScriptExecutionResult result;
        try {
            Duration totalDeadline = properties.maxStartupTime().plus(properties.maxWallTime());
            WorkerResult worker = future.get(totalDeadline.toMillis(), TimeUnit.MILLISECONDS);
            result = worker.success()
                    ? ScriptExecutionResult.success(worker.outputJson(), elapsed(started))
                    : failed(worker.status(), worker.errorCode(), started);
        } catch (TimeoutException exception) {
            future.cancel(true);
            result = failed(ScriptExecutionStatus.TIMEOUT, "SANDBOX_WALL_TIMEOUT", started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            result = failed(ScriptExecutionStatus.FAILURE, "SANDBOX_CALLER_INTERRUPTED", started);
        } catch (Exception exception) {
            LOGGER.error("脚本 Worker 调用失败，执行已 fail-closed", exception);
            result = failed(ScriptExecutionStatus.FAILURE, "SANDBOX_INTERNAL_FAILURE", started);
        }
        metrics.record(request.kind(), result.status(), result.duration());
        return result;
    }

    /** 应用停止时立即取消排队任务；运行中的 Worker 由中断路径强制销毁。 */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    /** 启动无环境变量、有限内存的 Worker，并通过二进制协议交换源码与 JSON。 */
    private WorkerResult executeInWorker(ScriptExecutionRequest request, WorkerMode mode) {
        Process process = null;
        Path workingDirectory = null;
        try {
            workingDirectory = Files.createTempDirectory("thingslink-script-");
            ProcessBuilder builder = new ProcessBuilder(workerCommand(mode));
            builder.directory(workingDirectory.toFile());
            builder.environment().clear();
            process = builder.start();

            DataInputStream input = new DataInputStream(process.getInputStream());
            // 源码先送入 Worker 做有界解析；READY 前受启动墙钟约束，READY 后再开始租户 CPU/执行墙钟预算。
            DataOutputStream output = new DataOutputStream(process.getOutputStream());
            writeUtf8(output, request.source());
            writeUtf8(output, request.inputJson());
            output.flush();
            if (!waitForReady(process, input, properties.maxStartupTime())) {
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    if (exitCode == CPU_LIMIT_EXIT) {
                        return WorkerResult.failed(ScriptExecutionStatus.TIMEOUT, "SANDBOX_CPU_LIMIT");
                    }
                    if (exitCode == MEMORY_LIMIT_EXIT) {
                        return WorkerResult.failed(
                                ScriptExecutionStatus.RESOURCE_EXHAUSTED, "SANDBOX_MEMORY_LIMIT");
                    }
                }
                // 先终止再读取 stderr；活进程的 readNBytes 会等待 EOF，顺序相反将泄漏恶意 Worker。
                destroy(process);
                logStartupFailure(process);
                return WorkerResult.failed(ScriptExecutionStatus.FAILURE, "SANDBOX_WORKER_STARTUP");
            }
            output.close();

            if (!process.waitFor(properties.maxWallTime().toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                return WorkerResult.failed(ScriptExecutionStatus.TIMEOUT, "SANDBOX_WALL_TIMEOUT");
            }
            int exitCode = process.exitValue();
            if (exitCode == CPU_LIMIT_EXIT) {
                return WorkerResult.failed(ScriptExecutionStatus.TIMEOUT, "SANDBOX_CPU_LIMIT");
            }
            if (exitCode == MEMORY_LIMIT_EXIT) {
                return WorkerResult.failed(
                        ScriptExecutionStatus.RESOURCE_EXHAUSTED, "SANDBOX_MEMORY_LIMIT");
            }
            if (exitCode != 0 || input.available() < Integer.BYTES * 2) {
                return WorkerResult.failed(ScriptExecutionStatus.FAILURE, "SANDBOX_WORKER_FAILURE");
            }
            int status = input.readInt();
            String payload = readUtf8(input, properties.maxOutputBytes().toBytes());
            return switch (status) {
                case ScriptWorkerMain.RESULT_SUCCESS -> WorkerResult.success(payload);
                case ScriptWorkerMain.RESULT_RESOURCE_EXHAUSTED -> WorkerResult.failed(
                        ScriptExecutionStatus.RESOURCE_EXHAUSTED, payload);
                default -> WorkerResult.failed(ScriptExecutionStatus.FAILURE, payload);
            };
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroy(process);
            return WorkerResult.failed(ScriptExecutionStatus.FAILURE, "SANDBOX_CALLER_INTERRUPTED");
        } catch (IOException | RuntimeException exception) {
            destroy(process);
            LOGGER.error("脚本 Worker 启动或协议失败，执行已 fail-closed", exception);
            return WorkerResult.failed(ScriptExecutionStatus.FAILURE, "SANDBOX_RUNTIME_UNAVAILABLE");
        } finally {
            destroy(process);
            deleteWorkingDirectory(workingDirectory);
        }
    }

    /** 构造测试 classpath 与 Spring Boot 可执行 JAR 都能启动的 Worker 命令。 */
    private List<String> workerCommand(WorkerMode mode) {
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", JAVA_EXECUTABLE).toString());
        command.add("-Xms16m");
        command.add("-Xmx" + properties.maxWorkerHeapMemory().toBytes());
        command.add("-XX:MaxMetaspaceSize=" + properties.maxWorkerMetaspace().toBytes());
        command.add("-Dpolyglot.engine.WarnInterpreterOnly=false");
        boolean executableJar = classpath.indexOf(File.pathSeparatorChar) < 0 && classpath.endsWith(".jar");
        if (executableJar) {
            // JVM 系统属性必须位于 main class 之前；否则 java 会把 -Dloader.main 误当成待加载类名。
            command.add("-Dloader.main=" + ScriptWorkerMain.class.getName());
        }
        command.add("-cp");
        command.add(classpath);
        if (executableJar) {
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add(ScriptWorkerMain.class.getName());
        }
        command.add(Long.toString(properties.maxCpuTime().toNanos()));
        command.add(Long.toString(properties.maxSourceBytes().toBytes()));
        command.add(Long.toString(properties.maxInputBytes().toBytes()));
        command.add(Long.toString(properties.maxOutputBytes().toBytes()));
        command.add(Long.toString(properties.maxErrorBytes().toBytes()));
        command.add(mode.name());
        return command;
    }

    /** 等待 Worker 完成 Context 安全配置后再开始计算脚本 CPU 与墙钟预算。 */
    private static boolean waitForReady(Process process, DataInputStream input, Duration timeout)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (input.available() >= Integer.BYTES) {
                return input.readInt() == READY_MAGIC;
            }
            if (!process.isAlive()) {
                return false;
            }
            Thread.sleep(5);
        }
        return false;
    }

    /**
     * 截断记录 Worker 启动期 stderr 以定位 classpath/JVM 参数错误。
     *
     * <p>租户源码虽已通过 stdin 发送，但 Worker 从不把协议载荷或 guest 异常正文写入 stderr；这里仍强制按字节截断，
     * 防止底层运行时异常形成无界日志。</p>
     */
    private void logStartupFailure(Process process) {
        try {
            byte[] bytes = process.getErrorStream().readNBytes((int) properties.maxErrorBytes().toBytes());
            LOGGER.error("脚本 Worker 启动失败: {}", new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            LOGGER.error("脚本 Worker 启动失败且 stderr 不可读", exception);
        }
    }

    /** 源码和输入在排队前按真实 UTF-8 字节拒绝。 */
    private ScriptExecutionResult validateSize(ScriptExecutionRequest request, long started) {
        if (utf8Length(request.source()) > properties.maxSourceBytes().toBytes()) {
            return failed(ScriptExecutionStatus.RESOURCE_EXHAUSTED, "SANDBOX_SOURCE_LIMIT", started);
        }
        if (utf8Length(request.inputJson()) > properties.maxInputBytes().toBytes()) {
            return failed(ScriptExecutionStatus.RESOURCE_EXHAUSTED, "SANDBOX_INPUT_LIMIT", started);
        }
        return null;
    }

    /** 写入有界 UTF-8 字段，Worker 不解析命令行中的租户内容。 */
    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    /** 读取 Worker 有界响应，防损坏或恶意运行时声明超长载荷。 */
    private static String readUtf8(DataInputStream input, long maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw new IOException("Worker 响应长度越界");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Worker 响应帧被截断");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 中断或超时时必须销毁整个故障域，不能只 interrupt 宿主等待线程。 */
    private static void destroy(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                // 等待操作系统真正回收进程，确保调用返回后不会遗留继续消耗 CPU 的故障域。
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 删除每次执行的空临时工作目录；失败仅留下无脚本内容的目录，不影响业务结果。 */
    private static void deleteWorkingDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // guest 无文件权限；这里只处理杀进程与杀毒软件短暂占用造成的空目录残留。
                }
            });
        } catch (IOException ignored) {
            // 临时目录不含租户源码或输入，清理失败不改变执行裁决。
        }
    }

    /** 创建守护线程，避免仅剩沙箱线程时阻止 JVM 正常退出。 */
    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, THREAD_NAME_PREFIX + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** @return 从开始纳秒到当前时刻的非负持续时间 */
    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    /** 创建不含输出和异常正文的失败结果。 */
    private static ScriptExecutionResult failed(
            ScriptExecutionStatus status, String errorCode, long started) {
        return ScriptExecutionResult.failed(status, errorCode, elapsed(started));
    }

    /** @return UTF-8 真实字节数 */
    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Worker 的有界响应，不携带异常正文。 */
    private record WorkerResult(
            boolean success,
            String outputJson,
            ScriptExecutionStatus status,
            String errorCode) {

        /** @param outputJson 已验证 JSON 输出 @return 成功结果 */
        private static WorkerResult success(String outputJson) {
            return new WorkerResult(true, outputJson, ScriptExecutionStatus.SUCCESS, null);
        }

        /** @param status 失败状态 @param errorCode 固定错误分类 @return 失败结果 */
        private static WorkerResult failed(ScriptExecutionStatus status, String errorCode) {
            return new WorkerResult(false, null, status, errorCode);
        }
    }

    /** Worker 协议操作；VALIDATE 只解析函数，绝不调用租户函数正文。 */
    private enum WorkerMode {
        /** 解析输入并执行函数。 */
        EXECUTE,
        /** 只解析并确认源码可执行。 */
        VALIDATE
    }
}
