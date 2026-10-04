package com.things.link.support.scheduling;

import ch.qos.logback.classic.LoggerContext;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.DirectEvidence;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.ExpectedContext;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.PrivateFiles;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.ShutdownMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仅support的两个固定Surefire execution使用：前一fork自然退出，后一fork向Maven传播验收失败。
 * 不继承容器基类；不把退出hook的异常或Maven的单独exit0当作证据。
 * 默认测试通过tag排除此夹具，专用execution固定选择器，用户-Dtest不能绕过后置门禁。
 */
@Tag("q13-dedicated-exit")
@Timeout(5)
class SchedulingShutdownExitFixture {

    /** 父Maven会话毫秒起点；目录CREATE_NEW再拒绝并发碰撞/旧输出复用。 */
    private static final String BUILD = "q13.gate.build";
    /** 默认正常；错误路径只在显式最低层命令中注入，验收方绝不因此改为接受FAIL。 */
    private static final String FAULT = "q13.exitFault";

    /** 真Boot持有关闭hook；本测试返回后仅由实际Surefire自然退出触发关闭。 */
    @Test
    void leaveContextForNaturalForkExit() throws Exception {
        assertThat(System.getProperty("q13.gate.stage")).isEqualTo("exit");
        String build = buildId();
        Path base = moduleTarget().resolve("q13-shutdown");
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) PrivateFiles.createDirectory(base);
        PrivateFiles.requirePrivate(base, true);
        Path root = PrivateFiles.createDirectory(base.resolve(build));
        Path records = PrivateFiles.createDirectory(root.resolve("records"));
        String run = UUID.randomUUID().toString();
        String fault = System.getProperty(FAULT, "NONE");
        assertThat(fault).isIn("NONE", "MISSING_TERMINAL", "CLOSE_FAIL", "WRITE_FAILURE", "MISSING_OUTCOME");
        Map<String, String> intent = Map.of("build", build, "runId", run, "module", "things-link-support",
                "pid", Long.toString(ProcessHandle.current().pid()), "expectedContexts", "1", "fault", fault,
                "hookSha256", classSha(PausedSchedulerShutdownTestConfiguration.class), "fixtureSha256", classSha(getClass()));
        write(root.resolve("intent.properties"), intent);
        String inheritedDirectory = System.getProperty(PausedSchedulerShutdownTestConfiguration.EVIDENCE_DIRECTORY_PROPERTY);
        String inheritedRun = System.getProperty(PausedSchedulerShutdownTestConfiguration.EVIDENCE_RUN_PROPERTY);
        SpringApplication app = new SpringApplication(MemoryBootConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(true);
        app.setLogStartupInfo(false);
        app.addInitializers(context -> {
            context.setId("q13-dedicated-surefire");
            isolateFixtureEvidence(context, records, run);
        });
        app.setDefaultProperties(Map.of("spring.config.location", "optional:classpath:q13-no-business-config.properties",
                "spring.main.banner-mode", "off", "things-link.scheduling.shutdown-await-seconds", "1",
                PausedSchedulerShutdownTestConfiguration.DEADLINE_PROPERTY, "1000"));
        ConfigurableApplicationContext context = app.run();
        boolean handedToBoot = false;
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertThat(context.getEnvironment().getProperty(PausedSchedulerShutdownTestConfiguration.EVIDENCE_DIRECTORY_PROPERTY)).isEqualTo(records.toString());
            assertThat(context.getEnvironment().getProperty(PausedSchedulerShutdownTestConfiguration.EVIDENCE_RUN_PROPERTY)).isEqualTo(run);
            assertThat(System.getProperty(PausedSchedulerShutdownTestConfiguration.EVIDENCE_DIRECTORY_PROPERTY)).isEqualTo(inheritedDirectory);
            assertThat(System.getProperty(PausedSchedulerShutdownTestConfiguration.EVIDENCE_RUN_PROPERTY)).isEqualTo(inheritedRun);
            ShutdownMonitor monitor = context.getBean(ShutdownMonitor.class);
            List<ThreadPoolTaskScheduler> pools = PausedSchedulerShutdownTestConfiguration.SCHEDULER_NAMES.stream()
                    .map(name -> context.getBean(name, ThreadPoolTaskScheduler.class)).toList();
            ThreadPoolTaskScheduler scheduler = pools.getFirst();
            List<Thread> owned = new CopyOnWriteArrayList<>();
            ThreadFactory original = scheduler.getScheduledThreadPoolExecutor().getThreadFactory();
            scheduler.getScheduledThreadPoolExecutor().setThreadFactory(action -> {
                Thread thread = original.newThread(action);
                owned.add(thread);
                return thread;
            });
            AtomicInteger ran = new AtomicInteger();
            CountDownLatch admitted = new CountDownLatch(1);
            if (!fault.equals("CLOSE_FAIL")) scheduler.stop();
            ScheduledFuture<?> future = scheduler.getScheduledThreadPoolExecutor().schedule(() -> {
                ran.incrementAndGet();
                admitted.countDown();
                if (fault.equals("CLOSE_FAIL")) {
                    try { release.await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
            }, 0, TimeUnit.MILLISECONDS);
            if (fault.equals("CLOSE_FAIL")) assertThat(admitted.await(1, TimeUnit.SECONDS)).isTrue();
            else {
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (!beforeExecute(owned) && System.nanoTime() < limit) LockSupport.parkNanos(1_000_000);
                assertThat(beforeExecute(owned)).isTrue();
                assertThat(ran).hasValue(0);
            }
            ExpectedContext identity = monitor.evidenceIdentity();
            assertThat(identity).isNotNull();
            assertThat(monitor.result().status()).isEqualTo("NOT_RUN");
            write(root.resolve("ready.properties"), Map.of("runId", run, "pid", Long.toString(identity.pid()),
                    "instanceId", identity.instanceId(), "intentSha256", sha(Files.readAllBytes(root.resolve("intent.properties"))),
                    "statusBeforeExit", "NOT_RUN", "expectedPools", Integer.toString(PausedSchedulerShutdownTestConfiguration.SCHEDULER_NAMES.size())));
            Path instance = records.resolve(identity.module() + "--" + identity.pid() + "--" + identity.instanceId());
            Path saved = root.resolve("registration-before-write-failure");
            if (fault.equals("WRITE_FAILURE")) {
                Files.move(instance, saved, StandardCopyOption.ATOMIC_MOVE);
                write(instance, Map.of("controlled", "not-a-directory"));
            }
            SpringApplication.getShutdownHandlers().add(() -> afterBootClose(root, records, identity, monitor, context,
                    pools, future, owned, ran, release, fault, instance, saved, inheritedDirectory, inheritedRun));
            handedToBoot = true;
            // 此execution只有本夹具；不关闭默认复用fork或验收fork的日志。
            ((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).stop();
        } finally {
            if (!handedToBoot) {
                release.countDown();
                context.close();
            }
        }
    }

    /**
     * 两个证据字段只覆盖本专用context；defaultProperties低于系统属性，会误占全reactor目录。
     * 不改System属性、其他context或关闭预算；永久低层和真实冲突CLI均调用此接线。
     */
    static void isolateFixtureEvidence(ConfigurableApplicationContext context, Path records, String run) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("q13-fixture-owned-evidence", Map.of(
                PausedSchedulerShutdownTestConfiguration.EVIDENCE_DIRECTORY_PROPERTY, records.toString(),
                PausedSchedulerShutdownTestConfiguration.EVIDENCE_RUN_PROPERTY, run)));
    }

