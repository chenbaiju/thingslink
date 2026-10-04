package com.things.link.bootstrap.alarm;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.alarm.application.NotificationDeliveryExecutionService;
import com.things.link.alarm.application.NotificationDeliveryProperties;
import com.things.link.alarm.application.NotificationRetryScheduler;
import com.things.link.alarm.application.NotificationSendException;
import com.things.link.alarm.application.AlarmEvaluationInput;
import com.things.link.alarm.application.AlarmEvaluationService;
import com.things.link.alarm.application.AlarmInstanceService;
import com.things.link.alarm.application.RuleAlarmActionInput;
import com.things.link.alarm.application.RuleAlarmActionService;
import com.things.link.alarm.domain.AlarmNotificationRepository;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.alarm.infrastructure.notification.EmailNotificationSender;
import com.things.link.alarm.infrastructure.notification.WebhookNotificationSender;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.support.resilience.NotificationExternalGuard;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.NotificationDeliveryRequest;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.AopTestUtils;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** ADR0069：专库中的原告警激活、真实token与worker入口证明冻结不外发、不重排队。 */
@Import(AlarmNotificationProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"AUTHORIZATION_POSTGRES"})
class AlarmNotificationProjectLifecycleTests extends AbstractIntegrationTest {
    /** 全局领取必须物理独占，不能靠随机项目掩盖其他候选。 */
    private static final String DATABASE_NAME = "alarm_notification_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 角色为集群事实，沿用真实迁移镜像而独占容器。 */
    private static final PostgreSQLContainer<?> AUTHORIZATION_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Spring、Flyway和owner统一实际地址。 */
    private static final String DATABASE_URL = startIsolatedDatabase();
    /** 禁止父runner修改共享库，专库使用真实默认配额。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota") private ApplicationRunner unusedRestQuotaRelaxation;
    /** 抑制全局自动领取，以下入口由测试显式执行。 */
    @MockitoBean(enforceOverride = true) private NotificationWorkCoordinator unusedNotificationWorkCoordinator;
    /** 原激活事务创建真实实例、事件、快照与Outbox。 */
    @Autowired private RuleAlarmActionService alarmActions;
    /** 与激活事实共享同一实例的人工确认及数据恢复入口。 */
    @Autowired private AlarmInstanceService instances;
    @Autowired private AlarmEvaluationService evaluations;
    /** 被测真实worker服务。 */
    @Autowired private NotificationDeliveryExecutionService execution;
    /** 真实受控领取及RLS仓储。 */
    @Autowired private AlarmNotificationRepository repository;
    /** 原重排队的事务Outbox端口。 */
    @Autowired private TransactionalOutboxRepository outbox;
    /** 原有退避与租约配置。 */
    @Autowired private NotificationDeliveryProperties properties;
    /** 与生产一致的时钟。 */
    @Autowired private Clock clock;
    /** 从真实Outbox解析，不手拼调度消息。 */
    @Autowired private ObjectMapper mapper;
    /** 两个APP池实际落点核验。 */
    @Autowired private JdbcTemplate jdbcTemplate;
    /** 原调度的短事务模板。 */
    @Autowired private TransactionTemplate transactions;
    /** 只替换外部效果，channel方法和其他Bean装配仍真实。 */
    @MockitoSpyBean private EmailNotificationSender emailSender;
    /** 只计调用，不声称HTTP供应商已送达。 */
    @MockitoSpyBean private WebhookNotificationSender webhookSender;
    /** 原项目许可，可观察真实原事务时序。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实OWNER删除，用于证明软删除仍拒绝保留的外部意图。 */
    @Autowired private ProjectService projects;
    /** 与生产发送共用真实本地资源，验证rollback与提交错误的资源回收。 */
    @Autowired private NotificationExternalGuard externalGuard;
    /** 每例独立业务祖先；不可变事件保留到专库回收。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 已冻结的真实首投Outbox信封。 */
    private NotificationDeliveryRequest request;
    /** 仅计外部边界调用，断言必须在所有数据库事务提交之后。 */
    private final AtomicInteger senderCalls = new AtomicInteger();

