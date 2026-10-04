package com.things.link.bootstrap.task;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.device.application.DeviceIngestionService.DeviceCommandRoute;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.CachedEffectiveQuotaPolicyProvider;
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
import com.things.link.task.application.TaskDispatchRateLimiter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0067任务派发持续许可与有界冻结收束的原代理真实PG验收。
 * 原任务服务自行开启事务，独立PG保存七表事实，旧反例另存不可变patch，不把原SQL故障伪装为确定失权。
 */
@Import(TaskDispatchLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"LIFECYCLE_POSTGRES"})
class TaskDispatchLifecycleTests extends AbstractIntegrationTest {
    /** 记录非敏感状态/计数及异常类型，便于区分业务反例与环境失败。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDispatchLifecycleTests.class);
    /** 隔离全局task领取及异步工作上下文。 */
    private static final String DATABASE_NAME = "task_dispatch_" + UUID.randomUUID().toString().replace("-", "");
    /** 保持既有PG镜像、角色和池预算，只建立物理专库。 */
    private static final PostgreSQLContainer<?> LIFECYCLE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Registrar先于运行池初始化，Flyway与观察连接共同指向该地址。 */
    private static final String DATABASE_URL = startDatabase();
    /** 完整事实覆盖配置、目标、命令、attempt及Outbox，数量相同不能掩盖覆盖写。 */
    private static final List<String> FACT_TABLES = List.of("task_job", "task_schedule", "task_execution", "task_target",
            "ts_device_command", "ts_device_command_attempt", "sys_outbox_event");
    /** 父runner只知道共享PG，不让专库验收误写其他库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedRestQuotaRelaxation;
    /** claim和process由测试显式触发，自动scanner不能抢走同一执行。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTaskScanner;
    /** 无关通知工作不参与本片事实。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 无关回补不能污染业务线程/连接观察。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 命令attempt由本例检查，自动超时不能提前改变快照。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedCommandTimeoutScanner;
    /** 原应用事务代理，目标spy用于核验物理APP事务。 */
    @MockitoSpyBean private TaskJobService tasks;
    /** 使用原真实SECURITY DEFINER领取函数与持久状态。 */
    @MockitoSpyBean private JdbcTaskJobRepository repository;
    /** 真实返回路由后设置竞争屏障，绝不替换路由值。 */
    @MockitoSpyBean private DeviceIngestionService deviceIngestion;
    /** 真实预热当前ACTIVE策略，显式证明缓存成功而不是mock额度。 */
    @MockitoSpyBean private CachedEffectiveQuotaPolicyProvider quotaPolicies;
    /** 原项目OWNER能力只用于合法创建。 */
    @Autowired private ProjectService projects;
    /** 真实事务PID与角色核验。 */
    @Autowired private JdbcTemplate jdbc;
    /** 校验迁移连接落点。 */
    @Autowired private Environment environment;
    /** 原JSON对象与完整行解析。 */
    @Autowired private ObjectMapper mapper;
    /** 精确回收本项目及租户的真实task限流键。 */
    @Autowired private StringRedisTemplate redis;
    /** 只观察真实项目许可，持续锁由原服务事务保持。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 原Redis限速不stub成功，观察拒绝前不会消耗令牌。 */
    @MockitoSpyBean private TaskDispatchRateLimiter rateLimiter;
    /** 已受理命令由原服务和回复创建/归并。 */
    @MockitoSpyBean private DeviceCommandService commands;
    /** 删除预检观察真实事务PID，项目锁行为不替换。 */
    @MockitoSpyBean private JdbcProjectRepository projectRepository;
    /** 每例独占真实租户、OWNER、项目和设备类型。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** UUID必须对应实际设备插入。 */
    private final List<UUID> deviceIds = new ArrayList<>();
    /** 原业务连接观察，不引入外层测试事务。 */
    private final ThreadLocal<Integer> processPid = new ThreadLocal<>();
    /** 记录真实路由是否成功返回，不能把未走到路径的拒绝冒充快照反例。 */
    private final AtomicInteger routeReturns = new AtomicInteger();

