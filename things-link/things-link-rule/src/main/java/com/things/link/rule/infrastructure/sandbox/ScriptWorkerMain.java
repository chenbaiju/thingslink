package com.things.link.rule.infrastructure.sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次脚本 Worker 进程入口。
 *
 * <p>该类型不是业务服务，也不会被 Spring 装配。宿主通过二进制 stdin/stdout 协议传递源码与 JSON；环境变量已清空，
 * guest 的 Host、文件、网络、进程和线程能力在 Context 创建时全部关闭。执行完成后进程退出，堆与 JIT 状态不复用。</p>
 */
public final class ScriptWorkerMain {

    /** 成功响应码。 */
    static final int RESULT_SUCCESS = 0;
    /** 语法或运行失败响应码。 */
    static final int RESULT_FAILURE = 1;
    /** 输出等资源耗尽响应码。 */
    static final int RESULT_RESOURCE_EXHAUSTED = 2;

    /** 工具进程禁止被实例化。 */
    private ScriptWorkerMain() {
    }

    /**
     * @param args CPU 纳秒、源码/输入/输出/stderr 字节上限与 EXECUTE/VALIDATE 模式
     * @throws Exception 协议或运行时装配失败时让进程以非零码退出，由宿主 fail-closed
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.exit(2);
            return;
        }
        long maxCpuNanos = positive(args[0]);
        long maxSourceBytes = positive(args[1]);
        long maxInputBytes = positive(args[2]);
        long maxOutputBytes = positive(args[3]);
        long maxErrorBytes = nonNegative(args[4]);
        boolean validationOnly = "VALIDATE".equals(args[5]);
        if (!validationOnly && !"EXECUTE".equals(args[5])) {
            System.exit(2);
            return;
        }
        BoundedOutputStream guestOutput = new BoundedOutputStream(maxOutputBytes);
        BoundedOutputStream guestError = new BoundedOutputStream(maxErrorBytes);

        try (Context context = Context.newBuilder("js")
                .sandbox(SandboxPolicy.CONSTRAINED)
                .in(new ByteArrayInputStream(new byte[0]))
                .out(guestOutput)
                .err(guestError)
                // CONSTRAINED 只允许显式 @HostAccess.Export；本 Worker 不向 bindings 放入任何 Host 对象。
                .allowHostAccess(HostAccess.CONSTRAINED)
                .allowHostClassLookup(name -> false)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowIO(IOAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .build();
             DataInputStream input = new DataInputStream(System.in);
             DataOutputStream output = new DataOutputStream(System.out)) {
            // 固定可信表达式只预热语言运行时；租户预算不能被 JVM/Graal 冷启动吞掉。
            context.eval("js", "0");
            String source = readUtf8(input, maxSourceBytes);
            String inputJson = readUtf8(input, maxInputBytes);
            Value function;
            try {
                // VALIDATE 不调用解析得到的函数正文；恶意源码求值仍由宿主启动超时强制终止整个 Worker。
                function = context.eval("js", "(" + source + ")");
                if (!function.canExecute()) {
                    throw new IllegalArgumentException("脚本必须返回函数");
                }
            } catch (RuntimeException exception) {
                output.writeInt(GraalJsScriptSandbox.READY_MAGIC);
                output.flush();
                writeResult(output, RESULT_FAILURE, "SANDBOX_SCRIPT_FAILURE");
                return;
            }
            if (validationOnly) {
                output.writeInt(GraalJsScriptSandbox.READY_MAGIC);
                output.flush();
                writeResult(output, RESULT_SUCCESS, "true");
                return;
            }
            Value guestInput;
            Value stringify;
            try {
                // 输入契约是 JSON 而不是第二段脚本；必须调用固定 JSON.parse，禁止把输入文本再次交给 eval。
                guestInput = context.eval("js", "JSON.parse").execute(inputJson);
                stringify = context.eval("js", "JSON.stringify");
            } catch (RuntimeException exception) {
                output.writeInt(GraalJsScriptSandbox.READY_MAGIC);
                output.flush();
                writeResult(output, RESULT_FAILURE, "SANDBOX_SCRIPT_FAILURE");
                return;
            }
            output.writeInt(GraalJsScriptSandbox.READY_MAGIC);
            output.flush();
            AtomicBoolean finished = new AtomicBoolean();
            Thread watchdog = cpuWatchdog(maxCpuNanos, finished, Thread.currentThread().threadId());
            watchdog.start();
            try {
                Value value = function.execute(guestInput);
                Value serialized = stringify.execute(value);
                if (serialized.isNull()) {
                    throw new IllegalArgumentException("脚本输出不得为 undefined");
                }
                String outputJson = serialized.asString();
                if (utf8Length(outputJson) > maxOutputBytes || guestOutput.exceeded() || guestError.exceeded()) {
                    writeResult(output, RESULT_RESOURCE_EXHAUSTED, "SANDBOX_OUTPUT_LIMIT");
                } else {
                    writeResult(output, RESULT_SUCCESS, outputJson);
                }
            } catch (PolyglotException exception) {
                String message = exception.getMessage() == null
                        ? "" : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (message.contains("out of memory") || message.contains("heap space")) {
                    Runtime.getRuntime().halt(GraalJsScriptSandbox.MEMORY_LIMIT_EXIT);
                }
                int status = guestOutput.exceeded() || guestError.exceeded()
                        ? RESULT_RESOURCE_EXHAUSTED : RESULT_FAILURE;
                String code = status == RESULT_RESOURCE_EXHAUSTED
                        ? "SANDBOX_OUTPUT_LIMIT" : "SANDBOX_SCRIPT_FAILURE";
                writeResult(output, status, code);
            } catch (OutOfMemoryError error) {
                Runtime.getRuntime().halt(GraalJsScriptSandbox.MEMORY_LIMIT_EXIT);
            } finally {
                finished.set(true);
            }
        }
    }

    /**
     * 创建按 guest 执行线程 CPU 时间计量的高优先级看门狗，超限时终止完整故障域。
     *
     * <p>Graal 编译线程和看门狗自身不属于租户脚本消耗，若按进程总 CPU 计费会让一次正常冷启动随机耗尽 50ms；guest
     * 始终在调用线程同步执行，因此线程 CPU 才是架构文档第 6 节“单次执行 CPU 时间”的准确口径。</p>
     */
    private static Thread cpuWatchdog(long maxCpuNanos, AtomicBoolean finished, long guestThreadId) {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!threadBean.isThreadCpuTimeSupported()) {
            throw new IllegalStateException("当前 JVM 不支持脚本 CPU 时间计量");
        }
        if (!threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
        long baseline = threadBean.getThreadCpuTime(guestThreadId);
        Thread watchdog = new Thread(() -> {
            while (!finished.get()) {
                long current = threadBean.getThreadCpuTime(guestThreadId);
                if (current >= 0 && current - baseline > maxCpuNanos) {
                    Runtime.getRuntime().halt(GraalJsScriptSandbox.CPU_LIMIT_EXIT);
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "rule-sandbox-cpu-watchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.MAX_PRIORITY);
        return watchdog;
    }

    /** 读取有界 UTF-8 字段，拒绝负数、超限与截断帧。 */
    private static String readUtf8(DataInputStream input, long maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw new IOException("Worker 输入长度越界");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Worker 输入帧被截断");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 写入有界响应；错误正文只使用固定代码。 */
    private static void writeResult(DataOutputStream output, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        output.writeInt(status);
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    /** @return UTF-8 真实字节数 */
    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 解析严格正整数资源参数。 */
    private static long positive(String value) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("Worker 资源上限必须大于零");
        }
        return parsed;
    }

    /** 解析允许零值的 stderr 上限。 */
    private static long nonNegative(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("Worker stderr 上限不得为负");
        }
        return parsed;
    }

    /** 只计数不保留 guest 输出的有界流，避免日志洪泛和敏感数据回流宿主。 */
    private static final class BoundedOutputStream extends OutputStream {
        /** 最大允许字节数。 */
        private final long maximumBytes;
        /** 已观察字节数。 */
        private long written;
        /** 是否已经超过上限。 */
        private boolean exceeded;

        /** @param maximumBytes 最大允许字节数 */
        private BoundedOutputStream(long maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        /** 沿用接口定义的契约。{@inheritDoc} */
        @Override
        public void write(int value) throws IOException {
            written++;
            if (written > maximumBytes) {
                exceeded = true;
                throw new IOException("guest 输出超过限制");
            }
        }

        /** @return 是否已观察到超限写入 */
        private boolean exceeded() {
            return exceeded;
        }
    }
}
