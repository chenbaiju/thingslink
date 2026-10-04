package com.things.link.support.scheduling;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.things.link.support.notification.mail.MailExecutorConfiguration;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.ShutdownMonitor;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.DirectEvidence;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.ExpectedContext;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.CloseResult;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.PoolResult;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration.PrivateFiles;
import com.things.link.testing.AbstractIntegrationTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.cache.DefaultContextCache;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 后台调度器隔离测试，证明慢 Outbox 不会占用命令生命周期或任务扫描线程。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(5)
class SchedulingIsolationConfigurationTests {

    /** 首个非预期失败后，其余预登记用例只记 NOT_RUN，不通过重复执行换绿。 */
    @RegisterExtension
    static final FirstFailureStop FIRST_FAILURE = new FirstFailureStop();

    /** 未指定归档目录的普通测试只清理自己通过createTempDirectory创建的目录。 */
    private static final List<Path> TEMPORARY_EVIDENCE = new ArrayList<>();

    /** Outbox 线程阻塞时 command/task/export 三个独立池仍须立即执行。 */
    @Test
    @Order(1)
    void blockedOutboxDoesNotStarveCommandOrTaskSchedulers() throws Exception {
        SchedulingIsolationConfiguration configuration = new SchedulingIsolationConfiguration(
                new SchedulerIsolationMetrics(new SimpleMeterRegistry()), 1);
        ThreadPoolTaskScheduler outbox = configuration.outboxTriggerScheduler();
        ThreadPoolTaskScheduler command = configuration.commandLifecycleScheduler();
        ThreadPoolTaskScheduler task = configuration.taskTriggerScheduler();
        ThreadPoolTaskScheduler export = configuration.exportLifecycleScheduler();
        ThreadPoolTaskScheduler ota = configuration.otaUploadScheduler();
        List<ThreadPoolTaskScheduler> schedulers = List.of(outbox, command, task, export, ota);
        schedulers.forEach(ThreadPoolTaskScheduler::initialize);
        CountDownLatch outboxStarted = new CountDownLatch(1);
        CountDownLatch releaseOutbox = new CountDownLatch(1);
        CountDownLatch independentTasks = new CountDownLatch(4);
        try {
            outbox.execute(() -> {
                outboxStarted.countDown();
                await(releaseOutbox);
            });
            assertThat(outboxStarted.await(2, TimeUnit.SECONDS)).isTrue();

            command.execute(independentTasks::countDown);
            task.execute(independentTasks::countDown);
            export.execute(independentTasks::countDown);
            ota.execute(independentTasks::countDown);

            assertThat(independentTasks.await(2, TimeUnit.SECONDS))
                    .as("慢 broker 所在 Outbox 池不得饿死命令超时、任务扫描或项目导出")
                    .isTrue();
        } finally {
            releaseOutbox.countDown();
            schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
        }
    }

    /** 使用真实 cache 的使用登记和切换暂停；已出队未知任务不依赖 SABPP 的穷举集合。 */
    @Test
    @Order(2)
    void cacheSwitchPauseThenCloseCancelsUnknownDequeuedTask() throws Exception {
        try (MemoryContext first = new MemoryContext("cache-first", 1_000, true);
                MemoryContext second = new MemoryContext("cache-second", 1_000, false)) {
            DefaultContextCache cache = new DefaultContextCache(4);
            MergedContextConfiguration firstKey = cacheKey(CacheFirst.class);
            MergedContextConfiguration secondKey = cacheKey(CacheSecond.class);
            cache.put(firstKey, ignored -> first.context);
            cache.registerContextUsage(firstKey, CacheFirst.class);
            cache.unregisterContextUsage(firstKey, CacheFirst.class);
            cache.put(secondKey, ignored -> second.context);
            assertThat(first.context.isRunning()).isFalse();
            ScheduledAnnotationBeanPostProcessor scheduled = first.context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(scheduled.getScheduledTasks()).hasSize(1);
            AtomicInteger ran = new AtomicInteger();
            ScheduledFuture<?> unknown = first.nativeSchedule("outboxTriggerScheduler", ran::incrementAndGet);
            first.awaitBeforeExecute("outboxTriggerScheduler");
            cache.remove(firstKey, HierarchyMode.CURRENT_LEVEL);
            first.monitor.assertSuccessful();
            assertThat(unknown.isCancelled()).isTrue();
            assertThat(ran).hasValue(0);
            assertThat(first.scheduler("outboxTriggerScheduler").getScheduledThreadPoolExecutor().isTerminated()).isTrue();
            assertThat(second.monitor.result().status()).isEqualTo("NOT_RUN");
            assertThat(second.context.isRunning()).isTrue();
            cache.remove(secondKey, HierarchyMode.CURRENT_LEVEL);
        }
    }

    /** stop 尚可接收但不准入新任务；STOP 才拒新，已准入任务必须先自然完成且不被中断。 */
    @Test
    @Order(3)
    void concurrentSubmissionIsCancelledAfterAdmittedTaskDrains() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger completed = new AtomicInteger();
        try (MemoryContext fixture = new MemoryContext("concurrent", 1_000, false)) {
            fixture.nativeSchedule("notificationLifecycleScheduler", () -> {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("未释放本例在途任务");
                    completed.incrementAndGet();
                } catch (InterruptedException error) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            FutureTask<Void> closing = new FutureTask<>(() -> { fixture.context.close(); return null; });
            Thread closer = fixture.startOwnedThread("q13-concurrent-close", closing);
            try {
                awaitCondition(() -> fixture.hasEvent("notificationLifecycleScheduler", "PAUSED_ADMISSION"));
                assertThat(fixture.scheduler("notificationLifecycleScheduler").getScheduledThreadPoolExecutor().isShutdown()).isFalse();
                AtomicInteger unexpected = new AtomicInteger();
                ScheduledFuture<?> accepted = fixture.nativeSchedule("notificationLifecycleScheduler", unexpected::incrementAndGet);
                fixture.awaitBeforeExecute("notificationLifecycleScheduler");
                assertThat(accepted.isCancelled()).isFalse();
                assertThat(unexpected).hasValue(0);
                assertThat(interrupted).isFalse();
                release.countDown();
                closing.get(2, TimeUnit.SECONDS);
                fixture.monitor.assertSuccessful();
                assertThat(completed).hasValue(1);
                assertThat(interrupted).isFalse();
                assertThat(accepted.isCancelled()).isTrue();
                assertThat(unexpected).hasValue(0);
                assertThatThrownBy(() -> fixture.scheduler("notificationLifecycleScheduler").schedule(() -> { }, Instant.now()))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(fixture.phaseIndex("notificationLifecycleScheduler", "PAUSED_ADMISSION"))
                        .isLessThan(fixture.phaseIndex("notificationLifecycleScheduler", "DRAINED_CALLBACK"));
                assertThat(fixture.phaseIndex("notificationLifecycleScheduler", "DRAINED_CALLBACK"))
                        .isLessThan(fixture.phaseIndex("notificationLifecycleScheduler", "NATIVE_STOP_REJECTS_SUBMISSION"));
            } finally {
                release.countDown();
                closer.join(2_000);
            }
        } finally {
            release.countDown();
        }
    }

