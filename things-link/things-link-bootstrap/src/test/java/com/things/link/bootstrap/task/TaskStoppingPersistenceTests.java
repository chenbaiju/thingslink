package com.things.link.bootstrap.task;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.task.domain.TaskExecution;
import com.things.link.task.domain.TaskJobRepository;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0066决策1/3/4：STOPPING持久基础在原APP_ROLE、RLS与真实PG事务中验收。
 * 本片测试仓储调用者事务，明确不冒充后续TaskJobService原事务接线；目标和命令ID仅为计数种子，不声称真实发送。
 * 独占PG隔离全局claim；本例任务夹具可精确清理，不删除不可变审计和项目祖先。
 */
@Import(TaskStoppingPersistenceTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"STOPPING_POSTGRES"})
class TaskStoppingPersistenceTests extends AbstractIntegrationTest {
    /** D-109：全局领取不能仅靠随机project隔离其他缓存上下文。 */
    private static final String DATABASE_NAME = "task_stopping_" + UUID.randomUUID().toString().replace("-", "");
    /** 与共享基线同镜像、owner及角色，只有物理实例不同。 */
    private static final PostgreSQLContainer<?> STOPPING_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Registrar前启动，运行池和Flyway必须共同落本库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 精确到PG微秒，状态及lease断言不依赖宿主机与容器时钟同步。 */
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    /** 固定原因是可读事实，不是隐藏控制状态。 */
    private static final String STOP_REASON = "项目已冻结，目标展开停止";
    /** 未受理目标的原因与执行摘要不同，防止误称已经取消命令。 */
    private static final String SKIP_REASON = "项目已冻结，目标未受理";
    /** 四张任务物理表完整快照用于回滚、条件no-op及历史配置保全。 */
    private static final List<String> TABLES = List.of("task_job", "task_schedule", "task_execution", "task_target");
    /** 父runner直接连接共享库，本类必须覆盖真实已存在Bean。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedRestQuota;
    /** 原后台scanner禁用，测试显式调用真实领取仓储。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTaskScanner;
    /** 无关worker不应领取本例后台事实。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotifications;
    /** 不让自动回补扩大专库的观察面。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfill;
    /** 所有被测SQL来自原Spring仓储，不以测试SQL代替条件操作。 */
    @Autowired private TaskJobRepository repository;
    /** 本片明确验证仓储基础，TT就是持锁调用者而不是外包生产服务事务。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 核验事务实际连接与独立claim的不同PID。 */
    @Autowired private JdbcTemplate jdbc;
    /** 真实Flyway连接落点检查。 */
    @Autowired private Environment environment;
    /** JSON保持全部行字段，避免只比数量漏掉原位改写。 */
    @Autowired private ObjectMapper mapper;
    /** 每个JUnit展开项独立身份，所有跨域祖先仅用于外键完整性。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());

    /** 先实证连接/角色/后台隔离，再建立EXPANDING与调度的完整持久种子。 */
    @BeforeEach
    void prepare() throws SQLException {
        verifyIsolation();
        seed();
    }

