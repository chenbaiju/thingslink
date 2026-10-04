package com.things.link.bootstrap.telemetry.command;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.DownlinkPreprocessingChain;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import com.things.link.testing.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** ADR0070：真实命令准入、冻结、身份及恢复验收；外部替身只证明调用，不冒称MQTT送达。 */
@Import(CommandDispatchProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"COMMAND_POSTGRES"})
class CommandDispatchProjectLifecycleTests extends AbstractIntegrationTest {
    /** 独占物理库防止自动领取或其他测试命令污染原信封证据。 */
    private static final String DATABASE_NAME = "command_dispatch_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 角色属于集群，沿真实迁移镜像使用独占容器。 */
    private static final PostgreSQLContainer<?> COMMAND_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Flyway、两个APP池和独立owner统一的实际落点。 */
    private static final String DATABASE_URL = startDatabase();
    /** 父runner使用共享库，不能让本类准备碰到其他测试库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedQuotaRunner;
    /** 禁止后台扫描推进当前真实命令；服务本身保持原事务代理。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedCommandScanner;
    /** 本类没有任务目标，禁止全局任务扫描旁路影响夹具。 */
    @MockitoBean(enforceOverride = true) private TaskSchedulingScanner unusedTaskScanner;
    /** 通知与本反例无关，仅禁自动业务领取，不替换真实线程池。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 无关回补不应争用验收数据库。 */
    @MockitoBean(enforceOverride = true) private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 唯一外部替身，只计MQTT发布端口调用而不证明Broker送达。 */
    @MockitoBean(enforceOverride = true) private CommandDownlinkPublisher publisher;
    /** 原事务受理入口生成命令、attempt与Outbox。 */
    @Autowired private DeviceCommandService commands;
    /** 原Kafka consumer直接接收原Outbox解码信封，不能手造合法业务身份。 */
    @Autowired private DeviceCommandDownlinkKafkaConsumer consumer;
    /** 原预处理链必须保留，不通过空替身绕过运行时合同。 */
    @Autowired private DownlinkPreprocessingChain preprocessing;
    /** 真实OWNER删除服务保留项目和历史命令。 */
    @Autowired private ProjectService projects;
    /** APP角色及专库运行落点取证。 */
    @Autowired private JdbcTemplate jdbc;
    /** 与生产一致的原信封解码器。 */
    @Autowired private ObjectMapper mapper;
    /** 每次参数化调用都生成独立合法祖先。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 只计当前原信封的外部边界次数。 */
    private final AtomicInteger publications = new AtomicInteger();

    /** 原生命周期方法保留真实SQL，仅在锁边界插入并发观察或数据库故障。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 原发送结果SQL执行之后才允许注入故障，验证部分写不能提交。 */
    @MockitoSpyBean private DeviceCommandRepository repository;
    /** 原Outbox解码的权威请求，每例只创建一个独立命令。 */
    private DeviceCommandDispatch request;
    /** 项目锁前观察只捕获真实事务连接，不替代许可结果。 */
    private Runnable beforePermit = () -> { };
    /** 项目锁后观察保持原事务，测试结束前必须解除全部外部等待。 */
    private Runnable afterPermit = () -> { };

