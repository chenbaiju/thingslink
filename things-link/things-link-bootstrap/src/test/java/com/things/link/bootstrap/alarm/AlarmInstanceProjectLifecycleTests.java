package com.things.link.bootstrap.alarm;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.alarm.application.AlarmInstanceService;
import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

/** S12-P0-5e5c：真实APP RLS下验收告警ACK/CLEAR的项目冻结与不可变事件原子性。 */
@Import(AlarmInstanceProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"ALARM_POSTGRES"})
class AlarmInstanceProjectLifecycleTests extends AbstractIntegrationTest {

    /** 人工事故处理包含全局通知装配，物理专库避免其他测试候选被后台领取。 */
    private static final String DATABASE_NAME = "alarm_instance_lifecycle_"
            + UUID.randomUUID().toString().replace("-", "");
    /** PostgreSQL/Timescale版本沿用全仓真实验收镜像。 */
    private static final PostgreSQLContainer<?> ALARM_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    /** Spring、Flyway与owner观察连接必须指向同一独占数据库。 */
    private static final String DATABASE_URL = startDatabase();

    /** 独占库无需共享配额runner修改策略。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 本类不验证通知发送，禁止全表领取制造额外事实。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 任务扫描与人工告警处理正交。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskScanner;
    /** 设备命令超时不得写入本类专库。 */
    @MockitoBean(enforceOverride = true)
    private DeviceCommandTimeoutScanner unusedCommandScanner;
    /** 属性聚合回补不参与实例维护合同。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfillScanner;

    /** ACK/CLEAR保留真实仓储、事务代理和状态机。 */
    @Autowired
    private AlarmInstanceService instances;
    /** 角色查询使用真实项目服务，spy只提供确定并发观察点。 */
    @MockitoSpyBean
    private ProjectService projects;
    /** 生命周期许可使用真实SHARE锁，spy只在锁已取得后暂停。 */
    @MockitoSpyBean
    private ProjectLifecycleAccessService lifecycle;
    /** APP连接用于核验真实事务、运行角色与后端PID。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 默认角色查询不暂停。 */
    private Runnable afterRole = () -> { };
    /** 默认项目许可取得后不暂停。 */
    private Runnable afterPermit = () -> { };

