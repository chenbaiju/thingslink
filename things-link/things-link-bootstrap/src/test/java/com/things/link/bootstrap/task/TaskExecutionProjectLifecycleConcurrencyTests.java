package com.things.link.bootstrap.task;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.device.application.DeviceSearchService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskJobCommand;
import com.things.link.task.application.TaskJobService;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.task.domain.TaskExecution;
import com.things.link.task.domain.TaskJob;
import com.things.link.task.domain.TaskJobRepository;
import com.things.link.task.infrastructure.persistence.JdbcTaskJobRepository;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.telemetry.application.TaskDeviceCommandRequest;
import com.things.link.telemetry.application.TaskDeviceCommandResult;
import com.things.link.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0066合并功能闭环的并发/恢复验收：原service自行开启事务，独立PG观察真实锁与持久事实。
 * 所有屏障只暂停真实方法；许可、分页、命令和条件SQL保持原实现，不把TSM标志当数据库事务。
 */
@Import(TaskExecutionProjectLifecycleConcurrencyTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"LIFECYCLE_POSTGRES"})
class TaskExecutionProjectLifecycleConcurrencyTests extends AbstractIntegrationTest {
    /** 独占全局claim候选，防止其他缓存上下文领取本类任务。 */
    private static final String DATABASE_NAME = "task_concurrency_" + UUID.randomUUID().toString().replace("-", "");
    /** 同生产验收镜像的专库，Flyway、APP池和owner观察必须同落点。 */
    private static final PostgreSQLContainer<?> LIFECYCLE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Registrar前启动，原角色和预算保持不变。 */
    private static final String DATABASE_URL = startDatabase();
    /** 完整行对比涵盖任务和旧命令副作用，避免只看计数遗漏覆盖更新。 */
    private static final List<String> FACT_TABLES = List.of("task_job", "task_schedule", "task_execution", "task_target",
            "ts_device_command", "ts_device_command_attempt", "sys_outbox_event");
    /** 父runner只知道共享PG，不允许向错误库写额度夹具。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedRestQuotaRelaxation;
    /** 手工真实claim和service替代自动触发时机，不替代其业务实现。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTaskScanner;
    /** 非本片工作者不得干扰冻结快照。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 回补工作者不参与本片任务合同。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 真实命令由本例显式回复，不让定时超时器竞争。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedCommandTimeoutScanner;
    /** 原事务代理目标spy只设置进入时观察点。 */
    @MockitoSpyBean private TaskJobService tasks;
    /** 只有真实SQL执行后才允许注入故障。 */
    @MockitoSpyBean private JdbcTaskJobRepository repository;
    /** 真实许可先执行，只有true后才触发持锁屏障。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 分页观察证明两旧Due使用锁后新游标。 */
    @MockitoSpyBean private DeviceSearchService deviceSearch;
    /** 真实已受理命令及真实回复，不伪造command UUID。 */
    @Autowired private DeviceCommandService commands;
    /** 真正OWNER删除由原项目代理执行。 */
    @Autowired private ProjectService projects;
    /** 删除预检只观察原事务PID，随后仍走原FOR UPDATE。 */
    @MockitoSpyBean private JdbcProjectRepository projectRepository;
    /** 读取原事务物理连接及本例未提交SQL事实。 */
    @Autowired private JdbcTemplate jdbc;
    /** 明确验证迁移连接的实际库。 */
    @Autowired private Environment environment;
    /** 生产JSON实现读取完整行并创建合法空对象命令。 */
    @Autowired private ObjectMapper mapper;
    /** 精确回收真实submitTask用到的本例限流键。 */
    @Autowired private StringRedisTemplate redis;
    /** 各例独占项目、OWNER和合法设备类型。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 仅记录真正插入的设备，不用无FK目标伪造设备存在。 */
    private final List<UUID> deviceIds = new ArrayList<>();
    /** 线程自己的原业务连接，避免并行验收把另一线程PID当成本线程。 */
    private final ThreadLocal<Integer> processPid = new ThreadLocal<>();
    /** 真实分页参数按调用次序记录；null游标用固定标签保存。 */
    private final List<String> pageCursors = new CopyOnWriteArrayList<>();
    /** 持真实许可后才暂停，不替换许可结果。 */
    private volatile Runnable afterPermit = () -> { };
    /** 许可之前记录等待者PID，不改变实际SQL预算。 */
    private volatile Runnable beforePermit = () -> { };
    /** 原service方法体已进入时观察PID；执行锁仍由生产代码获取。 */
    private volatile IntConsumer processingStarted = ignored -> { };

