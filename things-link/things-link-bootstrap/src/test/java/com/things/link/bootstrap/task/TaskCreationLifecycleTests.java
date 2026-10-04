package com.things.link.bootstrap.task;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskJobCommand;
import com.things.link.task.application.TaskJobService;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.task.domain.TaskJob;
import com.things.link.task.domain.TaskJobRepository;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
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
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0067决策1/2的创建执行资格验收：原项目SHARE覆盖日额度、计划CAS和执行创建。
 * 原run/processDueSchedule代理自行开启事务，真实领取单独提交；不把额度快照替换为成功桩。
 * 旧生产反例已独立归档；冻结先提交应拒绝/停止计划，许可先取得则必须阻塞冻结至本轮提交。
 */
@Import(TaskCreationLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"LIFECYCLE_POSTGRES"})
class TaskCreationLifecycleTests extends AbstractIntegrationTest {
    /** 全局claim必须物理专库隔离，不能靠随机projectId忽略其他候选。 */
    private static final String DATABASE_NAME = "task_creation_" + UUID.randomUUID().toString().replace("-", "");
    /** 与其他真实验收保持同镜像、角色及迁移版本。 */
    private static final PostgreSQLContainer<?> LIFECYCLE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Registrar读取之前启动专库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 七张完整物理表同时观察调度变化、执行新增及非法命令副作用。 */
    private static final List<String> FACT_TABLES = List.of("task_job", "task_schedule", "task_execution", "task_target",
            "ts_device_command", "ts_device_command_attempt", "sys_outbox_event");
    /** 父runner使用共享库，专库必须抑制该路径。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedRestQuotaRelaxation;
    /** 自动全局任务worker停用，测试显式调用真实claim和service。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTaskScanner;
    /** 无关通知worker不得抢占当前数据库事实。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 无关聚合扫描器不参与创建执行资格。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 未测试命令超时，新事实必须保持可独立观测。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedCommandTimeoutScanner;
    /** 原服务代理；spy只记录原事务PID，不制造事务。 */
    @MockitoSpyBean private TaskJobService tasks;
    /** 原quota真实查询结果及许可持锁后的观察点。 */
    @MockitoSpyBean private ProjectDailyQuotaDecisionService dailyQuota;
    /** 真实OWNER删除，不用SQL模拟删除服务合同。 */
    @Autowired private ProjectService projects;
    /** 真实SECURITY DEFINER全局领取函数。 */
    @Autowired private TaskJobRepository repository;
    /** 同事务真实APP数据库身份与PID证据。 */
    @Autowired private JdbcTemplate jdbc;
    /** Flyway连接落点核对。 */
    @Autowired private Environment environment;
    /** 生产命令输入对象类型。 */
    @Autowired private ObjectMapper mapper;
    /** 每例独立OWNER/项目/合法设备类型。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 记录原入口物理事务，防止quota被挪到另一个短事务。 */
    private final AtomicInteger entryPid = new AtomicInteger();
    /** 真实额度返回次数，而非mock成功次数。 */
    private final AtomicInteger normalQuotaReturns = new AtomicInteger();
    /** 既有生命周期用例默认为NORMAL；额度入口矩阵只观察真实返回，不替换决策。 */
    private QuotaStatus expectedQuotaStatus = QuotaStatus.NORMAL;
    /** 许可先持有用例的quota后观察器，其余用例不阻塞原线程。 */
    private Runnable afterQuota = () -> { };
    /** 原SQL锁超时用例观察原事务已实际开始，不缩短生产五秒预算。 */
    private final CompletableFuture<Integer> entered = new CompletableFuture<>();

    /** 先核对专库与角色，再准备真实owner和合法任务类型。 */
    @BeforeEach
    void prepare() throws Exception {
        verifyIsolation();
        seed();
        installObservers();
    }