    /** 真实受理完成后才安装观察器，避免将Console首受理与旧消息准入混为一个事务。 */
    @BeforeEach
    void prepare() throws Exception {
        verifyIsolation();
        seedParents();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        DeviceCommand accepted;
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            accepted = commands.submit(fixture.projectId(), fixture.deviceId(), "dispatch-freeze-" + fixture.projectId(),
                    "reboot", mapper.createObjectNode());
        } finally { TenantContext.clear(); }
        assertThat(accepted.status()).isEqualTo(DeviceCommand.Status.ACCEPTED);
        try (Connection owner = fixtureOwnerConnection()) {
            request = mapper.readValue(text(owner, "SELECT payload::text FROM sys_outbox_event WHERE project_id=? AND aggregate_id=? AND event_type='DEVICE_COMMAND_DISPATCH'", fixture.projectId(), accepted.id()), DeviceCommandDispatch.class);
            assertThat(text(owner, "SELECT outbox_event_id::text FROM ts_device_command_attempt WHERE id=? AND command_id=? AND status='PENDING' AND attempt_no=1", request.attemptId(), accepted.id())).isEqualTo(request.eventId().toString());
        }
        assertThat(request.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(request.projectId()).isEqualTo(fixture.projectId());
        assertThat(request.targetDeviceId()).isEqualTo(fixture.deviceId());
        assertThat(request.connectionDeviceId()).isEqualTo(fixture.deviceId());
        ProjectLifecycleAccessService lifecycleTarget = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("外发必须在短事务提交后").isFalse();
            assertThat((DeviceCommandDispatch) invocation.getArgument(0)).isEqualTo(request);
            var route=(com.things.link.device.application.DeviceMqttDownlinkRoute)invocation.getArgument(1);
            assertThat(route.deviceId()).isEqualTo(request.connectionDeviceId());
            assertThat(route.projectKey()).isEqualTo(request.projectKey());
            assertThat(route.deviceKey()).isEqualTo(request.connectionDeviceKey());
            publications.incrementAndGet();
            return Instant.now();
        }).when(publisher).publish(any(DeviceCommandDispatch.class), any(com.things.link.device.application.DeviceMqttDownlinkRoute.class));
        doAnswer(invocation -> {
            beforePermit.run();
            Object result = invocation.callRealMethod();
            if (Boolean.TRUE.equals(result)) afterPermit.run();
            return result;
        }).when(lifecycleTarget).lockActiveForWrite(any(UUID.class), any(UUID.class));
        assertStates("ACCEPTED", "PENDING", 0);
    }

    /** ADR0143：待发到期先退避，耗尽时只产生一个终态；原Outbox保留。 */
    @Test
    void pendingDeadlineRecoversAndTerminatesWithoutPublishing() throws Exception {
        expirePending(false);
        var due = pendingClaim();
        commands.processDue(due);
        assertStates("ACCEPTED", "FAILED", 0);
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(text(owner, "SELECT error_code FROM ts_device_command_attempt WHERE id=?", request.attemptId()))
                    .isEqualTo("DISPATCH_PENDING_TIMEOUT");
            assertThat(text(owner, "SELECT (deadline_at IS NULL AND retry_token IS NULL AND next_attempt_at > clock_timestamp())::text FROM ts_device_command WHERE id=?", request.commandId())).isEqualTo("true");
        }
        String after = facts();
        commands.processDue(due);
        assertThat(facts()).isEqualTo(after);
        assertThat(publications).hasValue(0);
    }

    /** 实际次数预算耗尽，重复领取身份不重复终态。 */
    @Test
    void exhaustedPendingProducesUniqueTerminal() throws Exception {
        expirePending(true);
        var due = pendingClaim();
        commands.processDue(due);
        assertStates("TIMED_OUT", "FAILED", 1);
        commands.processDue(due);
        assertStates("TIMED_OUT", "FAILED", 1);
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(text(owner, "SELECT failure_code FROM ts_device_command WHERE id=?", request.commandId()))
                    .isEqualTo("DISPATCH_RETRY_EXHAUSTED");
        }
    }

    /** 冻结优先于PENDING恢复，不创建后继尝试。 */
    @Test
    void frozenPendingStopsInsteadOfScheduling() throws Exception {
        expirePending(false);
        var due = pendingClaim();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
        commands.processDue(due);
        assertFrozen();
    }

    /** 后续CAS故障必须把领取消费和attempt失败一起回滚。 */
    @Test
    void pendingRecoveryRollsBackPartialWrites() throws Exception {
        expirePending(false);
        var due = pendingClaim();
        String before = facts();
        DeviceCommandRepository target = AopTestUtils.getUltimateTargetObject(repository);
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (fail.getAndSet(false)) throw new IllegalStateException("pending recovery rollback");
            return result;
        }).when(target).scheduleDispatchRetry(any(), any(), anyInt(), any());
        assertThat(catchThrowable(() -> commands.processDue(due))).hasMessage("pending recovery rollback");
        assertThat(facts()).isEqualTo(before);
        commands.processDue(due);
        assertStates("ACCEPTED", "FAILED", 0);
    }

    /** 到期时间是数据库夹具输入；不伪造任何已发布或回复事实。 */
    private void expirePending(boolean exhausted) throws Exception {
        if (exhausted) {
            for (int attempt = 1; attempt < 3; attempt++) {
                expirePending(false);
                commands.processDue(pendingClaim());
                try (Connection owner = fixtureOwnerConnection()) {
                    execute(owner, "UPDATE ts_device_command SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", request.commandId());
                }
                var retry = repository.claimDue(100).stream().filter(item -> item.commandId().equals(request.commandId())).findFirst().orElseThrow();
                commands.processDue(retry);
                try (Connection owner = fixtureOwnerConnection()) {
                    request = mapper.readValue(text(owner, "SELECT o.payload::text FROM sys_outbox_event o JOIN ts_device_command_attempt a ON a.outbox_event_id=o.id JOIN ts_device_command c ON c.id=a.command_id AND c.attempt_count=a.attempt_no WHERE c.id=?", request.commandId()), DeviceCommandDispatch.class);
                }
            }
        }
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE ts_device_command SET deadline_at=clock_timestamp()-interval '1 second', next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", request.commandId());
            execute(owner, "UPDATE ts_device_command_attempt SET deadline_at=clock_timestamp()-interval '1 second' WHERE id=?", request.attemptId());
        }
    }

    /** 真实受限函数返回待发分支身份。 */
    private DeviceCommandRepository.DueCommand pendingClaim() {
        var due = repository.claimDue(100).stream().filter(item -> item.commandId().equals(request.commandId())).findFirst().orElseThrow();
        assertThat(due.claimKind()).isEqualTo(DeviceCommandRepository.ClaimKind.PUSH_PENDING_TIMEOUT);
        return due;
    }

    /** ACTIVE可达；归档及真实OWNER删除可靠写冻结终态，原消息重复不会再外发或重复终态。 */
    @ParameterizedTest
    @EnumSource(ProjectState.class)
    void oldAcceptedDispatchRequiresCurrentProjectPermission(ProjectState state) throws Exception {
        if (state == ProjectState.DELETED) {
            TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
            try { projects.delete(fixture.projectId()); } finally { TenantContext.clear(); }
        } else if (state == ProjectState.ARCHIVED) {
            try (Connection owner = fixtureOwnerConnection()) {
                execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            }
        }
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(text(owner, "SELECT status FROM sys_project WHERE id=?", fixture.projectId()))
                    .isEqualTo(state == ProjectState.ARCHIVED ? "ARCHIVED" : state == ProjectState.DELETED ? "DELETING" : "ACTIVE");
            assertThat(text(owner, "SELECT (deleted_at IS NOT NULL)::text FROM sys_project WHERE id=?", fixture.projectId()))
                    .isEqualTo(state == ProjectState.DELETED ? "true" : "false");
        }
        consume(request);
        if (state == ProjectState.ACTIVE) {
            assertStates("DISPATCHED", "PUBLISHED", 0);
        } else {
            assertFrozen();
        }
        String after = facts();
        consume(request);
        assertThat(facts()).as("重放不能更新事实或重复终态Outbox").isEqualTo(after);
        assertThat(publications).hasValue(state == ProjectState.ACTIVE ? 1 : 0);
        if (state == ProjectState.ARCHIVED) {
            try (Connection owner = fixtureOwnerConnection()) {
                execute(owner, "UPDATE sys_project SET status='ACTIVE' WHERE id=?", fixture.projectId());
            }
            consume(request);
            assertThat(facts()).as("恢复项目不能复活冻结终态命令").isEqualTo(after);
            assertThat(publications).hasValue(0);
        }
    }

    /** 同一持久ID的不可变字段篡改必须明确拒绝，冻结不能把伪造消息转成合法失败事实。 */
    @ParameterizedTest
    @EnumSource(TamperedField.class)
    void tamperedPendingEnvelopeCannotSendOrPoisonCommand(TamperedField field) throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
        String before = facts();
        DeviceCommandDispatch tampered = new DeviceCommandDispatch(request.eventId(),
                field == TamperedField.TENANT ? Uuid7.generate() : request.tenantId(), request.projectId(),
                request.commandId(), request.attemptId(), request.attemptNo(), request.targetDeviceId(),
                field == TamperedField.TARGET_KEY ? "forged_target" : request.targetDeviceKey(),
                request.connectionDeviceId(), request.connectionDeviceKey(), request.projectKey(),
                request.operationType(), request.commandKey(),
                field == TamperedField.INPUT ? "{\"unexpected\":true}" : request.inputJson(),
                field == TamperedField.DEADLINE ? request.deadlineAt().plusSeconds(1) : request.deadlineAt(), request.traceId());
        assertThat(catchThrowable(() -> consume(tampered))).isInstanceOf(InvalidDownlinkMessageException.class);
        assertThat(facts()).isEqualTo(before);
        assertThat(publications).hasValue(0);
        consume(request);
        assertFrozen();
    }

    /** 老版未写operationType的原持久信封仍按COMMAND解析；不依赖Outbox确认已落库。 */
    @Test
    void legacyOperationTypeAndUnpublishedOutboxRemainDeliverable() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_outbox_event SET payload=(payload::jsonb - 'operationType')::text WHERE id=?", request.eventId());
            assertThat(text(owner, "SELECT (published_at IS NULL)::text FROM sys_outbox_event WHERE id=?", request.eventId())).isEqualTo("true");
            String payload = text(owner, "SELECT payload::text FROM sys_outbox_event WHERE id=?", request.eventId());
            assertThat(mapper.readTree(payload).has("operationType")).isFalse();
            request = mapper.readValue(payload, DeviceCommandDispatch.class);
        }
        consume(request);
        assertThat(publications).hasValue(1);
        assertStates("DISPATCHED", "PUBLISHED", 0);
    }

    /** 原Outbox缺失是事实损坏，不用当前设备路由补造身份，不改变当前PENDING。 */
    @Test
    void missingOriginalOutboxFailsClosed() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "DELETE FROM sys_outbox_event WHERE id=?", request.eventId());
        }
        String before = facts();
        assertThat(catchThrowable(() -> consume(request))).isInstanceOf(InvalidDownlinkMessageException.class);
        assertThat(facts()).isEqualTo(before);
        assertThat(publications).hasValue(0);
    }

    /** 许可先持SHARE，真实归档UPDATE必须等待准入提交；本次已获准发送可以继续。 */
    @Test
    void permitFirstBlocksArchiveUntilAdmissionCommits() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> archivePid = new CompletableFuture<>();
        CompletableFuture<Future<?>> archive = new CompletableFuture<>();
        afterPermit = () -> {
            int holder = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            Future<?> worker = executor.submit(() -> {
                try (Connection owner = fixtureOwnerConnection()) {
                    archivePid.complete(Integer.parseInt(text(owner, "SELECT pg_backend_pid()::text")));
                    execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            archive.complete(worker);
            try { assertBlocked(archivePid.get(3, TimeUnit.SECONDS), holder, worker); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        try {
            consume(request);
            archive.get(3, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            assertThat(publications).hasValue(1);
            assertStates("DISPATCHED", "PUBLISHED", 0);
            try (Connection owner = fixtureOwnerConnection()) {
                assertThat(text(owner, "SELECT status FROM sys_project WHERE id=?", fixture.projectId())).isEqualTo("ARCHIVED");
            }
        } finally { finish(executor); }
    }

    /** 冻结持锁在先，准入真实等待后必须重读，不能沿旧ACTIVE快照发送。 */
    @Test
    void archiveFirstRechecksAfterActualLockWait() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> consumerPid = new CompletableFuture<>();
        beforePermit = () -> consumerPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            Future<?> worker = executor.submit(() -> consume(request));
            try {
                assertBlocked(consumerPid.get(3, TimeUnit.SECONDS), Integer.parseInt(text(owner, "SELECT pg_backend_pid()::text")), worker);
                owner.commit();
                worker.get(5, TimeUnit.SECONDS);
                assertFrozen();
                assertThat(publications).hasValue(0);
            } finally { owner.rollback(); }
        } finally { finish(executor); }
    }

    /** ACTIVE许可成功后真实延迟约束在COMMIT失败，消费者绝不能提前调用publisher。 */
    @Test
    void failedAdmissionCommitPreventsPublisherAndAllowsRecovery() throws Exception {
        String before = facts();
        AtomicBoolean injected = new AtomicBoolean();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "CREATE FUNCTION command_dispatch_test_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='" + request.commandId() + "'::uuid THEN RAISE EXCEPTION 'command admission commit probe' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            execute(owner, "CREATE CONSTRAINT TRIGGER command_dispatch_test_commit_failure AFTER UPDATE ON ts_device_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION command_dispatch_test_commit_failure()");
        }
        afterPermit = () -> {
            assertThat(jdbc.update("UPDATE ts_device_command SET updated_at=updated_at WHERE id=?", request.commandId())).isEqualTo(1);
            injected.set(true);
        };
        try {
            Throwable failure = catchThrowable(() -> consume(request));
            assertThat(injected.get()).isTrue();
            assertThat(sqlState(failure)).isEqualTo("23514");
            assertThat(publications).hasValue(0);
            assertThat(facts()).isEqualTo(before);
        } finally {
            afterPermit = () -> { };
            try (Connection owner = fixtureOwnerConnection()) {
                execute(owner, "DROP TRIGGER command_dispatch_test_commit_failure ON ts_device_command");
                execute(owner, "DROP FUNCTION command_dispatch_test_commit_failure()");
            }
        }
        consume(request);
        assertThat(publications).hasValue(1);
        assertStates("DISPATCHED", "PUBLISHED", 0);
    }

    /** Broker替身已成功返回但真实状态SQL之后异常，两行回滚；重放可再次外发且最终收束。 */
    @Test
    void publicationThenStateWriteFailureReplaysAtLeastOnce() throws Exception {
        String before = facts();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object changed = invocation.callRealMethod();
            assertThat(changed).isEqualTo(true);
            assertThat(jdbc.queryForObject("SELECT status FROM ts_device_command WHERE id=?", String.class, request.commandId())).isEqualTo("DISPATCHED");
            assertThat(jdbc.queryForObject("SELECT status FROM ts_device_command_attempt WHERE id=?", String.class, request.attemptId())).isEqualTo("PUBLISHED");
            if (failOnce.getAndSet(false)) throw new IllegalStateException("command markDispatched after real write probe");
            return changed;
        }).when(repository).markDispatched(any(UUID.class), any(UUID.class), anyInt(), any(Instant.class));
        Throwable failure = catchThrowable(() -> consume(request));
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertThat(failOnce.get()).isFalse();
        assertThat(publications).hasValue(1);
        assertThat(facts()).isEqualTo(before);
        consume(request);
        assertThat(publications).hasValue(2);
        assertStates("DISPATCHED", "PUBLISHED", 0);
        consume(request);
        assertThat(publications).hasValue(2);
    }

    /** 直接调用真实consumer，明确不将此证据冒称Kafka提交offset或真实Broker送达。 */
    private void consume(DeviceCommandDispatch source) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            consumer.consume(new ConsumerRecord<>(DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, 0, 0L,
                    source.connectionDeviceId().toString(), source));
        } finally { TenantContext.clear(); }
    }

    /** 冻结终态必须同时保存两级原因和唯一终态事件，不以零sender代替持久收束。 */
    private void assertFrozen() throws Exception {
        assertStates("FAILED", "FAILED", 1);
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(text(owner, "SELECT failure_code FROM ts_device_command WHERE id=?", request.commandId())).isEqualTo("PROJECT_FROZEN");
            assertThat(text(owner, "SELECT error_code FROM ts_device_command_attempt WHERE id=?", request.attemptId())).isEqualTo("PROJECT_FROZEN");
            assertThat(text(owner, "SELECT payload::jsonb->>'status' FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'", request.commandId())).isEqualTo("FAILED");
            assertThat(text(owner, "SELECT payload::jsonb->>'failureCode' FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'", request.commandId())).isEqualTo("PROJECT_FROZEN");
        }
    }

    /** 每例唯一真实命令和attempt，不允许额外重试或重复终态事件混入。 */
    private void assertStates(String commandStatus, String attemptStatus, int terminalEvents) throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(text(owner, "SELECT status FROM ts_device_command WHERE id=?", request.commandId())).isEqualTo(commandStatus);
            assertThat(text(owner, "SELECT status FROM ts_device_command_attempt WHERE id=?", request.attemptId())).isEqualTo(attemptStatus);
            assertThat(text(owner, "SELECT count(*)::text FROM ts_device_command_attempt WHERE command_id=?", request.commandId())).isEqualTo(Integer.toString(request.attemptNo()));
            assertThat(text(owner, "SELECT count(*)::text FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'", request.commandId())).isEqualTo(Integer.toString(terminalEvents));
        }
    }

    /** 完整行快照包含内部领取列和诊断时间，错误信封不能留下隐性状态污染。 */
    private String facts() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            return text(owner, "SELECT row_to_json(c)::text FROM ts_device_command c WHERE id=?", request.commandId())
                    + text(owner, "SELECT row_to_json(a)::text FROM ts_device_command_attempt a WHERE id=?", request.attemptId())
                    + text(owner, "SELECT COALESCE(json_agg(o ORDER BY id)::text,'[]') FROM sys_outbox_event o WHERE aggregate_id=?", request.commandId());
        }
    }

    /** 观察真正PostgreSQL阻塞关系，后台线程提前成功或失败都不能当作锁等待证据。 */
    private void assertBlocked(int waiter, int holder, Future<?> worker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try (Connection owner = fixtureOwnerConnection()) {
            while (System.nanoTime() < deadline) {
                assertThat(worker.isDone()).as("目标必须实际阻塞，而非提前返回").isFalse();
                if ("true".equals(text(owner, "SELECT (? = ANY(pg_blocking_pids(?)))::text", holder, waiter))) return;
                Thread.sleep(10);
            }
        }
        throw new AssertionError("未观察到真实项目行锁等待");
    }

    /** 有界回收工作线程，不能把未完成事务或锁带入下一例。 */
    private static void finish(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(6, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 保留真实JDBC SQLSTATE，不把事务包装异常当成已命中数据库提交失败。 */
    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }

    /** 覆盖历史信封中影响租户、目标、输入和有效时间的独立篡改轴。 */
    private enum TamperedField {
        /** 持久tenant不能由Kafka自报替换。 */ TENANT,
        /** 网关报文的真正目标不能只依赖target UUID。 */ TARGET_KEY,
        /** 同ID也不能替换已冻结输入。 */ INPUT,
        /** 保留原信封精度且拒绝不同截止时间。 */ DEADLINE
    }

    /** 真实祖先满足OWNER、设备类型、命令Schema、项目和路由约束，命令事实只能由原服务创建。 */
    private void seedParents() throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '命令冻结反例租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '命令反例OWNER')", fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '命令冻结反例项目', 'sh-1', ?)", fixture.projectId(), fixture.tenantId(), "command_probe_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'command_probe_type', '命令反例类型', 'STANDARD', 'DIRECT', 'PUBLISHED')", fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?, ?, ?, ?, 'reboot', '重启', '{}'::jsonb, '{}'::jsonb, 30)", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, 'command_probe_device', '命令反例设备', 'ONLINE')", fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.commit();
        }
    }

    /** 必须使用真实APP角色、独立库和原事务代理，替身仅限外部端口及自动scanner。 */
    private void verifyIsolation() {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(AopUtils.isAopProxy(commands)).isTrue();
        assertThat(mockingDetails(consumer).isMock()).isFalse();
        assertThat(mockingDetails(preprocessing).isMock()).isFalse();
        for (Object disabled : List.of(unusedQuotaRunner, unusedCommandScanner, unusedTaskScanner,
                unusedNotificationCoordinator, unusedBackfillScanner)) assertThat(mockingDetails(disabled).isMock()).isTrue();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            }
        }
    }

    /** 不删除历史命令、Outbox或审计，专库由OwnedTestContainers在本类上下文物理关闭后回收；线程上下文不能泄漏到下例。 */
    @AfterEach
    void clearContext() { TenantContext.clear(); }

    /** 所有owner夹具与观察必须落专库，不能继承共享库连接。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, COMMAND_POSTGRES.getUsername(), COMMAND_POSTGRES.getPassword());
    }

    /** 参数化固定SQL且保留原五秒预算。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) query.setObject(index + 1, values[index]);
            query.executeUpdate();
        }
    }

    /** 单行完整事实读取，不用mock返回替代实际数据库结果。 */
    private String text(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) query.setObject(index + 1, values[index]);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** 静态启动专库，生命周期由本类OwnedTestContainers负责。 */
    private static String startDatabase() { COMMAND_POSTGRES.start(); return COMMAND_POSTGRES.getJdbcUrl(); }

    /** 原APP角色、Flyway和真实线程池保持，只覆盖专库与自动外部副作用开关。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** Registrar优先于父动态属性，防止Spring与owner误连不同数据库。 */
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

    /** 明确区分合法正常对照、归档和真实OWNER删除。 */
    private enum ProjectState {
        /** 原合法意图应进入外发边界一次。 */ ACTIVE,
        /** 已提交的归档状态必须拒绝旧意图。 */ ARCHIVED,
        /** 已提交的软删除仍保留历史命令，但不能借其外发。 */ DELETED
    }

    /** 全部身份均对应真实插入父表，不以随机不存在的UUID充当持久前置。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId, UUID deviceId) { }
}