    /** 精确处理八个原工厂实例，另一 context 的 mail 与普通 scheduler 均不被该 hook 触碰。 */
    @Test
    @Order(4)
    void allOwnedSchedulersCloseWithoutTouchingOtherContextOrMail() throws Exception {
        CountDownLatch mailEntered = new CountDownLatch(1);
        CountDownLatch releaseMail = new CountDownLatch(1);
        AtomicBoolean mailInterrupted = new AtomicBoolean();
        AtomicInteger mailCompleted = new AtomicInteger();
        try (MemoryContext closing = new MemoryContext("owned-pools", 1_000, false, true);
                MemoryContext other = new MemoryContext("other-context", 1_000, false)) {
            ThreadPoolTaskExecutor mail = closing.context.getBean("mailTaskExecutor", ThreadPoolTaskExecutor.class);
            ThreadFactory mailFactory = mail.getThreadPoolExecutor().getThreadFactory();
            mail.getThreadPoolExecutor().setThreadFactory(runnable -> {
                Thread thread = mailFactory.newThread(runnable);
                closing.ownedThreads.add(thread);
                return thread;
            });
            Thread closer = null;
            try {
                mail.execute(() -> {
                    mailEntered.countDown();
                    try {
                        if (!releaseMail.await(3, TimeUnit.SECONDS)) throw new AssertionError("未释放邮件内存夹具");
                        mailCompleted.incrementAndGet();
                    } catch (InterruptedException error) {
                        mailInterrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(mailEntered.await(1, TimeUnit.SECONDS)).isTrue();
                AtomicInteger ran = new AtomicInteger();
                List<ScheduledFuture<?>> pending = new ArrayList<>();
                for (String name : PausedSchedulerShutdownTestConfiguration.SCHEDULER_NAMES) {
                    // 只暂停被测八池；mail 的独立在途完成合同不应被这个夹具暂停。
                    closing.scheduler(name).stop();
                    pending.add(closing.nativeSchedule(name, ran::incrementAndGet));
                    closing.awaitBeforeExecute(name);
                }
                FutureTask<Void> closeAction = new FutureTask<>(() -> { closing.context.close(); return null; });
                closer = closing.startOwnedThread("q13-owned-pool-close", closeAction);
                awaitCondition(() -> closing.monitor.result().status().equals("PASS"));
                closing.monitor.assertSuccessful();
                assertThat(closing.monitor.result().pools()).hasSize(9).allSatisfy(pool -> {
                    assertThat(pool.terminated()).isTrue();
                    assertThat(pool.beanIdentity()).isEqualTo(System.identityHashCode(closing.scheduler(pool.scheduler())));
                });
                assertThat(closing.monitor.result().pools()).extracting(PoolResult::scheduler)
                        .containsExactlyInAnyOrder("outboxTriggerScheduler", "commandLifecycleScheduler",
                                "modbusLifecycleScheduler", "notificationLifecycleScheduler", "taskTriggerScheduler",
                                "exportLifecycleScheduler", "otaUploadScheduler", "maintenanceScheduler", "automationLifecycleScheduler");
                assertThat(pending).allMatch(Future::isCancelled);
                assertThat(ran).hasValue(0);
                assertThat(other.monitor.result().status()).isEqualTo("NOT_RUN");
                assertThat(other.scheduler("maintenanceScheduler").getScheduledThreadPoolExecutor().isShutdown()).isFalse();
                assertThat(other.scheduler("otaUploadScheduler").getScheduledThreadPoolExecutor().isShutdown()).isFalse();
                assertThat(closeAction.isDone()).as("Spring 必须继续等待独立邮件在途任务").isFalse();
                assertThat(mailCompleted).hasValue(0);
                assertThat(mailInterrupted).isFalse();
                releaseMail.countDown();
                closeAction.get(2, TimeUnit.SECONDS);
                assertThat(mailCompleted).hasValue(1);
                assertThat(mailInterrupted).isFalse();
            } finally {
                releaseMail.countDown();
                if (closer != null) closer.join(2_000);
            }
        }
    }

    /** 子 context 无本地八池时不实例化或回退到父实例，父关闭监听也必须忽略子事件。 */
    @Test
    @Order(5)
    void childContextCannotAcquireParentSchedulerOwnership() {
        try (MemoryContext parent = new MemoryContext("parent", 1_000, false);
                AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.setId("q13-child");
            child.setParent(parent.context);
            child.register(PausedSchedulerShutdownTestConfiguration.class);
            child.refresh();
            ShutdownMonitor childMonitor = child.getBean(ShutdownMonitor.class);
            child.close();
            childMonitor.assertSuccessful();
            assertThat(childMonitor.result().pools()).allMatch(pool -> pool.phase().equals("NOT_CREATED_LOCAL"));
            assertThat(parent.monitor.result().status()).isEqualTo("NOT_RUN");
            assertThat(parent.scheduler("outboxTriggerScheduler").getScheduledThreadPoolExecutor().isShutdown()).isFalse();
        }
    }

    /** 预期负例：未排空时不强杀，超时首因冻结；稍后自然完成与 STOP 清理不得把 FAIL 改绿。 */
    @Test
    @Order(6)
    void timeoutRemainsFailureAfterLateCallbackAndCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (MemoryContext fixture = new MemoryContext("late-callback", 1_000, false)) {
            fixture.nativeSchedule("outboxTriggerScheduler", () -> {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("未释放超时负例任务");
                } catch (InterruptedException error) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            FutureTask<Void> closing = new FutureTask<>(() -> { fixture.context.close(); return null; });
            Thread closer = fixture.startOwnedThread("q13-timeout-close", closing);
            try {
                awaitCondition(() -> fixture.monitor.result().status().equals("FAIL"), 1_700);
                var frozen = fixture.monitor.result();
                assertThat(frozen.firstFailure()).isEqualTo("outboxTriggerScheduler:DRAIN_TIMEOUT");
                assertThat(fixture.hasEvent("outboxTriggerScheduler", "NATIVE_STOP_REJECTS_SUBMISSION")).isFalse();
                assertThat(interrupted).isFalse();
                assertThatThrownBy(fixture.monitor::assertSuccessful).isInstanceOf(IllegalStateException.class);
                release.countDown();
                closing.get(2, TimeUnit.SECONDS);
                assertThat(fixture.monitor.result()).isSameAs(frozen);
                assertThat(fixture.monitor.result().status()).isEqualTo("FAIL");
                assertThat(fixture.hasEvent("outboxTriggerScheduler", "NATIVE_STOP_REJECTS_SUBMISSION")).isTrue();
                assertThat(interrupted).isFalse();
                assertThat(fixture.scheduler("outboxTriggerScheduler").getScheduledThreadPoolExecutor().isTerminated()).isTrue();
            } finally {
                release.countDown();
                closer.join(2_000);
            }
        } finally {
            release.countDown();
        }
    }

    /** 未知白名单类型必须显式 FAIL，Spring close 正常返回和重复事件不能掩盖它。 */
    @Test
    @Order(7)
    void invalidLocalTargetIsObservableFailureNotSuccessfulClose() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setId("q13-invalid-target");
            context.register(PausedSchedulerShutdownTestConfiguration.class);
            context.registerBean("outboxTriggerScheduler", String.class, () -> "not-a-scheduler");
            context.refresh();
            ShutdownMonitor monitor = context.getBean(ShutdownMonitor.class);
            assertThat(monitor.supportsAsyncExecution()).isFalse();
            context.close();
            var failed = monitor.result();
            assertThat(failed.firstFailure()).isEqualTo("outboxTriggerScheduler:TARGET_TYPE");
            assertThatThrownBy(monitor::assertSuccessful).isInstanceOf(IllegalStateException.class);
            monitor.onApplicationEvent(new ContextClosedEvent(context));
            assertThat(monitor.result()).isSameAs(failed);
        }
    }

    /** 原 Metrics timer/gauge 保留，默认 hook 预算 30 秒不改工厂等待参数。 */
    @Test
    @Order(8)
    void originalMetricsDecoratorAndDefaultDeadlineRemainEffective() throws Exception {
        try (MemoryContext fixture = new MemoryContext("metrics", 30_000, false)) {
            AtomicInteger completed = new AtomicInteger();
            fixture.nativeSchedule("outboxTriggerScheduler", completed::incrementAndGet).get(1, TimeUnit.SECONDS);
            awaitCondition(() -> fixture.registry.find(SchedulerIsolationMetrics.DURATION).tag("scheduler", "outbox-trigger").timer() != null);
            fixture.context.close();
            fixture.monitor.assertSuccessful();
            assertThat(fixture.monitor.result().deadlineMillis()).isEqualTo(30_000);
            assertThat(completed).hasValue(1);
            assertThat(fixture.registry.get(SchedulerIsolationMetrics.DURATION).tag("scheduler", "outbox-trigger").timer().count()).isEqualTo(1);
            assertThat(fixture.registry.find(SchedulerIsolationMetrics.ACTIVE).tag("scheduler", "outbox-trigger").gauge()).isNotNull();
        }
    }

    /** 等价旧错误路径先被严格覆盖判据拒绝；INFO失效不应再影响直接文件出口。 */
    @Test
    @Order(9)
    void logOnlyEquivalentCannotSatisfyDirectEvidenceCoverage() throws Exception {
        Path root = evidenceCase("old-log-only");
        String run = UUID.randomUUID().toString();
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ShutdownMonitor.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            try (MemoryContext oldPath = new MemoryContext("old-log-only", 1_000, false)) {
                oldPath.context.close();
                oldPath.monitor.assertSuccessful();
                assertThat(oldPath.monitor.evidenceIdentity()).isNull();
                var expectedButUnrecorded = new ExpectedContext("things-link-support", ProcessHandle.current().pid(), UUID.randomUUID().toString());
                assertThatThrownBy(() -> DirectEvidence.verify(root, run, Set.of(expectedButUnrecorded)))
                        .hasMessage("Q13_EVIDENCE_REJECTED_COVERAGE_MISMATCH");
                System.out.println("Q13_EQUIVALENT_OLD_PATH_REJECTED: close PASS without direct records is not evidence PASS");
            }
            try (MemoryContext fixed = new MemoryContext("direct-without-info", 1_000, false, false, root, run)) {
                fixed.context.close();
                fixed.monitor.assertSuccessful();
                DirectEvidence.verify(root, run, Set.of(fixed.monitor.evidenceIdentity()));
            }
            // class literal/annotation读取不初始化基类静态容器；独立验证实际导入，而非从现有文件反推接线完整。
            assertThat(AbstractIntegrationTest.class.getAnnotation(Import.class).value())
                    .contains(PausedSchedulerShutdownTestConfiguration.class);
        } finally {
            logger.setLevel(original);
        }
    }