    /** 后置handler只写事实；真正使构建变红的是下一个独立Surefire execution。 */
    private static void afterBootClose(Path root, Path records, ExpectedContext identity, ShutdownMonitor monitor,
            ConfigurableApplicationContext context, List<ThreadPoolTaskScheduler> pools, ScheduledFuture<?> future,
            List<Thread> owned, AtomicInteger ran, CountDownLatch release, String fault, Path instance, Path saved,
            String inheritedDirectory, String inheritedRun) {
        String verification = "PASS";
        try { monitor.assertSuccessful(); }
        catch (RuntimeException error) { verification = "FAIL"; }
        if (!Objects.equals(inheritedDirectory, System.getProperty(PausedSchedulerShutdownTestConfiguration.EVIDENCE_DIRECTORY_PROPERTY))
                || !Objects.equals(inheritedRun, System.getProperty(PausedSchedulerShutdownTestConfiguration.EVIDENCE_RUN_PROPERTY))) verification = "FAIL";
        // 预登记超时负例仅在事实冻结后释放本例任务；这是清理，不是把首FAIL改绿。
        release.countDown();
        boolean cleanup = true;
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try {
            for (ThreadPoolTaskScheduler pool : pools) {
                long left = Math.max(0, limit - System.nanoTime());
                cleanup &= pool.getScheduledThreadPoolExecutor().awaitTermination(left, TimeUnit.NANOSECONDS);
            }
            // executor的TERMINATED信号可能略早于Thread.run返回；只在同一清理预算内等待精确线程。
            for (Thread thread : owned) TimeUnit.NANOSECONDS.timedJoin(thread, Math.max(0, limit - System.nanoTime()));
            cleanup &= owned.stream().noneMatch(Thread::isAlive);
            if (fault.equals("WRITE_FAILURE")) {
                Files.delete(instance);
                Files.move(saved, instance, StandardCopyOption.ATOMIC_MOVE);
            }
            if (fault.equals("MISSING_TERMINAL")) Files.delete(instance.resolve("terminal.properties"));
            if (!fault.equals("MISSING_OUTCOME")) write(root.resolve("outcome.properties"), Map.of(
                    "runId", identity == null ? "UNKNOWN" : read(root.resolve("intent.properties")).get("runId"),
                    "pid", Long.toString(ProcessHandle.current().pid()), "instanceId", identity.instanceId(),
                    "verification", verification, "status", monitor.result().status(), "cleanup", cleanup ? "PASS" : "FAIL",
                    "ran", Integer.toString(ran.get()), "cancelled", Boolean.toString(future.isCancelled()),
                    "contextActive", Boolean.toString(context.isActive()), "handlerThread", Thread.currentThread().getName()));
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            // 即使退出期无法再写错误记录，父层的非空预期与文件集合检查也必须失败。
        }
    }