    /** 专库身份先核验，再播种真实关系并装配仅观察的spy。 */
    @BeforeEach
    void prepare() throws Exception {
        verifyIsolation();
        seed();
        installObservers();
    }
    /** 真实SHARE持续到原展开提交，OWNER删除和非键归档都不能越过这个写者。 */
    @ParameterizedTest
    @EnumSource(Freeze.class)
    void acquiredWritePermitBlocksFreezeUntilExpansionCommits(Freeze freeze) throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        Map<String, List<String>> before = facts();
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CompletableFuture<Integer> freezerPid = new CompletableFuture<>();
        afterPermit = () -> { writerPid.complete(actualAppPid()); await(release); };
        if (freeze == Freeze.DELETE) observeDeletePid(freezerPid);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> writer = executor.submit(() -> observed(due));
            int pid = writerPid.get(4, TimeUnit.SECONDS);
            Future<?> freezer = executor.submit(() -> runFreeze(freeze, freezerPid));
            assertBlockedBy(freezerPid.get(3, TimeUnit.SECONDS), pid, freezer);
            assertThat(facts()).isEqualTo(before);
            release.countDown();
            assertThat(writer.get(5, TimeUnit.SECONDS)).isNull();
            freezer.get(5, TimeUnit.SECONDS);
            assertState(200, 200, 0, 0, "EXPANDING");
            assertThat(pageCursors).hasSize(1);
            afterPermit = () -> { };
            process(claimExecution(execution.id()));
            assertState(200, 100, 100, 0, "STOPPING");
            assertThat(pageCursors).hasSize(1);
        } finally { finish(executor, release); }
    }

    /** 执行行已经持有但尚未获许可时，真正冻结先提交；随后原入口不能再查询设备。 */
    @ParameterizedTest
    @EnumSource(Freeze.class)
    void committedFreezeBeforePermissionStopsWithoutExpanding(Freeze freeze) throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> reached = new CompletableFuture<>();
        beforePermit = () -> { reached.complete(actualAppPid()); await(release); };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> writer = executor.submit(() -> observed(due));
            reached.get(4, TimeUnit.SECONDS);
            freeze(freeze);
            release.countDown();
            assertThat(writer.get(5, TimeUnit.SECONDS)).isNull();
            assertState(0, 0, 0, 0, "FAILED");
            assertThat(pageCursors).isEmpty();
            assertThat(execution(facts()).path("failure_summary").asText()).isEqualTo("项目已冻结，目标展开停止");
        } finally { finish(executor, release); }
    }

    /** 未提交归档实际阻塞SHARE，提交后READ COMMITTED重新检查，不采用等待前ACTIVE快照。 */
    @Test
    void waitingPermissionRechecksArchiveAfterItsCommit() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        beforePermit = () -> pid.complete(actualAppPid());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            Future<Throwable> writer = executor.submit(() -> observed(due));
            assertBlockedBy(pid.get(3, TimeUnit.SECONDS), connectionPid(holder), writer);
            holder.commit();
            assertThat(writer.get(5, TimeUnit.SECONDS)).isNull();
            assertState(0, 0, 0, 0, "FAILED");
            assertThat(pageCursors).isEmpty();
        } finally { finish(executor, new CountDownLatch(0)); }
    }

    /** 相同旧Due并行进入，第二事务必须等执行锁并使用第一事务提交后的第二页游标。 */
    @Test
    void concurrentOldDueReloadsCursorOnlyAfterExecutionLock() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> firstPid = new CompletableFuture<>();
        CompletableFuture<Integer> secondPid = new CompletableFuture<>();
        AtomicInteger entrants = new AtomicInteger();
        AtomicBoolean firstPermission = new AtomicBoolean(true);
        processingStarted = pid -> { if (entrants.incrementAndGet() == 2) secondPid.complete(pid); };
        afterPermit = () -> {
            if (firstPermission.compareAndSet(true, false)) { firstPid.complete(actualAppPid()); await(release); }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> observed(due));
            int pid = firstPid.get(4, TimeUnit.SECONDS);
            Future<Throwable> second = executor.submit(() -> observed(due));
            assertBlockedBy(secondPid.get(3, TimeUnit.SECONDS), pid, second);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();
            assertState(201, 201, 0, 0, "DISPATCHING");
            assertThat(pageCursors).hasSize(2);
            assertThat(pageCursors.getFirst()).isEqualTo("<first-page>");
            assertThat(pageCursors.get(1)).isNotEqualTo("<first-page>");
            assertThat(facts().get("task_target").stream().map(row -> mapper.readTree(row).path("device_id").asText()).distinct().count()).isEqualTo(201);
        } finally { finish(executor, release); }
    }

    /** 短租约已过期也不能抢占持执行锁的处理者；真实claim的SKIP LOCKED必须跳过。 */
    @Test
    void expiredLeaseDoesNotLetGlobalClaimPassHeldExecutionLock() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        expireLease(execution.id());
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> reached = new CompletableFuture<>();
        afterPermit = () -> { reached.complete(actualAppPid()); await(release); };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> writer = executor.submit(() -> observed(due));
            reached.get(4, TimeUnit.SECONDS);
            assertThat(inData(() -> repository.claimDueExecutions(100))).isEmpty();
            assertThat(execution(facts()).path("lease_until").isNull()).isFalse();
            assertThat(writer.isDone()).isFalse();
            release.countDown();
            assertThat(writer.get(5, TimeUnit.SECONDS)).isNull();
            assertState(200, 200, 0, 0, "EXPANDING");
            afterPermit = () -> { };
            process(claimExecution(execution.id()));
            assertState(201, 201, 0, 0, "DISPATCHING");
        } finally { finish(executor, release); }
    }

    /** 使用原JDBC五秒预算取得真实57014；STOPPING不得提交，原lease和七表完整回滚后可重领。 */
    @Test
    void actualPermissionTimeoutRollsBackAndReclaimedExecutionRecovers() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        Map<String, List<String>> before = facts();
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        beforePermit = () -> pid.complete(actualAppPid());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            long started = System.nanoTime();
            Future<Throwable> writer = executor.submit(() -> observed(due));
            assertBlockedBy(pid.get(3, TimeUnit.SECONDS), connectionPid(holder), writer);
            Throwable failure = writer.get(8, TimeUnit.SECONDS);
            assertThat(sqlState(failure)).isEqualTo("57014");
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(4000L, 8500L);
            assertThat(facts()).isEqualTo(before);
            assertThat(pageCursors).isEmpty();
            holder.rollback();
            beforePermit = () -> { };
            expireLease(execution.id());
            process(claimExecution(execution.id()));
            assertState(200, 200, 0, 0, "EXPANDING");
        } finally { finish(executor, new CountDownLatch(0)); }
    }

    /** 真正跳过SQL已经修改100行后抛异常：首轮STOP转换与既有STOP维护都必须整事务回滚。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualSkipWriteFailureRollsBackWholeTurnAndRecovers(boolean alreadyStopping) throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        freeze(Freeze.ARCHIVED);
        if (alreadyStopping) process(claimExecution(execution.id()));
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        Map<String, List<String>> before = facts();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        JdbcTaskJobRepository target = AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> {
            actualAppPid();
            Object count = invocation.callRealMethod();
            assertThat(count).isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM task_target WHERE execution_id=? AND status='SKIPPED'",
                    Integer.class, execution.id())).isEqualTo(alreadyStopping ? 200 : 100);
            if (failOnce.compareAndSet(true, false)) throw new IllegalStateException("真实有界跳过写后故障");
            return count;
        }).when(target).skipPendingForStopping(eq(execution.id()), eq(100), any());
        Throwable failure = catchThrowable(() -> process(due));
        assertThat(rootCause(failure)).isInstanceOf(IllegalStateException.class).hasMessage("真实有界跳过写后故障");
        assertThat(facts()).isEqualTo(before);
        assertThat(execution(before).path("status").asText()).isEqualTo(alreadyStopping ? "STOPPING" : "EXPANDING");
        expireLease(execution.id());
        process(claimExecution(execution.id()));
        assertState(200, alreadyStopping ? 0 : 100, alreadyStopping ? 200 : 100, 0,
                alreadyStopping ? "FAILED" : "STOPPING");
    }

    /** 唯一剩余PENDING被真实连接锁住时不能提前终结；无进展从本轮时刻重算30秒退避。 */
    @Test
    void lockedPendingTargetPreventsCompletionAndRecomputesIdleLease() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        freeze(Freeze.ARCHIVED);
        process(claimExecution(execution.id()));
        List<String> targets = facts().get("task_target");
        UUID lockedDevice = targets.stream().map(mapper::readTree)
                .filter(row -> row.path("status").asText().equals("PENDING"))
                .map(row -> UUID.fromString(row.path("device_id").asText())).findFirst().orElseThrow();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            lockTarget(holder, execution.id(), lockedDevice);
            process(claimExecution(execution.id()));
            assertState(200, 1, 199, 0, "STOPPING");
            TaskJobRepository.DueExecution due = claimExecution(execution.id());
            expireLease(execution.id());
            Map<String, List<String>> before = facts();
            Instant started = Instant.now();
            process(due);
            Instant ended = Instant.now();
            assertIdleMaintenance(before, started, ended);
            assertState(200, 1, 199, 0, "STOPPING");
            assertThat(inData(() -> repository.claimDueExecutions(100))).isEmpty();
            holder.rollback();
        }
        expireLease(execution.id());
        process(claimExecution(execution.id()));
        assertState(200, 0, 200, 0, "FAILED");
    }

    /** 真实ACCEPTED命令阻止结束，重复检查不重受理；退避结束后真实回复使旧命令正常归并。 */
    @Test
    void acceptedCommandWaitRecomputesIdleLeaseThenRealReplyCompletes() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        UUID deviceId = UUID.fromString(mapper.readTree(facts().get("task_target").getFirst()).path("device_id").asText());
        TaskDeviceCommandResult accepted = asOwner(() -> commands.submitTask(new TaskDeviceCommandRequest(
                execution.id(), fixture.projectId(), fixture.accountId(), deviceId, "reboot", mapper.createObjectNode())));
        assertThat(accepted.status()).isEqualTo(TaskDeviceCommandResult.Status.ACCEPTED);
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE task_target SET status='ACCEPTED',command_id=?,accepted_at=now() WHERE execution_id=? AND device_id=?",
                    accepted.commandId(), execution.id(), deviceId);
        }
        freeze(Freeze.ARCHIVED);
        process(claimExecution(execution.id()));
        process(claimExecution(execution.id()));
        assertState(200, 0, 199, 1, "STOPPING");
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        expireLease(execution.id());
        Map<String, List<String>> before = facts();
        Instant started = Instant.now();
        process(due);
        Instant ended = Instant.now();
        assertIdleMaintenance(before, started, ended);
        assertState(200, 0, 199, 1, "STOPPING");
        assertThat(inData(() -> repository.claimDueExecutions(100))).isEmpty();
        replySuccess(accepted.commandId(), deviceId);
        expireLease(execution.id());
        process(claimExecution(execution.id()));
        assertState(200, 0, 199, 0, "PARTIAL_FAILED");
        assertThat(execution(facts()).path("succeeded_targets").asInt()).isEqualTo(1);
    }

    /** 真正原服务创建/运行，真实日额度和OWNER前置先完整通过。 */
    private TaskExecution createAndRun() {
        TaskJob job = createJob(Instant.now().plusSeconds(86_400));
        TaskExecution execution = asOwner(() -> tasks.run(fixture.projectId(), job.id()));
        assertThat(execution.status()).isEqualTo(TaskExecution.Status.EXPANDING);
        assertThat(execution.totalTargets()).isZero();
        return execution;
    }

    /** 原应用代理自行开启DATA事务，测试外部不增加TransactionTemplate。 */
    private void process(TaskJobRepository.DueExecution due) {
        try { inData(() -> { tasks.processExecution(due); return null; }); }
        finally { processPid.remove(); }
    }

    /** 捕获最外层结果，Future必须结束后再读取独立已提交事实。 */
    private Throwable observed(TaskJobRepository.DueExecution due) { return catchThrowable(() -> process(due)); }

    /** 原代理目标spy仅设置屏障；各个领域调用必须沿用同一物理事务连接。 */
    private void installObservers() {
        assertThat(AopUtils.isAopProxy(tasks)).isTrue();
        TaskJobService target = AopTestUtils.getUltimateTargetObject(tasks);
        doAnswer(invocation -> {
            int pid = actualAppPid();
            processPid.set(pid);
            processingStarted.accept(pid);
            return invocation.callRealMethod();
        }).when(target).processExecution(any());
        ProjectLifecycleAccessService permit = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            actualAppPid();
            beforePermit.run();
            Object allowed = invocation.callRealMethod();
            if (Boolean.TRUE.equals(allowed)) afterPermit.run();
            return allowed;
        }).when(permit).lockActiveForWrite(any(), any());
        DeviceSearchService search = AopTestUtils.getUltimateTargetObject(deviceSearch);
        doAnswer(invocation -> {
            actualAppPid();
            String cursor = invocation.getArgument(1);
            pageCursors.add(cursor == null ? "<first-page>" : cursor);
            return invocation.callRealMethod();
        }).when(search).listTaskTargets(any(), nullable(String.class), anyInt());
    }

    /** APP非bypass角色、专库及真实事务必须同时成立，线程局部PID证明调用未另开事务。 */
    private int actualAppPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        if (processPid.get() != null) assertThat(pid).isEqualTo(processPid.get());
        return pid;
    }

    /** 只改变明确的租约调度夹具，保留状态/目标；替代真实等待30秒而不改生产预算。 */
    private void expireLease(UUID executionId) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement update = owner.prepareStatement(
                "UPDATE task_execution SET lease_until=now()-interval '1 second' WHERE id=?")) {
            update.setQueryTimeout(5);
            update.setObject(1, executionId);
            assertThat(update.executeUpdate()).as("指定执行租约必须实际更新").isEqualTo(1);
        }
        // 独立观察已提交租约确实到期；否则claim空集不能证明SKIP LOCKED跳过处理中的执行。
        try (Connection observer = fixtureOwnerConnection(); PreparedStatement query = observer.prepareStatement(
                "SELECT lease_until < now() FROM task_execution WHERE id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, executionId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).as("真实claim前置要求租约已到期").isTrue();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** 独立owner完整事实验证状态与目标组成，不把线程成功返回当作事务提交。 */
    private void assertState(int total, int pending, int skipped, int accepted, String status) throws SQLException {
        Map<String, List<String>> current = facts();
        assertThat(execution(current).path("status").asText()).isEqualTo(status);
        assertThat(execution(current).path("total_targets").asInt()).isEqualTo(total);
        assertThat(current.get("task_target")).hasSize(total);
        for (Map.Entry<String, Integer> expected : Map.of("PENDING", pending, "SKIPPED", skipped, "ACCEPTED", accepted).entrySet())
            assertThat(current.get("task_target").stream().map(mapper::readTree)
                    .filter(row -> row.path("status").asText().equals(expected.getKey())).count()).isEqualTo(expected.getValue().longValue());
    }

    /** 无进展仅允许维护updated_at/lease；项目业务与旧命令的完整行不变化。 */
    private void assertIdleMaintenance(Map<String, List<String>> before, Instant started, Instant ended) throws SQLException {
        Map<String, List<String>> after = facts();
        JsonNode old = execution(before);
        JsonNode current = execution(after);
        for (String table : FACT_TABLES) if (!table.equals("task_execution")) assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        for (String field : List.of("id", "tenant_id", "project_id", "job_id", "status", "total_targets", "accepted_targets",
                "succeeded_targets", "failed_targets", "skipped_targets", "expansion_cursor", "failure_summary", "finished_at", "started_at"))
            assertThat(current.path(field)).as(field).isEqualTo(old.path(field));
        Instant updated = Instant.parse(current.path("updated_at").asText());
        Instant lease = Instant.parse(current.path("lease_until").asText());
        assertThat(updated).isBetween(started.minusMillis(1), ended.plusMillis(1));
        assertThat(lease).isEqualTo(updated.plusSeconds(30));
        assertThat(lease).isAfter(Instant.parse(old.path("lease_until").asText()));
        assertThat(current.path("finished_at").isNull()).isTrue();
    }

    /** 行锁来自独立物理事务，SKIP LOCKED应留这个PENDING到后续处理。 */
    private void lockTarget(Connection holder, UUID executionId, UUID deviceId) throws SQLException {
        try (PreparedStatement query = holder.prepareStatement("SELECT device_id FROM task_target WHERE execution_id=? AND device_id=? FOR UPDATE")) {
            query.setQueryTimeout(5); query.setObject(1, executionId); query.setObject(2, deviceId);
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    /** 删除findRole预检记录真实PID，之后仍沿原管理排他锁及身份重验。 */
    private void observeDeletePid(CompletableFuture<Integer> pid) {
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(projectRepository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            pid.complete(actualAppPid());
            return result;
        }).when(target).findRole(eq(fixture.projectId()), eq(fixture.accountId()));
    }

    /** DELETE走原OWNER服务；ARCHIVED为现有字段的独立真实SQL事务。 */
    private void runFreeze(Freeze freeze, CompletableFuture<Integer> pid) {
        if (freeze == Freeze.DELETE) { asOwner(() -> { projects.delete(fixture.projectId()); return null; }); return; }
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            pid.complete(connectionPid(owner));
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            owner.commit();
        } catch (SQLException failure) { throw new IllegalStateException("独立归档失败", failure); }
    }

    /** PG阻塞PID和未授予锁共同证明等待，不能用线程未结束猜测锁已获得。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        assertThat(waiter).isNotEqualTo(holder);
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT pg_backend_pid(), ?=ANY(pg_blocking_pids(?)), EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)")) {
            query.setQueryTimeout(1); query.setInt(1, holder); query.setInt(2, waiter); query.setInt(3, waiter);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waiter).isNotEqualTo(holder);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) throw new AssertionError("业务已结束，未观察到指定PG锁等待");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定APP事务的真实阻塞关系");
    }

    /** 观察连接PID不能来自工作者的逻辑线程名。 */
    private int connectionPid(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT pg_backend_pid()"); ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue(); return rows.getInt(1);
        }
    }

    /** 原SQLSTATE沿cause提取，不靠异常中文或外层翻译名猜测超时。 */
    private String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    /** 根因用于区分真实SQL后故障与无关代理异常。 */
    private Throwable rootCause(Throwable failure) {
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    /** 屏障失败要直接失败；中断恢复标志，不吞异常伪装成功。 */
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("原业务屏障必须被主测试释放").isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("等待业务屏障被中断", failure); }
    }

    /** 所有退出先释放屏障，再有界等待线程；清理失败不能忽略而污染下一例。 */
    private void finish(ExecutorService executor, CountDownLatch release) throws InterruptedException {
        release.countDown();
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("验收工作线程必须回收").isTrue();
        }
        TenantContext.clear();
        processPid.remove();
    }
    /** 合法DIRECT/STANDARD设备类型声明reboot空对象命令，不能用无定义UUID掩盖后续路由。 */
    private TaskJob createJob(Instant runAt) {
        return asOwner(() -> tasks.create(fixture.projectId(), new TaskJobCommand("项目展开许可验收", null,
                TaskJob.ScheduleType.ONCE, runAt, null, null, TaskJob.TargetType.ALL_DEVICES, null,
                "reboot", mapper.createObjectNode(), true, null)));
    }

    /** 调用原SECURITY DEFINER函数，事务外autocommit使lease在进入process之前真正持久化。 */
    private TaskJobRepository.DueExecution claimExecution(UUID executionId) throws SQLException {
        TaskJobRepository.DueExecution claimedDue = inData(() -> {
            List<TaskJobRepository.DueExecution> claimed = repository.claimDueExecutions(100);
            assertThat(claimed).hasSize(1);
            TaskJobRepository.DueExecution due = claimed.getFirst();
            assertThat(due.executionId()).isEqualTo(executionId);
            assertThat(due.tenantId()).isEqualTo(fixture.tenantId());
            assertThat(due.projectId()).isEqualTo(fixture.projectId());
            return due;
        });
        assertClaimPersisted("task_execution");
        return claimedDue;
    }

    /** 领取后由独立owner观察lease非空且仍有效，排除同连接未提交可见造成假证明。 */
    private void assertClaimPersisted(String table) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT lease_until IS NOT NULL AND lease_until > now() FROM " + table + " WHERE project_id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** 每例只有一个真实execution，完整物理行不能被RLS空投影掩盖。 */
    private JsonNode execution(Map<String, List<String>> facts) {
        assertThat(facts.get("task_execution")).hasSize(1);
        return mapper.readTree(facts.get("task_execution").getFirst());
    }

    /** 专库独立owner读取七张物理表的完整行，避免RLS空投影把有写误判为零。 */
    private Map<String, List<String>> facts() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=? ORDER BY row_to_json(f)::text")) {
                    query.setQueryTimeout(5);
                    query.setObject(1, fixture.projectId());
                    List<String> values = new ArrayList<>();
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) values.add(rows.getString(1)); }
                    result.put(table, List.copyOf(values));
                }
            }
        }
        return result;
    }

    /** 真实OWNER删除保留成员和任务历史；归档没有现有业务API，显式SQL仅改已定义status字段。 */
    private void freeze(Freeze freeze) throws SQLException {
        if (freeze == Freeze.DELETE) asOwner(() -> { projects.delete(fixture.projectId()); return null; });
        else try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT status,deleted_at IS NOT NULL FROM sys_project WHERE id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(freeze == Freeze.DELETE ? "DELETING" : "ARCHIVED");
                assertThat(rows.getBoolean(2)).isEqualTo(freeze == Freeze.DELETE);
            }
        }
    }

    /** HTTP身份仅用于真实create/run/delete；后台入口从无TenantContext的DATA工作线程合同进入。 */
    private <T> T asOwner(Supplier<T> action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { return action.get(); } finally { TenantContext.clear(); }
    }

    /** workload scope不创建事务，原claim使用autocommit，原service则自行启动业务事务。 */
    private <T> T inData(Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return action.get();
        } finally { assertThat(TenantContext.current()).isEmpty(); }
    }

    /** 真实表关系与统一合法设备类型，不以task_target无FK为借口制造不存在的设备UUID。 */
    private void seed() throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '任务展开许可验收租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '任务验收OWNER')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '任务展开许可验收', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "task_probe_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'task_probe_type', '任务验收类型', 'STANDARD', 'DIRECT', 'PUBLISHED')",
                    fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?, ?, ?, ?, 'reboot', '重启', '{}'::jsonb, '{}'::jsonb, 30)",
                    Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.commit();
        }
    }

    /** 每个设备都使用真实合法类型及命令定义，不以无FK目标UUID冒充目标展开。 */
    private void seedDevices(int count) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            for (int index = 0; index < count; index++) {
                UUID deviceId = Uuid7.generate();
                deviceIds.add(deviceId);
                execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, ?, '任务验收设备', 'ONLINE')",
                        deviceId, fixture.tenantId(), fixture.projectId(), fixture.typeId(), "task_accept_" + index);
            }
            owner.commit();
        }
    }

    /** 同库同角色逐连接核对；同时确证task事实对象没有变成owner也受WHERE过滤的视图。 */
    private void verifyIsolation() throws SQLException {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        for (Object disabled : List.of(unusedRestQuotaRelaxation, unusedTaskScanner, unusedNotificationCoordinator, unusedBackfillScanner, unusedCommandTimeoutScanner))
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection migration = DriverManager.getConnection(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
             Connection owner = fixtureOwnerConnection()) {
            verifyOwnerIdentity(migration);
            verifyOwnerIdentity(owner);
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement("SELECT relkind::text FROM pg_class WHERE oid=?::regclass")) {
                    query.setQueryTimeout(5);
                    query.setString(1, table);
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isIn("r", "p");
                    }
                }
            }
        }
    }

    /** 明确验证Flyway/独立观察均使用专库owner，不能只有相同URL文本。 */
    private void verifyOwnerIdentity(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT current_database(),current_user")) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
                assertThat(rows.getString(2)).isEqualTo(LIFECYCLE_POSTGRES.getUsername());
            }
        }
    }

    /**
     * 快照取证之后只清理本例四张可变任务夹具，避免下例全局claim领取前例执行。
     * 不删除项目、设备、成员保全或不可变审计，不关闭任何数据库守卫；其余祖先保留至容器回收。
     */
    @AfterEach
    void cleanupOwnTaskFixtures() throws SQLException {
        TenantContext.clear();
        processPid.remove();
        redis.delete(List.of("tc:task:rate:{project:" + fixture.projectId() + "}",
                "tc:task:rate:{tenant:" + fixture.tenantId() + "}"));
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            for (String table : List.of("task_target", "task_execution", "task_schedule", "task_job"))
                execute(owner, "DELETE FROM " + table + " WHERE project_id=?", fixture.projectId());
            owner.commit();
        }
    }

    /** 所有独立owner路径落专库，不能继承默认共享数据库连接。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, LIFECYCLE_POSTGRES.getUsername(), LIFECYCLE_POSTGRES.getPassword());
    }

    /** 固定SQL仅接受参数化身份；5秒界限防止未来锁变化令测试无期限卡住。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 容器由本类OwnedTestContainers回收，不关闭其他上下文的共享库。 */
    private static String startDatabase() { LIFECYCLE_POSTGRES.start(); return LIFECYCLE_POSTGRES.getJdbcUrl(); }
    /** 只归并已经受理的真实回复，DATA工作范围不提供外层事务。 */
    private void replySuccess(UUID commandId, UUID deviceId) {
        Instant now = Instant.now();
        assertThat(inData(() -> commands.applyReply(new DeviceCommandReply(Uuid7.generate(), fixture.tenantId(),
                fixture.projectId(), deviceId, commandId, now, now, DeviceCommandReply.Status.SUCCESS,
                "{}", null, null, "task-lifecycle-test")))).isTrue();
    }

    /** 专库继承原角色、凭证及池预算，仅覆盖地址并禁止本片不验证的自动副作用。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** Registrar优先于父DynamicPropertySource；无业务Bean或额度成功替身。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }

    /** 两种冻结各自独立提交，不能用归档替代真实OWNER删除的状态与成员保全路径。 */
    private enum Freeze {
        /** 真实OWNER删除提交，项目物理行仍保留。 */ DELETE,
        /** 既有status字段的明确归档夹具。 */ ARCHIVED
    }

    /** 独立项目OWNER和合法设备类型，设备UUID只在实际插入时生成。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId) { }
}