    /** 两个同显示名context仍须分别登记和终结；漏一份或用零覆盖自证均应拒绝。 */
    @Test
    @Order(10)
    void duplicateDisplayNamesNeedDistinctInstancesAndEveryTerminal() throws Exception {
        Path root = evidenceCase("duplicate-display");
        String run = UUID.randomUUID().toString();
        try (MemoryContext first = new MemoryContext("same-display", 1_000, false, false, root, run);
                MemoryContext second = new MemoryContext("same-display", 1_000, false, false, root, run)) {
            ExpectedContext one = first.monitor.evidenceIdentity();
            ExpectedContext two = second.monitor.evidenceIdentity();
            assertThat(one.instanceId()).isNotEqualTo(two.instanceId());
            Set<ExpectedContext> expected = Set.of(one, two);
            first.context.close();
            assertThatThrownBy(() -> DirectEvidence.verify(root, run, expected)).hasMessage("Q13_EVIDENCE_REJECTED_RECORD_SET");
            second.context.close();
            DirectEvidence.verify(root, run, expected);
            assertThatThrownBy(() -> DirectEvidence.verify(root, run, Set.of())).hasMessage("Q13_EVIDENCE_REJECTED_EMPTY_EXPECTATION");
            assertThatThrownBy(() -> DirectEvidence.verify(root, run, Set.of(one))).hasMessage("Q13_EVIDENCE_REJECTED_COVERAGE_MISMATCH");
            assertThatThrownBy(() -> DirectEvidence.verify(root, UUID.randomUUID().toString(), expected)).hasMessage("Q13_EVIDENCE_REJECTED_IDENTITY");
            List<String> names;
            try (var files = Files.list(root)) {
                names = files.map(path -> {
                    try { return Files.readString(path.resolve("registration.properties")); }
                    catch (IOException error) { throw new AssertionError(error); }
                }).toList();
            }
            String digest = names.getFirst().lines().filter(line -> line.startsWith("displayNameSha256=")).findFirst().orElseThrow();
            assertThat(names.getLast()).contains(digest).doesNotContain("q13-same-display");
        }
    }

