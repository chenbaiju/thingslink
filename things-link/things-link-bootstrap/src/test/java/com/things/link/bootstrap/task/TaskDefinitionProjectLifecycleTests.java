package com.things.link.bootstrap.task;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskJobCommand;
import com.things.link.task.application.TaskJobService;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.task.domain.TaskExecution;
import com.things.link.task.domain.TaskJob;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** S12-P0-5e5d：真实APP RLS下验收任务定义及调度配置的项目冻结和双写原子性。 */
@Import(TaskDefinitionProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"TASK_POSTGRES"})
class TaskDefinitionProjectLifecycleTests extends AbstractIntegrationTest {

    /** task schedule使用全局领取函数，随机ID不能替代独立物理数据库隔离。 */
    private static final String DATABASE_NAME = "task_definition_lifecycle_"
            + UUID.randomUUID().toString().replace("-", "");
    /** PostgreSQL/Timescale版本与全仓真实验收一致。 */
    private static final PostgreSQLContainer<?> TASK_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    /** Spring、Flyway和owner观察必须落在同一专库。 */
    private static final String DATABASE_URL = startDatabase();

    /** 父测试runner不知道专库，禁止它修改共享数据库的配额策略。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 本类不运行到期调度或执行worker。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskScanner;
    /** 通知全表领取与任务定义管理正交。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 属性回补不得污染专库事务观察。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 命令超时器不参与任务定义与schedule配置。 */
    @MockitoBean(enforceOverride = true)
    private DeviceCommandTimeoutScanner unusedCommandScanner;

    /** 五个Console定义写入口保留真实事务、仓储与cron解释。 */
    @Autowired
    private TaskJobService tasks;
    /** 角色、owner tenant、时区查询保留真实实现，spy只建立确定竞争窗口。 */
    @MockitoSpyBean
    private ProjectService projects;
    /** 持续许可保留真实SHARE锁，spy只在许可取得后暂停。 */
    @MockitoSpyBean
    private ProjectLifecycleAccessService lifecycle;
    /** APP数据库连接用于核验RLS角色、事务与后端PID。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** 任务input必须保持真实JSON对象。 */
    @Autowired
    private ObjectMapper mapper;

    /** 默认角色查询不暂停。 */
    private Runnable afterRole = () -> { };
    /** 默认持续许可取得后不暂停。 */
    private Runnable afterPermit = () -> { };
    /** 默认项目时区/owner tenant查询后不注入schedule竞争。 */
    private Runnable afterSchedulingContext = () -> { };