    /** 旧实现会冻结后发送并标成功；两种非PUSH渠道都必须留下PROJECT_FROZEN终态。 */
    @ParameterizedTest
    @EnumSource(value = NotificationChannel.class, names = {"EMAIL", "WEBHOOK"})
    void frozenQueuedIntentStopsWithoutSender(NotificationChannel channel) throws Exception {
        prepare(channel, false);
        Claimed claimed = claim(request);
        freeze();
        deliver(claimed);
        assertThat(senderCalls).hasValue(0);
        assertState("DEAD_LETTER", 1, "PROJECT_FROZEN");
        assertThat(outboxCount()).isEqualTo(1);
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE sys_project SET status = 'ACTIVE' WHERE id = ?", fixture.projectId());
        }
        execution.accept(request);
        deliver(claimed);
        assertState("DEAD_LETTER", 1, "PROJECT_FROZEN");
        assertThat(senderCalls).hasValue(0);
    }

    /** 先真实供应商失败并进入退避，再冻结；旧实现会再追加一个Outbox并回到QUEUED。 */
    @ParameterizedTest
    @EnumSource(value = NotificationChannel.class, names = {"EMAIL", "WEBHOOK"})
    void frozenRetryStopsWithoutAppendingOutbox(NotificationChannel channel) throws Exception {
        prepare(channel, true);
        deliver(claim(request));
        assertThat(senderCalls).hasValue(1);
        assertState("RETRY_SCHEDULED", 1, "INVALID_DELIVERY");
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE alarm_notification_delivery SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE project_id = ? AND status = 'RETRY_SCHEDULED'", fixture.projectId());
        }
        freeze();
        NotificationRetryScheduler scheduler = new NotificationRetryScheduler(repository, outbox, transactions, mapper, clock, properties, lifecycle);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            scheduler.enqueueDueRetries();
        }
        assertThat(outboxCount()).isEqualTo(1);
        assertState("DEAD_LETTER", 1, "PROJECT_FROZEN");
        assertThat(senderCalls).hasValue(1);
    }

    /** ACTIVE完成与终态重放保持原行为，sender只证明提交后的调用边界。 */
    @ParameterizedTest
    @EnumSource(value = NotificationChannel.class, names = {"EMAIL", "WEBHOOK"})
    void activeIntentSendsAfterCommitAndAbsorbsReplay(NotificationChannel channel) throws Exception {
        prepare(channel, false);
        Claimed claimed = claim(request);
        deliver(claimed);
        assertState("SUCCEEDED", 1, null);
        deliver(claimed);
        execution.accept(request);
        assertThat(senderCalls).hasValue(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    /** D-058旅程4：同一活动事故经历通知失败、ACK、数据恢复和第二次发送成功。 */
    @Test
    void alarmNotificationRetrySucceedsAfterAckAndDataRecovery() throws Exception {
        prepare(NotificationChannel.EMAIL, false);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            senderCalls.incrementAndGet();
            throw new NotificationSendException(NotificationSendException.Reason.SMTP_FAILURE, true, null);
        }).when(emailSender).send(any());
        Claimed first = claim(request);
        deliver(first);
        assertState("RETRY_SCHEDULED", 1, "SMTP_FAILURE");
        assertThat(senderCalls).hasValue(1);

        UUID instanceId;
        int version;
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT id, version FROM alarm_instance WHERE project_id = ? AND rule_id = ?")) {
            query.setObject(1, fixture.projectId());
            query.setObject(2, fixture.ruleId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                instanceId = rows.getObject(1, UUID.class);
                version = rows.getInt(2);
                assertThat(rows.next()).isFalse();
            }
        }
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            var acknowledged = instances.acknowledge(fixture.projectId(), instanceId, version);
            assertThat(acknowledged.conditionState()).isEqualTo(com.things.link.alarm.domain.AlarmInstance.ConditionState.ACTIVE);
            assertThat(acknowledged.ackState()).isEqualTo(com.things.link.alarm.domain.AlarmInstance.AckState.ACKNOWLEDGED);
            Instant recoveredAt = clock.instant().plusSeconds(2);
            evaluations.evaluate(new AlarmEvaluationInput(Uuid7.generate(), fixture.tenantId(), fixture.projectId(),
                    fixture.deviceId(), "temperature", 24, recoveredAt, recoveredAt,
                    "g3-local-15a-data-recovery"));
        } finally { TenantContext.clear(); }

        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM alarm_instance WHERE id = ? AND condition_state = 'CLEARED' AND ack_state = 'ACKNOWLEDGED' AND clear_reason = 'AUTO_RECOVERY'", instanceId)).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM alarm_event WHERE instance_id = ? AND event_type = 'ACTIVATED'", instanceId)).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM alarm_event WHERE instance_id = ? AND event_type = 'ACKNOWLEDGED'", instanceId)).isEqualTo(1);
            assertThat(count(owner, "SELECT count(*) FROM alarm_event WHERE instance_id = ? AND event_type = 'CLEARED'", instanceId)).isEqualTo(1);
            execute(owner, "UPDATE alarm_notification_delivery SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE project_id = ? AND id = ? AND status = 'RETRY_SCHEDULED'", fixture.projectId(), request.deliveryId());
        }

        runRetries(properties.retryLease());
        NotificationDeliveryRequest second = latestRequest();
        assertThat(second.deliveryId()).isEqualTo(request.deliveryId());
        assertThat(second.attemptNo()).isEqualTo(2);
        assertThat(second.eventId()).isNotEqualTo(request.eventId());
        assertThat(outboxCount()).isEqualTo(2);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            senderCalls.incrementAndGet();
            return "recorded-recovery-provider-call";
        }).when(emailSender).send(any());
        Claimed recovered = claim(second);
        deliver(recovered);
        assertState("SUCCEEDED", 2, null);
        deliver(first);
        deliver(recovered);
        assertThat(senderCalls).hasValue(2);
        assertThat(outboxCount()).isEqualTo(2);
    }

    /** 删除先提交，保留的原通知快照也必须收束，恢复ACTIVE不自动复活终态。 */
    @ParameterizedTest
    @EnumSource(value = NotificationChannel.class, names = {"EMAIL", "WEBHOOK"})
    void deletedProjectStopsPreservedIntent(NotificationChannel channel) throws Exception {
        prepare(channel, false);
        Claimed claimed = claim(request);
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { projects.delete(fixture.projectId()); } finally { TenantContext.clear(); }
        deliver(claimed);
        assertState("DEAD_LETTER", 1, "PROJECT_FROZEN");
        execution.accept(request);
        assertThat(senderCalls).hasValue(0);
    }

    /** 渠道全满先回滚start再release；冻结后仍在满容量下收束，不无限释放。 */
    @Test
    void saturatedGuardRollsBackAttemptButCannotDelayFrozenTerminal() throws Exception {
        prepare(NotificationChannel.EMAIL, false);
        Claimed claimed = claim(request);
        List<NotificationExternalGuard.Guard> held = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) held.add(externalGuard.tryAcquire("EMAIL", "capacity@example.com"));
            assertThat(held).doesNotContainNull();
            deliver(claimed);
            assertState("QUEUED", 0, null);
            try (Connection owner = ownerConnection()) {
                assertThat(count(owner, "SELECT count(*) FROM alarm_notification_delivery WHERE id = ? AND dispatch_lease_token IS NULL AND dispatch_leased_until IS NULL AND dispatch_available_at > updated_at", request.deliveryId())).isEqualTo(1);
                execute(owner, "UPDATE alarm_notification_delivery SET dispatch_available_at = clock_timestamp() - interval '1 second' WHERE id = ?", request.deliveryId());
            }
            freeze();
            deliver(claim(request));
            assertState("DEAD_LETTER", 1, "PROJECT_FROZEN");
            assertThat(senderCalls).hasValue(0);
        } finally { held.stream().filter(java.util.Objects::nonNull).forEach(NotificationExternalGuard.Guard::close); }
    }

    /** event与token分别覆盖：当前token的错误信封回滚，旧token保持原no-op且不触达许可。 */
    @Test
    void wrongEventRollsBackStartedAttemptWhileStaleTokenIsNoOp() throws Exception {
        prepare(NotificationChannel.WEBHOOK, false);
        Claimed claimed = claim(request);
        String before = deliveryFacts();
        NotificationDeliveryRequest wrong = new NotificationDeliveryRequest(Uuid7.generate(), request.tenantId(),
                request.projectId(), request.deliveryId(), request.alarmInstanceId(), request.alarmEventId(),
                request.attemptNo(), request.requestedAt(), request.traceId());
        assertThatThrownBy(() -> deliver(new Claimed(wrong, claimed.leaseToken())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("通知请求与投递事实不匹配");
        assertThat(deliveryFacts()).isEqualTo(before);
        deliver(new Claimed(wrong, Uuid7.generate()));
        assertThat(deliveryFacts()).isEqualTo(before);
        assertThat(senderCalls).hasValue(0);
        deliver(claimed);
        assertState("SUCCEEDED", 1, null);
    }

    /** 错误tenant不能借正确project/id启动真实行，范围错误也不能变为PROJECT_FROZEN终态。 */
    @Test
    void wrongTenantCannotMutateKnownDelivery() throws Exception {
        prepare(NotificationChannel.EMAIL, false);
        Claimed claimed = claim(request);
        String before = deliveryFacts();
        NotificationDeliveryRequest wrong = new NotificationDeliveryRequest(request.eventId(), Uuid7.generate(),
                request.projectId(), request.deliveryId(), request.alarmInstanceId(), request.alarmEventId(),
                request.attemptNo(), request.requestedAt(), request.traceId());
        assertThatThrownBy(() -> deliver(new Claimed(wrong, claimed.leaseToken())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(deliveryFacts()).isEqualTo(before);
        assertThat(senderCalls).hasValue(0);
    }

    /** 调用方有实际事务时拒绝，整个原投递与token保持原样。 */
    @Test
    void ambientTransactionIsRejectedBeforeAttemptMutation() throws Exception {
        prepare(NotificationChannel.EMAIL, false);
        Claimed claimed = claim(request);
        String before = deliveryFacts();
        scoped(() -> assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> execution.deliverClaimed(request, claimed.leaseToken())))
                .isInstanceOf(IllegalStateException.class).hasMessage("非PUSH发送不得加入调用方事务"));
        assertThat(deliveryFacts()).isEqualTo(before);
        assertThat(senderCalls).hasValue(0);
    }

    /** 项目UPDATE先持锁时发送真实阻塞；提交归档后原start直接同事务死信。 */
    @Test
    void archiveFirstBlocksAdmissionThenStopsWithoutSender() throws Exception {
        prepare(NotificationChannel.EMAIL, false);
        Claimed claimed = claim(request);
        CompletableFuture<Integer> permitPid = capturePermitPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                Future<?> operation = executor.submit(() -> deliver(claimed));
                assertBlockedBy(permitPid.get(3, TimeUnit.SECONDS), holderPid, operation);
                holder.commit();
                operation.get(5, TimeUnit.SECONDS);
                assertState("DEAD_LETTER", 1, "PROJECT_FROZEN");
                assertThat(senderCalls).hasValue(0);
            } finally { holder.rollback(); shutdown(executor); }
        }
    }

    /** 许可先取得时归档UPDATE被真实SHARE阻塞；许可提交后sender允许继续且没有长事务。 */
    @Test
    void admittedSenderDoesNotHoldProjectLockAcrossExternalIo() throws Exception {
        prepare(NotificationChannel.WEBHOOK, false);
        Claimed claimed = claim(request);
        CountDownLatch permitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> permitPid = new CompletableFuture<>();
        CompletableFuture<Void> archived = new CompletableFuture<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            archived.get(5, TimeUnit.SECONDS);
            senderCalls.incrementAndGet();
            return "recorded-after-archive";
        }).when(webhookSender).send(any());
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            Object allowed = invocation.callRealMethod();
            permitPid.complete(appPid()); permitted.countDown(); awaitRelease(release);
            return allowed;
        }).when(target).lockActiveForWrite(fixture.tenantId(), fixture.projectId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> sending = executor.submit(() -> deliver(claimed));
            assertThat(permitted.await(3, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> archivePid = new CompletableFuture<>();
            Future<?> archiving = executor.submit(() -> {
                try (Connection owner = ownerConnection()) {
                    archivePid.complete((int) count(owner, "SELECT pg_backend_pid()"));
                    execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
                    archived.complete(null);
                } catch (SQLException failure) { throw new IllegalStateException(failure); }
            });
            assertBlockedBy(archivePid.get(3, TimeUnit.SECONDS), permitPid.get(3, TimeUnit.SECONDS), archiving);
            release.countDown(); sending.get(5, TimeUnit.SECONDS); archiving.get(5, TimeUnit.SECONDS);
            assertState("SUCCEEDED", 1, null);
            assertThat(senderCalls).hasValue(1);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** PG57014发生于项目许可，启动整体回滚且原token可恢复，不计渠道失败。 */
    @Test
    void realPermissionTimeoutRollsBackAndOriginalTokenRecovers() throws Exception {
        prepare(NotificationChannel.WEBHOOK, false);
        Claimed claimed = claim(request);
        String before = deliveryFacts();
        CompletableFuture<Integer> permitPid = capturePermitPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET updated_at = updated_at WHERE id = ?", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                Future<Throwable> attempt = executor.submit(() -> catchThrowable(() -> deliver(claimed)));
                assertBlockedBy(permitPid.get(3, TimeUnit.SECONDS), holderPid, attempt);
                assertThat(sqlState(attempt.get(10, TimeUnit.SECONDS))).isEqualTo("57014");
                assertThat(deliveryFacts()).isEqualTo(before);
                assertThat(senderCalls).hasValue(0);
                holder.rollback();
            } finally { holder.rollback(); shutdown(executor); }
        }
        deliver(claimed);
        assertState("SUCCEEDED", 1, null);
    }

    /** 延迟约束只在真实COMMIT报错；未发送许可必须回收，start与token全部回滚。 */
    @Test
    void realCommitFailureCancelsGuardAndRestoresDispatchFact() throws Exception {
        prepare(NotificationChannel.WEBHOOK, false);
        Claimed claimed = claim(request);
        String before = deliveryFacts();
        try (Connection owner = ownerConnection()) {
            execute(owner, "CREATE FUNCTION alarm_notify_test_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='" + request.deliveryId() + "'::uuid AND NEW.status='SENDING' THEN RAISE EXCEPTION 'probe commit failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            execute(owner, "CREATE CONSTRAINT TRIGGER alarm_notify_test_commit_failure AFTER UPDATE ON alarm_notification_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION alarm_notify_test_commit_failure()");
        }
        try {
            assertThat(sqlState(catchThrowable(() -> deliver(claimed)))).isEqualTo("23514");
            assertThat(deliveryFacts()).isEqualTo(before);
            assertThat(senderCalls).hasValue(0);
            List<NotificationExternalGuard.Guard> recovered = new ArrayList<>();
            try {
                for (int index = 0; index < 4; index++) recovered.add(externalGuard.tryAcquire("WEBHOOK", "https://alarm.example.com/webhook"));
                assertThat(recovered).doesNotContainNull();
            } finally { recovered.stream().filter(java.util.Objects::nonNull).forEach(NotificationExternalGuard.Guard::close); }
        } finally {
            try (Connection owner = ownerConnection()) {
                execute(owner, "DROP TRIGGER alarm_notify_test_commit_failure ON alarm_notification_delivery");
                execute(owner, "DROP FUNCTION alarm_notify_test_commit_failure()");
            }
        }
        deliver(claimed);
        assertState("SUCCEEDED", 1, null);
    }

    /** 许可等待跨过真实retry租约时，最终CAS必须拒绝；不能用许可前保存的now延长资格。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryLeaseExpiresWhileWaitingForPermissionWithoutAppendingOrStopping(boolean freezeAfterWait) throws Exception {
        prepare(NotificationChannel.EMAIL, false);
        createDueRetry(request);
        CompletableFuture<Integer> permitPid = capturePermitPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                execute(holder, "UPDATE sys_project SET status = ? WHERE id = ?", freezeAfterWait ? "ARCHIVED" : "ACTIVE", fixture.projectId());
                int holderPid = (int) count(holder, "SELECT pg_backend_pid()");
                Future<?> operation = executor.submit(() -> runRetries(properties.retryLease()));
                assertBlockedBy(permitPid.get(3, TimeUnit.SECONDS), holderPid, operation);
                try (Connection observer = ownerConnection()) {
                    // 先证明worker已持正常租约进入真实项目锁等待，再缩短同token租期；慢CI不能在观察前丢资格。
                    UUID claimedToken;
                    try (PreparedStatement query = observer.prepareStatement("""
                            SELECT retry_lease_token FROM alarm_notification_delivery
                             WHERE tenant_id = ? AND project_id = ? AND id = ? AND status = 'RETRY_SCHEDULED'
                               AND retry_lease_token IS NOT NULL AND retry_leased_until > clock_timestamp()
                            """)) {
                        query.setObject(1, fixture.tenantId()); query.setObject(2, fixture.projectId());
                        query.setObject(3, request.deliveryId());
                        try (ResultSet rows = query.executeQuery()) {
                            assertThat(rows.next()).isTrue(); claimedToken = rows.getObject(1, UUID.class);
                            assertThat(rows.next()).isFalse();
                        }
                    }
                    try (PreparedStatement shorten = observer.prepareStatement("""
                            UPDATE alarm_notification_delivery
                               SET retry_leased_until = clock_timestamp() + interval '1 second'
                             WHERE tenant_id = ? AND project_id = ? AND id = ? AND retry_lease_token = ?
                               AND status = 'RETRY_SCHEDULED' AND retry_leased_until > clock_timestamp()
                            """)) {
                        shorten.setObject(1, fixture.tenantId()); shorten.setObject(2, fixture.projectId());
                        shorten.setObject(3, request.deliveryId()); shorten.setObject(4, claimedToken);
                        assertThat(shorten.executeUpdate()).isEqualTo(1);
                    }
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (count(observer, "SELECT count(*) FROM alarm_notification_delivery WHERE id = ? AND retry_lease_token = ? AND retry_leased_until <= clock_timestamp()", request.deliveryId(), claimedToken) == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                    assertThat(count(observer, "SELECT count(*) FROM alarm_notification_delivery WHERE id = ? AND retry_lease_token = ? AND retry_leased_until <= clock_timestamp()", request.deliveryId(), claimedToken)).isEqualTo(1);
                }
                holder.commit(); operation.get(5, TimeUnit.SECONDS);
                assertState("RETRY_SCHEDULED", 1, "INVALID_DELIVERY");
                assertThat(outboxCount()).isEqualTo(1);
                assertThat(senderCalls).hasValue(0);
            } finally { holder.rollback(); shutdown(executor); }
        }
        runRetries(properties.retryLease());
        assertState(freezeAfterWait ? "DEAD_LETTER" : "QUEUED", 1, freezeAfterWait ? "PROJECT_FROZEN" : "INVALID_DELIVERY");
        assertThat(outboxCount()).isEqualTo(freezeAfterWait ? 1 : 2);
    }

    /** 第三次SENDING崩溃仍被真实claim恢复并冻结，保留attempt=3，不制造第四次尝试。 */
    @Test
    void thirdSendingCrashCanBeClaimedAndStoppedWithoutFourthAttempt() throws Exception {
        prepare(NotificationChannel.WEBHOOK, false);
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThat(request.attemptNo()).isEqualTo(attempt);
            createDueRetry(request);
            runRetries(properties.retryLease());
            request = latestRequest();
        }
        assertThat(request.attemptNo()).isEqualTo(3);
        Claimed claimed = claim(request);
        scoped(() -> transactions.executeWithoutResult(status -> assertThat(repository.startDelivery(
                request.projectId(), request.deliveryId(), 3, claimed.leaseToken(), clock.instant(), clock.instant().plusSeconds(60))).isTrue()));
        assertState("SENDING", 3, null);
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE alarm_notification_delivery SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?", request.deliveryId());
        }
        freeze(); runRetries(properties.retryLease());
        assertState("DEAD_LETTER", 3, "PROJECT_FROZEN");
        assertThat(outboxCount()).isEqualTo(3);
        assertThat(senderCalls).hasValue(0);
    }

    /** 仓储原start及markRetry建立真实退避，仅调整到期字段；不是手写状态或有效token。 */
    private void createDueRetry(NotificationDeliveryRequest source) throws SQLException {
        Claimed claimed = claim(source);
        scoped(() -> transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            assertThat(repository.startDelivery(source.projectId(), source.deliveryId(), source.attemptNo(),
                    claimed.leaseToken(), now, now.plusSeconds(60))).isTrue();
            assertThat(repository.markDeliveryRetry(source.projectId(), source.deliveryId(), source.attemptNo(),
                    now.minusSeconds(1), "INVALID_DELIVERY", now)).isTrue();
        }));
    }

    /** 保留生产批次与所有预算，只指定本例需要观察的合法retry租期。 */
    private void runRetries(Duration lease) {
        NotificationDeliveryProperties configured = new NotificationDeliveryProperties(properties.requestTimeout(),
                properties.firstRetryDelay(), properties.secondRetryDelay(), properties.retryBatchSize(), lease, properties.webhook());
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            new NotificationRetryScheduler(repository, outbox, transactions, mapper, clock, configured, lifecycle).enqueueDueRetries();
        }
    }

    /** 数据平面事务范围与真实归属一致，仅用于调用原仓储建立故障前置。 */
    private void scoped(Runnable work) {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) { work.run(); }
        finally { TenantContext.clear(); }
    }

    /** 完整delivery行对比涵盖lease、attempt、快照与终态，不只看受影响行数。 */
    private String deliveryFacts() throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("SELECT row_to_json(d)::text FROM alarm_notification_delivery d WHERE id = ?")) {
            query.setObject(1, request.deliveryId());
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
        }
    }

    /** 真实许可前记录APP PID，绝不替代或跳过许可SQL。 */
    private CompletableFuture<Integer> capturePermitPid() {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> { pid.complete(appPid()); return invocation.callRealMethod(); })
                .when(target).lockActiveForWrite(fixture.tenantId(), fixture.projectId());
        return pid;
    }

    /** 只能观察当前实际非只读APP事务，owner连接不能冒充业务PID。 */
    private int appPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 保留真实SQLState，不把连接失败或Java断言误算成PG取消。 */
    private String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    /** 屏障失败显式抛出，中断保持线程语义。 */
    private void awaitRelease(CountDownLatch release) {
        try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    /** 真实激活前验证专库和空领取域；发送替身只记录调用并可产生明确可重试结果。 */
    private void prepare(NotificationChannel channel, boolean failSend) throws Exception {
        verifyDatabaseIsolationBeforeFixtureWrites();
        seedFixture(channel);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            senderCalls.incrementAndGet();
            if (failSend) throw new NotificationSendException(NotificationSendException.Reason.INVALID_DELIVERY, true, null);
            return "recorded-provider-call";
        }).when(emailSender).send(any());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            senderCalls.incrementAndGet();
            if (failSend) throw new NotificationSendException(NotificationSendException.Reason.INVALID_DELIVERY, true, null);
            return "recorded-provider-call";
        }).when(webhookSender).send(any());
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try {
            Instant now = clock.instant();
            assertThat(alarmActions.create(new RuleAlarmActionInput(fixture.messageId(), fixture.tenantId(), fixture.projectId(),
                    fixture.ruleId(), fixture.deviceId(), now, now, "alarm-life-" + fixture.messageId())).changed()).isTrue();
        } finally { TenantContext.clear(); }
        request = latestRequest();
        assertState("QUEUED", 0, null);
    }

    /** 冻结现有项目且保留旧告警、路由和收件人，拒绝不能归因于删除夹具。 */
    private void freeze() throws SQLException {
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE sys_project SET status = 'ARCHIVED' WHERE id = ?", fixture.projectId());
        }
    }

    /** 持久状态、attempt及原终态原因来自owner独立观察。 */
    private void assertState(String state, int attempts, String error) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT status, attempt_count, last_error_code FROM alarm_notification_delivery WHERE project_id = ?")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(state);
                assertThat(rows.getInt(2)).isEqualTo(attempts);
                assertThat(rows.getString(3)).isEqualTo(error);
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** 只能观察本例真实持久Outbox，不借mock调用次数充当原子性证据。 */
    private long outboxCount() throws SQLException {
        try (Connection owner = ownerConnection()) {
            return count(owner, "SELECT count(*) FROM sys_outbox_event WHERE project_id = ? AND aggregate_type = 'ALARM_NOTIFICATION_DELIVERY'", fixture.projectId());
        }
    }
    /** 三个独立PID、阻塞者和未授予锁证明真实排序，不只观察Future尚未完成。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        assertThat(waiter).isNotEqualTo(holder);
        try (Connection observer = ownerConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)
                """)) {
            query.setInt(1, holder); query.setInt(2, waiter); query.setInt(3, waiter);
            query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waiter).isNotEqualTo(holder);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) throw new AssertionError("操作已完成却未观察到预期项目锁等待");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定项目许可锁等待");
    }

    /** 各测试先释放持锁事务/屏障，再收束线程，失败不能泄漏后台写入。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 专库先于Spring普通单例与Flyway初始化，本类结束且Spring物理关闭后由OwnedTestContainers回收。 */
    private static String startIsolatedDatabase() {
        AUTHORIZATION_POSTGRES.start();
        return AUTHORIZATION_POSTGRES.getJdbcUrl();
    }

    /** 动工前验证两个连接池和owner实际落点及RLS权限，不把配置字符串当作隔离证据。 */
    private void verifyDatabaseIsolationBeforeFixtureWrites() throws SQLException {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        // 具名且enforceOverride的mock确实替代父runner；不依赖首次启动调用记录，避免Mockito每例重置造成假失败。
        assertThat(mockingDetails(unusedRestQuotaRelaxation).isMock()).isTrue();
        assertThat(mockingDetails(unusedNotificationWorkCoordinator).isMock()).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                Map<String, Object> identity = jdbcTemplate.queryForMap("SELECT current_database(), current_user");
                assertThat(identity.get("current_database")).isEqualTo(DATABASE_NAME);
                assertThat(identity.get("current_user")).isEqualTo(APP_ROLE);
                assertThat(jdbcTemplate.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("SELECT current_database(), current_user")) {
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME).isNotEqualTo(POSTGRES.getDatabaseName());
                assertThat(rows.getString(2)).isEqualTo(AUTHORIZATION_POSTGRES.getUsername());
            }
            // 本类不运行自动worker；每例精确清理后的全库必须无剩余Outbox。
            assertThat(count(owner, "SELECT count(*) FROM sys_outbox_event")).isZero();
        }
    }

    /** 只从原持久Outbox解析当前event/attempt；函数领取必须随后证明对应唯一delivery。 */
    private NotificationDeliveryRequest latestRequest() throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT o.payload::text FROM sys_outbox_event o JOIN alarm_notification_delivery d ON d.last_outbox_event_id = o.id WHERE d.project_id = ?")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                NotificationDeliveryRequest result = mapper.readValue(rows.getString(1), NotificationDeliveryRequest.class);
                assertThat(rows.next()).isFalse(); return result;
            }
        }
    }

    /** 专库唯一候选才允许真实全局领取；lease由仓储受控函数生成，不能手写或复用失效值。 */
    private Claimed claim(NotificationDeliveryRequest source) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(count(owner, "SELECT count(*) FROM alarm_notification_delivery WHERE status = 'QUEUED'")).isEqualTo(1);
            // createDelivery未写dispatch_available_at，它由数据库DEFAULT now()生成；重排队亦保留此DB时刻。
            // created_at/JVM消息时间仅参与排序，不控制领取资格；按同一数据库时钟确认当前唯一候选已到期。
            assertThat(count(owner, "SELECT count(*) FROM alarm_notification_delivery WHERE project_id = ? AND id = ? AND status = 'QUEUED' AND dispatch_available_at <= clock_timestamp() AND (dispatch_leased_until IS NULL OR dispatch_leased_until <= clock_timestamp())",
                    source.projectId(), source.deliveryId())).isEqualTo(1);
        }
        AlarmNotificationRepository.DispatchClaim claim;
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            claim = repository.claimDispatches(1, Duration.ofSeconds(30));
        }
        assertThat(claim.deliveries()).hasSize(1);
        var candidate = claim.deliveries().getFirst();
        assertThat(candidate.id()).isEqualTo(source.deliveryId());
        assertThat(candidate.eventId()).isEqualTo(source.eventId());
        assertThat(candidate.projectId()).isEqualTo(source.projectId());
        assertThat(candidate.tenantId()).isEqualTo(source.tenantId());
        assertThat(candidate.attemptNo()).isEqualTo(source.attemptNo());
        return new Claimed(source, claim.leaseToken());
    }

    /** 与真实worker相同，execution没有外层测试事务并走DATA池，完成后清空全部上下文。 */
    private void deliver(Claimed claimed) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            execution.deliverClaimed(claimed.request(), claimed.leaseToken());
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TenantContext.current()).isEmpty();
            assertThat(RlsScopeContext.current()).isEmpty();
        }
    }

    /** 有效设备、关系与非PUSH路由按真实schema构造；额度使用默认真实FREE策略，不mock仓储。 */
    private void seedFixture(NotificationChannel channel) throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '告警受众生命周期租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name) VALUES (?, ?, '{noop}unused', '告警规则owner')", fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member (id, tenant_id, account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, region, project_key) VALUES (?, ?, '外部通知项目', 'sh-1', ?)", fixture.projectId(), fixture.tenantId(), "external_notification_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member (id, project_id, account_id, role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, access_protocol, device_kind, status) VALUES (?, ?, ?, 'external_notification_type', '受众设备类型', 'STANDARD', 'DIRECT', 'PUBLISHED')", fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status) VALUES (?, ?, ?, ?, 'external_notification_device', '受众设备', 'ONLINE')", fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, "INSERT INTO dev_property_definition (id, tenant_id, project_id, device_type_id, property_key, name, access_type, data_type) VALUES (?, ?, ?, ?, 'temperature', '温度', 'REPORT', 'NUMBER')", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO alarm_rule (id, tenant_id, project_id, name, alarm_type, originator_type, originator_id,
                        property_key, trigger_operator, trigger_threshold, clear_operator, clear_threshold, severity)
                    VALUES (?, ?, ?, '生命周期受众规则', 'LIFECYCLE_NOTIFICATION', 'DEVICE', ?, 'temperature', 'GT', 30, 'LT', 25, 'WARNING')
                    """, fixture.ruleId(), fixture.tenantId(), fixture.projectId(), fixture.deviceId());
            execute(owner, "INSERT INTO alarm_notification_group (id, tenant_id, project_id, name) VALUES (?, ?, ?, '外部通知组')", fixture.groupId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO alarm_notification_template (id, tenant_id, project_id, name, channel, subject_template, body_template) VALUES (?, ?, ?, '外部通知模板', ?, '${alarm.type}', '告警值=${alarm.value}')", fixture.templateId(), fixture.tenantId(), fixture.projectId(), channel.name());
            execute(owner, "INSERT INTO alarm_notification_binding (id, tenant_id, project_id, rule_id, group_id, template_id, channel) VALUES (?, ?, ?, ?, ?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.ruleId(), fixture.groupId(), fixture.templateId(), channel.name());
            execute(owner, "INSERT INTO alarm_notification_recipient (id, tenant_id, project_id, group_id, channel, target) VALUES (?, ?, ?, ?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.groupId(), channel.name(), channel == NotificationChannel.EMAIL ? "alarm@example.com" : "https://alarm.example.com/webhook");
            owner.commit();
        }
    }

    /** 精确清理可调度业务夹具；不可变alarm_event及祖先留待专库回收，不关闭守卫。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear(); RlsScopeContext.clear();
        try (Connection owner = ownerConnection()) {
            execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ? AND aggregate_type = 'ALARM_NOTIFICATION_DELIVERY'", fixture.projectId());
            execute(owner, "DELETE FROM alarm_notification_delivery WHERE project_id = ?", fixture.projectId());
        }
    }
    /** owner只用于种子、独立观察和清理，告警激活与删除始终通过真实APP角色与业务服务。 */
    private Connection ownerConnection() throws SQLException {
        return fixtureOwnerConnection();
    }

    /** 基类可复用播种入口同样指向专库，防止未来增加版本夹具时回落共享数据库。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, AUTHORIZATION_POSTGRES.getUsername(), AUTHORIZATION_POSTGRES.getPassword());
    }

    /** 参数化SQL固定本例身份，不拼接设备或用户输入。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 查询必须有结果，不能把数据库错误或不可见行伪装成零。 */
    private long count(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** Registrar晚于继承的DynamicPropertySource注册，防止父类URL覆盖本类物理隔离。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 运行/Flyway统一专库；本类验证创建事务，不启用自动发布、通知重试或Kafka listener。 */
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

    /** @param request 原持久信封 @param leaseToken 真实函数产生的当前领取租约 */
    private record Claimed(NotificationDeliveryRequest request, UUID leaseToken) { }
    /** @param tenantId 真实租户 @param projectId 告警项目 @param accountId OWNER @param typeId 设备类型 @param deviceId 来源设备 @param ruleId 告警规则 @param groupId 收件组 @param templateId 模板 @param messageId 原始激活消息 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId, UUID deviceId,
                           UUID ruleId, UUID groupId, UUID templateId, UUID messageId) { }
}