    /** 文件系统写失败不得被算法PASS或稍后恢复目录洗掉；登记失败则monitor不可创建。 */
    @Test
    @Order(11)
    void directWriteFailureIsNotRepairedByLaterCleanup() throws Exception {
        Path root = evidenceCase("write-failure");
        Path notDirectory = root.resolve("not-a-directory");
        Files.writeString(notDirectory, "owned fixture", StandardCharsets.UTF_8);
        String run = UUID.randomUUID().toString();
        assertThatThrownBy(() -> new DirectEvidence(notDirectory, run, "things-link-support", "canary-secret-display"))
                .hasMessage("Q13_EVIDENCE_REGISTRATION_FAILED");
        Path records = privateDirectory(root.resolve("records"));
        DirectEvidence evidence = new DirectEvidence(records, run, "things-link-support", "canary-secret-display");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ShutdownMonitor monitor = new ShutdownMonitor(context, Duration.ofSeconds(1), evidence);
            context.addApplicationListener(monitor);
            context.refresh();
            Path original = evidence.directory();
            Path saved = original.resolveSibling(original.getFileName() + ".saved");
            Files.move(original, saved);
            Files.createFile(original);
            try {
                context.close();
                assertThat(monitor.result().status()).isEqualTo("PASS");
                assertThat(evidence.failure()).isEqualTo("TERMINAL_PUBLICATION_FAILED");
                assertThatThrownBy(monitor::assertSuccessful).hasMessage("Q13_EVIDENCE_TERMINAL_PUBLICATION_FAILED");
            } finally {
                Files.delete(original);
                Files.move(saved, original);
            }
            evidence.publish(monitor.result());
            assertThat(evidence.failure()).isEqualTo("TERMINAL_PUBLICATION_FAILED");
            assertThatThrownBy(() -> DirectEvidence.verify(records, run, Set.of(evidence.identity())))
                    .hasMessage("Q13_EVIDENCE_REJECTED_RECORD_SET");
            assertThat(Files.readString(original.resolve("registration.properties"))).doesNotContain("canary-secret-display");
        }
    }

    /** 真实临时文件已经写全后原子移动失败，必须留原件且不发布半份终态；重复发布不覆盖首份。 */
    @Test
    @Order(12)
    void atomicPublicationFailureAndDuplicateAreFailClosed() throws Exception {
        Path root = evidenceCase("atomic-failure");
        Path failedRoot = privateDirectory(root.resolve("failed"));
        String run = UUID.randomUUID().toString();
        AtomicBoolean completeTemporaryObserved = new AtomicBoolean();
        DirectEvidence failed = new DirectEvidence(failedRoot, run, "things-link-support", "atomic", (temporary, destination) -> {
            if (destination.getFileName().toString().equals("terminal.properties")) {
                completeTemporaryObserved.set(Files.readString(temporary).contains("status=PASS\n"));
                throw new AtomicMoveNotSupportedException("owned-temporary", "owned-terminal", "controlled failure");
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        });
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ShutdownMonitor monitor = new ShutdownMonitor(context, Duration.ofSeconds(1), failed);
            context.addApplicationListener(monitor);
            context.refresh();
            context.close();
            assertThat(completeTemporaryObserved).isTrue();
            assertThat(Files.exists(failed.directory().resolve("terminal.properties"))).isFalse();
            assertThat(failed.failure()).isEqualTo("TERMINAL_PUBLICATION_FAILED");
            assertThatThrownBy(() -> DirectEvidence.verify(failedRoot, run, Set.of(failed.identity())))
                    .hasMessage("Q13_EVIDENCE_REJECTED_RECORD_SET");
            failed.publish(monitor.result());
            assertThat(failed.failure()).isEqualTo("TERMINAL_PUBLICATION_FAILED");
        }
        Path duplicateRoot = privateDirectory(root.resolve("duplicate"));
        DirectEvidence duplicate = new DirectEvidence(duplicateRoot, run, "things-link-support", "duplicate");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ShutdownMonitor monitor = new ShutdownMonitor(context, Duration.ofSeconds(1), duplicate);
            context.addApplicationListener(monitor);
            context.refresh();
            context.close();
            String original = Files.readString(duplicate.directory().resolve("terminal.properties"));
            duplicate.publish(monitor.result());
            assertThat(duplicate.failure()).isEqualTo("DUPLICATE_TERMINAL_ATTEMPT");
            assertThat(Files.readString(duplicate.directory().resolve("terminal.properties"))).isEqualTo(original);
            assertThatThrownBy(() -> DirectEvidence.verify(duplicateRoot, run, Set.of(duplicate.identity())))
                    .hasMessage("Q13_EVIDENCE_REJECTED_RECORD_SET");
        }
    }

    /** 仅测试判读器的受控错误记录，不把伪造资源状态冒称真实关闭事实。 */
    @Test
    @Order(13)
    void nonTerminatedOrMismatchedTerminalCannotPass() throws Exception {
        Path root = evidenceCase("invalid-records");
        String run = UUID.randomUUID().toString();
        Path busyRoot = privateDirectory(root.resolve("not-terminated"));
        DirectEvidence busy = new DirectEvidence(busyRoot, run, "things-link-support", "negative-record");
        List<PoolResult> falsePools = PausedSchedulerShutdownTestConfiguration.SCHEDULER_NAMES.stream()
                .map(name -> new PoolResult(name, 1, "NATIVE_STOP", true, false, 0, 0, 0)).toList();
        busy.publish(new CloseResult("negative-record", "PASS", null, 1, 1_000, falsePools, List.of()));
        assertThatThrownBy(() -> DirectEvidence.verify(busyRoot, run, Set.of(busy.identity())))
                .hasMessage("Q13_EVIDENCE_REJECTED_POOL_NOT_TERMINATED");
        Path mismatchRoot = privateDirectory(root.resolve("mismatch"));
        try (MemoryContext fixture = new MemoryContext("mismatch", 1_000, false, false, mismatchRoot, run)) {
            fixture.context.close();
            ExpectedContext identity = fixture.monitor.evidenceIdentity();
            Path directory = mismatchRoot.resolve(identity.module() + "--" + identity.pid() + "--" + identity.instanceId());
            Path terminal = directory.resolve("terminal.properties");
            String valid = Files.readString(terminal);
            Files.writeString(terminal, valid.replace("instanceId=" + identity.instanceId(), "instanceId=" + UUID.randomUUID()));
            assertThatThrownBy(() -> DirectEvidence.verify(mismatchRoot, run, Set.of(identity)))
                    .hasMessage("Q13_EVIDENCE_REJECTED_TERMINAL_IDENTITY");
            Files.writeString(terminal, valid + "unexpected=controlled-negative\n");
            assertThatThrownBy(() -> DirectEvidence.verify(mismatchRoot, run, Set.of(identity)))
                    .hasMessage("Q13_EVIDENCE_REJECTED_TERMINAL_FIELDS");
        }
    }

    /** 真子JVM在日志系统/标准流已停后执行正常退出；父进程只以独立登记和原子文件裁决。 */
    @Test
    @Order(14)
    void realChildJvmShutdownPublishesWithoutLogOrStdout() throws Exception {
        Path root = evidenceCase("real-child");
        Path records = privateDirectory(root.resolve("records"));
        String run = UUID.randomUUID().toString();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ExitEvidenceChild.class.getName(), records.toString(), run,
                root.resolve("ready.properties").toString(), root.resolve("outcome.properties").toString())
                .directory(Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).toFile())
                .redirectOutput(root.resolve("child.stdout").toFile()).redirectError(root.resolve("child.stderr").toFile()).start();
        try {
            assertThat(process.waitFor(3, TimeUnit.SECONDS)).as("独立子JVM不得依赖强杀退出").isTrue();
            assertThat(process.exitValue()).isZero();
            Map<String, String> ready = simpleFields(root.resolve("ready.properties"));
            assertThat(Long.parseLong(ready.get("pid"))).isEqualTo(process.pid());
            assertThat(ready.get("runId")).isEqualTo(run);
            assertThat(ready.get("module")).isEqualTo(Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).getFileName().toString());
            ExpectedContext identity = new ExpectedContext(ready.get("module"), process.pid(), ready.get("instanceId"));
            DirectEvidence.verify(records, run, Set.of(identity));
            assertThat(ready.get("hookClassSha256")).isEqualTo(loadedHookSha256());
            assertThat(simpleFields(root.resolve("outcome.properties")))
                    .containsEntry("status", "PASS").containsEntry("ran", "0").containsEntry("cancelled", "true")
                    .containsEntry("allTerminated", "true").containsEntry("pid", Long.toString(process.pid()));
            assertThat(Files.readString(root.resolve("child.stdout"))).doesNotContain("Q13_SCHEDULER_CLOSE CloseResult");
            assertThat(Files.readString(root.resolve("child.stderr"))).doesNotContain("Q13_SCHEDULER_CLOSE CloseResult");
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly().waitFor(500, TimeUnit.MILLISECONDS);
            }
            assertThat(process.isAlive()).isFalse();
        }
    }

    /** ACL分支使用NIO原子创建属性；能力未知时不能静默退回普通临时目录。 */
    @Test
    @Order(15)
    void nonPosixPermissionsAreExplicitAndFailClosed() throws Exception {
        UserPrincipal owner = () -> "q13-owner";
        var attribute = PrivateFiles.initialAttribute(true, false, true, owner);
        assertThat(attribute.name()).isEqualTo("acl:acl");
        @SuppressWarnings("unchecked") List<AclEntry> entries = (List<AclEntry>) attribute.value();
        PrivateFiles.requireOwnerOnlyAcl(owner, entries);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().permissions()).isEqualTo(EnumSet.allOf(AclEntryPermission.class));
        assertThat(PrivateFiles.initialAttribute(false, false, true, owner).name()).isEqualTo("acl:acl");
        assertThatThrownBy(() -> PrivateFiles.initialAttribute(true, false, false, owner))
                .hasMessage("Q13_PRIVATE_PERMISSIONS_UNSUPPORTED");
        AclEntry foreign = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(() -> "other")
                .setPermissions(AclEntryPermission.READ_DATA).build();
        assertThatThrownBy(() -> PrivateFiles.requireOwnerOnlyAcl(owner, List.of(entries.getFirst(), foreign)))
                .hasMessage("Q13_PRIVATE_ACL");
        assertThatThrownBy(() -> PrivateFiles.requireOwnerOnlyAcl(owner, List.of(foreign))).hasMessage("Q13_PRIVATE_ACL");
        assertThatThrownBy(() -> PrivateFiles.requireOwnerOnlyAcl(owner, List.of())).hasMessage("Q13_PRIVATE_ACL");
        AclEntry inherited = AclEntry.newBuilder(entries.getFirst()).setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT).build();
        assertThatThrownBy(() -> PrivateFiles.requireOwnerOnlyAcl(owner, List.of(inherited))).hasMessage("Q13_PRIVATE_ACL");
        AclEntry incomplete = AclEntry.newBuilder(entries.getFirst()).setPermissions(AclEntryPermission.READ_DATA).build();
        assertThatThrownBy(() -> PrivateFiles.requireOwnerOnlyAcl(owner, List.of(incomplete))).hasMessage("Q13_PRIVATE_ACL");
    }

    /** 当前provider真实读回与复用fork日志均须成立，不能只验证权限策略字符串。 */
    @Test
    @Order(16)
    void privateFilesRoundTripAndReusableForkLoggingRemainIntact() throws Exception {
        Path root = evidenceCase("permission-roundtrip");
        Path file = PrivateFiles.createTempFile(root, "record-", ".properties");
        Files.writeString(file, "safe=metadata\n");
        PrivateFiles.requirePrivate(root, true);
        PrivateFiles.requirePrivate(file, false);
        assertThat(Files.readString(file)).isEqualTo("safe=metadata\n");
        assertThat(((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).isStarted()).isTrue();
        assertThatThrownBy(() -> PrivateFiles.createDirectory(root)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    /** 调用实际父层身份判据；陈旧构建、缺字段、零登记及异源不能靠旧PASS补足。 */
    @Test
    @Order(17)
    void permanentParentGateRejectsStaleOrIncompleteExpectation() {
        Map<String, String> intent = Map.of("build", "m1000000000000", "runId", UUID.randomUUID().toString(),
                "module", "things-link-support", "pid", "1", "expectedContexts", "1", "fault", "NONE",
                "hookSha256", "actual-hook", "fixtureSha256", "actual-fixture");
        SchedulingShutdownExitFixture.requireCurrentIntent(intent, "m1000000000000", "actual-hook", "actual-fixture");
        assertThatThrownBy(() -> SchedulingShutdownExitFixture.requireCurrentIntent(intent, "m1000000000001", "actual-hook", "actual-fixture"))
                .isInstanceOf(AssertionError.class);
        Map<String, String> wrong = new LinkedHashMap<>(intent);
        wrong.put("expectedContexts", "0");
        assertThatThrownBy(() -> SchedulingShutdownExitFixture.requireCurrentIntent(wrong, "m1000000000000", "actual-hook", "actual-fixture"))
                .isInstanceOf(AssertionError.class);
        wrong.remove("runId");
        assertThatThrownBy(() -> SchedulingShutdownExitFixture.requireCurrentIntent(wrong, "m1000000000000", "actual-hook", "actual-fixture"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> SchedulingShutdownExitFixture.requireCurrentIntent(intent, "m1000000000000", "different-hook", "actual-fixture"))
                .isInstanceOf(AssertionError.class);
    }

    /** 真实创建/读回函数的非POSIX接线；NIO能力/ACL view为夹具，不冒称本机运行了NTFS。 */
    @Test
    @Order(18)
    void aclProviderCreationAndReadbackCannotFallBackToLoosePermissions() throws Exception {
        Path directory = org.mockito.Mockito.mock(Path.class);
        Path parent = org.mockito.Mockito.mock(Path.class);
        var fileSystem = org.mockito.Mockito.mock(java.nio.file.FileSystem.class);
        var store = org.mockito.Mockito.mock(java.nio.file.FileStore.class);
        var lookup = org.mockito.Mockito.mock(java.nio.file.attribute.UserPrincipalLookupService.class);
        var view = org.mockito.Mockito.mock(java.nio.file.attribute.AclFileAttributeView.class);
        UserPrincipal owner = () -> "q13-owner";
        org.mockito.Mockito.when(directory.toAbsolutePath()).thenReturn(directory);
        org.mockito.Mockito.when(directory.getParent()).thenReturn(parent);
        org.mockito.Mockito.when(parent.getFileSystem()).thenReturn(fileSystem);
        org.mockito.Mockito.when(directory.getFileSystem()).thenReturn(fileSystem);
        org.mockito.Mockito.when(fileSystem.getUserPrincipalLookupService()).thenReturn(lookup);
        org.mockito.Mockito.when(lookup.lookupPrincipalByName(System.getProperty("user.name"))).thenReturn(owner);
        org.mockito.Mockito.when(store.supportsFileAttributeView("acl")).thenReturn(true);
        org.mockito.Mockito.when(view.getOwner()).thenReturn(owner);
        @SuppressWarnings("unchecked") List<AclEntry> expected = (List<AclEntry>) PrivateFiles.initialAttribute(true, false, true, owner).value();
        org.mockito.Mockito.when(view.getAcl()).thenReturn(expected);
        try (var files = org.mockito.Mockito.mockStatic(Files.class)) {
            files.when(() -> Files.getFileStore(parent)).thenReturn(store);
            files.when(() -> Files.getFileStore(directory)).thenReturn(store);
            files.when(() -> Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(true);
            files.when(() -> Files.getFileAttributeView(directory, java.nio.file.attribute.AclFileAttributeView.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(view);
            files.when(() -> Files.createDirectory(org.mockito.ArgumentMatchers.eq(directory),
                    org.mockito.ArgumentMatchers.<java.nio.file.attribute.FileAttribute<?>[]>any())).thenAnswer(invocation -> {
                        java.nio.file.attribute.FileAttribute<?> attribute = invocation.getArgument(1);
                        assertThat(attribute.name()).isEqualTo("acl:acl");
                        assertThat(attribute.value()).isEqualTo(expected);
                        return directory;
                    });
            assertThat(PrivateFiles.createDirectory(directory)).isSameAs(directory);
            org.mockito.Mockito.when(view.getAcl()).thenReturn(List.of());
            assertThatThrownBy(() -> PrivateFiles.requirePrivate(directory, true)).hasMessage("Q13_PRIVATE_ACL");
            org.mockito.Mockito.when(store.supportsFileAttributeView("acl")).thenReturn(false);
            assertThatThrownBy(() -> PrivateFiles.createDirectory(directory)).hasMessage("Q13_PRIVATE_PERMISSIONS_UNSUPPORTED");
            files.verify(() -> Files.createDirectory(org.mockito.ArgumentMatchers.eq(directory),
                    org.mockito.ArgumentMatchers.<java.nio.file.attribute.FileAttribute<?>[]>any()), org.mockito.Mockito.times(1));
        }
    }

    /** 真实报告路径形状的文件反例：绿色XML不能洗掉Surefire退出异常制品。 */
    @Test
    @Order(19)
    void currentSurefireAbnormalReportsRejectGreenXmlAndExitedPid() throws Exception {
        Path root = evidenceCase("surefire-abnormal-artifacts");
        String fixture = SchedulingShutdownExitFixture.class.getName();
        Path reports = privateDirectory(root.resolve("current-reports"));
        Files.writeString(reports.resolve("TEST-" + fixture + ".xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"leaveContextForNaturalForkExit\"/></testsuite>");
        Files.writeString(reports.resolve(fixture + ".txt"), "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n");
        Path historical = privateDirectory(root.resolve("other-session"));
        Files.writeString(historical.resolve("old-jvmRun1.dump"), "NOT_THIS_EXECUTION\n");
        SchedulingShutdownExitFixture.requireSuccessfulSurefireReports(reports);
        // 只模拟已确认的文件状态；不调用halt、不把此控制文本冒充历史原始dump。
        for (String name : List.of("2026-09-03T00-00-00_000-jvmRun1.dump",
                "2026-09-03T00-00-00_000-jvmRun1.dumpstream", "2026-09-03T00-00-00_000.dumpstream", "unknown-artifact.txt")) {
            Path abnormal = reports.resolve(name);
            Files.writeString(abnormal, "CONTROLLED_SUREFIRE_EXIT_ANOMALY\n");
            assertThatThrownBy(() -> SchedulingShutdownExitFixture.requireSuccessfulSurefireReports(reports))
                    .hasMessage("Q13_GATE_SUREFIRE_ARTIFACTS");
            Files.delete(abnormal);
        }
        SchedulingShutdownExitFixture.requireSuccessfulSurefireReports(reports);
        // 文件集合名字相同也不足：报告路径被目录替换、缺项或报告目录自身缺失均不能PASS。
        Path text = reports.resolve(fixture + ".txt");
        Files.delete(text);
        Files.createDirectory(text);
        assertThatThrownBy(() -> SchedulingShutdownExitFixture.requireSuccessfulSurefireReports(reports))
                .hasMessage("Q13_GATE_SUREFIRE_ARTIFACTS");
        Files.delete(text);
        assertThatThrownBy(() -> SchedulingShutdownExitFixture.requireSuccessfulSurefireReports(reports))
                .hasMessage("Q13_GATE_SUREFIRE_ARTIFACTS");
        assertThatThrownBy(() -> SchedulingShutdownExitFixture.requireSuccessfulSurefireReports(root.resolve("not-created")))
                .hasMessage("Q13_GATE_SUREFIRE_ARTIFACTS");
    }

    /** 两字段的最高本地优先级不得修改System属性或另一个context，且不需要启动任何业务资源。 */
    @Test
    @Order(20)
    void dedicatedFixtureEvidenceOverridesOnlyItsOwnEnvironment() throws Exception {
        String directoryKey = PausedSchedulerShutdownTestConfiguration.EVIDENCE_DIRECTORY_PROPERTY;
        String runKey = PausedSchedulerShutdownTestConfiguration.EVIDENCE_RUN_PROPERTY;
        String systemDirectory = System.getProperty(directoryKey);
        String systemRun = System.getProperty(runKey);
        Path records = evidenceCase("fixture-local-properties");
        String localRun = UUID.randomUUID().toString();
        Map<String, Object> global = Map.of(directoryKey, records.resolve("external").toString(),
                runKey, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        try (AnnotationConfigApplicationContext fixture = new AnnotationConfigApplicationContext();
                AnnotationConfigApplicationContext other = new AnnotationConfigApplicationContext()) {
            // 只替换两个尚未refresh的Environment中的同名源；没有System.setProperty或全局恢复窗口。
            fixture.getEnvironment().getPropertySources().replace("systemProperties", new MapPropertySource("systemProperties", global));
            other.getEnvironment().getPropertySources().replace("systemProperties", new MapPropertySource("systemProperties", global));
            SchedulingShutdownExitFixture.isolateFixtureEvidence(fixture, records, localRun);
            assertThat(fixture.getEnvironment().getProperty(directoryKey)).isEqualTo(records.toString());
            assertThat(fixture.getEnvironment().getProperty(runKey)).isEqualTo(localRun);
            assertThat(other.getEnvironment().getProperty(directoryKey)).isEqualTo(global.get(directoryKey));
            assertThat(other.getEnvironment().getProperty(runKey)).isEqualTo(global.get(runKey));
            assertThat(System.getProperty(directoryKey)).isEqualTo(systemDirectory);
            assertThat(System.getProperty(runKey)).isEqualTo(systemRun);
            try (var paths = Files.list(records)) { assertThat(paths.toList()).isEmpty(); }
        }
    }

    /** 仅本测试创建的目录；启用归档时保留在执行者提供的ignored私有目录。 */
    private static Path evidenceCase(String prefix) throws IOException {
        String archive = System.getenv("THINGS_LINK_Q13_LOW_LEVEL_DIR");
        Path parent = archive == null ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(archive);
        Path result = PrivateFiles.createTempDirectory(parent, "q13-" + prefix);
        if (archive == null) TEMPORARY_EVIDENCE.add(result);
        return result.toAbsolutePath();
    }

    /** @return 原子创建的本例私有目录；不沿用公共tmp权限 */
    private static Path privateDirectory(Path path) throws IOException {
        return PrivateFiles.createDirectory(path);
    }

    /** 只用于父子进程的安全固定字段，不读取环境或凭据。 */
    private static Map<String, String> simpleFields(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            int split = line.indexOf('=');
            if (split <= 0 || values.putIfAbsent(line.substring(0, split), line.substring(split + 1)) != null) {
                throw new AssertionError("父子进程证据字段重复或不完整");
            }
        }
        return values;
    }

    /** 绑定实际ClassLoader返回的字节，而非仅声明某个classpath字符串。 */
    private static String loadedHookSha256() throws Exception {
        try (var stream = PausedSchedulerShutdownTestConfiguration.class.getResourceAsStream("PausedSchedulerShutdownTestConfiguration.class")) {
            if (stream == null) throw new AssertionError("缺少实际hook类原文");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }

    /** 普通测试结束只删除本类创建的精确目录；归档路径和其他临时资源不动。 */
    @AfterAll
    static void removeOwnedTemporaryEvidence() throws IOException {
        for (Path root : TEMPORARY_EVIDENCE) removeOwnedDirectory(root);
    }

    /** 目标只能是本测试createTempDirectory返回的精确目录，不接受全机清理范围。 */
    private static void removeOwnedDirectory(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /** 正常System.exit启动真实JDK关闭hook；不调用halt，不启动容器或业务应用。 */
    public static final class ExitEvidenceChild {
        /** 固定父进程提供的四个安全参数，无任意反射入口或通用探针分派。 */
        public static void main(String[] args) throws Exception {
            Path records = Path.of(args[0]);
            String run = args[1];
            MemoryContext fixture = new MemoryContext("child-exit", 1_000, false, false, records, run);
            AtomicInteger ran = new AtomicInteger();
            fixture.scheduler("outboxTriggerScheduler").stop();
            ScheduledFuture<?> future = fixture.nativeSchedule("outboxTriggerScheduler", ran::incrementAndGet);
            fixture.awaitBeforeExecute("outboxTriggerScheduler");
            ExpectedContext identity = fixture.monitor.evidenceIdentity();
            Files.writeString(Path.of(args[2]), "runId=" + run + "\nmodule=" + identity.module() + "\npid=" + identity.pid()
                    + "\ninstanceId=" + identity.instanceId() + "\nhookClassSha256=" + loadedHookSha256() + "\n");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                fixture.context.close();
                try {
                    boolean terminated = fixture.schedulers.values().stream().allMatch(scheduler -> scheduler.getScheduledThreadPoolExecutor().isTerminated());
                    Files.writeString(Path.of(args[3]), "pid=" + ProcessHandle.current().pid() + "\nstatus=" + fixture.monitor.result().status()
                            + "\nran=" + ran.get() + "\ncancelled=" + future.isCancelled() + "\nallTerminated=" + terminated + "\n");
                } catch (IOException error) {
                    // 父进程必须拒绝缺失outcome；不能依赖已关闭标准流报错或把exit0当成功。
                }
            }, "q13-direct-exit"));
            ((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).stop();
            System.out.close();
            System.err.close();
            System.exit(0);
        }
    }

    /** 给真实 cache 不同的结构键；不调用会启动容器的集成测试 loader。 */
    private static MergedContextConfiguration cacheKey(Class<?> marker) {
        return new MergedContextConfiguration(marker, new String[0], new Class<?>[] {marker}, new String[0], new AnnotationConfigContextLoader());
    }

    /** 用有界事实等待代替固定 sleep，避免靠时序巧合接受候选。 */
    private static void awaitCondition(BooleanSupplier condition) {
        awaitCondition(condition, 1_000);
    }

    /** @param condition 必须成立的事实 @param millis 本用例局部预算，不修改被测关闭预算 */
    private static void awaitCondition(BooleanSupplier condition, long millis) {
        long limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && System.nanoTime() < limit && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(condition.getAsBoolean()).as("有界事实前置必须成立").isTrue();
    }

    /** 每例只装配真实工厂、指标和 test-only hook，不扫描业务组件或继承容器基类。 */
    private static final class MemoryContext implements AutoCloseable {
        /** 本例 context 身份。 */
        private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        /** 原工厂使用的真实内存指标注册表。 */
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        /** 原工厂对象按白名单保存，避免从已经关闭的 BeanFactory 查找。 */
        private final Map<String, ThreadPoolTaskScheduler> schedulers = new LinkedHashMap<>();
        /** 测试自身持有的任务只用于失败后的精确 finally，不参与候选实现。 */
        private final List<Future<?>> ownedFutures = new CopyOnWriteArrayList<>();
        /** 通过原 threadFactory 创建并精确登记的本例线程，不以全局名称猜归属。 */
        private final Map<String, List<Thread>> poolThreads = new LinkedHashMap<>();
        /** 本例关闭发布线程，同样必须有界回收。 */
        private final List<Thread> ownedThreads = new CopyOnWriteArrayList<>();
        /** 关闭之后仍保存结果引用供 JUnit 明确断言。 */
        private final ShutdownMonitor monitor;

        /** @param id 独占身份 @param deadlineMillis 1 秒负例或 30 秒默认 @param annotations 是否装配真实 SABPP */
        private MemoryContext(String id, long deadlineMillis, boolean annotations) {
            this(id, deadlineMillis, annotations, false);
        }

        /** @param withMail 是否同时装配原始 mail 配置，以验证独立在途完成语义 */
        private MemoryContext(String id, long deadlineMillis, boolean annotations, boolean withMail) {
            this(id, deadlineMillis, annotations, withMail, null, null);
        }

        /** 仅证据用例显式启用直接出口；原8项与普通运行保持未配置行为。 */
        private MemoryContext(String id, long deadlineMillis, boolean annotations, boolean withMail, Path evidenceRoot, String evidenceRun) {
            this.context.setId("q13-" + id);
            if (evidenceRoot != null) {
                this.context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("q13-direct-evidence", Map.of(
                        PausedSchedulerShutdownTestConfiguration.EVIDENCE_DIRECTORY_PROPERTY, evidenceRoot.toString(),
                        PausedSchedulerShutdownTestConfiguration.EVIDENCE_RUN_PROPERTY, evidenceRun)));
            }
            if (deadlineMillis != PausedSchedulerShutdownTestConfiguration.DEFAULT_DEADLINE_MILLIS) {
                this.context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("q13-local",
                        Map.of(PausedSchedulerShutdownTestConfiguration.DEADLINE_PROPERTY, deadlineMillis)));
            }
            SchedulingIsolationConfiguration factory = new SchedulingIsolationConfiguration(new SchedulerIsolationMetrics(this.registry), 1);
            this.schedulers.put("outboxTriggerScheduler", factory.outboxTriggerScheduler());
            this.schedulers.put("commandLifecycleScheduler", factory.commandLifecycleScheduler());
            this.schedulers.put("modbusLifecycleScheduler", factory.modbusLifecycleScheduler());
            this.schedulers.put("notificationLifecycleScheduler", factory.notificationLifecycleScheduler());
            this.schedulers.put("taskTriggerScheduler", factory.taskTriggerScheduler());
            this.schedulers.put("automationLifecycleScheduler", factory.automationLifecycleScheduler());
            this.schedulers.put("exportLifecycleScheduler", factory.exportLifecycleScheduler());
            this.schedulers.put("otaUploadScheduler", factory.otaUploadScheduler());
            this.schedulers.put("maintenanceScheduler", factory.maintenanceScheduler());
            this.schedulers.forEach((name, scheduler) -> {
                List<Thread> threads = new CopyOnWriteArrayList<>();
                this.poolThreads.put(name, threads);
                // 原工厂默认使用 scheduler 自身作为 threadFactory；仅登记其创建结果，保留全部线程参数。
                ThreadFactory original = scheduler;
                scheduler.setThreadFactory(runnable -> {
                    Thread thread = original.newThread(runnable);
                    threads.add(thread);
                    return thread;
                });
                this.context.registerBean(name, ThreadPoolTaskScheduler.class, () -> scheduler);
            });
            this.context.register(PausedSchedulerShutdownTestConfiguration.class);
            if (annotations) this.context.register(AnnotationFixture.class);
            if (withMail) this.context.register(MailExecutorConfiguration.class);
            this.context.refresh();
            this.monitor = this.context.getBean(ShutdownMonitor.class);
        }

        /** @return 原始工厂实例，不做替换或代理 */
        private ThreadPoolTaskScheduler scheduler(String name) {
            return this.schedulers.get(name);
        }

        /** 直接绕过 SABPP 提交到实际 native executor，专门挑战“登记集合已完整”的错误假设。 */
        private ScheduledFuture<?> nativeSchedule(String name, Runnable runnable) {
            ScheduledFuture<?> future = scheduler(name).getScheduledThreadPoolExecutor().schedule(runnable, 0, TimeUnit.MILLISECONDS);
            this.ownedFutures.add(future);
            return future;
        }

        /** 用精确本例线程的真实 Spring 栈确认已出队但尚未执行。 */
        private void awaitBeforeExecute(String name) {
            awaitCondition(() -> this.poolThreads.get(name).stream().anyMatch(thread -> {
                StackTraceElement[] stack = thread.getStackTrace();
                return Arrays.stream(stack).anyMatch(frame -> frame.getClassName().equals("org.springframework.scheduling.concurrent.ExecutorLifecycleDelegate")
                        && frame.getMethodName().equals("beforeExecute"))
                        && Arrays.stream(stack).anyMatch(frame -> frame.getMethodName().equals("await"));
            }));
        }

        /** @return 当前目标是否已到达精确阶段 */
        private boolean hasEvent(String name, String phase) {
            return this.monitor.events().stream().anyMatch(event -> event.scheduler().equals(name) && event.phase().equals(phase));
        }

        /** 事件先后由实现实际记录，而不是以测试线程 sleep 推断。 */
        private int phaseIndex(String name, String phase) {
            var events = this.monitor.events();
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).scheduler().equals(name) && events.get(i).phase().equals(phase)) return i;
            }
            throw new AssertionError("缺失关闭阶段 " + name + ":" + phase);
        }

        /** @return 仅属于本例的真实关闭线程 */
        private Thread startOwnedThread(String name, Runnable action) {
            Thread thread = new Thread(action, name);
            this.ownedThreads.add(thread);
            thread.start();
            return thread;
        }

        /** finally 清理与被测关闭结果分开；不得将此处干预后的状态当作候选通过。 */
        @Override
        public void close() {
            this.ownedFutures.forEach(future -> future.cancel(false));
            this.context.close();
            try {
                for (ThreadPoolTaskScheduler scheduler : this.schedulers.values()) {
                    if (!scheduler.getScheduledThreadPoolExecutor().isTerminated()) {
                        scheduler.initiateShutdown();
                        scheduler.start();
                    }
                    assertThat(scheduler.getScheduledThreadPoolExecutor().awaitTermination(1, TimeUnit.SECONDS)).isTrue();
                }
                List<Thread> threads = new ArrayList<>(this.ownedThreads);
                this.poolThreads.values().forEach(threads::addAll);
                for (Thread thread : threads) {
                    thread.join(250);
                    assertThat(thread.isAlive()).as("本例线程必须结束: " + thread.getName()).isFalse();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("本例清理被中断", error);
            } finally {
                this.registry.close();
            }
        }
    }

    /** 测试真实注解登记，但初次调度远于本轮预算，禁止引入业务 IO。 */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class AnnotationFixture {
        /** @return 真实 SABPP 管理的内存任务 */
        @Bean
        DelayedAnnotationTask delayedAnnotationTask() {
            return new DelayedAnnotationTask();
        }
    }

    /** 只为确认注解 Future 生命周期，不模拟业务服务。 */
    static class DelayedAnnotationTask {
        /** 60 秒初延迟确保本轮仅观察登记/取消，任何执行都属于意外失败。 */
        @Scheduled(initialDelay = 60_000, fixedDelay = 60_000, scheduler = "outboxTriggerScheduler")
        void neverDuringThisTest() {
            throw new AssertionError("注解夹具不应在本轮执行");
        }
    }

    /** 第一份 cache 配置身份。 */
    static class CacheFirst { }
    /** 第二份 cache 配置身份。 */
    static class CacheSecond { }

    /** 类内首败纪律；后续用例的 aborted 状态必须在失败回执中列为 NOT_RUN。 */
    static final class FirstFailureStop implements BeforeEachCallback, TestExecutionExceptionHandler {
        /** 只对本类本次执行有效，不改变其他测试或全局 JUnit 配置。 */
        private final AtomicBoolean failed = new AtomicBoolean();

        /** 首败后的用例不启动任何新夹具。 */
        @Override
        public void beforeEach(ExtensionContext context) {
            assumeFalse(this.failed.get(), "Q13 首个非预期失败后的用例 NOT_RUN");
        }

        /** 保存失败状态并原样传播原异常，不放宽断言或重试。 */
        @Override
        public void handleTestExecutionException(ExtensionContext context, Throwable error) throws Throwable {
            this.failed.set(true);
            throw error;
        }
    }

    /** 门闩等待失败必须抛出，禁止后台线程异常只写日志而测试假绿。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Outbox 隔离测试未释放");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Outbox 隔离测试被中断", exception);
        }
    }
}