    /** SaaS快照只管新批量受理：软限允许，硬限/降级原事务拒绝，不将日量声称为逐条原子账。 */
    @ParameterizedTest(name = "{0}: {1}, used={2}")
    @org.junit.jupiter.params.provider.CsvSource({
            "MANUAL, SOFT_LIMIT, 8", "MANUAL, HARD_LIMIT, 10", "MANUAL, DEGRADED, 12",
            "SCHEDULED, SOFT_LIMIT, 8", "SCHEDULED, HARD_LIMIT, 10", "SCHEDULED, DEGRADED, 12"
    })
    void realDailyQuotaControlsNewManualAndScheduledBatches(Entry entry, QuotaStatus status, long used)
            throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, entry == Entry.SCHEDULED);
        TaskJobRepository.DueSchedule due = entry == Entry.SCHEDULED ? claimSchedule(job.id()) : null;
        UUID policyId = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            JdbcTemplate sql = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(owner, true));
            UUID oldPolicy = sql.queryForObject("SELECT quota_policy_id FROM sys_tenant WHERE id=?", UUID.class, fixture.tenantId());
            sql.update("INSERT INTO sys_quota_policy(id,code,downlink_message_daily_limit) VALUES (?,?,10)",
                    policyId, "tq" + policyId.toString().replace("-", "").substring(0, 20));
            try {
                sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?", policyId, fixture.tenantId());
                sql.update("""
                        INSERT INTO sys_usage_counter_daily(id,tenant_id,project_id,usage_date,metric,used_value)
                        VALUES (?,?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,'DOWNLINK_MESSAGE',?)
                        """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), used);
                var fact = sql.queryForMap("""
                        SELECT limit_value,tenant_used_value FROM trusted_project_daily_quota_decision(
                            ?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,'DOWNLINK_MESSAGE')
                        """, fixture.tenantId(), fixture.projectId());
                assertThat(fact.get("limit_value")).isEqualTo(10L);
                assertThat(((Number) fact.get("tenant_used_value")).longValue()).isEqualTo(used);
                var decision = dailyQuota.decisionTrustedProject(fixture.tenantId(), fixture.projectId(),
                        com.things.link.project.application.QuotaMetric.DOWNLINK_MESSAGE);
                assertThat(decision.disabled()).isFalse();
                assertThat(decision.status()).isEqualTo(status);
                expectedQuotaStatus = status;
                Map<String, List<String>> before = facts();
                if (status != QuotaStatus.SOFT_LIMIT) {
                    Throwable failure = catchThrowable(() -> invoke(entry, job, due));
                    assertThat(failure).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(40022);
                    assertThat(facts()).as("拒绝不推进计划、不建执行/目标/命令/Outbox").isEqualTo(before);
                    assertThat(normalQuotaReturns).hasValue(1);
                    // 只扩大本次测试策略，不回退单调用量；不冒充购买资源包或真实跨日恢复。
                    sql.update("UPDATE sys_quota_policy SET downlink_message_daily_limit=? WHERE id=?",
                            used == 10 ? 12L : 15L, policyId);
                    assertThat(dailyQuota.decisionTrustedProject(fixture.tenantId(), fixture.projectId(),
                            com.things.link.project.application.QuotaMetric.DOWNLINK_MESSAGE).status())
                            .isEqualTo(QuotaStatus.SOFT_LIMIT);
                    expectedQuotaStatus = QuotaStatus.SOFT_LIMIT;
                }
                invoke(entry, job, due);
                Map<String, List<String>> accepted = facts();
                assertThat(accepted.get("task_execution")).hasSize(1);
                assertThat(mapper.readTree(accepted.get("task_execution").getFirst()).path("status").asText())
                        .isEqualTo("EXPANDING");
                for (String table : List.of("task_target", "ts_device_command", "ts_device_command_attempt", "sys_outbox_event"))
                    assertThat(accepted.get(table)).as(table).isEqualTo(before.get(table));
                if (entry == Entry.SCHEDULED) {
                    int calls = normalQuotaReturns.get();
                    process(due);
                    assertThat(facts()).as("同计划触发点重放不重复受理").isEqualTo(accepted);
                    assertThat(normalQuotaReturns).hasValue(calls);
                }
            } finally {
                sql.update("DELETE FROM sys_usage_counter_daily WHERE project_id=? AND metric='DOWNLINK_MESSAGE'", fixture.projectId());
                sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?", oldPolicy, fixture.tenantId());
                sql.update("DELETE FROM sys_quota_policy WHERE id=?", policyId);
            }
        }
    }

    /** 真实归档/删除先提交，手动入口必须明确拒绝且未访问日额度或新增七表事实。 */
    @ParameterizedTest
    @EnumSource(Freeze.class)
    void manualCreationRejectsCommittedFreezeBeforeQuota(Freeze freeze) throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, false);
        freeze(freeze);
        Map<String, List<String>> before = facts();
        Throwable failure = catchThrowable(() -> asOwner(() -> tasks.run(fixture.projectId(), job.id())));
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(freeze == Freeze.ARCHIVED ? 50017 : 50001);
        assertThat(normalQuotaReturns).hasValue(0);
        assertThat(facts()).isEqualTo(before);
    }

    /** 冻结调度只停止已领取的next_run_at，保留定义、历史及所有非调度事实，旧Due重复无副作用。 */
    @ParameterizedTest
    @EnumSource(Freeze.class)
    void frozenScheduleStopsWithoutQuotaOrNewExecution(Freeze freeze) throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, true);
        TaskJobRepository.DueSchedule due = claimSchedule(job.id());
        freeze(freeze);
        Map<String, List<String>> before = facts();
        process(due);
        assertStoppedSchedule(before);
        Map<String, List<String>> stopped = facts();
        process(due);
        assertThat(facts()).isEqualTo(stopped);
        assertThat(normalQuotaReturns).hasValue(0);
        assertThat(inData(() -> repository.claimDueSchedules(100))).isEmpty();
    }

    /**
     * 原日额度真实NORMAL之后暂停原事务；另一连接的ARCHIVED写入必须被项目SHARE真实阻塞。
     * 释放后本轮执行提交在先、归档提交在后，不再使用同步归档导致自锁的旧诊断注入方式。
     */
    @ParameterizedTest
    @EnumSource(Entry.class)
    void permissionHeldThroughCreationBlocksArchiveUntilOriginalCommit(Entry entry) throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, entry == Entry.SCHEDULED);
        TaskJobRepository.DueSchedule due = entry == Entry.SCHEDULED ? claimSchedule(job.id()) : null;
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> quotaReached = new CompletableFuture<>();
        CompletableFuture<Integer> freezerPid = new CompletableFuture<>();
        afterQuota = () -> { quotaReached.complete(actualAppPid()); awaitLatch(release); };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Throwable> created = workers.submit(() -> catchThrowable(() -> invoke(entry, job, due)));
        Future<?> frozen = null;
        try {
            int creatorPid = quotaReached.get(10, TimeUnit.SECONDS);
            frozen = workers.submit(() -> {
                try (Connection owner = fixtureOwnerConnection()) {
                    owner.setAutoCommit(false);
                    freezerPid.complete(ownerPid(owner));
                    execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                    owner.commit();
                    return null;
                }
            });
            int blockerPid = freezerPid.get(5, TimeUnit.SECONDS);
            assertThat(blockerPid).isNotEqualTo(creatorPid);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() -> assertBlockedBy(blockerPid, creatorPid));
            assertThat(facts().get("task_execution")).isEmpty();
            assertThat(frozen.isDone()).isFalse();
            release.countDown();
            assertThat(created.get(10, TimeUnit.SECONDS)).isNull();
            frozen.get(10, TimeUnit.SECONDS);
            assertArchivedCommitted();
            assertThat(normalQuotaReturns).hasValue(1);
            Map<String, List<String>> committed = facts();
            assertThat(committed.get("task_execution")).hasSize(1);
            assertThat(mapper.readTree(committed.get("task_execution").getFirst()).path("status").asText()).isEqualTo("EXPANDING");
            assertThat(committed.get("task_target")).isEmpty();
            assertThat(committed.get("ts_device_command")).isEmpty();
            assertThat(committed.get("sys_outbox_event")).isEmpty();
        } finally {
            release.countDown();
            finishWorkers(workers);
        }
    }

    /** 正常手動每次允许独立运行；调度同一fireAt只建立一次，旧Due在CAS已推进后不再读取quota。 */
    @ParameterizedTest
    @EnumSource(Entry.class)
    void activeCreationRetainsManualRepeatAndScheduledIdempotency(Entry entry) throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, entry == Entry.SCHEDULED);
        TaskJobRepository.DueSchedule due = entry == Entry.SCHEDULED ? claimSchedule(job.id()) : null;
        invoke(entry, job, due);
        Map<String, List<String>> first = facts();
        assertThat(first.get("task_execution")).hasSize(1);
        invoke(entry, job, due);
        if (entry == Entry.MANUAL) {
            assertThat(facts().get("task_execution")).hasSize(2);
            assertThat(normalQuotaReturns).hasValue(2);
        } else {
            assertThat(facts()).isEqualTo(first);
            assertThat(normalQuotaReturns).hasValue(1);
            assertThat(schedule(first).path("next_run_at").isNull()).isTrue();
            assertThat(inData(() -> repository.claimDueSchedules(100))).isEmpty();
        }
    }

    /** 错身份/旧fireAt不许可、不quota、不改调度；空字段必须查询前报错，原claim仍可随后正常处理。 */
    @Test
    void invalidScheduleIdentityAndOldFireAtHaveNoSideEffects() throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, true);
        TaskJobRepository.DueSchedule due = claimSchedule(job.id());
        Map<String, List<String>> before = facts();
        for (TaskJobRepository.DueSchedule wrong : List.of(
                new TaskJobRepository.DueSchedule(UUID.randomUUID(), due.projectId(), due.jobId(), due.scheduledFireAt()),
                new TaskJobRepository.DueSchedule(due.tenantId(), UUID.randomUUID(), due.jobId(), due.scheduledFireAt()),
                new TaskJobRepository.DueSchedule(due.tenantId(), due.projectId(), UUID.randomUUID(), due.scheduledFireAt()),
                new TaskJobRepository.DueSchedule(due.tenantId(), due.projectId(), due.jobId(), due.scheduledFireAt().minusSeconds(1))))
            process(wrong);
        List<TaskJobRepository.DueSchedule> missing = new ArrayList<>();
        missing.add(null);
        missing.add(new TaskJobRepository.DueSchedule(null, due.projectId(), due.jobId(), due.scheduledFireAt()));
        missing.add(new TaskJobRepository.DueSchedule(due.tenantId(), null, due.jobId(), due.scheduledFireAt()));
        missing.add(new TaskJobRepository.DueSchedule(due.tenantId(), due.projectId(), null, due.scheduledFireAt()));
        missing.add(new TaskJobRepository.DueSchedule(due.tenantId(), due.projectId(), due.jobId(), null));
        for (TaskJobRepository.DueSchedule incomplete : missing)
            assertThat(catchThrowable(() -> process(incomplete))).isInstanceOf(IllegalArgumentException.class);
        assertThat(facts()).isEqualTo(before);
        assertThat(normalQuotaReturns).hasValue(0);
        process(due);
        assertThat(facts().get("task_execution")).hasSize(1);
    }

    /** 原五秒SQL预算下项目锁超时必须整体回滚；释放后同一手动请求或过期重领取计划可以恢复。 */
    @ParameterizedTest
    @EnumSource(Entry.class)
    void originalProjectLockTimeoutRollsBackAndAllowsRetry(Entry entry) throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, entry == Entry.SCHEDULED);
        TaskJobRepository.DueSchedule due = entry == Entry.SCHEDULED ? claimSchedule(job.id()) : null;
        Map<String, List<String>> before = facts();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "UPDATE sys_project SET status=status WHERE id=?", fixture.projectId());
            int lockerPid = ownerPid(owner);
            Future<Throwable> blocked = worker.submit(() -> catchThrowable(() -> invoke(entry, job, due)));
            int writerPid = entered.get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20))
                    .untilAsserted(() -> assertBlockedBy(writerPid, lockerPid));
            Throwable failure = blocked.get(10, TimeUnit.SECONDS);
            assertThat(sqlState(failure)).isEqualTo("57014");
            assertThat(facts()).isEqualTo(before);
            assertThat(normalQuotaReturns).hasValue(0);
            owner.rollback();
        } finally { finishWorkers(worker); }
        if (entry == Entry.SCHEDULED) {
            expireScheduleLease(job.id());
            process(claimSchedule(job.id()));
        } else invoke(entry, job, null);
        assertThat(facts().get("task_execution")).hasSize(1);
    }

    /** quota后并发推进计划使真实CAS失败，过期Due不得因为旧job快照仍建立execution。 */
    @Test
    void concurrentScheduleAdvanceAfterQuotaPreventsStaleCreation() throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, true);
        TaskJobRepository.DueSchedule due = claimSchedule(job.id());
        Map<String, List<String>> before = facts();
        afterQuota = () -> {
            try (Connection owner = fixtureOwnerConnection()) {
                execute(owner, "UPDATE task_schedule SET next_run_at=NULL,lease_until=NULL,updated_at=now() WHERE job_id=?", job.id());
            } catch (SQLException failure) { throw new IllegalStateException("验收并发计划推进失败", failure); }
        };
        process(due);
        assertStoppedSchedule(before);
        assertThat(normalQuotaReturns).hasValue(1);
        Map<String, List<String>> stopped = facts();
        process(due);
        assertThat(normalQuotaReturns).hasValue(1);
        assertThat(facts()).isEqualTo(stopped);
    }

    /** schedule CAS真实推进后，execution INSERT触发实际PG错误；CAS和新增执行同事务回滚后可重新领取。 */
    @Test
    void scheduledInsertSqlFailureRollsBackPriorScheduleCas() throws Exception {
        TaskJob job = createJob(TaskJob.ScheduleType.ONCE, true);
        TaskJobRepository.DueSchedule due = claimSchedule(job.id());
        Map<String, List<String>> before = facts();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, """
                    CREATE FUNCTION task_creation_test_reject_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF EXISTS (SELECT 1 FROM task_schedule WHERE job_id=NEW.job_id AND next_run_at IS NULL) THEN
                            RAISE EXCEPTION '验收：计划CAS后的执行INSERT失败' USING ERRCODE='23514';
                        END IF;
                        RETURN NEW;
                    END $$
                    """);
            execute(owner, "CREATE TRIGGER task_creation_test_reject_insert AFTER INSERT ON task_execution "
                    + "FOR EACH ROW EXECUTE FUNCTION task_creation_test_reject_insert()");
        }
        try {
            Throwable failure = catchThrowable(() -> process(due));
            assertThat(sqlState(failure)).isEqualTo("23514");
            assertThat(normalQuotaReturns).hasValue(1);
            assertThat(facts()).isEqualTo(before);
        } finally { dropInsertFailureTrigger(); }
        expireScheduleLease(job.id());
        process(claimSchedule(job.id()));
        assertThat(facts().get("task_execution")).hasSize(1);
        assertThat(schedule(facts()).path("next_run_at").isNull()).isTrue();
    }

    /** 恢复ACTIVE只恢复项目资格，不自动恢复NULL计划；CRON显式enable和ONCE显式update各保留原语义。 */
    @ParameterizedTest
    @EnumSource(value = TaskJob.ScheduleType.class, names = {"ONCE", "CRON"})
    void restoringProjectDoesNotReviveStoppedScheduleWithoutExplicitAction(TaskJob.ScheduleType type) throws Exception {
        TaskJob job = createJob(type, true);
        TaskJobRepository.DueSchedule due = claimSchedule(job.id());
        freeze(Freeze.ARCHIVED);
        process(due);
        Map<String, List<String>> stopped = facts();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ACTIVE' WHERE id=?", fixture.projectId());
        }
        assertThat(inData(() -> repository.claimDueSchedules(100))).isEmpty();
        process(due);
        assertThat(facts()).isEqualTo(stopped);
        assertThat(normalQuotaReturns).hasValue(0);
        TaskJob enabled = asOwner(() -> tasks.enable(fixture.projectId(), job.id()));
        if (type == TaskJob.ScheduleType.CRON) {
            assertThat(enabled.nextRunAt()).isNotNull();
            assertThat(enabled.nextRunAt()).isAfter(Instant.now().minusSeconds(1));
        } else {
            assertThat(enabled.nextRunAt()).isNull();
            assertThat(inData(() -> repository.claimDueSchedules(100))).isEmpty();
            Instant newRunAt = Instant.now().plusSeconds(3600);
            TaskJob updated = asOwner(() -> tasks.update(fixture.projectId(), job.id(), new TaskJobCommand(
                    enabled.name(), enabled.description(), TaskJob.ScheduleType.ONCE, newRunAt, null, null,
                    enabled.targetType(), enabled.targetGroupId(), enabled.commandKey(), mapper.readTree(enabled.inputJson()),
                    true, enabled.version())));
            assertThat(updated.nextRunAt()).isEqualTo(newRunAt);
        }
        assertThat(facts().get("task_execution")).isEmpty();
        asOwner(() -> tasks.run(fixture.projectId(), job.id()));
        assertThat(facts().get("task_execution")).hasSize(1);
    }

    /** 通过原OWNER服务创建合法任务；CRON用精确私有fixture移动到期点，模拟时钟已经到期。 */
    private TaskJob createJob(TaskJob.ScheduleType type, boolean due) throws SQLException {
        TaskJob job = asOwner(() -> tasks.create(fixture.projectId(), new TaskJobCommand("任务创建冻结验收", null,
                type, type == TaskJob.ScheduleType.ONCE ? Instant.now().plusSeconds(due ? -60 : 86_400) : null,
                type == TaskJob.ScheduleType.CRON ? "0 * * * * *" : null, null, TaskJob.TargetType.ALL_DEVICES,
                null, "reboot", mapper.createObjectNode(), true, null)));
        if (type == TaskJob.ScheduleType.CRON && due) try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE task_schedule SET next_run_at=now()-interval '1 minute' WHERE job_id=?", job.id());
        }
        return job;
    }

    /** 两个入口都由原代理自行开启事务，不在测试外面包TransactionTemplate。 */
    private void invoke(Entry entry, TaskJob job, TaskJobRepository.DueSchedule due) {
        if (entry == Entry.MANUAL) asOwner(() -> tasks.run(fixture.projectId(), job.id()));
        else process(due);
    }

    /** DATA范围只选择池；原后台处理方法自行创建业务事务。 */
    private void process(TaskJobRepository.DueSchedule due) {
        inData(() -> { tasks.processDueSchedule(due); return null; });
    }

    /** 真实OWNER删除沿已有成员保全；归档没有API，明确只改已有status字段。 */
    private void freeze(Freeze freeze) throws SQLException {
        if (freeze == Freeze.DELETE) asOwner(() -> { projects.delete(fixture.projectId()); return null; });
        else try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
    }

    /** 调度拒绝只准许next_run_at/lease/updated_at维护，原定义和七表其余事实保持不变。 */
    private void assertStoppedSchedule(Map<String, List<String>> before) throws SQLException {
        Map<String, List<String>> after = facts();
        for (String table : FACT_TABLES) if (!table.equals("task_schedule"))
            assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        ObjectNode expected = (ObjectNode) schedule(before).deepCopy();
        ObjectNode actual = (ObjectNode) schedule(after).deepCopy();
        for (String field : List.of("next_run_at", "lease_until", "updated_at")) { expected.remove(field); actual.remove(field); }
        assertThat(actual).isEqualTo(expected);
        assertThat(schedule(after).path("next_run_at").isNull()).isTrue();
        assertThat(schedule(after).path("lease_until").isNull()).isTrue();
    }

    /** 每例只创建一个计划，避免按首行掩盖额外调度。 */
    private JsonNode schedule(Map<String, List<String>> facts) {
        assertThat(facts.get("task_schedule")).hasSize(1);
        return mapper.readTree(facts.get("task_schedule").getFirst());
    }

    /** 仅模拟已持久领取租约自然到期，不改变业务触发点或任务定义。 */
    private void expireScheduleLease(UUID jobId) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE task_schedule SET lease_until=now()-interval '1 second' WHERE job_id=?", jobId);
        }
    }

    /** 隔离专库的验收触发器必须在故障恢复前移除；不关闭任何生产触发器。 */
    private void dropInsertFailureTrigger() throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "DROP TRIGGER IF EXISTS task_creation_test_reject_insert ON task_execution");
            execute(owner, "DROP FUNCTION IF EXISTS task_creation_test_reject_insert()");
        }
    }

    /** 原生SQLSTATE从完整异常链读取，不能把业务拒绝或通用RuntimeException当SQL超时。 */
    private String sqlState(Throwable failure) {
        for (Throwable item = failure; item != null; item = item.getCause())
            if (item instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    /** 观察真实PG阻塞关系，而不是根据Future未完成或睡眠推断持锁。 */
    private void assertBlockedBy(int waiterPid, int blockerPid) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT ?=ANY(pg_blocking_pids(?))")) {
            query.setQueryTimeout(5);
            query.setInt(1, blockerPid); query.setInt(2, waiterPid);
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue(); }
        }
    }

    /** 非生产owner连接PID只用于锁证据，不用于运行服务。 */
    private int ownerPid(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT pg_backend_pid()")) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getInt(1); }
        }
    }

    /** 十秒屏障只用于测试控制，finally必释放，不能改变生产单SQL预算。 */
    private void awaitLatch(CountDownLatch release) {
        try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("验收屏障被中断", failure); }
    }

    /** 完成或失败都回收本例线程，下一例不能继承仍持数据库锁的worker。 */
    private void finishWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 原全局claim在autocommit连接提交，随后独立owner必须看到仍有效的调度lease。 */
    private TaskJobRepository.DueSchedule claimSchedule(UUID jobId) throws SQLException {
        TaskJobRepository.DueSchedule due = inData(() -> {
            List<TaskJobRepository.DueSchedule> claimed = repository.claimDueSchedules(100);
            assertThat(claimed).hasSize(1);
            return claimed.getFirst();
        });
        assertThat(due.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(due.projectId()).isEqualTo(fixture.projectId());
        assertThat(due.jobId()).isEqualTo(jobId);
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT lease_until IS NOT NULL AND lease_until>now() FROM task_schedule WHERE job_id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, jobId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.next()).isFalse();
            }
        }
        return due;
    }

    /** 目标spy处于原@Transactional代理里面，原方法结果始终由callRealMethod决定。 */
    private void installObservers() {
        assertThat(AopUtils.isAopProxy(tasks)).isTrue();
        TaskJobService target = AopTestUtils.getUltimateTargetObject(tasks);
        assertThat(mockingDetails(target).isSpy()).isTrue();
        doAnswer(invocation -> {
            entryPid.set(actualAppPid());
            entered.complete(entryPid.get());
            try { return invocation.callRealMethod(); }
            finally { entryPid.set(0); }
        }).when(target).run(any(), any());
        doAnswer(invocation -> {
            entryPid.set(actualAppPid());
            entered.complete(entryPid.get());
            try { return invocation.callRealMethod(); }
            finally { entryPid.set(0); }
        }).when(target).processDueSchedule(any());
        assertThat(AopUtils.isAopProxy(dailyQuota)).isTrue();
        ProjectDailyQuotaDecisionService quota = AopTestUtils.getUltimateTargetObject(dailyQuota);
        assertThat(mockingDetails(quota).isSpy()).isTrue();
        doAnswer(invocation -> {
            assertThat(actualAppPid()).isEqualTo(entryPid.get());
            assertThat((UUID) invocation.getArgument(0)).isEqualTo(fixture.tenantId());
            assertThat((UUID) invocation.getArgument(1)).isEqualTo(fixture.projectId());
            Object result = invocation.callRealMethod();
            assertThat(result).isEqualTo(expectedQuotaStatus);
            normalQuotaReturns.incrementAndGet();
            afterQuota.run();
            return result;
        }).when(quota).decideTrustedProject(any(), any(), any());
    }

    /** 原事务必须是专库APP非只读事务；不能用owner或bypass连接运行生产入口。 */
    private int actualAppPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_setting('transaction_read_only')", String.class)).isEqualTo("off");
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 第三次独立owner读取已提交状态，不能只相信归档事务自己的未提交行。 */
    private void assertArchivedCommitted() throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT status,deleted_at FROM sys_project WHERE id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("ARCHIVED");
                assertThat(rows.getTimestamp(2)).isNull();
                assertThat(rows.next()).isFalse();
            }
        }
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

    /** HTTP身份仅用于真实create/run；后台入口从无TenantContext的DATA工作线程合同进入。 */
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
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '任务创建许可验收租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '任务验收OWNER')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '任务创建许可验收', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "task_creation_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'task_creation_type', '任务验收类型', 'STANDARD', 'DIRECT', 'PUBLISHED')",
                    fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?, ?, ?, ?, 'reboot', '重启', '{}'::jsonb, '{}'::jsonb, 30)",
                    Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
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
        dropInsertFailureTrigger();
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

    /** 容器生命周期由本类OwnedTestContainers负责；不关闭其他上下文的共享容器。 */
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


    /** 两种确定冻结由不同持久字段表达。 */
    private enum Freeze {
        /** 已归档、物理行仍可读。 */ ARCHIVED,
        /** OWNER软删除、物理行与成员保全。 */ DELETE
    }

    /** 两个原入口独立取证，不把手动创建的权限推定为后台调度身份。 */
    private enum Entry {
        /** 真实OWNER手动运行。 */ MANUAL,
        /** 真实到期调度领取后处理。 */ SCHEDULED
    }

    /** 独立的租户、项目、OWNER和合法类型关系。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId) { }
}