    /** 三元组必须全部匹配；普通RLS不能被新锁定端口扩大为跨项目读取或写入。 */
    @Test
    void lockingReadUsesPersistedIdentityAndRespectsRls() throws SQLException {
        Map<String, List<String>> before = facts();
        inTransaction(() -> {
            TaskExecution execution = repository.lockExecution(fixture.tenantId(), fixture.projectId(), fixture.executionId()).orElseThrow();
            assertThat(execution.tenantId()).isEqualTo(fixture.tenantId());
            assertThat(execution.projectId()).isEqualTo(fixture.projectId());
            assertThat(execution.expansionCursor()).isEqualTo("last-successful-cursor");
            assertThat(repository.lockExecution(UUID.randomUUID(), fixture.projectId(), fixture.executionId())).isEmpty();
            assertThat(repository.lockExecution(fixture.tenantId(), UUID.randomUUID(), fixture.executionId())).isEmpty();
            assertThat(repository.lockExecution(fixture.tenantId(), fixture.projectId(), UUID.randomUUID())).isEmpty();
            return null;
        });
        withScope(UUID.randomUUID(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(repository.findExecution(fixture.projectId(), fixture.executionId())).isEmpty();
            assertThat(repository.lockExecution(fixture.tenantId(), fixture.projectId(), fixture.executionId())).isEmpty();
            assertThat(repository.beginStopping(fixture.executionId(), NOW)).isFalse();
            return null;
        }));
        assertThat(facts()).isEqualTo(before);
    }

    /** 无事务和只读事务不能制造提前释放的锁；非法身份必须明确拒绝。 */
    @Test
    void lockingReadRequiresActualWritableTransactionAndValidIdentity() throws SQLException {
        Map<String, List<String>> before = facts();
        assertRoot(catchThrowable(() -> repository.lockExecution(fixture.tenantId(), fixture.projectId(), fixture.executionId())), IllegalStateException.class);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        assertRoot(catchThrowable(() -> withScope(fixture.projectId(), () -> readOnly.execute(status ->
                repository.lockExecution(fixture.tenantId(), fixture.projectId(), fixture.executionId())))), IllegalStateException.class);
        assertRoot(catchThrowable(() -> inTransaction(() -> repository.lockExecution(null, fixture.projectId(), fixture.executionId()))), NullPointerException.class);
        assertRoot(catchThrowable(() -> inTransaction(() -> repository.lockExecution(fixture.tenantId(), null, fixture.executionId()))), NullPointerException.class);
        assertRoot(catchThrowable(() -> inTransaction(() -> repository.lockExecution(fixture.tenantId(), fixture.projectId(), null))), NullPointerException.class);
        assertThat(facts()).isEqualTo(before);
        assertThat(inTransaction(() -> repository.lockExecution(fixture.tenantId(), fixture.projectId(), fixture.executionId()))).isPresent();
    }

    /** 执行行锁跨真实独立claim持续；释放后STOPPING可领取，未过期同一租约不能重复领取。 */
    @Test
    void stoppingIsClaimableButLockedAndAlreadyLeasedExecutionsAreSkipped() {
        assertThat(inTransaction(() -> repository.beginStopping(fixture.executionId(), NOW))).isTrue();
        inTransaction(() -> {
            repository.lockExecution(fixture.tenantId(), fixture.projectId(), fixture.executionId()).orElseThrow();
            int holderPid = appPid();
            TransactionTemplate independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.execute(status -> {
                assertThat(appPid()).isNotEqualTo(holderPid);
                assertThat(repository.claimDueExecutions(100)).isEmpty();
                return null;
            });
            return null;
        });
        List<TaskJobRepository.DueExecution> claimed = withScope(fixture.projectId(), () -> repository.claimDueExecutions(100));
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().executionId()).isEqualTo(fixture.executionId());
        assertThat(claimed.getFirst().tenantId()).isEqualTo(fixture.tenantId());
        assertThat(claimed.getFirst().projectId()).isEqualTo(fixture.projectId());
        assertThat(withScope(fixture.projectId(), () -> repository.claimDueExecutions(100))).isEmpty();
    }

    /** 201条只能100/100/1三批完成，目标ID、总数、已成功游标和配置不能被停止重建。 */
    @Test
    void skipsExactlyThreeBoundedBatchesWithoutPrematureCompletionOrDoubleCounting() throws SQLException {
        seedTargets("PENDING", 201);
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE task_execution SET lease_until=? WHERE id=?", Timestamp.from(NOW.plusSeconds(3600)), fixture.executionId());
        }
        Map<String, List<String>> original = facts();
        assertThat(inTransaction(() -> repository.beginStopping(fixture.executionId(), NOW))).isTrue();
        var beforeTransition = (tools.jackson.databind.node.ObjectNode) mapper.readTree(original.get("task_execution").getFirst());
        var afterTransition = (tools.jackson.databind.node.ObjectNode) execution();
        // 状态、原因、updated_at之外逐字段保全，特别是不把beginStopping误写成提前清lease。
        for (String field : List.of("status", "failure_summary", "updated_at")) {
            beforeTransition.remove(field); afterTransition.remove(field);
        }
        assertThat(afterTransition).isEqualTo(beforeTransition);
        Map<String, List<String>> stopping = facts();
        assertThat(inTransaction(() -> repository.beginStopping(fixture.executionId(), NOW.plusSeconds(1)))).isFalse();
        assertThat(facts()).isEqualTo(stopping);
        for (int batch = 0; batch < 3; batch++) {
            int expected = batch == 2 ? 1 : 100;
            assertThat(inTransaction(() -> repository.skipPendingForStopping(fixture.executionId(), 100, NOW))).isEqualTo(expected);
            assertThat(countTargetState("SKIPPED")).isEqualTo(Math.min((batch + 1) * 100, 201));
            if (batch < 2) assertThat(inTransaction(() -> repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW))).isFalse();
        }
        assertThat(inTransaction(() -> repository.skipPendingForStopping(fixture.executionId(), 100, NOW))).isZero();
        assertThat(inTransaction(() -> repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW))).isTrue();
        JsonNode completed = execution();
        assertThat(completed.path("status").asText()).isEqualTo("FAILED");
        assertThat(completed.path("total_targets").asInt()).isEqualTo(201);
        assertThat(completed.path("skipped_targets").asInt()).isEqualTo(201);
        assertThat(completed.path("accepted_targets").asInt()).isZero();
        assertThat(completed.path("expansion_cursor").asText()).isEqualTo("last-successful-cursor");
        assertThat(completed.path("failure_summary").asText()).isEqualTo(STOP_REASON);
        assertThat(completed.path("lease_until").isNull()).isTrue();
        assertThat(Instant.parse(completed.path("finished_at").asText())).isEqualTo(NOW);
        List<String> beforeIds = targetIds(original);
        Map<String, List<String>> after = facts();
        assertThat(targetIds(after)).isEqualTo(beforeIds);
        assertThat(after.get("task_job")).isEqualTo(original.get("task_job"));
        assertThat(after.get("task_schedule")).isEqualTo(original.get("task_schedule"));
        for (String row : after.get("task_target")) {
            JsonNode target = mapper.readTree(row);
            assertThat(target.path("failure_summary").asText()).isEqualTo(SKIP_REASON);
            assertThat(target.path("lease_until").isNull()).isTrue();
            assertThat(target.path("command_id").isNull()).isTrue();
        }
        assertThat(inTransaction(() -> repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW.plusSeconds(1)))).isFalse();
        assertThat(facts()).isEqualTo(after);
    }

    /** 其他事务锁住的PENDING留到下一批；未受理目标旧lease不阻止跳过，锁遗漏也不能提前终态。 */
    @Test
    void skipsLockedPendingRowsWithoutTreatingTheirLeaseAsAcceptance() throws SQLException {
        seedTargets("PENDING", 3);
        inTransaction(() -> repository.beginStopping(fixture.executionId(), NOW));
        UUID lockedId = UUID.fromString(mapper.readTree(facts().get("task_target").getFirst()).path("device_id").asText());
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            try (PreparedStatement lock = owner.prepareStatement("SELECT device_id FROM task_target WHERE execution_id=? AND device_id=? FOR UPDATE")) {
                lock.setQueryTimeout(5);
                lock.setObject(1, fixture.executionId()); lock.setObject(2, lockedId);
                try (ResultSet rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            assertThat(inTransaction(() -> repository.skipPendingForStopping(fixture.executionId(), 100, NOW))).isEqualTo(2);
            assertThat(inTransaction(() -> repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW))).isFalse();
            owner.rollback();
        }
        assertThat(inTransaction(() -> repository.skipPendingForStopping(fixture.executionId(), 100, NOW))).isEqualTo(1);
        assertThat(inTransaction(() -> repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW))).isTrue();
    }

    /** ACCEPTED仅作为防御性计数种子；停止仓储不能改写command_id或把它跳过，归并端口另片真实验收。 */
    @Test
    void acceptedTargetPreventsCompletionAndIsNotSkipped() throws SQLException {
        seedTargets("ACCEPTED", 1);
        inTransaction(() -> repository.beginStopping(fixture.executionId(), NOW));
        Map<String, List<String>> before = facts();
        assertThat(inTransaction(() -> repository.skipPendingForStopping(fixture.executionId(), 100, NOW))).isZero();
        assertThat(inTransaction(() -> repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW))).isFalse();
        assertThat(facts()).isEqualTo(before);
    }

    /** 空目标、全跳过、全成功与混合结果保持原最终规则，不把未知未展开设备纳入total。 */
    @ParameterizedTest
    @CsvSource({"EMPTY,FAILED,0,0,0,0", "SKIPPED,FAILED,0,0,0,2", "SUCCEEDED,SUCCEEDED,2,2,0,0", "MIXED,PARTIAL_FAILED,3,1,2,1"})
    void completedCountsPreserveExistingAggregationRules(String shape, String expectedStatus, int accepted, int succeeded, int failed, int skipped) throws SQLException {
        if (shape.equals("MIXED")) {
            for (String state : List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "SKIPPED")) seedTargets(state, 1);
        } else if (!shape.equals("EMPTY")) seedTargets(shape, 2);
        inTransaction(() -> repository.beginStopping(fixture.executionId(), NOW));
        assertThat(inTransaction(() -> repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW))).isTrue();
        JsonNode completed = execution();
        assertThat(completed.path("status").asText()).isEqualTo(expectedStatus);
        assertThat(completed.path("accepted_targets").asInt()).isEqualTo(accepted);
        assertThat(completed.path("succeeded_targets").asInt()).isEqualTo(succeeded);
        assertThat(completed.path("failed_targets").asInt()).isEqualTo(failed);
        assertThat(completed.path("skipped_targets").asInt()).isEqualTo(skipped);
        assertThat(completed.path("total_targets").asInt()).isEqualTo(accepted + skipped);
        assertThat(completed.path("failure_summary").asText()).isEqualTo(STOP_REASON);
    }

    /** 无进展重新从本轮时刻算30秒，不能保留早已过期lease；有进展才清空允许立即续批。 */
    @Test
    void maintenanceLeaseUsesProcessingTimeAndProgress() throws SQLException {
        inTransaction(() -> repository.beginStopping(fixture.executionId(), NOW));
        assertThat(inTransaction(() -> repository.updateStoppingLease(fixture.executionId(), false, NOW))).isTrue();
        assertThat(Instant.parse(execution().path("lease_until").asText())).isEqualTo(NOW.plusSeconds(30));
        Instant later = NOW.plusSeconds(120);
        assertThat(inTransaction(() -> repository.updateStoppingLease(fixture.executionId(), false, later))).isTrue();
        assertThat(Instant.parse(execution().path("lease_until").asText())).isEqualTo(later.plusSeconds(30));
        assertThat(inTransaction(() -> repository.updateStoppingLease(fixture.executionId(), true, later))).isTrue();
        assertThat(execution().path("lease_until").isNull()).isTrue();
        assertThat(Instant.parse(execution().path("updated_at").asText())).isEqualTo(later);
    }

    /** 专用维护不能静默改变原EXPANDING/DISPATCHING/RUNNING条件或汇总。 */
    @ParameterizedTest
    @ValueSource(strings = {"EXPANDING", "DISPATCHING", "RUNNING"})
    void stoppingMaintenanceDoesNotChangeExistingStates(String state) throws SQLException {
        seedTargets("PENDING", 1);
        try (Connection owner = fixtureOwnerConnection()) { execute(owner, "UPDATE task_execution SET status=? WHERE id=?", state, fixture.executionId()); }
        Map<String, List<String>> before = facts();
        inTransaction(() -> {
            assertThat(repository.skipPendingForStopping(fixture.executionId(), 100, NOW)).isZero();
            assertThat(repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW)).isFalse();
            assertThat(repository.updateStoppingLease(fixture.executionId(), true, NOW)).isFalse();
            if (!state.equals("EXPANDING")) assertThat(repository.beginStopping(fixture.executionId(), NOW)).isFalse();
            return null;
        });
        assertThat(facts()).isEqualTo(before);
    }

    /** 上限是明确合同，不能静默clamp非法批量参数掩盖调用错误。 */
    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void invalidBatchLimitRejectsWithoutChangingFacts(int limit) throws SQLException {
        seedTargets("PENDING", 1);
        inTransaction(() -> repository.beginStopping(fixture.executionId(), NOW));
        Map<String, List<String>> before = facts();
        assertRoot(catchThrowable(() -> inTransaction(() -> repository.skipPendingForStopping(fixture.executionId(), limit, NOW))), IllegalArgumentException.class);
        assertThat(facts()).isEqualTo(before);
    }

    /** 真实begin和skip已执行后故障，调用者事务必须同时回滚两者；下一次真实调用可正常恢复。 */
    @Test
    void stoppingAndBatchWritesRollBackTogetherAndCanBeRetried() throws SQLException {
        seedTargets("PENDING", 2);
        Map<String, List<String>> before = facts();
        Throwable failure = catchThrowable(() -> inTransaction(() -> {
            repository.lockExecution(fixture.tenantId(), fixture.projectId(), fixture.executionId()).orElseThrow();
            assertThat(repository.beginStopping(fixture.executionId(), NOW)).isTrue();
            assertThat(repository.skipPendingForStopping(fixture.executionId(), 100, NOW)).isEqualTo(2);
            throw new InjectedFailure();
        }));
        assertThat(failure).isInstanceOf(InjectedFailure.class);
        assertThat(facts()).isEqualTo(before);
        inTransaction(() -> {
            assertThat(repository.beginStopping(fixture.executionId(), NOW)).isTrue();
            assertThat(repository.skipPendingForStopping(fixture.executionId(), 100, NOW)).isEqualTo(2);
            assertThat(repository.completeStoppingExecutionIfReady(fixture.executionId(), NOW)).isTrue();
            return null;
        });
        assertThat(execution().path("status").asText()).isEqualTo("FAILED");
    }

    /** 固定身份建立正常领域外键，本片不经TaskJobService以免提前验收尚未接线行为。 */
    private void seed() throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '停止持久验收租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '停止持久验收账号')", fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?, ?, '停止持久验收项目', ?)", fixture.projectId(), fixture.tenantId(), "task_stop_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO task_job(id,tenant_id,project_id,name,status,target_type,command_key,input,created_by,created_at,updated_at) VALUES (?, ?, ?, '停止持久验收任务', 'ACTIVE', 'ALL_DEVICES', 'reboot', '{}'::jsonb, ?, ?, ?)", fixture.jobId(), fixture.tenantId(), fixture.projectId(), fixture.accountId(), Timestamp.from(NOW), Timestamp.from(NOW));
            execute(owner, "INSERT INTO task_schedule(id,tenant_id,project_id,job_id,schedule_type,run_at,timezone,next_run_at,created_at,updated_at) VALUES (?, ?, ?, ?, 'ONCE', ?, 'Asia/Shanghai', ?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.jobId(), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
            execute(owner, "INSERT INTO task_execution(id,tenant_id,project_id,job_id,target_type,command_key,input,requested_by,trigger_type,status,expansion_cursor,started_at,created_at,updated_at) VALUES (?, ?, ?, ?, 'ALL_DEVICES', 'reboot', '{}'::jsonb, ?, 'MANUAL', 'EXPANDING', 'last-successful-cursor', ?, ?, ?)", fixture.executionId(), fixture.tenantId(), fixture.projectId(), fixture.jobId(), fixture.accountId(), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
            owner.commit();
        }
    }

    /** 目标ID/command_id是明确的仓储计数种子，非发送证明；所有PENDING故意带未来lease验证可停止。 */
    private void seedTargets(String state, int count) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            for (int index = 0; index < count; index++) execute(owner,
                    "INSERT INTO task_target(execution_id,tenant_id,project_id,device_id,status,command_id,lease_until) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    fixture.executionId(), fixture.tenantId(), fixture.projectId(), Uuid7.generate(), state,
                    List.of("ACCEPTED", "SUCCEEDED", "FAILED", "TIMED_OUT").contains(state) ? Uuid7.generate() : null,
                    Timestamp.from(Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)));
            execute(owner, "UPDATE task_execution SET total_targets=total_targets+? WHERE id=?", count, fixture.executionId());
            owner.commit();
        }
    }

    /** 原调用者先持真实三元组执行锁再写状态，遵循ADR0066锁序，禁止owner代替RLS执行被测仓储。 */
    private <T> T inTransaction(Supplier<T> action) {
        return withScope(fixture.projectId(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            appPid();
            repository.lockExecution(fixture.tenantId(), fixture.projectId(), fixture.executionId()).orElseThrow();
            return action.get();
        }));
    }

    /** 显式作用域只设置本例真实project，换项目仅用于RLS拒绝反例。 */
    private <T> T withScope(UUID projectId, Supplier<T> action) {
        TenantContext.set(new TenantScope(fixture.tenantId(), projectId, fixture.accountId()));
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return action.get();
        } finally { TenantContext.clear(); }
    }

    /** 实際事务连接必須APP_ROLE、同专库且非只读。 */
    private int appPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 完整物理行比较，数据库时间及所有业务字段都包含在内。 */
    private Map<String, List<String>> facts() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : TABLES) {
                List<String> values = new ArrayList<>();
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=? ORDER BY row_to_json(f)::text")) {
                    query.setQueryTimeout(5); query.setObject(1, fixture.projectId());
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) values.add(rows.getString(1)); }
                }
                result.put(table, List.copyOf(values));
            }
        }
        return result;
    }

    /** 快照中仅有本例执行，读取完整JSON避免遗漏原字段。 */
    private JsonNode execution() throws SQLException { return mapper.readTree(facts().get("task_execution").getFirst()); }

    /** 比较目标身份集合而非自然排序，状态字段变化不能影响顺序。 */
    private List<String> targetIds(Map<String, List<String>> facts) {
        return facts.get("task_target").stream().map(row -> mapper.readTree(row).path("device_id").asText()).sorted().toList();
    }

    /** 独立owner确认每批实际持久化数量。 */
    private long countTargetState(String state) throws SQLException {
        return facts().get("task_target").stream().map(mapper::readTree).filter(row -> row.path("status").asText().equals(state)).count();
    }

    /** Repository翻译可能包装参数/事务异常，按真实根因而非绑定某一Spring包装类。 */
    private void assertRoot(Throwable failure, Class<? extends Throwable> type) {
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        assertThat(failure).isInstanceOf(type);
    }

    /** Flyway、CONTROL、DATA和owner均逐连接核验，不仅比较配置字符串。 */
    private void verifyIsolation() throws SQLException {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        for (Object bean : List.of(unusedRestQuota, unusedTaskScanner, unusedNotifications, unusedBackfill)) assertThat(mockingDetails(bean).isMock()).isTrue();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
        }
        try (Connection migration = DriverManager.getConnection(environment.getRequiredProperty("spring.flyway.url"), environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password")); Connection owner = fixtureOwnerConnection()) {
            verifyOwner(migration); verifyOwner(owner);
            for (String table : TABLES) try (PreparedStatement query = owner.prepareStatement("SELECT relkind::text FROM pg_class WHERE oid=?::regclass")) {
                query.setQueryTimeout(5); query.setString(1, table);
                try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isIn("r", "p"); }
            }
        }
    }

    /** owner及Flyway必须同库同用户，避免混用共享库保留假阳性。 */
    private void verifyOwner(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT current_database(),current_user")) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME); assertThat(rows.getString(2)).isEqualTo(STOPPING_POSTGRES.getUsername()); }
        }
    }

    /** 精确清理可变任务夹具以隔离下一例全局claim；不删除项目祖先或不可变审计。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            for (String table : List.of("task_target", "task_execution", "task_schedule", "task_job")) execute(owner, "DELETE FROM " + table + " WHERE project_id=?", fixture.projectId());
            owner.commit();
        }
    }

    /** 所有owner夹具真实落到本片专库。 */
    @Override protected Connection fixtureOwnerConnection() throws SQLException { return DriverManager.getConnection(DATABASE_URL, STOPPING_POSTGRES.getUsername(), STOPPING_POSTGRES.getPassword()); }

    /** 所有辅助SQL有界且参数化，防止数据库锁让验收永久卡住。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 静态启动便于Registrar提供实际端口，清理由OwnedTestContainers在本类上下文物理关闭后负责。 */
    private static String startDatabase() { STOPPING_POSTGRES.start(); return STOPPING_POSTGRES.getJdbcUrl(); }

    /** 与其他专PG生命周期夹具同配置，不引入新的凭据、预算或业务替身。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 地址和后台开关覆盖父动态配置，原业务组件仍正常装配。 */
        @Bean DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }

    /** 业务写后明确内部故障，不与Spring参数异常翻译混淆。 */
    private static final class InjectedFailure extends RuntimeException { }

    /** 已保存任务事实身份，不从账号所属租户推断项目之外的数据。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID jobId, UUID executionId) { }
}
