package com.things.link.bootstrap.rule;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.rule.application.RuleNotificationDeliveryService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.resilience.NotificationExternalGuard;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.outbox.OutboxEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.doAnswer;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.things.link.rule.application.RuleNotificationDeliveryStore;
import com.things.link.rule.application.RuleNotificationRetryScheduler;
import com.things.link.shared.message.RuleNotificationDeliveryRequest;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.notification.delivery.ExternalNotificationSender;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** ADR0069旧生产真实反例及永久验收；发送器替身仅证明调用，绝不冒充外部送达。 */
@Import(RuleNotificationProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"PROBE_POSTGRES"})
class RuleNotificationProjectLifecycleTests extends AbstractIntegrationTest {
    /** 物理专库隔离全局领取，历史不可变规则由容器回收。 */
    private static final String DATABASE_NAME = "rule_notify_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 沿原PG镜像、owner和APP角色。 */
    private static final PostgreSQLContainer<?> PROBE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Flyway与Runtime统一专库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 保留真实线程池供Q13退出核验，仅取消本类显式驱动的规则retry定时注册。 */
    @Autowired @Qualifier("notificationLifecycleScheduler") private ThreadPoolTaskScheduler lifecycleScheduler;
    /** 查找真实ScheduledTask，不以mock基础设施掩盖资源回收。 */
    @Autowired private ScheduledAnnotationBeanPostProcessor scheduled;
    /** 原worker不能抢走测试显式领取的候选。 */
    @MockitoBean(enforceOverride=true) private NotificationWorkCoordinator unusedCoordinator;
    /** 父runner固定共享库，在专库禁止运行。 */
    @MockitoBean(enforceOverride=true,name="relaxRestQuota") private ApplicationRunner unusedQuota;
    /** 仅外部发送端口替身；原规则适配器、guard、服务、SQL保持真实。 */
    @MockitoBean(name=ExternalNotificationSender.EMAIL_BEAN) private ExternalNotificationSender sender;
    /** 原发送入口自行维持事务。 */
    @Autowired private RuleNotificationDeliveryService service;
    /** 原受限SQL状态机。 */
    @Autowired private RuleNotificationDeliveryStore store;
    /** 原重试入口自行领取并逐条短事务。 */
    @Autowired private RuleNotificationRetryScheduler retries;
    /** 原APP角色和池检查。 */
    @Autowired private JdbcTemplate applicationJdbc;
    /** 原OWNER删除端口，不用直接改deleted_at冒充业务删除。 */
    @Autowired private ProjectService projects;
    /** 仅在真实append成功后注入异常，证明重排队和Outbox同事务回滚。 */
    @MockitoSpyBean private TransactionalOutboxRepository outbox;
    /** 原持续项目许可只做观察或屏障，不stub返回值。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 实际本地guard验证容量回滚与提交失败释放。 */
    @Autowired private NotificationExternalGuard guard;
    /** ambient事务拒绝反例不能以单元ThreadLocal替代真实事务。 */
    @Autowired private TransactionTemplate transactions;
    /** 真实许可SQL前观察。 */
    private volatile Runnable beforePermit = () -> { };
    /** 已取得真实许可后观察，SQL结束仍在原短事务。 */
    private volatile Runnable afterPermit = () -> { };
    /** 真实发送调用计数。 */
    private final AtomicInteger sends = new AtomicInteger();