    /** 派发前策略读取次数。 */
    private final AtomicInteger policyCalls = new AtomicInteger();
    /** 项目拒绝前不得领取PENDING目标租约。 */
    private final AtomicInteger pendingClaims = new AtomicInteger();
    /** 非事务Redis令牌调用次数，用于拒绝边界取证。 */
    private final AtomicInteger rateCalls = new AtomicInteger();
    /** 仅计原项目许可，不能以quota/route快照替代。 */
    private final AtomicInteger permitCalls = new AtomicInteger();
    /** 包括幂等回读在内的任务命令受理入口次数。 */
    private final AtomicInteger submitCalls = new AtomicInteger();
    /** 成功真实路由返回后的观察屏障。 */
    private volatile Runnable afterRoute = () -> { };
    /** 真实许可SQL前记录等待PID。 */
    private volatile Runnable beforePermit = () -> { };

    /** 原装配与真实基础关系先通过，再设置仅观察的服务spy。 */
    @BeforeEach
    void prepare() throws Exception {
        verifyIsolation();
        seed();
        installObservers();
    }

    /** 许可先得后真实路由暂停：归档或OWNER删除必须等待命令、attempt、Outbox原事务提交。 */
    @ParameterizedTest
    @EnumSource(Freeze.class)
    void originalDispatchPermitBlocksFreezeUntilCommandCommit(Freeze freeze) throws Exception {
        TaskExecution execution = createDispatchingExecution(1);
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        Map<String, List<String>> before = facts();
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CompletableFuture<Integer> freezerPid = new CompletableFuture<>();
        afterRoute = () -> { writerPid.complete(actualAppPid()); await(release); };
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
            Map<String, List<String>> accepted = facts();
            assertState(1, 0, 0, 1, "RUNNING");
            assertThat(accepted.get("ts_device_command")).hasSize(1);
            assertThat(accepted.get("ts_device_command_attempt")).hasSize(1);
            assertThat(accepted.get("sys_outbox_event")).hasSize(1);
            assertThat(routeReturns).hasValue(1);
            assertThat(permitCalls).hasValue(1);
            assertThat(policyCalls).hasValue(1);
            assertThat(rateCalls).hasValue(1);
            assertThat(submitCalls).hasValue(1);
        } finally { finish(executor, release); }
    }

    /** 冷/暖策略路由都必须在原许可拒绝后停止，不触及额度、目标受理、Redis令牌或命令幂等回读。 */
    @ParameterizedTest
    @CsvSource({"ARCHIVED,false", "ARCHIVED,true", "DELETE,false", "DELETE,true"})
    void committedFreezeStopsBeforeColdOrWarmQuotaAndCommandRoutes(Freeze freeze, boolean warm) throws Exception {
        TaskExecution execution = createDispatchingExecution(1);
        assertProjectPolicyWarm(false);
        if (warm) {
            assertThat(asOwner(() -> quotaPolicies.resolveTrustedProject(fixture.projectId())).taskProjectDispatchPerSecond()).isPositive();
            assertProjectPolicyWarm(true);
        }
        freeze(freeze);
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        Map<String, List<String>> before = facts();
        resetObservations();
        process(due);
        Map<String, List<String>> after = facts();
        assertNoNewCommandFacts(before, after);
        assertState(1, 0, 1, 0, "FAILED");
        assertThat(execution(after).path("failure_summary").asText()).isEqualTo("项目已冻结，任务派发停止");
        assertThat(execution(after).path("skipped_targets").asInt()).isEqualTo(1);
        assertThat(permitCalls).hasValue(1);
        assertNoDispatchWork();
        assertThat(redis.hasKey("tc:task:rate:{project:" + fixture.projectId() + "}")).isFalse();
        assertThat(redis.hasKey("tc:task:rate:{tenant:" + fixture.tenantId() + "}")).isFalse();
        process(due);
        assertThat(facts()).isEqualTo(after);
        assertThat(permitCalls).hasValue(1);
        assertNoDispatchWork();
    }

    /** 原默认桶真实受理N个目标，剩余至少101个按100有界跳过；旧命令保全并按真实回复归并。 */
    @Test
    void partiallyAcceptedDispatchStopsInBoundedBatchesAndPreservesRealCommands() throws Exception {
        TaskExecution execution = createDispatchingExecution(201);
        process(claimExecution(execution.id()));
        Map<String, List<String>> accepted = facts();
        int count = (int) targetCount(accepted, "ACCEPTED");
        assertThat(count).as("默认真实Redis桶允许受理，但单轮最多100").isBetween(1, 100);
        int pending = 201 - count;
        assertThat(pending).isGreaterThanOrEqualTo(101);
        assertState(201, pending, 0, count, "DISPATCHING");
        assertThat(accepted.get("ts_device_command")).hasSize(count);
        assertThat(accepted.get("ts_device_command_attempt")).hasSize(count);
        assertThat(accepted.get("sys_outbox_event")).hasSize(count);
        List<JsonNode> acceptedTargets = accepted.get("task_target").stream().map(mapper::readTree)
                .filter(row -> row.path("status").asText().equals("ACCEPTED")).toList();
        freeze(Freeze.ARCHIVED);
        expireLease(execution.id());
        resetObservations();
        process(claimExecution(execution.id()));
        assertState(201, pending - 100, 100, count, "STOPPING");
        assertPreservedCommandsAndTargetIdentities(accepted, facts());
        assertThat(execution(facts()).path("failure_summary").asText()).isEqualTo("项目已冻结，任务派发停止");
        // STOPPING提交后即使ACTIVE恢复也只能维护旧事实，不能把剩余PENDING重新派发。
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ACTIVE' WHERE id=?", fixture.projectId());
        }
        process(claimExecution(execution.id()));
        assertState(201, 0, pending, count, "STOPPING");
        assertPreservedCommandsAndTargetIdentities(accepted, facts());
        assertThat(permitCalls).hasValue(1);
        assertNoDispatchWork();
        for (JsonNode target : acceptedTargets)
            replySuccess(UUID.fromString(target.path("command_id").asText()), UUID.fromString(target.path("device_id").asText()));
        Map<String, List<String>> replied = facts();
        assertThat(replied.get("sys_outbox_event")).hasSize(count * 2);
        process(claimExecution(execution.id()));
        assertState(201, 0, pending, 0, "PARTIAL_FAILED");
        JsonNode completed = execution(facts());
        assertThat(completed.path("accepted_targets").asInt()).isEqualTo(count);
        assertThat(completed.path("succeeded_targets").asInt()).isEqualTo(count);
        assertThat(completed.path("failed_targets").asInt()).isZero();
        assertThat(completed.path("skipped_targets").asInt()).isEqualTo(pending);
        assertNoNewCommandFacts(replied, facts());
        assertNoDispatchWork();
        Map<String, List<String>> finished = facts();
        process(new TaskJobRepository.DueExecution(fixture.tenantId(), fixture.projectId(), execution.id()));
        assertThat(facts()).isEqualTo(finished);
        LOGGER.info("task dispatch bounded stop total=201 accepted={} skipped={} realDefaultQuota=true", count, pending);
    }

    /** 许可等待的原五秒SQL超时不是确定冻结，完整回滚后真实重领可正常派发。 */
    @Test
    void originalDispatchPermissionTimeoutRollsBackThenReclaimRecovers() throws Exception {
        TaskExecution execution = createDispatchingExecution(1);
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
            assertNoDispatchWork();
            holder.rollback();
            beforePermit = () -> { };
            expireLease(execution.id());
            process(claimExecution(execution.id()));
            assertState(1, 0, 0, 1, "RUNNING");
            assertThat(facts().get("ts_device_command")).hasSize(1);
        } finally { finish(executor, new CountDownLatch(0)); }
    }

    /** 新DIS→STOP真实SQL写入后抛异常，转换及所有目标完整回滚，后续同Due重领可确定收束。 */
    @Test
    void actualDispatchStopWriteFailureRollsBackAndReclaimedTurnRecovers() throws Exception {
        TaskExecution execution = createDispatchingExecution(1);
        freeze(Freeze.ARCHIVED);
        TaskJobRepository.DueExecution due = claimExecution(execution.id());
        Map<String, List<String>> before = facts();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        JdbcTaskJobRepository target = AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> {
            actualAppPid();
            Object changed = invocation.callRealMethod();
            assertThat(changed).isEqualTo(true);
            assertThat(jdbc.queryForObject("SELECT status FROM task_execution WHERE id=?", String.class, execution.id())).isEqualTo("STOPPING");
            assertThat(jdbc.queryForObject("SELECT failure_summary FROM task_execution WHERE id=?", String.class, execution.id()))
                    .isEqualTo("项目已冻结，任务派发停止");
            if (failOnce.compareAndSet(true, false)) throw new IllegalStateException("真实派发停止写后故障");
            return changed;
        }).when(target).beginDispatchStopping(eq(execution.id()), any());
        Throwable failure = catchThrowable(() -> process(due));
        assertThat(rootCause(failure)).isInstanceOf(IllegalStateException.class).hasMessage("真实派发停止写后故障");
        assertThat(facts()).isEqualTo(before);
        assertState(1, 1, 0, 0, "DISPATCHING");
        assertNoDispatchWork();
        expireLease(execution.id());
        process(claimExecution(execution.id()));
        assertState(1, 0, 1, 0, "FAILED");
        assertNoNewCommandFacts(before, facts());
        assertNoDispatchWork();
    }

    /** 正常OWNER创建和实际200页展开，不把测试拼接DIS状态当作前置。 */
    private TaskExecution createDispatchingExecution(int devices) throws SQLException {
        seedDevices(devices);
        TaskJob job = createJob(Instant.now().plusSeconds(86_400));
        TaskExecution execution = asOwner(() -> tasks.run(fixture.projectId(), job.id()));
        assertThat(execution.status()).isEqualTo(TaskExecution.Status.EXPANDING);
        process(claimExecution(execution.id()));
        if (devices > 200) process(claimExecution(execution.id()));
        assertThat(execution(facts()).path("status").asText()).isEqualTo("DISPATCHING");
        assertThat(facts().get("task_target")).hasSize(devices);
        resetObservations();
        return execution;
    }

    /** 只读核对该项目的实际quota路由缓存冷热，不清共享cache或注入策略成功值。 */
    private void assertProjectPolicyWarm(boolean expected) {
        CachedEffectiveQuotaPolicyProvider target = AopTestUtils.getUltimateTargetObject(quotaPolicies);
        Object routes = ReflectionTestUtils.getField(target, "projectTenants");
        assertThat(routes).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) routes).containsKey(fixture.projectId())).isEqualTo(expected);
    }

    /** 新派发专用许可的拒绝不能流入额度、令牌、路由或命令幂等查找。 */
    private void assertNoDispatchWork() {
        assertThat(policyCalls).hasValue(0);
        assertThat(pendingClaims).hasValue(0);
        assertThat(rateCalls).hasValue(0);
        assertThat(routeReturns).hasValue(0);
        assertThat(submitCalls).hasValue(0);
    }

    /** 正常展开的许可调用不能计为待验证DIS分支的证据。 */
    private void resetObservations() {
        policyCalls.set(0); pendingClaims.set(0); rateCalls.set(0); routeReturns.set(0); permitCalls.set(0); submitCalls.set(0);
    }

    /** 七表完整快照保护任务配置、目标身份与真正已受理的command/attempt/Outbox。 */
    private void assertPreservedCommandsAndTargetIdentities(Map<String, List<String>> before, Map<String, List<String>> after) {
        assertNoNewCommandFacts(before, after);
        for (String table : List.of("task_job", "task_schedule")) assertThat(after.get(table)).isEqualTo(before.get(table));
        assertThat(after.get("task_target").stream().map(mapper::readTree).map(row -> row.path("device_id").asText()).sorted().toList())
                .isEqualTo(before.get("task_target").stream().map(mapper::readTree).map(row -> row.path("device_id").asText()).sorted().toList());
        Map<String, JsonNode> currentTargets = new LinkedHashMap<>();
        for (String row : after.get("task_target")) {
            JsonNode target = mapper.readTree(row);
            currentTargets.put(target.path("device_id").asText(), target);
        }
        for (String oldRow : before.get("task_target")) {
            JsonNode old = mapper.readTree(oldRow);
            JsonNode current = currentTargets.get(old.path("device_id").asText());
            assertThat(current).isNotNull();
            for (String field : List.of("execution_id", "device_id", "tenant_id", "project_id", "created_at", "command_id", "accepted_at"))
                assertThat(current.path(field)).as(field).isEqualTo(old.path(field));
            if (old.path("status").asText().equals("ACCEPTED")) assertThat(current).isEqualTo(old);
        }
    }

    /** 完整行验证三种新增业务意图零变化，不能把事务异常或计数巧合当拒绝成功。 */
    private void assertNoNewCommandFacts(Map<String, List<String>> before, Map<String, List<String>> after) {
        for (String table : List.of("ts_device_command", "ts_device_command_attempt", "sys_outbox_event"))
            assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
    }

    /** 数据域只选连接池，原process代理自行开启非只读事务。 */
    private void process(TaskJobRepository.DueExecution due) {
        try { inData(() -> { tasks.processExecution(due); return null; }); }
        finally { processPid.remove(); }
    }

    /** 服务、许可、策略、令牌、路由和命令都保留真实方法，spy只计数或在返回时设屏障。 */
    private void installObservers() {
        assertThat(AopUtils.isAopProxy(tasks)).isTrue();
        TaskJobService task = AopTestUtils.getUltimateTargetObject(tasks);
        doAnswer(invocation -> { processPid.set(actualAppPid()); return invocation.callRealMethod(); })
                .when(task).processExecution(any());
        ProjectLifecycleAccessService permit = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            actualAppPid(); permitCalls.incrementAndGet(); beforePermit.run();
            return invocation.callRealMethod();
        }).when(permit).lockActiveForWrite(any(), any());
        CachedEffectiveQuotaPolicyProvider policy = AopTestUtils.getUltimateTargetObject(quotaPolicies);
        doAnswer(invocation -> { policyCalls.incrementAndGet(); return invocation.callRealMethod(); })
                .when(policy).resolveTrustedProject(any());
        JdbcTaskJobRepository taskRepository = AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> { actualAppPid(); pendingClaims.incrementAndGet(); return invocation.callRealMethod(); })
                .when(taskRepository).claimPendingTargets(any(), anyInt());
        TaskDispatchRateLimiter limiter = AopTestUtils.getUltimateTargetObject(rateLimiter);
        doAnswer(invocation -> { actualAppPid(); rateCalls.incrementAndGet(); return invocation.callRealMethod(); })
                .when(limiter).tryAcquire(any(), any(), any(), any());
        DeviceIngestionService route = AopTestUtils.getUltimateTargetObject(deviceIngestion);
        doAnswer(invocation -> {
            actualAppPid();
            DeviceCommandRoute result = (DeviceCommandRoute) invocation.callRealMethod();
            routeReturns.incrementAndGet(); afterRoute.run(); return result;
        }).when(route).resolveCommandRoute(any(), any(), any(), any());
        DeviceCommandService command = AopTestUtils.getUltimateTargetObject(commands);
        doAnswer(invocation -> { actualAppPid(); submitCalls.incrementAndGet(); return invocation.callRealMethod(); })
                .when(command).submitTask(any());
    }

    /** target状态计数为long，与stream.count保持类型一致，不靠装箱类型造成伪失败。 */
    private long targetCount(Map<String, List<String>> facts, String status) {
        return facts.get("task_target").stream().map(mapper::readTree).filter(row -> row.path("status").asText().equals(status)).count();
    }
    /** 只归并已经受理的真实回复，DATA工作范围不提供外层事务。 */
    private void replySuccess(UUID commandId, UUID deviceId) {
        Instant now = Instant.now();
        assertThat(inData(() -> commands.applyReply(new DeviceCommandReply(Uuid7.generate(), fixture.tenantId(),
                fixture.projectId(), deviceId, commandId, now, now, DeviceCommandReply.Status.SUCCESS,
                "{}", null, null, "task-lifecycle-test")))).isTrue();
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
    /** 屏障失败要直接失败；中断恢复标志，不吞异常伪装成功。 */
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("原业务屏障必须被主测试释放").isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("等待业务屏障被中断", failure); }
    }

    /** 根因用于区分真实SQL后故障与无关代理异常。 */
    private Throwable rootCause(Throwable failure) {
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    /** 原SQLSTATE沿cause提取，不靠异常中文或外层翻译名猜测超时。 */
    private String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    /** 观察连接PID不能来自工作者的逻辑线程名。 */
    private int connectionPid(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT pg_backend_pid()"); ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue(); return rows.getInt(1);
        }
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

    /** 删除findRole预检记录真实PID，之后仍沿原管理排他锁及身份重验。 */
    private void observeDeletePid(CompletableFuture<Integer> pid) {
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(projectRepository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            pid.complete(actualAppPid());
            return result;
        }).when(target).findRole(eq(fixture.projectId()), eq(fixture.accountId()));
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

    /** 捕获最外层结果，Future必须结束后再读取独立已提交事实。 */
    private Throwable observed(TaskJobRepository.DueExecution due) { return catchThrowable(() -> process(due)); }

    /** spy内部验证原APP事务及同一连接，不把测试包装事务当作生产边界。 */
    private int actualAppPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        if (processPid.get() != null) assertThat(pid).isEqualTo(processPid.get());
        return pid;
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