    /** 核对专库、APP角色和RC隔离，并在三个真实方法返回后安装观察点。 */
    @BeforeEach
    void prepare() {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        for (Object disabled : List.of(unusedQuotaRunner, unusedTaskScanner, unusedNotificationCoordinator,
                unusedBackfillScanner, unusedCommandScanner)) {
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        }
        ProjectService projectTarget = AopTestUtils.getUltimateTargetObject(projects);
        doAnswer(invocation -> {
            Object role = invocation.callRealMethod();
            afterRole.run();
            return role;
        }).when(projectTarget).requireRoleInProject(any());
        doAnswer(invocation -> {
            Object context = invocation.callRealMethod();
            afterSchedulingContext.run();
            return context;
        }).when(projectTarget).requireSchedulingContext(any());
        ProjectLifecycleAccessService lifecycleTarget = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            afterPermit.run();
            return result;
        }).when(lifecycleTarget).requireActiveForWrite(any(), any());
    }

    /** 跨租户ADMIN创建时，job和schedule归属owner tenant，createdBy仍记录实际协作者。 */
    @Test
    void crossTenantAdminCreatesJobAndScheduleWithPersistentOwnerFacts() throws Exception {
        Fixture fixture = seed();

        TaskJob created = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> tasks.create(fixture.projectId(), createCommand("跨租户任务")));
        CreatedFacts facts = createdFacts(created.id());

        assertSoftly(softly -> {
            softly.assertThat(created.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(created.createdBy()).isEqualTo(fixture.adminId());
            softly.assertThat(created.timezone()).isEqualTo("Asia/Shanghai");
            softly.assertThat(facts.jobTenant()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(facts.scheduleTenant()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(facts.createdBy()).isEqualTo(fixture.adminId());
            softly.assertThat(facts.timezone()).isEqualTo("Asia/Shanghai");
        });
    }

    /** ARCHIVED保留任务详情、列表与执行历史读取，三类读取不得改写定义或计划事实。 */
    @Test
    void archivedProjectKeepsDefinitionsAndExecutionHistoryReadable() throws Exception {
        Fixture fixture = seed();
        archive(fixture);
        List<String> before = facts(fixture);

        TaskJob one = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> tasks.get(fixture.projectId(), fixture.activeJobId()));
        List<TaskJob> listed = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> tasks.list(fixture.projectId()));
        List<TaskExecution> executions = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> tasks.executions(fixture.projectId(), fixture.activeJobId()));
        List<String> after = facts(fixture);

        assertSoftly(softly -> {
            softly.assertThat(one.id()).isEqualTo(fixture.activeJobId());
            softly.assertThat(listed).extracting(TaskJob::id)
                    .containsExactlyInAnyOrder(fixture.activeJobId(), fixture.pausedJobId());
            softly.assertThat(executions).extracting(TaskExecution::id).containsExactly(fixture.executionId());
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** ARCHIVED下五个定义写入口都必须50017，任务、计划和既有执行历史完整不变。 */
    @ParameterizedTest
    @EnumSource(ArchivedMutation.class)
    void archivedProjectRejectsEveryDefinitionMutation(ArchivedMutation mutation) throws Exception {
        Fixture fixture = seed();
        archive(fixture);
        List<String> before = facts(fixture);

        Throwable failure = catchThrowable(() -> as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> mutation.invoke(tasks, mapper, fixture)));
        List<String> after = facts(fixture);

        assertSoftly(softly -> {
            softly.assertThat(errorCode(failure)).isEqualTo(50017);
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** update先取得SHARE后，归档真实等待job和schedule在同一事务提交完成。 */
    @Test
    void updatePermitKeepsArchiveBehindJobAndScheduleCommit() throws Exception {
        Fixture fixture = seed();
        CountDownLatch permitReached = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        afterPermit = () -> {
            writerPid.complete(actualPid());
            permitReached.countDown();
            await(releaseWriter);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TaskJob> writer = executor.submit(() -> as(
                    fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                    () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                            updateCommand("许可先得更新", 1L))));
            assertThat(permitReached.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> archivePid = new CompletableFuture<>();
            Future<?> archiver = executor.submit(() -> {
                try (Connection owner = owner()) {
                    archivePid.complete(backendId(owner));
                    execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                } catch (SQLException exception) {
                    archivePid.completeExceptionally(exception);
                    throw new IllegalStateException(exception);
                }
            });
            assertBlockedBy(archivePid.get(5, TimeUnit.SECONDS), writerPid.get(5, TimeUnit.SECONDS), archiver);
            releaseWriter.countDown();
            TaskJob updated = writer.get(5, TimeUnit.SECONDS);
            archiver.get(5, TimeUnit.SECONDS);
            JobScheduleFacts committed = jobScheduleFacts(fixture.activeJobId());
            String status = projectStatus(fixture);

            assertSoftly(softly -> {
                softly.assertThat(updated.name()).isEqualTo("许可先得更新");
                softly.assertThat(updated.version()).isEqualTo(2);
                softly.assertThat(committed.jobName()).isEqualTo("许可先得更新");
                softly.assertThat(committed.jobVersion()).isEqualTo(2);
                softly.assertThat(committed.scheduleType()).isEqualTo("ONCE");
                softly.assertThat(status).isEqualTo("ARCHIVED");
            });
        } finally {
            releaseWriter.countDown();
            stop(executor);
        }
    }

    /** 归档先持项目锁时，后到update真实等待提交，随后50017且三张事实表不变。 */
    @Test
    void archiveLockFirstMakesWaitingUpdateRejectReadOnly() throws Exception {
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        CompletableFuture<Integer> waitingPid = captureFirstRolePid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                        () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                                updateCommand("归档先得更新", 1L)))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                holder.commit();
                Throwable failure = waiting.get(10, TimeUnit.SECONDS);
                List<String> after = facts(fixture);

                assertSoftly(softly -> {
                    softly.assertThat(errorCode(failure)).isEqualTo(50017);
                    softly.assertThat(after).isEqualTo(before);
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 等待项目锁期间ADMIN降为OPERATOR，锁后复核必须返回原任务域40020并零写。 */
    @Test
    void waitingAdminRechecksManageRoleAfterPermit() throws Exception {
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        CompletableFuture<Integer> waitingPid = captureFirstRolePid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at=updated_at WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                        () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                                updateCommand("等待降权更新", 1L)))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                execute(holder, "UPDATE sys_project_member SET role='OPERATOR' "
                                + "WHERE project_id=? AND account_id=?",
                        fixture.projectId(), fixture.adminId());
                holder.commit();
                Throwable failure = waiting.get(10, TimeUnit.SECONDS);
                List<String> after = facts(fixture);

                assertSoftly(softly -> {
                    softly.assertThat(errorCode(failure)).isEqualTo(40020);
                    softly.assertThat(after).isEqualTo(before);
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** schedule UPDATE后的延迟23514必须回滚先前job CAS，移除故障后同version恢复。 */
    @Test
    void deferredScheduleFailureRollsBackJobThenRecovers() throws Exception {
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        String suffix = fixture.projectId().toString().replace("-", "");
        String function = "td_fail_fn_" + suffix;
        String trigger = "td_fail_tr_" + suffix;
        try {
            try (Connection owner = owner()) {
                execute(owner, "CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN IF NEW.job_id='" + fixture.activeJobId() + "'::uuid THEN "
                        + "RAISE EXCEPTION 'task definition commit failure' USING ERRCODE='23514'; "
                        + "END IF; RETURN NEW; END $$");
                execute(owner, "CREATE CONSTRAINT TRIGGER " + trigger + " AFTER UPDATE ON task_schedule "
                        + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            }
            Throwable failure = catchThrowable(() -> as(
                    fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                    () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                            updateCommand("提交失败更新", 1L))));
            List<String> afterFailure = facts(fixture);

            assertSoftly(softly -> {
                softly.assertThat(sqlState(failure)).isEqualTo("23514");
                softly.assertThat(afterFailure).isEqualTo(before);
            });
        } finally {
            try (Connection owner = owner()) {
                execute(owner, "DROP TRIGGER IF EXISTS " + trigger + " ON task_schedule");
                execute(owner, "DROP FUNCTION IF EXISTS " + function + "()");
            }
        }

        TaskJob recovered = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                        updateCommand("提交失败更新", 1L)));
        JobScheduleFacts recoveredFacts = jobScheduleFacts(fixture.activeJobId());
        assertSoftly(softly -> {
            softly.assertThat(recovered.version()).isEqualTo(2);
            softly.assertThat(recoveredFacts.jobName()).isEqualTo("提交失败更新");
            softly.assertThat(recoveredFacts.jobVersion()).isEqualTo(2);
        });
    }

    /** schedule在锁前读取后消失时，第二写零行必须回滚job CAS；恢复计划后相同version可重试。 */
    @Test
    void missingScheduleSecondWriteRollsBackJobThenRecovers() throws Exception {
        Fixture fixture = seed();
        String beforeJob = jobFact(fixture.activeJobId());
        AtomicBoolean removed = new AtomicBoolean();
        afterSchedulingContext = () -> {
            if (removed.compareAndSet(false, true)) {
                try (Connection owner = owner()) {
                    execute(owner, "DELETE FROM task_schedule WHERE job_id=?", fixture.activeJobId());
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };

        Throwable failure = catchThrowable(() -> as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                        updateCommand("计划缺失更新", 1L))));
        String afterJob = jobFact(fixture.activeJobId());
        long scheduleCount = scheduleCount(fixture.activeJobId());

        assertSoftly(softly -> {
            softly.assertThat(failure).isNotNull()
                    .isNotInstanceOf(BusinessException.class)
                    .hasMessageContaining("任务定义更新必须同步更新唯一调度定义");
            softly.assertThat(afterJob).isEqualTo(beforeJob);
            softly.assertThat(scheduleCount).isZero();
        });

        afterSchedulingContext = () -> { };
        try (Connection owner = owner()) {
            insertSchedule(owner, fixture.activeJobId(), fixture.ownerTenantId(), fixture.projectId(), true);
        }
        TaskJob recovered = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                        updateCommand("计划缺失更新", 1L)));
        assertSoftly(softly -> {
            softly.assertThat(recovered.version()).isEqualTo(2);
            softly.assertThat(recovered.name()).isEqualTo("计划缺失更新");
        });
    }

    /** 原五秒JDBC预算取消项目锁等待为57014，两表零变；释放后同一update恢复。 */
    @Test
    void projectLockTimeoutRollsBackDefinitionThenRecovers() throws Exception {
        assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        CompletableFuture<Integer> waitingPid = captureFirstRolePid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at=updated_at WHERE id=?", fixture.projectId());
                int holderPid = backendId(holder);
                Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> as(
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                        () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                                updateCommand("超时更新", 1L)))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                Throwable failure = waiting.get(12, TimeUnit.SECONDS);
                List<String> afterFailure = facts(fixture);

                assertSoftly(softly -> {
                    softly.assertThat(sqlState(failure)).isEqualTo("57014");
                    softly.assertThat(afterFailure).isEqualTo(before);
                });
                holder.rollback();
                TaskJob recovered = as(
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                        () -> tasks.update(fixture.projectId(), fixture.activeJobId(),
                                updateCommand("超时更新", 1L)));
                assertSoftly(softly -> {
                    softly.assertThat(recovered.name()).isEqualTo("超时更新");
                    softly.assertThat(recovered.version()).isEqualTo(2);
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 建立跨租户ADMIN、ACTIVE/PAUSED两份定义及一条已完成执行历史。 */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        Instant createdAt = Instant.parse("2026-09-04T12:00:00Z");
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '任务OWNER租户'), (?, '任务协作者租户')",
                    fixture.ownerTenantId(), fixture.collaboratorTenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES "
                            + "(?, ?, '{noop}unused', '任务OWNER'), (?, ?, '{noop}unused', '任务ADMIN')",
                    fixture.ownerId(), fixture.ownerId() + "@example.com",
                    fixture.adminId(), fixture.adminId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?),(?,?,?)",
                    Uuid7.generate(), fixture.ownerTenantId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.adminId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,timezone,project_key) "
                            + "VALUES (?,?,'任务定义项目','sh-1','Asia/Shanghai',?)",
                    fixture.projectId(), fixture.ownerTenantId(),
                    "task_definition_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES "
                            + "(?,?,?,'OWNER'),(?,?,?,'ADMIN')",
                    Uuid7.generate(), fixture.projectId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.projectId(), fixture.adminId());
            insertJob(owner, fixture.activeJobId(), fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    "活动任务", "ACTIVE", createdAt);
            insertSchedule(owner, fixture.activeJobId(), fixture.ownerTenantId(), fixture.projectId(), true);
            insertJob(owner, fixture.pausedJobId(), fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    "暂停任务", "PAUSED", createdAt);
            insertSchedule(owner, fixture.pausedJobId(), fixture.ownerTenantId(), fixture.projectId(), false);
            execute(owner, "INSERT INTO task_execution(id,tenant_id,project_id,job_id,target_type,command_key,input,"
                            + "requested_by,trigger_type,status,started_at,finished_at,created_at,updated_at) "
                            + "VALUES (?,?,?,?,'ALL_DEVICES','reboot','{}'::jsonb,?,'MANUAL','SUCCEEDED',?,?,?,?)",
                    fixture.executionId(), fixture.ownerTenantId(), fixture.projectId(), fixture.activeJobId(),
                    fixture.ownerId(), createdAt, createdAt.plusSeconds(1), createdAt, createdAt.plusSeconds(1));
            owner.commit();
        }
        return fixture;
    }

    /** 插入一条合法任务定义；调度字段由独立表保存。 */
    private static void insertJob(Connection owner, UUID jobId, UUID tenantId, UUID projectId,
                                  UUID createdBy, String name, String status, Instant at) throws SQLException {
        execute(owner, "INSERT INTO task_job(id,tenant_id,project_id,name,status,version,target_type,"
                        + "command_key,input,created_by,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,1,'ALL_DEVICES','reboot','{}'::jsonb,?,?,?)",
                jobId, tenantId, projectId, name, status, createdBy, at, at);
    }

    /** active任务用未来ONCE计划，paused任务用无next_run的CRON计划。 */
    private static void insertSchedule(Connection owner, UUID jobId, UUID tenantId, UUID projectId,
                                       boolean active) throws SQLException {
        Instant at = Instant.parse("2026-09-04T12:00:00Z");
        Instant runAt = Instant.parse("2030-01-01T00:00:00Z");
        execute(owner, "INSERT INTO task_schedule(id,tenant_id,project_id,job_id,schedule_type,run_at,"
                        + "cron_expression,timezone,next_run_at,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                Uuid7.generate(), tenantId, projectId, jobId, active ? "ONCE" : "CRON",
                active ? runAt : null, active ? null : "0 0 * * * *", "Asia/Shanghai",
                active ? runAt : null, at, at);
    }

    /** 新建使用固定未来ONCE时间，避免测试运行时间改变应有事实。 */
    private TaskJobCommand createCommand(String name) {
        return new TaskJobCommand(name, "验收任务", TaskJob.ScheduleType.ONCE,
                Instant.parse("2031-01-01T00:00:00Z"), null, null, TaskJob.TargetType.ALL_DEVICES,
                null, "reboot", mapper.createObjectNode().put("mode", "safe"), true, null);
    }

    /** 更新沿用固定未来ONCE合同并携带显式CAS版本。 */
    private TaskJobCommand updateCommand(String name, long version) {
        TaskJobCommand created = createCommand(name);
        return new TaskJobCommand(created.name(), created.description(), created.scheduleType(), created.runAt(),
                created.cronExpression(), created.timezone(), created.targetType(), created.targetGroupId(),
                created.commandKey(), created.input(), created.enabled(), version);
    }

    /** 独立提交ARCHIVED，成员及任务事实保留供只读合同验收。 */
    private void archive(Fixture fixture) throws SQLException {
        try (Connection owner = owner()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
    }

    /** job、schedule与既有execution完整行快照，覆盖版本、时间、租约和删除标记。 */
    private List<String> facts(Fixture fixture) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = owner()) {
            for (String table : List.of("task_job", "task_schedule", "task_execution")) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(r)::text FROM " + table
                                + " r WHERE project_id=? ORDER BY id")) {
                    query.setQueryTimeout(5);
                    query.setObject(1, fixture.projectId());
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            result.add(table + ':' + rows.getString(1));
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 跨租户创建后同时读取领域两表的tenant、actor及时区。 */
    private CreatedFacts createdFacts(UUID jobId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT j.tenant_id job_tenant, s.tenant_id schedule_tenant,
                       j.created_by, s.timezone
                  FROM task_job j JOIN task_schedule s ON s.job_id=j.id WHERE j.id=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, jobId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new CreatedFacts(rows.getObject("job_tenant", UUID.class),
                        rows.getObject("schedule_tenant", UUID.class),
                        rows.getObject("created_by", UUID.class), rows.getString("timezone"));
            }
        }
    }

    /** update成功后读取job/schedule双写结果。 */
    private JobScheduleFacts jobScheduleFacts(UUID jobId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT j.name, j.version, s.schedule_type
                  FROM task_job j JOIN task_schedule s ON s.job_id=j.id WHERE j.id=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, jobId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new JobScheduleFacts(rows.getString("name"), rows.getLong("version"),
                        rows.getString("schedule_type"));
            }
        }
    }

    /** 缺失schedule故障只比较job全行，避免故障夹具自身删除掩盖单边提交。 */
    private String jobFact(UUID jobId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT row_to_json(j)::text FROM task_job j WHERE id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, jobId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** @return 指定任务当前调度行数 */
    private long scheduleCount(UUID jobId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT count(*) FROM task_schedule WHERE job_id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, jobId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 第一次真实角色查询后记录APP事务PID，后续锁后复核不覆盖。 */
    private CompletableFuture<Integer> captureFirstRolePid() {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        AtomicBoolean first = new AtomicBoolean();
        afterRole = () -> {
            if (first.compareAndSet(false, true)) {
                pid.complete(actualPid());
            }
        };
        return pid;
    }

    /** 当前业务线程必须是原APP写事务，返回真实后端PID供锁图验证。 */
    private int actualPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** owner夹具连接PID不得与APP事务PID混用。 */
    private static int backendId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    /** pg阻塞图和未授予锁同时成立才承认真实项目锁等待。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        try (Connection observer = owner(); PreparedStatement query = observer.prepareStatement(
                "SELECT ?=ANY(pg_blocking_pids(?)), "
                        + "EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)")) {
            query.setQueryTimeout(5);
            query.setInt(1, holder);
            query.setInt(2, waiter);
            query.setInt(3, waiter);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    if (rows.getBoolean(1) && rows.getBoolean(2)) {
                        return;
                    }
                }
                if (operation.isDone()) {
                    throw new AssertionError("任务定义操作完成但未观察到项目锁等待");
                }
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到任务定义项目锁等待");
    }

    /** 屏障中断必须失败并恢复中断标记。 */
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** 关闭并等待业务线程，禁止延迟写污染后续用例。 */
    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 项目状态用owner读取最终已提交事实。 */
    private String projectStatus(Fixture fixture) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT status FROM sys_project WHERE id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** 只接受异常链中的真实SQLSTATE。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /** 业务异常公开码；基础设施异常和无异常返回空。 */
    private static Integer errorCode(Throwable failure) {
        return failure instanceof BusinessException business ? business.errorCode().code() : null;
    }

    /** 真实控制台调用结束后同时清空账号和RLS线程上下文。 */
    private static <T> T as(UUID tenantId, UUID projectId, UUID accountId, Callable<T> action)
            throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        try {
            return action.call();
        } finally {
            TenantContext.clear();
            RlsScopeContext.clear();
        }
    }

    /** owner连接仅用于夹具、故障注入、生命周期状态和提交事实观察。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(
                DATABASE_URL, TASK_POSTGRES.getUsername(), TASK_POSTGRES.getPassword());
    }

    /** 通用夹具SQL绑定Instant时显式转Timestamp，其他JDBC类型保持原值。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof Instant instant) {
                    statement.setTimestamp(index + 1, java.sql.Timestamp.from(instant));
                } else {
                    statement.setObject(index + 1, value);
                }
            }
            statement.executeUpdate();
        }
    }

    /** 静态专库必须早于DynamicPropertyRegistrar启动。 */
    private static String startDatabase() {
        TASK_POSTGRES.start();
        return TASK_POSTGRES.getJdbcUrl();
    }

    /** 专库由OwnedTestContainers在本类上下文物理关闭后回收；每例恢复观察点和线程上下文，锁/线程/触发器各自finally释放。 */
    @AfterEach
    void clearContext() {
        afterRole = () -> { };
        afterPermit = () -> { };
        afterSchedulingContext = () -> { };
        TenantContext.clear();
        RlsScopeContext.clear();
    }

    /** 专库禁用无关全局worker，保留真实任务定义、仓储、事务及项目锁。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 数据源和Flyway统一落专库，关闭Outbox、Kafka及通知重试后台。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }

    /** ARCHIVED必须统一拒绝的五个任务定义入口。 */
    private enum ArchivedMutation {
        /** 新建定义与schedule。 */
        CREATE {
            @Override
            Object invoke(TaskJobService service, ObjectMapper mapper, Fixture fixture) {
                return service.create(fixture.projectId(), command(mapper, "归档新建", null, true));
            }
        },
        /** CAS更新定义与schedule。 */
        UPDATE {
            @Override
            Object invoke(TaskJobService service, ObjectMapper mapper, Fixture fixture) {
                return service.update(fixture.projectId(), fixture.activeJobId(),
                        command(mapper, "归档更新", 1L, true));
            }
        },
        /** 软删除定义。 */
        DELETE {
            @Override
            Object invoke(TaskJobService service, ObjectMapper mapper, Fixture fixture) {
                service.delete(fixture.projectId(), fixture.activeJobId(), 1L);
                return null;
            }
        },
        /** 启用原PAUSED定义。 */
        ENABLE {
            @Override
            Object invoke(TaskJobService service, ObjectMapper mapper, Fixture fixture) {
                return service.enable(fixture.projectId(), fixture.pausedJobId());
            }
        },
        /** 暂停原ACTIVE定义。 */
        DISABLE {
            @Override
            Object invoke(TaskJobService service, ObjectMapper mapper, Fixture fixture) {
                return service.disable(fixture.projectId(), fixture.activeJobId());
            }
        };

        /** 使用固定合法ONCE命令，create不带version、update带显式version。 */
        private static TaskJobCommand command(ObjectMapper mapper, String name, Long version, boolean enabled) {
            return new TaskJobCommand(name, "归档验收", TaskJob.ScheduleType.ONCE,
                    Instant.parse("2031-01-01T00:00:00Z"), null, null, TaskJob.TargetType.ALL_DEVICES,
                    null, "reboot", mapper.createObjectNode(), enabled, version);
        }

        /** 调用真实Console定义服务入口。 */
        abstract Object invoke(TaskJobService service, ObjectMapper mapper, Fixture fixture);
    }

    /** 跨租户创建的job/schedule身份投影。 */
    private record CreatedFacts(UUID jobTenant, UUID scheduleTenant, UUID createdBy, String timezone) {
    }

    /** update后两表核心字段投影。 */
    private record JobScheduleFacts(String jobName, long jobVersion, String scheduleType) {
    }

    /** 所有任务事实都有真实父行；ADMIN来自另一tenant但属于目标项目。 */
    private record Fixture(UUID ownerTenantId, UUID collaboratorTenantId, UUID projectId, UUID ownerId,
                           UUID adminId, UUID activeJobId, UUID pausedJobId, UUID executionId) {
    }
}