    /** 每例确认专库、APP角色和READ COMMITTED，并在真实方法返回后安装可控观察点。 */
    @BeforeEach
    void prepare() {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        for (Object disabled : List.of(unusedQuotaRunner, unusedNotificationCoordinator, unusedTaskScanner,
                unusedCommandScanner, unusedBackfillScanner)) {
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        }
        ProjectService projectTarget = AopTestUtils.getUltimateTargetObject(projects);
        doAnswer(invocation -> {
            Object role = invocation.callRealMethod();
            afterRole.run();
            return role;
        }).when(projectTarget).requireRoleInProject(any());
        ProjectLifecycleAccessService lifecycleTarget = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            afterPermit.run();
            return result;
        }).when(lifecycleTarget).requireActiveForWrite(any(), any());
    }

    /** 跨租户OPERATOR可处理ACTIVE事故，但事件tenant取项目归属且actor取真实操作者。 */
    @ParameterizedTest
    @EnumSource(Mutation.class)
    void crossTenantOperatorMutatesWithOwnerTenantAndActor(Mutation mutation) throws Exception {
        Fixture fixture = seed();

        AlarmInstance result = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                () -> mutation.invoke(instances, fixture));
        ManualEvent event = manualEvent(fixture, mutation);

        assertSoftly(softly -> {
            softly.assertThat(result.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(result.projectId()).isEqualTo(fixture.projectId());
            softly.assertThat(result.version()).isEqualTo(1);
            softly.assertThat(result.acknowledgedBy())
                    .isEqualTo(mutation == Mutation.ACK ? fixture.operatorId() : null);
            mutation.assertResult(softly, result);
            softly.assertThat(event.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(event.projectId()).isEqualTo(fixture.projectId());
            softly.assertThat(event.instanceId()).isEqualTo(fixture.instanceId());
            softly.assertThat(event.actorId()).isEqualTo(fixture.operatorId());
            softly.assertThat(event.sourceMessageId()).isNull();
            softly.assertThat(event.type()).isEqualTo(mutation.eventType.name());
            softly.assertThat(event.count()).isEqualTo(1);
        });
    }

    /** ARCHIVED保留实例、列表和不可变事件读取，且三类读取不产生任何持久副作用。 */
    @Test
    void archivedProjectKeepsInstanceAndEventHistoryReadableWithoutWrites() throws Exception {
        Fixture fixture = seed();
        archive(fixture);
        List<String> before = facts(fixture);

        AlarmInstance one = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                () -> instances.get(fixture.projectId(), fixture.instanceId()));
        int instanceCount = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                () -> instances.page(fixture.projectId(), null, 10).items().size());
        List<AlarmEvent> events = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                () -> instances.pageEvents(fixture.projectId(), fixture.instanceId(), null, 10).items());
        List<String> after = facts(fixture);

        assertSoftly(softly -> {
            softly.assertThat(one.id()).isEqualTo(fixture.instanceId());
            softly.assertThat(one.conditionState()).isEqualTo(AlarmInstance.ConditionState.ACTIVE);
            softly.assertThat(instanceCount).isEqualTo(1);
            softly.assertThat(events).extracting(AlarmEvent::eventType)
                    .containsExactly(AlarmEvent.EventType.ACTIVATED, AlarmEvent.EventType.PENDING);
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** ARCHIVED下ACK/CLEAR都先返回50017，不能泄漏状态码或改变实例和事件。 */
    @ParameterizedTest
    @EnumSource(Mutation.class)
    void archivedProjectRejectsMaintenanceBeforeDomainMutation(Mutation mutation) throws Exception {
        Fixture fixture = seed();
        archive(fixture);
        List<String> before = facts(fixture);

        Throwable failure = catchThrowable(() -> as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                () -> mutation.invoke(instances, fixture)));
        List<String> after = facts(fixture);

        assertSoftly(softly -> {
            softly.assertThat(errorCode(failure)).isEqualTo(50017);
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** ACK先取得SHARE后，归档必须等待实例与事件在同一业务事务提交。 */
    @Test
    void writePermitKeepsArchiveBehindInstanceAndEventCommit() throws Exception {
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
            Future<AlarmInstance> writer = executor.submit(() -> as(
                    fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                    () -> Mutation.ACK.invoke(instances, fixture)));
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
            AlarmInstance acknowledged = writer.get(5, TimeUnit.SECONDS);
            archiver.get(5, TimeUnit.SECONDS);
            String status = projectStatus(fixture);
            long eventCount = manualEvent(fixture, Mutation.ACK).count();

            assertSoftly(softly -> {
                softly.assertThat(acknowledged.ackState()).isEqualTo(AlarmInstance.AckState.ACKNOWLEDGED);
                softly.assertThat(status).isEqualTo("ARCHIVED");
                softly.assertThat(eventCount).isEqualTo(1);
            });
        } finally {
            releaseWriter.countDown();
            stop(executor);
        }
    }

    /** 归档先持排他锁时，后到CLEAR真实等待其提交，随后50017且两张事实表不变。 */
    @Test
    void archiveLockFirstMakesWaitingClearRejectReadOnly() throws Exception {
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
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                        () -> Mutation.CLEAR.invoke(instances, fixture))));
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

    /** 项目锁等待期间OPERATOR降为VIEWER，锁后复核必须保留告警域40005并零写。 */
    @Test
    void waitingOperatorRechecksMaintainRoleAfterPermit() throws Exception {
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
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                        () -> Mutation.ACK.invoke(instances, fixture))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                execute(holder, "UPDATE sys_project_member SET role='VIEWER' "
                                + "WHERE project_id=? AND account_id=?",
                        fixture.projectId(), fixture.operatorId());
                holder.commit();
                Throwable failure = waiting.get(10, TimeUnit.SECONDS);
                List<String> after = facts(fixture);

                assertSoftly(softly -> {
                    softly.assertThat(errorCode(failure)).isEqualTo(40005);
                    softly.assertThat(after).isEqualTo(before);
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 两个相同version的并发人工动作只能有一个CAS成功，并只能追加一条对应不可变事件。 */
    @ParameterizedTest
    @EnumSource(Mutation.class)
    void concurrentSameVersionCreatesExactlyOneLegalEvent(Mutation mutation) throws Exception {
        Fixture fixture = seed();
        CountDownLatch permitsReached = new CountDownLatch(2);
        CountDownLatch releaseWriters = new CountDownLatch(1);
        afterPermit = () -> {
            permitsReached.countDown();
            await(releaseWriters);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> action = () -> {
                try {
                    return new Outcome(as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                            () -> mutation.invoke(instances, fixture)), null);
                } catch (Throwable failure) {
                    return new Outcome(null, failure);
                }
            };
            Future<Outcome> first = executor.submit(action);
            Future<Outcome> second = executor.submit(action);
            assertThat(permitsReached.await(5, TimeUnit.SECONDS)).isTrue();
            releaseWriters.countDown();
            List<Outcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            AlarmInstance persisted = persistedInstance(fixture);
            long eventCount = manualEvent(fixture, mutation).count();

            assertSoftly(softly -> {
                softly.assertThat(outcomes).filteredOn(outcome -> outcome.value() != null).hasSize(1);
                softly.assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null)
                        .extracting(outcome -> errorCode(outcome.failure())).containsExactly(40006);
                softly.assertThat(persisted.version()).isEqualTo(1);
                mutation.assertResult(softly, persisted);
                softly.assertThat(eventCount).isEqualTo(1);
            });
        } finally {
            releaseWriters.countDown();
            stop(executor);
        }
    }

    /** ACK/CLEAR事件INSERT后的延迟23514必须回滚实例CAS，移除故障后同version恢复且恰写一次。 */
    @ParameterizedTest
    @EnumSource(Mutation.class)
    void deferredEventFailureRollsBackInstanceThenRecovers(Mutation mutation) throws Exception {
        Fixture fixture = seed();
        List<String> before = facts(fixture);
        String suffix = fixture.projectId().toString().replace("-", "");
        String function = "ai_fail_fn_" + suffix;
        String trigger = "ai_fail_tr_" + suffix;
        try {
            try (Connection owner = owner()) {
                execute(owner, "CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN IF NEW.instance_id='" + fixture.instanceId() + "'::uuid "
                        + "AND NEW.event_type='" + mutation.eventType.name() + "' THEN "
                        + "RAISE EXCEPTION 'alarm instance commit failure' USING ERRCODE='23514'; "
                        + "END IF; RETURN NEW; END $$");
                execute(owner, "CREATE CONSTRAINT TRIGGER " + trigger + " AFTER INSERT ON alarm_event "
                        + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            }
            Throwable failure = catchThrowable(() -> as(
                    fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                    () -> mutation.invoke(instances, fixture)));
            List<String> afterFailure = facts(fixture);

            assertSoftly(softly -> {
                softly.assertThat(sqlState(failure)).isEqualTo("23514");
                softly.assertThat(afterFailure).isEqualTo(before);
            });
        } finally {
            try (Connection owner = owner()) {
                execute(owner, "DROP TRIGGER IF EXISTS " + trigger + " ON alarm_event");
                execute(owner, "DROP FUNCTION IF EXISTS " + function + "()");
            }
        }

        AlarmInstance recovered = as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                () -> mutation.invoke(instances, fixture));
        long recoveredEventCount = manualEvent(fixture, mutation).count();
        assertSoftly(softly -> {
            softly.assertThat(recovered.version()).isEqualTo(1);
            mutation.assertResult(softly, recovered);
            softly.assertThat(recoveredEventCount).isEqualTo(1);
        });
    }

    /** 原五秒JDBC预算取消项目锁等待为57014，两表零变；解锁后相同version的ACK/CLEAR均恢复。 */
    @ParameterizedTest
    @EnumSource(Mutation.class)
    void projectLockTimeoutRollsBackThenRecovers(Mutation mutation) throws Exception {
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
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                        () -> mutation.invoke(instances, fixture))));
                assertBlockedBy(waitingPid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                Throwable failure = waiting.get(12, TimeUnit.SECONDS);
                List<String> afterFailure = facts(fixture);

                assertSoftly(softly -> {
                    softly.assertThat(sqlState(failure)).isEqualTo("57014");
                    softly.assertThat(afterFailure).isEqualTo(before);
                });
                holder.rollback();
                AlarmInstance recovered = as(
                        fixture.collaboratorTenantId(), fixture.projectId(), fixture.operatorId(),
                        () -> mutation.invoke(instances, fixture));
                long recoveredEventCount = manualEvent(fixture, mutation).count();
                assertSoftly(softly -> {
                    softly.assertThat(recovered.version()).isEqualTo(1);
                    mutation.assertResult(softly, recovered);
                    softly.assertThat(recoveredEventCount).isEqualTo(1);
                });
            } finally {
                holder.rollback();
                stop(executor);
            }
        }
    }

    /** 建立真实跨租户OPERATOR及一条ACTIVE+UNACK事故，初始PENDING/ACTIVATED事件完整合法。 */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate());
        Instant pendingAt = Instant.parse("2026-09-04T12:00:00Z");
        Instant activeAt = pendingAt.plusSeconds(1);
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '事故OWNER租户'), (?, '事故协作者租户')",
                    fixture.ownerTenantId(), fixture.collaboratorTenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES "
                            + "(?, ?, '{noop}unused', '事故OWNER'), (?, ?, '{noop}unused', '事故OPERATOR')",
                    fixture.ownerId(), fixture.ownerId() + "@example.com",
                    fixture.operatorId(), fixture.operatorId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?),(?,?,?)",
                    Uuid7.generate(), fixture.ownerTenantId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.operatorId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) "
                            + "VALUES (?,?,'告警事故项目','sh-1',?)",
                    fixture.projectId(), fixture.ownerTenantId(),
                    "alarm_instance_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES "
                            + "(?,?,?,'OWNER'),(?,?,?,'OPERATOR')",
                    Uuid7.generate(), fixture.projectId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.projectId(), fixture.operatorId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,"
                            + "access_protocol,network_type,status) VALUES "
                            + "(?,?,?,'alarm_instance_type','告警实例设备','DIRECT','STANDARD','WIFI','PUBLISHED')",
                    fixture.typeId(), fixture.ownerTenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) "
                            + "VALUES (?,?,?,?,'alarm_instance_device','告警实例设备','OFFLINE')",
                    fixture.deviceId(), fixture.ownerTenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,"
                            + "property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) "
                            + "VALUES (?,?,?,'实例规则','HIGH_TEMPERATURE',?,'temperature','GT',30,'LT',25,'MAJOR')",
                    fixture.ruleId(), fixture.ownerTenantId(), fixture.projectId(), fixture.deviceId());
            execute(owner, "INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,"
                            + "originator_id,alarm_type,severity,condition_state,ack_state,first_condition_at,"
                            + "activated_at,last_received_at,last_occurred_at,last_value) "
                            + "VALUES (?,?,?,?,'DEVICE',?,'HIGH_TEMPERATURE','MAJOR','ACTIVE','UNACKNOWLEDGED',"
                            + "?,?,?,?,31)",
                    fixture.instanceId(), fixture.ownerTenantId(), fixture.projectId(), fixture.ruleId(),
                    fixture.deviceId(), pendingAt, activeAt, activeAt, activeAt);
            execute(owner, "INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,"
                            + "trace_id,value,occurred_at,received_at,condition_state,ack_state) VALUES "
                            + "(?,?,?,?,'PENDING',?,'fixture-pending',31,?,?,'PENDING','UNACKNOWLEDGED'),"
                            + "(?,?,?,?,'ACTIVATED',?,'fixture-active',31,?,?,'ACTIVE','UNACKNOWLEDGED')",
                    Uuid7.generate(), fixture.ownerTenantId(), fixture.projectId(), fixture.instanceId(),
                    fixture.sourceMessageId(), pendingAt, pendingAt,
                    Uuid7.generate(), fixture.ownerTenantId(), fixture.projectId(), fixture.instanceId(),
                    fixture.sourceMessageId(), activeAt, activeAt);
            owner.commit();
        }
        return fixture;
    }

    /** 独立提交ARCHIVED；成员事实原样保留以验收归档历史读取。 */
    private void archive(Fixture fixture) throws SQLException {
        try (Connection owner = owner()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
    }

    /** 实例与其全部事件的全行快照覆盖CAS、身份、actor及不可变事件，不以行数代替原子性。 */
    private List<String> facts(Fixture fixture) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = owner()) {
            for (String table : List.of("alarm_instance", "alarm_event")) {
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

    /** owner视角读取最终实例，避免归档后服务写权限影响验收取证。 */
    private AlarmInstance persistedInstance(Fixture fixture) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT tenant_id, project_id, condition_state, ack_state, clear_reason,
                       acknowledged_at, acknowledged_by, cleared_at, version
                  FROM alarm_instance WHERE id=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.instanceId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new AlarmInstance(fixture.instanceId(), rows.getObject("tenant_id", UUID.class),
                        rows.getObject("project_id", UUID.class), fixture.ruleId(),
                        com.things.link.alarm.domain.AlarmRule.OriginatorType.DEVICE, fixture.deviceId(),
                        "HIGH_TEMPERATURE", com.things.link.alarm.domain.AlarmRule.Severity.MAJOR,
                        AlarmInstance.ConditionState.valueOf(rows.getString("condition_state")),
                        AlarmInstance.AckState.valueOf(rows.getString("ack_state")),
                        enumValue(rows.getString("clear_reason"), AlarmInstance.ClearReason.class),
                        Instant.parse("2026-09-04T12:00:00Z"), null,
                        Instant.parse("2026-09-04T12:00:01Z"), instant(rows, "cleared_at"),
                        instant(rows, "acknowledged_at"), rows.getObject("acknowledged_by", UUID.class),
                        Instant.parse("2026-09-04T12:00:01Z"), Instant.parse("2026-09-04T12:00:01Z"),
                        31D, rows.getInt("version"), null, null);
            }
        }
    }

    /** 读取指定人工事件及其完整身份快照；初始自动事件不会混入计数。 */
    private ManualEvent manualEvent(Fixture fixture, Mutation mutation) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT min(tenant_id::text)::uuid AS tenant_id, min(project_id::text)::uuid AS project_id,
                       min(instance_id::text)::uuid AS instance_id, min(actor_id::text)::uuid AS actor_id,
                       min(source_message_id::text)::uuid AS source_message_id,
                       min(event_type) AS event_type, count(*) AS event_count
                  FROM alarm_event WHERE instance_id=? AND event_type=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.instanceId());
            query.setString(2, mutation.eventType.name());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new ManualEvent(rows.getObject("tenant_id", UUID.class),
                        rows.getObject("project_id", UUID.class), rows.getObject("instance_id", UUID.class),
                        rows.getObject("actor_id", UUID.class), rows.getObject("source_message_id", UUID.class),
                        rows.getString("event_type"), rows.getLong("event_count"));
            }
        }
    }

    /** 第一次真实角色查询后记录APP事务PID，后续查询不重复完成Future。 */
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

    /** 当前业务线程必须是原APP写事务，返回其真实PID供锁图验证。 */
    private int actualPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** owner夹具事务PID不得与APP业务PID混用。 */
    private static int backendId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    /** 数据库阻塞图与未授予锁同时成立才承认真实项目锁等待。 */
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
                    throw new AssertionError("操作完成但未观察到项目锁等待");
                }
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到项目锁等待");
    }

    /** 项目状态由owner连接读取最终已提交事实。 */
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

    /** 真实控制台范围调用后同时清空账号与RLS线程上下文。 */
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

    /** 屏障中断必须失败并恢复中断标记。 */
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** 结束并等待后台线程，禁止延迟写污染后续用例。 */
    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 只接受异常链内真实SQLException状态。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /** 业务异常公开码；无异常或基础设施异常返回空。 */
    private static Integer errorCode(Throwable failure) {
        return failure instanceof BusinessException business ? business.errorCode().code() : null;
    }

    /** nullable枚举转换供owner快照重建。 */
    private static <T extends Enum<T>> T enumValue(String value, Class<T> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    /** nullable时间转换供owner快照重建。 */
    private static Instant instant(ResultSet rows, String column) throws SQLException {
        java.sql.Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** owner连接只负责夹具、生命周期状态、故障注入和提交事实观察。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(
                DATABASE_URL, ALARM_POSTGRES.getUsername(), ALARM_POSTGRES.getPassword());
    }

    /** 所有测试SQL都使用五秒语句预算，避免夹具故障无限挂起。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                if (value instanceof Instant instant) {
                    // PG JDBC不能从setObject(Instant)推断timestamptz；显式转JDBC时间后仍由会话按UTC事实保存。
                    statement.setTimestamp(index + 1, java.sql.Timestamp.from(instant));
                } else {
                    statement.setObject(index + 1, value);
                }
            }
            statement.executeUpdate();
        }
    }

    /** 静态启动必须早于动态属性注册；发布 URL 前先确认真实可连接。 */
    private static String startDatabase() {
        ALARM_POSTGRES.start();
        return awaitDatabaseReady();
    }

    /**
     * 容器日志就绪 ≠ 宿主 JDBC 可用：TimescaleDB HA 镜像在初始化扩展后会重启 {@code postgres}，
     * 这段时间里端口握手会读超时——2026-09-17 的全量复跑就因此让本类 14 例全部倒在上下文加载
     * （{@code FlywaySqlUnableToConnectToDbException} → {@code SocketTimeoutException: Read timed out}），
     * 而本类单独运行时 14/14 通过。按 {@code ProjectExportDownloadLifecycleTests.startDatabase()} 的既有做法，
     * 在发布 URL 前做一次**带身份校验的有界探活**；探活只用短连接/短读超时，不改变 Flyway、
     * 业务连接或 SSL 的默认行为，也不放宽任何生产配置。
     *
     * @return 探活通过后的专属库 JDBC URL
     */
    private static String awaitDatabaseReady() {
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            try (Connection connection = ALARM_POSTGRES.createConnection("?connectTimeout=2&socketTimeout=5");
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT current_database()")) {
                if (!result.next() || !DATABASE_NAME.equals(result.getString(1))) {
                    throw new IllegalStateException("告警专库探活返回了错误的数据库身份");
                }
                return ALARM_POSTGRES.getJdbcUrl();
            } catch (SQLException failure) {
                lastFailure = failure;
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        IllegalStateException failure = new IllegalStateException("告警专库JDBC探活失败，未启动Flyway");
        if (lastFailure != null) {
            failure.addSuppressed(lastFailure);
            // 保留驱动原因链；容器日志用于区分数据库退出与宿主映射端口握手失败。
            try {
                failure.addSuppressed(new IllegalStateException("告警专库启动日志：\n" + ALARM_POSTGRES.getLogs()));
            } catch (RuntimeException diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
            }
        }
        throw failure;
    }

    /** 专库由OwnedTestContainers在本类上下文物理关闭后回收；每例恢复挂钩与线程上下文，锁/线程/触发器由各测试finally释放。 */
    @AfterEach
    void clearContext() {
        afterRole = () -> { };
        afterPermit = () -> { };
        TenantContext.clear();
        RlsScopeContext.clear();
    }

    /** 专库禁用无关全表领取，保留真实实例服务、仓储、事务和项目锁。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 数据源和Flyway统一使用专库，并关闭Outbox、Kafka及通知重试后台。 */
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

    /** ACK与CLEAR共享生命周期、CAS和事件原子合同，差异只在状态机输出。 */
    private enum Mutation {
        /** ACK只改变确认维度并保留ACTIVE条件。 */
        ACK(AlarmEvent.EventType.ACKNOWLEDGED) {
            @Override
            AlarmInstance invoke(AlarmInstanceService service, Fixture fixture) {
                return service.acknowledge(fixture.projectId(), fixture.instanceId(), 0);
            }

            @Override
            void assertResult(org.assertj.core.api.SoftAssertions softly, AlarmInstance value) {
                softly.assertThat(value.conditionState()).isEqualTo(AlarmInstance.ConditionState.ACTIVE);
                softly.assertThat(value.ackState()).isEqualTo(AlarmInstance.AckState.ACKNOWLEDGED);
                softly.assertThat(value.clearReason()).isNull();
                softly.assertThat(value.acknowledgedBy()).isNotNull();
            }
        },
        /** 人工CLEAR结束活动事故但不得伪造ACK。 */
        CLEAR(AlarmEvent.EventType.CLEARED) {
            @Override
            AlarmInstance invoke(AlarmInstanceService service, Fixture fixture) {
                return service.clear(fixture.projectId(), fixture.instanceId(), 0);
            }

            @Override
            void assertResult(org.assertj.core.api.SoftAssertions softly, AlarmInstance value) {
                softly.assertThat(value.conditionState()).isEqualTo(AlarmInstance.ConditionState.CLEARED);
                softly.assertThat(value.ackState()).isEqualTo(AlarmInstance.AckState.UNACKNOWLEDGED);
                softly.assertThat(value.clearReason()).isEqualTo(AlarmInstance.ClearReason.MANUAL);
                softly.assertThat(value.clearedAt()).isNotNull();
            }
        };

        /** 对应人工事件类型。 */
        private final AlarmEvent.EventType eventType;

        /** @param eventType 成功时必须追加的不可变事件类型 */
        Mutation(AlarmEvent.EventType eventType) {
            this.eventType = eventType;
        }

        /** @return 使用相同初始version执行真实服务动作 */
        abstract AlarmInstance invoke(AlarmInstanceService service, Fixture fixture);

        /** 校验本动作独有的状态机输出。 */
        abstract void assertResult(org.assertj.core.api.SoftAssertions softly, AlarmInstance value);
    }

    /** 单次人工事件的持久身份与计数投影。 */
    private record ManualEvent(UUID tenantId, UUID projectId, UUID instanceId, UUID actorId,
                               UUID sourceMessageId, String type, long count) {
    }

    /** 并发调用结果同时保留成功值或真实失败。 */
    private record Outcome(AlarmInstance value, Throwable failure) {
    }

    /** 所有ID都指向真实父行；operator属于另一tenant但在目标项目有OPERATOR角色。 */
    private record Fixture(UUID ownerTenantId, UUID collaboratorTenantId, UUID projectId, UUID ownerId,
                           UUID operatorId, UUID typeId, UUID deviceId, UUID ruleId, UUID instanceId,
                           UUID sourceMessageId) {
    }
}