    /** 本fork在前一fork自然退出后开始；任一断言失败由Surefire直接传播为Maven非零。 */
    @Test
    void acceptExitedFork() throws Exception {
        assertThat(System.getProperty("q13.gate.stage")).isEqualTo("accept");
        assertThat(((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).isStarted()).isTrue();
        Path root = moduleTarget().resolve("q13-shutdown").resolve(buildId());
        PrivateFiles.requirePrivate(root, true);
        try (var paths = Files.list(root)) {
            assertThat(paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()))
                    .isEqualTo(Set.of("intent.properties", "ready.properties", "outcome.properties", "records"));
        }
        Map<String, String> intent = read(root.resolve("intent.properties"));
        requireCurrentIntent(intent, buildId(), classSha(PausedSchedulerShutdownTestConfiguration.class), classSha(getClass()));
        long pid = Long.parseLong(intent.get("pid"));
        assertThat(pid).isPositive().isNotEqualTo(ProcessHandle.current().pid());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("前一真实fork必须已自然退出").isFalse();
        Map<String, String> ready = read(root.resolve("ready.properties"));
        assertThat(ready.keySet()).isEqualTo(Set.of("runId", "pid", "instanceId", "intentSha256", "statusBeforeExit", "expectedPools"));
        assertThat(ready).containsEntry("runId", intent.get("runId")).containsEntry("pid", intent.get("pid"))
                .containsEntry("intentSha256", sha(Files.readAllBytes(root.resolve("intent.properties"))))
                .containsEntry("statusBeforeExit", "NOT_RUN").containsEntry("expectedPools", Integer.toString(PausedSchedulerShutdownTestConfiguration.SCHEDULER_NAMES.size()));
        ExpectedContext expected = new ExpectedContext("things-link-support", pid, ready.get("instanceId"));
        DirectEvidence.verify(root.resolve("records"), intent.get("runId"), Set.of(expected));
        Map<String, String> outcome = read(root.resolve("outcome.properties"));
        assertThat(outcome.keySet()).isEqualTo(Set.of("runId", "pid", "instanceId", "verification", "status", "cleanup",
                "ran", "cancelled", "contextActive", "handlerThread"));
        assertThat(outcome).containsEntry("runId", intent.get("runId")).containsEntry("pid", intent.get("pid"))
                .containsEntry("instanceId", ready.get("instanceId")).containsEntry("verification", "PASS").containsEntry("status", "PASS")
                .containsEntry("cleanup", "PASS").containsEntry("ran", "0").containsEntry("cancelled", "true")
                .containsEntry("contextActive", "false").containsEntry("handlerThread", "SpringApplicationShutdownHook");
        // 读取本次专用execution的原生XML，不把历史报告或自写PASS当作测试实际执行。
        Path reports = moduleTarget().resolve("surefire-q13-exit").resolve(buildId());
        requireSuccessfulSurefireReports(reports);
        write(root.resolve("acceptance.properties"), Map.of("build", buildId(), "runId", intent.get("runId"), "status", "PASS",
                "exitedPid", intent.get("pid"), "acceptorPid", Long.toString(ProcessHandle.current().pid())));
    }