    /** 显式确认物理落点及外发不存在活动事务。 */
    @BeforeEach
    void prepare() throws Exception {
        var retryTasks=scheduled.getScheduledTasks().stream().filter(task -> {
            Runnable runnable=task.getTask().getRunnable();
            // Spring 7.0.8 Task源码明确包装为OutcomeTrackingRunnable，取其原Runnable才有方法身份。
            // 只解开已核验的这一层；未来包装变化仍由精确1条断言失败，不宽泛取消其他调度。
            if(runnable.getClass().getName().equals("org.springframework.scheduling.config.Task$OutcomeTrackingRunnable"))
                runnable=(Runnable)ReflectionTestUtils.getField(runnable,"runnable");
            return runnable instanceof ScheduledMethodRunnable method
                    && method.getMethod().getDeclaringClass().equals(RuleNotificationRetryScheduler.class)
                    && method.getMethod().getName().equals("enqueueDueRetries");
        }).toList();
        assertThat(retryTasks).hasSize(1);
        retryTasks.forEach(task -> task.cancel(true));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(lifecycleScheduler.getScheduledThreadPoolExecutor().getActiveCount()!=0 && System.nanoTime()<deadline) Thread.sleep(5);
        assertThat(lifecycleScheduler.getScheduledThreadPoolExecutor().getActiveCount()).as("先回收已启动的定时空扫再写夹具").isZero();
        for(DatabaseWorkload workload:DatabaseWorkload.values()) {
            try(DatabaseWorkloadContext.Scope ignored=DatabaseWorkloadContext.enter(workload)) {
                assertThat(applicationJdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
                assertThat(applicationJdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(applicationJdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)).isTrue();
            }
        }
        ProjectLifecycleAccessService target = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            actualPid(); beforePermit.run();
            Object result = invocation.callRealMethod();
            afterPermit.run(); return result;
        }).when(target).lockActiveForWrite(any(), any());
        when(sender.channel()).thenReturn("EMAIL");
        when(sender.send(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends.incrementAndGet(); return "test-call-only";
        });
    }

    /** 旧生产反例：冻结已接管QUEUED后，原dispatch仍调用sender，永久合同要求零外发。 */
    @Test
    void archivedAcceptedDeliveryStopsBeforeSender() throws Exception {
        Fixture fixture = seedFixture();
        RuleNotificationDeliveryRequest request = request(fixture);
        service.accept(request);
        RuleNotificationDeliveryStore.DispatchClaim claim = store.claimDispatches(10, Duration.ofSeconds(30));
        assertThat(claim.deliveries()).singleElement().satisfies(value -> assertThat(value.id()).isEqualTo(request.eventId()));
        archive(fixture);
        service.deliverClaimed(request, claim.leaseToken());
        assertThat(sends).hasValue(0);
        assertThat(delivery(request.eventId())).containsEntry("status", "DEAD_LETTER").containsEntry("attempt_count", 1)
                .containsEntry("last_error_code", "PROJECT_FROZEN");
        try(Connection owner=fixtureOwnerConnection()) {jdbc(owner).update("UPDATE sys_project SET status='ACTIVE' WHERE id=?",fixture.projectId());}
        assertThat(store.accept(request)).isEqualTo(RuleNotificationDeliveryStore.Acceptance.IDEMPOTENT_REPLAY);
        service.deliverClaimed(request,claim.leaseToken()); assertThat(sends).hasValue(0);
    }

    /** 旧生产反例：冻结到期重试后原scanner仍追加Outbox，永久合同要求保留attempt并死信收束。 */
    @Test
    void archivedRetryStopsWithoutNewOutbox() throws Exception {
        Fixture fixture = seedFixture();
        RuleNotificationDeliveryRequest request = request(fixture);
        service.accept(request);
        RuleNotificationDeliveryStore.DispatchClaim claim = store.claimDispatches(10, Duration.ofSeconds(30));
        assertThat(claim.deliveries()).hasSize(1);
        Instant now = Instant.now();
        assertThat(store.startClaimed(request.projectId(),request.eventId(),1,claim.leaseToken(),now,now.plusSeconds(30))).isTrue();
        assertThat(store.markRetry(request.projectId(),request.eventId(),1,now.minusSeconds(1),"SMTP_FAILURE",now)).isTrue();
        archive(fixture);
        retries.enqueueDueRetries();
        try (Connection owner=fixtureOwnerConnection()) {
            assertThat(jdbc(owner).queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Long.class,fixture.projectId())).isZero();
        }
        assertThat(delivery(request.eventId())).containsEntry("status", "DEAD_LETTER").containsEntry("attempt_count", 1)
                .containsEntry("last_error_code", "PROJECT_FROZEN");
        assertThat(sends).hasValue(0);
    }

    /** 正常许可提交之后才调用sender；终态同一请求仍幂等吸收。 */
    @Test
    void activeDeliveryCallsSenderOutsideTransactionAndAbsorbsReplay() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request);
        service.deliverClaimed(request,token);
        assertThat(sends).hasValue(1);
        assertThat(delivery(request.eventId())).containsEntry("status","DELIVERED").containsEntry("attempt_count",1);
        service.accept(request); service.deliverClaimed(request,token);
        assertThat(sends).hasValue(1);
    }

    /** tenant或不可变正文冲突必须撤回真实start写入，不能碰项目许可或发送。 */
    @Test
    void mismatchedTenantAndBodyRollBackStartedFact() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request); Map<String,Object> before=delivery(request.eventId());
        for (RuleNotificationDeliveryRequest wrong : List.of(copy(request,Uuid7.generate(),request.body()),copy(request,request.tenantId(),"changed"))) {
            assertThat(catchThrowable(()->service.deliverClaimed(wrong,token))).isInstanceOf(IllegalArgumentException.class);
            assertThat(delivery(request.eventId())).isEqualTo(before);
        }
        assertThat(sends).hasValue(0);
        service.deliverClaimed(request,token); assertThat(sends).hasValue(1);
    }

    /** 旧token失败不能创建新的接管事实；合法旧事实完整不变。 */
    @Test
    void wrongTokenLeavesQueuedFactUnchanged() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); claim(request); Map<String,Object> before=delivery(request.eventId());
        service.deliverClaimed(request,Uuid7.generate());
        assertThat(delivery(request.eventId())).isEqualTo(before); assertThat(sends).hasValue(0);
    }

    /** 已有真实调用方事务必须在任何start之前拒绝，两种后台入口均不能吸收该事务。 */
    @Test
    void ambientTransactionIsRejectedBeforeSendingOrRetryClaim() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request); Map<String,Object> before=delivery(request.eventId());
        transactions.executeWithoutResult(status -> {
            assertThat(catchThrowable(()->service.deliverClaimed(request,token))).isInstanceOf(IllegalStateException.class);
            assertThat(catchThrowable(()->retries.enqueueDueRetries())).isInstanceOf(IllegalStateException.class);
        });
        assertThat(delivery(request.eventId())).isEqualTo(before); assertThat(sends).hasValue(0);
    }

    /** 渠道四槽占满时start实际回滚，attempt仍零；原token退避释放而不是进入SENDING。 */
    @Test
    void fullGuardRollsBackAttemptAndReleasesOriginalToken() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request);
        List<NotificationExternalGuard.Guard> held=new ArrayList<>();
        try {
            for(int index=0;index<4;index++) held.add(guard.tryAcquire("EMAIL","capacity@example.com"));
            assertThat(held).doesNotContainNull();
            service.deliverClaimed(request,token);
            assertThat(delivery(request.eventId())).containsEntry("status","QUEUED").containsEntry("attempt_count",0)
                    .containsEntry("dispatch_lease_token",null).containsEntry("dispatch_leased_until",null);
            assertThat(sends).hasValue(0);
        } finally { held.stream().filter(java.util.Objects::nonNull).forEach(NotificationExternalGuard.Guard::close); }
    }

    /** 冻结优先于渠道拒绝，四槽全满也必须落PROJECT_FROZEN而非无限QUEUED。 */
    @Test
    void frozenProjectTerminatesEvenWhenGuardIsFull() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request); archive(fixture);
        List<NotificationExternalGuard.Guard> held=new ArrayList<>();
        try {
            for(int index=0;index<4;index++) held.add(guard.tryAcquire("EMAIL","capacity@example.com"));
            service.deliverClaimed(request,token);
            assertThat(delivery(request.eventId())).containsEntry("status","DEAD_LETTER").containsEntry("attempt_count",1)
                    .containsEntry("last_error_code","PROJECT_FROZEN");
        } finally { held.stream().filter(java.util.Objects::nonNull).forEach(NotificationExternalGuard.Guard::close); }
        assertThat(sends).hasValue(0);
    }

    /** 项目许可先得，归档等待短事务提交；许可提交后发送无需持数据库事务。 */
    @Test
    void acquiredPermitBlocksArchiveUntilAdmissionCommits() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request);
        CountDownLatch release=new CountDownLatch(1); CompletableFuture<Integer> workerPid=new CompletableFuture<>();
        CompletableFuture<Integer> archivePid=new CompletableFuture<>();
        afterPermit=()->{workerPid.complete(actualPid()); await(release);};
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<?> worker=executor.submit(()->service.deliverClaimed(request,token));
            int pid=workerPid.get(4,TimeUnit.SECONDS);
            Future<?> freezer=executor.submit(()->{
                try(Connection owner=fixtureOwnerConnection()) {
                    owner.setAutoCommit(false); archivePid.complete(pid(owner));
                    jdbc(owner).update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",fixture.projectId()); owner.commit();
                } catch(SQLException failure) {throw new IllegalStateException(failure);}
            });
            blocked(archivePid.get(3,TimeUnit.SECONDS),pid,freezer);
            assertThat(sends).hasValue(0);
            release.countDown(); worker.get(5,TimeUnit.SECONDS); freezer.get(5,TimeUnit.SECONDS);
            assertThat(sends).hasValue(1);
        } finally {finish(executor,release);}
    }

    /** 冻结先持排他锁，发送等待提交后重读false并停止，不能使用锁前ACTIVE快照。 */
    @Test
    void waitingAdmissionRechecksCommittedArchive() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request); CompletableFuture<Integer> workerPid=new CompletableFuture<>();
        beforePermit=()->workerPid.complete(actualPid()); ExecutorService executor=Executors.newSingleThreadExecutor();
        try(Connection owner=fixtureOwnerConnection()) {
            owner.setAutoCommit(false); jdbc(owner).update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",fixture.projectId());
            Future<?> worker=executor.submit(()->service.deliverClaimed(request,token));
            try {blocked(workerPid.get(3,TimeUnit.SECONDS),pid(owner),worker); owner.commit(); worker.get(5,TimeUnit.SECONDS);}
            finally {owner.rollback();}
        } finally {finish(executor,new CountDownLatch(0));}
        assertThat(delivery(request.eventId())).containsEntry("status","DEAD_LETTER").containsEntry("last_error_code","PROJECT_FROZEN");
        assertThat(sends).hasValue(0);
    }

    /** 五秒真实PG许可超时回滚start/attempt/token，原锁释放后同token可正常恢复。 */
    @Test
    void sql57014RollsBackStartAndOriginalTokenRecovers() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request); Map<String,Object> before=delivery(request.eventId());
        CompletableFuture<Integer> workerPid=new CompletableFuture<>(); beforePermit=()->workerPid.complete(actualPid());
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(Connection owner=fixtureOwnerConnection()) {
            owner.setAutoCommit(false); jdbc(owner).update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",fixture.projectId());
            Future<Throwable> worker=executor.submit(()->catchThrowable(()->service.deliverClaimed(request,token)));
            try {blocked(workerPid.get(3,TimeUnit.SECONDS),pid(owner),worker); assertThat(sqlState(worker.get(8,TimeUnit.SECONDS))).isEqualTo("57014");}
            finally {owner.rollback();}
        } finally {finish(executor,new CountDownLatch(0));}
        assertThat(delivery(request.eventId())).isEqualTo(before); assertThat(sends).hasValue(0);
        beforePermit=()->{}; service.deliverClaimed(request,token); assertThat(sends).hasValue(1);
    }

    /** 只读matches后真实lease到期，最终requeue必须再次拒绝；未换token也不能越权重排队。 */
    @Test
    void retryLeaseExpiresWhileWaitingForProjectPermit() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        dueRetry(request); CompletableFuture<Integer> workerPid=new CompletableFuture<>(); beforePermit=()->workerPid.complete(actualPid());
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(Connection holder=fixtureOwnerConnection()) {
            holder.setAutoCommit(false); jdbc(holder).update("UPDATE sys_project SET name=name WHERE id=?",fixture.projectId());
            Future<?> worker=executor.submit(retries::enqueueDueRetries);
            try {
                blocked(workerPid.get(3,TimeUnit.SECONDS),pid(holder),worker);
                try(Connection owner=fixtureOwnerConnection()) {
                    assertThat(jdbc(owner).update("UPDATE rule_notification_delivery SET retry_leased_until=clock_timestamp()-interval '1 second' WHERE id=? AND retry_lease_token IS NOT NULL",request.eventId())).isEqualTo(1);
                }
                holder.rollback(); worker.get(5,TimeUnit.SECONDS);
            } finally {holder.rollback();}
        } finally {finish(executor,new CountDownLatch(0));}
        assertThat(delivery(request.eventId())).containsEntry("status","RETRY_SCHEDULED").containsEntry("attempt_count",1);
        try(Connection owner=fixtureOwnerConnection()) {assertThat(jdbc(owner).queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Long.class,fixture.projectId())).isZero();}
    }

    /** 正常重试真实重排队与Outbox同提交，不以冻结整改删除原成功路径。 */
    @Test
    void activeRetryStillRequeuesWithOutbox() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture); dueRetry(request);
        retries.enqueueDueRetries();
        assertThat(delivery(request.eventId())).containsEntry("status","QUEUED").containsEntry("attempt_count",1);
        try(Connection owner=fixtureOwnerConnection()) {assertThat(jdbc(owner).queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Long.class,fixture.projectId())).isEqualTo(1);}
    }

    /** 原上下文即使属于别的项目也必须在处理后恢复，不把信封scope泄漏给调度线程。 */
    @Test
    void restoresOriginalTenantScopeAfterProcessing() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request);
        TenantScope previous=new TenantScope(Uuid7.generate(),Uuid7.generate(),Uuid7.generate()); TenantContext.set(previous);
        try {service.deliverClaimed(request,token); assertThat(TenantContext.current()).contains(previous);}
        finally {TenantContext.clear();}
    }

    /** 原OWNER删除提交后旧通知保持历史，但首次外发仍必须停止。 */
    @Test
    void ownerDeletedProjectStopsAcceptedNotification() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request);
        TenantContext.set(new TenantScope(fixture.tenantId(),fixture.projectId(),fixture.accountId()));
        try {projects.delete(fixture.projectId());} finally {TenantContext.clear();}
        service.deliverClaimed(request,token);
        assertThat(delivery(request.eventId())).containsEntry("status","DEAD_LETTER").containsEntry("last_error_code","PROJECT_FROZEN");
        assertThat(sends).hasValue(0);
    }

    /** 延迟约束在真实COMMIT阶段报错：不得外发，start回滚且全部guard槽恢复。 */
    @Test
    void realCommitFailureRollsBackAndCancelsGuardBeforeSend() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture);
        service.accept(request); UUID token=claim(request); Map<String,Object> before=delivery(request.eventId());
        try(Connection owner=fixtureOwnerConnection()) {
            jdbc(owner).execute("CREATE FUNCTION rule_notify_test_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"+request.eventId()+"'::uuid AND NEW.status='SENDING' THEN RAISE EXCEPTION 'probe commit failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            jdbc(owner).execute("CREATE CONSTRAINT TRIGGER rule_notify_test_commit_failure AFTER UPDATE ON rule_notification_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION rule_notify_test_commit_failure()");
        }
        try {
            Throwable failure=catchThrowable(()->service.deliverClaimed(request,token));
            assertThat(sqlState(failure)).isEqualTo("23514");
            assertThat(delivery(request.eventId())).isEqualTo(before); assertThat(sends).hasValue(0);
            List<NotificationExternalGuard.Guard> held=new ArrayList<>();
            try {
                for(int index=0;index<4;index++) held.add(guard.tryAcquire("EMAIL","commit@example.com"));
                assertThat(held).doesNotContainNull();
            } finally {held.stream().filter(java.util.Objects::nonNull).forEach(NotificationExternalGuard.Guard::close);}
        } finally {
            try(Connection owner=fixtureOwnerConnection()) {
                jdbc(owner).execute("DROP TRIGGER rule_notify_test_commit_failure ON rule_notification_delivery");
                jdbc(owner).execute("DROP FUNCTION rule_notify_test_commit_failure()");
            }
        }
        service.deliverClaimed(request,token); assertThat(sends).hasValue(1);
    }

    /** 真实claim的只读复核不能接受不同tenant/body，正确候选仍保持有效且可由原CAS处理。 */
    @Test
    void retryReadbackRejectsWrongTenantAndImmutableBody() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture); dueRetry(request);
        RuleNotificationDeliveryStore.RetryClaim claim=store.claimDueRetries(10,Duration.ofSeconds(30));
        assertThat(claim.deliveries()).hasSize(1); var candidate=claim.deliveries().getFirst();
        TenantContext.set(new TenantScope(fixture.tenantId(),fixture.projectId(),fixture.accountId()));
        try {
            assertThat(store.matchesClaimedRetry(candidate,claim.leaseToken(),Instant.now())).isTrue();
            for(var wrong:List.of(retryCopy(candidate,Uuid7.generate(),candidate.body()),retryCopy(candidate,candidate.tenantId(),"wrong")))
                assertThat(store.matchesClaimedRetry(wrong,claim.leaseToken(),Instant.now())).isFalse();
        } finally {TenantContext.clear();}
        assertThat(delivery(request.eventId())).containsEntry("status","RETRY_SCHEDULED").containsEntry("attempt_count",1);
    }

    /** 仅改归属或正文，其他候选字段严格沿真实claim，不伪造候选缺字段错误。 */
    private RuleNotificationDeliveryStore.RetryCandidate retryCopy(RuleNotificationDeliveryStore.RetryCandidate value,UUID tenant,String body) {
        return new RuleNotificationDeliveryStore.RetryCandidate(value.id(),tenant,value.projectId(),value.ruleId(),value.ruleVersionId(),value.messageId(),
                value.sceneId(),value.sceneVersionId(),value.sceneExecutionId(),value.deviceId(),value.channel(),value.recipient(),value.subject(),body,value.traceId(),value.nextAttemptNo());
    }

    /** 真实requeue及Outbox INSERT完成后抛错，原单条事务必须整体回滚并保留已领取lease供恢复。 */
    @Test
    void failureAfterRealRetryOutboxInsertRollsBackRequeueAndRecovers() throws Exception {
        Fixture fixture=seedFixture(); RuleNotificationDeliveryRequest request=request(fixture); dueRetry(request);
        Map<String,Object> before=delivery(request.eventId());
        AtomicBoolean inserted=new AtomicBoolean();
        AtomicBoolean failOnce=new AtomicBoolean(true);
        TransactionalOutboxRepository target=AopTestUtils.getUltimateTargetObject(outbox);
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            OutboxEvent event=invocation.getArgument(0);
            if(event.aggregateId().equals(request.eventId()) && failOnce.compareAndSet(true,false)) {
                actualPid();
                assertThat(applicationJdbc.queryForObject("SELECT status FROM rule_notification_delivery WHERE id=?",String.class,request.eventId())).isEqualTo("QUEUED");
                assertThat(applicationJdbc.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE id=?",Long.class,event.id())).isEqualTo(1L);
                inserted.set(true);
                throw new IllegalStateException("real retry outbox inserted then failed");
            }
            return result;
        }).when(target).append(any());
        Throwable failure=catchThrowable(retries::enqueueDueRetries);
        // @Repository会按既有Spring异常翻译暴露DataAccessException，仍精确保留注入根因。
        assertThat(failure).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("real retry outbox inserted then failed");
        assertThat(inserted).isTrue();
        Map<String,Object> after=delivery(request.eventId());
        // claim本来在单条事务之外已提交，允许它的两项lease变化，其余业务字段必须完整还原。
        var expected=new java.util.LinkedHashMap<>(before); var actual=new java.util.LinkedHashMap<>(after);
        for(String field:List.of("retry_lease_token","retry_leased_until")) {expected.remove(field);actual.remove(field);}
        assertThat(actual).isEqualTo(expected);
        assertThat(after.get("retry_lease_token")).isNotNull(); assertThat(after.get("retry_leased_until")).isNotNull();
        try(Connection owner=fixtureOwnerConnection()) {
            assertThat(jdbc(owner).queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Long.class,fixture.projectId())).isZero();
            assertThat(jdbc(owner).queryForObject("SELECT retry_leased_until>clock_timestamp() FROM rule_notification_delivery WHERE id=?",Boolean.class,request.eventId())).isTrue();
            assertThat(jdbc(owner).update("UPDATE rule_notification_delivery SET retry_leased_until=clock_timestamp()-interval '1 second' WHERE id=?",request.eventId())).isEqualTo(1);
        }
        retries.enqueueDueRetries();
        assertThat(delivery(request.eventId())).containsEntry("status","QUEUED").containsEntry("attempt_count",1);
        try(Connection owner=fixtureOwnerConnection()) {assertThat(jdbc(owner).queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Long.class,fixture.projectId())).isEqualTo(1L);}
        assertThat(sends).hasValue(0);
    }

    /** 真实首次start与可恢复失败建立到期重试，不直接伪造状态。 */
    private void dueRetry(RuleNotificationDeliveryRequest request) {
        service.accept(request); UUID token=claim(request); Instant now=Instant.now();
        assertThat(store.startClaimed(request.projectId(),request.eventId(),1,token,now,now.plusSeconds(30))).isTrue();
        assertThat(store.markRetry(request.projectId(),request.eventId(),1,now.minusSeconds(1),"SMTP_FAILURE",now)).isTrue();
    }
    /** 全局claim必须精确得到本例通知和有效token。 */
    private UUID claim(RuleNotificationDeliveryRequest request) {
        RuleNotificationDeliveryStore.DispatchClaim result=store.claimDispatches(10,Duration.ofSeconds(30));
        assertThat(result.deliveries()).singleElement().satisfies(value->assertThat(value.id()).isEqualTo(request.eventId()));
        return result.leaseToken();
    }
    /** 仅改指定不可变字段构造冲突，剩余完整来源不变。 */
    private RuleNotificationDeliveryRequest copy(RuleNotificationDeliveryRequest request,UUID tenant,String body) {
        return RuleNotificationDeliveryRequest.rule(request.eventId(),tenant,request.projectId(),request.ruleId(),request.ruleVersionId(),
                request.messageId(),request.deviceId(),request.channel(),request.recipient(),request.subject(),body,request.traceId(),request.attemptNo(),request.enqueuedAt());
    }
    /** 原短事务APP物理PID，不用测试事务代替服务开启的事务。 */
    private int actualPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(applicationJdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(DATABASE_NAME);
        assertThat(applicationJdbc.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
        return applicationJdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
    }
    /** 独立owner真实后端PID。 */
    private int pid(Connection owner) {return jdbc(owner).queryForObject("SELECT pg_backend_pid()",Integer.class);}
    /** 有界检查真实PG阻塞，不用睡眠时长推断锁序。 */
    private void blocked(int waiter,int holder,Future<?> operation) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        try(Connection owner=fixtureOwnerConnection()) {
            while(System.nanoTime()<deadline) {
                if(Boolean.TRUE.equals(jdbc(owner).queryForObject("SELECT ?=ANY(pg_blocking_pids(?)) AND EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)",Boolean.class,holder,waiter,waiter))) return;
                if(operation.isDone()) throw new AssertionError("业务已结束但未观察到真实阻塞");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定PG锁等待");
    }
    /** PG异常类型不替代实际SQLSTATE。 */
    private String sqlState(Throwable failure) {for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof SQLException sql) return sql.getSQLState(); return null;}
    /** 屏障有界，中断保留。 */
    private void await(CountDownLatch latch) {
        try {assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue();}
        catch(InterruptedException failure) {Thread.currentThread().interrupt(); throw new IllegalStateException(failure);}
    }
    /** 释放屏障后有界回收线程，避免下一例接管未结束事务。 */
    private void finish(ExecutorService executor,CountDownLatch release) throws InterruptedException {
        release.countDown(); executor.shutdown(); if(!executor.awaitTermination(10,TimeUnit.SECONDS)) {
            executor.shutdownNow(); assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
        TenantContext.clear();
    }

    /** 首次信封完整且不可变，真实accept负责校验规则版本同属。 */
    private RuleNotificationDeliveryRequest request(Fixture fixture) {
        return RuleNotificationDeliveryRequest.rule(Uuid7.generate(),fixture.tenantId(),fixture.projectId(),fixture.ruleId(),
                fixture.versionId(),Uuid7.generate(),fixture.deviceId(),"EMAIL","ops@example.com","subject","body","lifecycle-test",1,Instant.now());
    }
    /** 独立owner提交真实ARCHIVED字段，避免同事务未提交冻结。 */
    private void archive(Fixture fixture) throws SQLException {
        try(Connection owner=fixtureOwnerConnection()) { assertThat(jdbc(owner).update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",fixture.projectId())).isEqualTo(1); }
    }
    /** 物理owner事实不受RLS空投影误导。 */
    private Map<String,Object> delivery(UUID id) throws SQLException {
        try(Connection owner=fixtureOwnerConnection()) { return jdbc(owner).queryForMap("SELECT * FROM rule_notification_delivery WHERE id=?",id); }
    }
    /** 各例只清本专库可变通知事实，祖先及不可变历史完整保留。 */
    @AfterEach
    void cleanup() throws SQLException { TenantContext.clear(); try(Connection owner=fixtureOwnerConnection()) { jdbc(owner).update("DELETE FROM rule_notification_delivery"); } }
    /** 原JDBC包装不关闭由try控制的连接。 */
    private static JdbcTemplate jdbc(Connection connection) { return new JdbcTemplate(new SingleConnectionDataSource(connection,true)); }
    /** 每条owner连接固定专库。 */
    @Override protected Connection fixtureOwnerConnection() throws SQLException { return DriverManager.getConnection(DATABASE_URL,PROBE_POSTGRES.getUsername(),PROBE_POSTGRES.getPassword()); }
    /** 专库交由OwnedTestContainers在本类上下文物理关闭后回收。 */
    private static String startDatabase() { PROBE_POSTGRES.start(); return PROBE_POSTGRES.getJdbcUrl(); }
    /** 只覆盖物理地址并暂停无关自动扫描。 */
    @TestConfiguration(proxyBeanMethods=false)
    static class IsolatedDatabaseConfiguration {
        /** 原retry入口可显式调用；一小时周期使首次空扫之后不抢测试。 */
        @Bean DynamicPropertyRegistrar isolatedProperties() { return registry -> {
            registry.add("spring.datasource.url",()->DATABASE_URL);
            registry.add("spring.flyway.url",()->DATABASE_URL);
            registry.add("things-link.rule.notification.retry.scan-millis",()->"3600000");
            registry.add("things-link.outbox.publisher.enabled",()->"false");
            registry.add("spring.kafka.listener.auto-startup",()->"false");
        }; }
    }
    /** 最小合法骨架只供通知 SQL 同属校验，不发布规则、不启动脚本和数据面物模型链路。 */
    private Fixture seedFixture() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        try (Connection connection = fixtureOwnerConnection()) {
            JdbcTemplate owner = jdbc(connection);
            assertThat(owner.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
            owner.update("INSERT INTO sys_tenant (id, name) VALUES (?, 'D-109 通知测试租户')", fixture.tenantId());
            owner.update("""
                    INSERT INTO sys_account (id, email, password_hash, display_name)
                    VALUES (?, ?, '{noop}unused', 'D-109 测试账号')
                    """, fixture.accountId(), "d109-" + fixture.accountId() + "@example.com");
            owner.update("""
                    INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                    VALUES (?, ?, 'D-109 通知测试项目', 'sh-1', ?)
                    """, fixture.projectId(), fixture.tenantId(),
                    "d109" + fixture.projectId().toString().replace("-", "").substring(0, 16));
            owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",Uuid7.generate(),fixture.tenantId(),fixture.accountId());
            owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",Uuid7.generate(),fixture.projectId(),fixture.accountId());
            owner.update("""
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name,
                                          access_protocol, device_kind, status)
                    VALUES (?, ?, ?, 'notification-type', '通知测试类型', 'STANDARD', 'DIRECT', 'DRAFT')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            owner.update("""
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name)
                    VALUES (?, ?, ?, ?, 'notification-device', '通知测试设备')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.update("""
                    INSERT INTO rule_message (id, tenant_id, project_id, name, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, 'D-109 通知测试规则', ?, now(), now())
                    """, fixture.ruleId(), fixture.tenantId(), fixture.projectId(), fixture.accountId());
            owner.update("""
                    INSERT INTO rule_version (id, tenant_id, project_id, rule_id, version_number,
                                              source, source_sha256, created_by, created_at)
                    VALUES (?, ?, ?, ?, 1, 'input => input',
                            encode(digest('input => input', 'sha256'), 'hex'), ?, now())
                    """, fixture.versionId(), fixture.tenantId(), fixture.projectId(), fixture.ruleId(),
                    fixture.accountId());
            owner.update("UPDATE rule_message SET status = 'ACTIVE', active_version_id = ? WHERE id = ?",
                    fixture.versionId(), fixture.ruleId());
        }
        return fixture;
    }

    /** 所有身份均对应真实专库行。 */
    private record Fixture(UUID tenantId, UUID accountId, UUID projectId, UUID typeId, UUID deviceId, UUID ruleId, UUID versionId) { }
}