    /** 实际父层报告判据的最小接缝；低层用落盘等价反例，不等待真实30秒强制退出。 */
    static void requireSuccessfulSurefireReports(Path reports) throws Exception {
        // Surefire 3.5.6的兜底halt(0)可与绿色XML并存；只接纳本轮两个正常原生报告。
        // 不读取dump正文猜严重度，也不扫描其他会话：任何额外文件/目录或未知形状都拒绝。
        String fixture = SchedulingShutdownExitFixture.class.getName();
        if (!Files.isDirectory(reports, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(reports)) {
            throw new IllegalStateException("Q13_GATE_SUREFIRE_ARTIFACTS");
        }
        try (var paths = Files.list(reports)) {
            List<Path> files = paths.toList();
            if (!files.stream().map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("TEST-" + fixture + ".xml", fixture + ".txt"))
                    || files.stream().anyMatch(path -> Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalStateException("Q13_GATE_SUREFIRE_ARTIFACTS");
            }
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var suite = factory.newDocumentBuilder().parse(reports.resolve("TEST-" + SchedulingShutdownExitFixture.class.getName() + ".xml").toFile()).getDocumentElement();
        assertThat(suite.getAttribute("tests")).isEqualTo("1");
        for (String key : List.of("errors", "failures", "skipped")) assertThat(suite.getAttribute(key)).isEqualTo("0");
        assertThat(suite.getElementsByTagName("testcase").item(0).getAttributes().getNamedItem("name").getNodeValue())
                .isEqualTo("leaveContextForNaturalForkExit");
    }

    /** 供普通最低层验证陈旧run、缺字段、零预期和源码错配，验收仍复用同一实际函数。 */
    static void requireCurrentIntent(Map<String, String> intent, String build, String hook, String fixture) {
        assertThat(intent.keySet()).isEqualTo(Set.of("build", "runId", "module", "pid", "expectedContexts", "fault", "hookSha256", "fixtureSha256"));
        assertThat(intent).containsEntry("build", build).containsEntry("module", "things-link-support")
                .containsEntry("expectedContexts", "1").containsEntry("hookSha256", hook).containsEntry("fixtureSha256", fixture);
        assertThat(UUID.fromString(intent.get("runId")).toString()).isEqualTo(intent.get("runId"));
    }

    /** 同一Maven会话值传入两个execution；缺失或表达式未求值直接失败，不读取任意旧目录。 */
    private static String buildId() {
        String value = System.getProperty(BUILD);
        assertThat(value).matches("m[0-9]{10,20}");
        return value;
    }

    /** Maven实际模块basedir，不采用调用者任意指定的外部共享目录。 */
    private static Path moduleTarget() { return Path.of(System.getProperty("basedir")).toAbsolutePath().resolve("target"); }

    /** 只认本例真实已登记线程的beforeExecute，不能靠固定sleep替代任务已出队事实。 */
    private static boolean beforeExecute(List<Thread> threads) {
        return threads.stream().anyMatch(thread -> Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals("org.springframework.scheduling.concurrent.ExecutorLifecycleDelegate") && frame.getMethodName().equals("beforeExecute")));
    }

    /** 小型安全字段原子写出；重复目标/不完整临时文件都不能当作本轮成功。 */
    private static void write(Path target, Map<String, String> fields) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Q13_GATE_DUPLICATE_RECORD");
        StringBuilder text = new StringBuilder();
        new TreeMap<>(fields).forEach((key, value) -> {
            if (!key.matches("[A-Za-z0-9]+") || value == null || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Q13_GATE_FIELD");
            text.append(key).append('=').append(value).append('\n');
        });
        Path temporary = PrivateFiles.createTempFile(target.getParent(), ".publishing-", ".part");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(text.toString());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 不接受Properties的重复覆盖，也不跟随符号链接或读入大载荷。 */
    private static Map<String, String> read(Path path) throws IOException {
        PrivateFiles.requirePrivate(path, false);
        if (Files.size(path) > 65_536) throw new IOException("Q13_GATE_RECORD_SIZE");
        Map<String, String> fields = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int at = line.indexOf('=');
            if (at <= 0 || fields.putIfAbsent(line.substring(0, at), line.substring(at + 1)) != null) throw new IOException("Q13_GATE_RECORD_LINE");
        }
        return fields;
    }

    /** 绑定实际ClassLoader原文而非仅声明依赖版本。 */
    private static String classSha(Class<?> type) throws Exception {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (bytes == null) throw new IOException("Q13_GATE_CLASS_MISSING");
            return sha(bytes.readAllBytes());
        }
    }

    /** 安全元数据摘要，不输出JVM环境或配置原值。 */
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    /** 不启用自动配置/业务扫描，实际Boot/Spring只拥有全部受管池和test-only监听。 */
    @Configuration(proxyBeanMethods = false)
    @Import({SchedulingIsolationConfiguration.class, PausedSchedulerShutdownTestConfiguration.class})
    static class MemoryBootConfiguration {
        /** 原Metrics保留，不覆盖已有任务装饰器。 */
        @Bean SimpleMeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        /** 仅为实际工厂提供内存指标依赖。 */
        @Bean SchedulerIsolationMetrics metrics(SimpleMeterRegistry registry) { return new SchedulerIsolationMetrics(registry); }
    }
}
