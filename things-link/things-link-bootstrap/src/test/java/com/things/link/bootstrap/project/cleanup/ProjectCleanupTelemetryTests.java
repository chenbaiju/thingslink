package com.things.link.bootstrap.project.cleanup;

import com.things.link.export.application.ProjectExportPurgeContributor;
import com.things.link.export.infrastructure.persistence.JdbcProjectExportPurgeRepository;
import com.things.link.project.application.ProjectCleanupAdmissionService;
import com.things.link.project.application.ProjectCleanupBatchResult;
import com.things.link.project.application.ProjectCleanupBatchService;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupStage;
import com.things.link.project.infrastructure.persistence.JdbcProjectCleanupRepository;
import com.things.link.support.audit.AuditLogService;
import com.things.link.task.application.TaskProjectCleanupContributor;
import com.things.link.task.infrastructure.persistence.JdbcTaskProjectCleanupRepository;
import com.things.link.rule.application.RuleProjectCleanupContributor;
import com.things.link.rule.infrastructure.persistence.JdbcRuleProjectCleanupRepository;
import com.things.link.alarm.application.AlarmProjectCleanupContributor;
import com.things.link.alarm.infrastructure.persistence.JdbcAlarmProjectCleanupRepository;
import com.things.link.enduser.application.AppProjectCleanupContributor;
import com.things.link.enduser.infrastructure.persistence.JdbcAppProjectCleanupRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.things.link.telemetry.application.TelemetryProjectCleanupContributor;
import com.things.link.telemetry.infrastructure.persistence.JdbcTelemetryProjectCleanupRepository;
import com.things.link.telemetry.infrastructure.persistence.JdbcDeviceCommandRepository;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0084：真实遥测全域分批、重放静默、隐藏命令外键和旧回复零写。 */
@Testcontainers
class ProjectCleanupTelemetryTests {

    /** 本类独占PG，所有跨项目清理与FK并发仅作用于此测试容器。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("telemetry_cleanup").withUsername("thingslink").withPassword("thingslink");
    /** 本片五表的邻居完整快照。 */
    private static final List<String> TABLES=List.of("ts_device_command_attempt","ts_device_command",
            "ts_device_message_log","sys_inbox_message","sys_message_log_inbox");
    /** 固定三级聚合。 */
    private static final List<String> AGGREGATES=List.of("ts_property_point_1m_internal","ts_property_point_1h_internal","ts_property_point_1d_internal");
    /** owner只构造持久历史与观察事实。 */
    private static JdbcTemplate owner;
    /** 真实APP数据连接。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 原物理事务管理。 */
    private DataSourceTransactionManager transactions;
    /** 项目权威清理状态。 */
    private JdbcProjectCleanupRepository projects;
    /** 实际准入及租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 实际完整遥测贡献器。 */
    private TelemetryProjectCleanupContributor telemetry;
    /** 五个历史前置领域真实执行，看板以当前顺序完成空域。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 独立五秒批次与原事务进度。 */
    private ProjectCleanupBatchService batches;

    /** 0700旧库真实升级0800，权限保持且重跑幂等。 */
    @BeforeAll
    static void migrate() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink"));
        flyway("20260905.0700").migrate();
        assertThat(flyway("20260905.0800").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0800").migrate().migrationsExecuted).isZero();
        ProjectCleanupDashboardCompatibilityFixture.alignStageConstraint(owner);
        owner.queryForList("SELECT alter_job(job_id,scheduled=>false) FROM timescaledb_information.jobs");
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a "
                +"WHERE p.oid='public.telemetry_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0",Long.class)).isZero();
    }

    /** 仅owner清本类夹具，不把全表删除或RLS绕过混入生产入口。 */
    @BeforeEach
    void prepare() {
        for (String table:TABLES) owner.update("DELETE FROM public."+table);
        owner.update("DELETE FROM public.ts_property_point_internal");
        for (String aggregate:AGGREGATES) owner.update("DELETE FROM public."+aggregate);
        owner.update("DELETE FROM public.ts_property_aggregate_backfill");
        owner.update("DELETE FROM public.sys_project");
        DriverManagerDataSource source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink_app","thingslink");
        app=new JdbcTemplate(source);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions=new DataSourceTransactionManager(source);
        projects=new JdbcProjectCleanupRepository(app);
        admission=proxy(new ProjectCleanupAdmissionService(projects,new AuditLogService(app,new ObjectMapper()),app));
        prerequisites=List.of(proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app))),
                proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app))),
                proxy(new RuleProjectCleanupContributor(new JdbcRuleProjectCleanupRepository(app))),
                proxy(new AlarmProjectCleanupContributor(new JdbcAlarmProjectCleanupRepository(app))),
                proxy(new AppProjectCleanupContributor(new JdbcAppProjectCleanupRepository(app))),
                ProjectCleanupDashboardCompatibilityFixture.emptyContributor(),
                ProjectCleanupDashboardCompatibilityFixture.emptyOtaContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyIntegrationContributor(owner),
                ProjectCleanupDashboardCompatibilityFixture.emptyAssistantContributor(owner));
        telemetry=proxy(new TelemetryProjectCleanupContributor(new JdbcTelemetryProjectCleanupRepository(app)));
        batches=batch(telemetry::clean);
    }

    /** 五表各1001行加raw和三层聚合，实际5009行分19个删除批次；旧回复/派发零写且邻居逐字段保持。 */
    @Test
    void cleansCompleteTelemetryInBoundedBatchesAndOldCallbacksDoNotRecreateIt() {
        Fixture first=fixture(false);
        Fixture neighbor=fixture(true);
        seed(first,1001);
        seed(neighbor,1);
        history(first);
        history(neighbor);
        Map<String,String> before=snapshot(neighbor);
        String device=owner.queryForObject("SELECT to_jsonb(d)::text FROM public.dev_device d WHERE id=?",String.class,first.device());
        assertThat(batches.execute(telemetryClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        batches=batch(telemetry::clean);
        assertThat(drain(neighbor,before)).isEqualTo(5008);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM public.sys_project WHERE id=?",Long.class,first.project())).isEqualTo(5009);
        assertThat(owner.queryForObject("SELECT cleanup_batches FROM public.sys_project WHERE id=?",Long.class,first.project())).isEqualTo(19);
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM public.sys_project WHERE id=?",String.class,first.project())).isEqualTo("DEVICE");
        assertThat(owner.queryForObject("SELECT to_jsonb(d)::text FROM public.dev_device d WHERE id=?",String.class,first.device())).isEqualTo(device);
        assertThat(snapshot(first).values()).allSatisfy(value -> assertThat(value).isEqualTo("[]"));
        // 本类前半段刻意停在 20260905.0800 的旧库状态（清理迁移的升级路径就是这个用例的对象）；
        // 但 AX-2c 起生产命令回复入口会写 ts_device_command_claim，该表在旧库上不存在。
        // 因此在调用生产仓储之前补到最新，避免把「旧库没有这张表」误报成业务缺陷。
        flyway(null).migrate();
        // 补迁移会新增列，to_jsonb 快照随之变化：以补迁移后的邻居事实作为新基线，
        // 后置断言的对象仍是「清理没有动过邻居」，而不是「迁移没有加过列」。
        before=snapshot(neighbor);
        TransactionTemplate transaction=new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            scope(first);
            JdbcDeviceCommandRepository commands=new JdbcDeviceCommandRepository(app);
            assertThat(commands.findByCommandId(first.project(),first.command())).isEmpty();
            assertThat(commands.applyReply(first.project(),first.command(),first.device(),UUID.randomUUID(),"SUCCESS","{}",null,null,Instant.now())).isFalse();
            assertThat(commands.markDispatched(first.project(),first.command(),1,Instant.now())).isFalse();
            assertThat(commands.claimDue(1)).isEmpty();
        });
        assertThat(snapshot(neighbor)).isEqualTo(before);
        assertThat(snapshot(first).values()).allSatisfy(value -> assertThat(value).isEqualTo("[]"));
    }

    /** 每类近期平台事实都阻止清理，设备ts再老也不能代替平台处理时间。 */
    @ParameterizedTest
    @ValueSource(strings={"ts_device_command","ts_device_command_attempt","ts_device_message_log","sys_inbox_message","sys_message_log_inbox"})
    void waitsForRecentFacts(String table) {
        Fixture first=fixture(false);
        seed(first,1);
        String column=switch(table) {
            case "ts_device_command","ts_device_command_attempt" -> "updated_at";
            case "ts_device_message_log" -> "created_at";
            default -> "received_at";
        };
        owner.update("UPDATE public."+table+" SET "+column+"=clock_timestamp() WHERE project_id=?",first.project());
        Map<String,String> before=snapshot(first);
        assertThat(batches.execute(telemetryClaim()).orElseThrow().blockedReason()).isEqualTo("TELEMETRY_REPLAY_WINDOW");
        assertThat(snapshot(first)).isEqualTo(before);
    }

    /** 租约与响应deadline分开；过期非终态仍可清，不能永久等待已冻结工作转终态。 */
    @ParameterizedTest
    @ValueSource(strings={"retry","command","attempt"})
    void waitsForRealWorkLeaseOrResponseDeadline(String kind) {
        Fixture first=fixture(false);
        seed(first,1);
        switch(kind) {
            case "retry" -> owner.update("UPDATE public.ts_device_command SET retry_token=gen_random_uuid(),retry_leased_until=clock_timestamp()+interval '1 minute' WHERE id=?",first.command());
            case "command" -> owner.update("UPDATE public.ts_device_command SET status='DISPATCHED',deadline_at=clock_timestamp()+interval '1 minute' WHERE id=?",first.command());
            default -> owner.update("UPDATE public.ts_device_command_attempt SET status='PUBLISHED',deadline_at=clock_timestamp()+interval '1 minute' WHERE project_id=?",first.project());
        }
        assertThat(batches.execute(telemetryClaim()).orElseThrow().blockedReason()).isEqualTo("TELEMETRY_WORK_IN_FLIGHT");
        owner.update("UPDATE public.ts_device_command SET retry_token=NULL,retry_leased_until=NULL,deadline_at=clock_timestamp()-interval '1 second' WHERE id=?",first.command());
        owner.update("UPDATE public.ts_device_command_attempt SET deadline_at=clock_timestamp()-interval '1 second' WHERE project_id=?",first.project());
        makeDue(first);
        assertThat(next().deletedRows()).isEqualTo(1);
    }

    /** 已提交的跨项目单列FK入边不可被父命令CASCADE越权删除。 */
    @Test
    void preservesHiddenAttemptReferencingThisProjectsCommand() throws Exception {
        Fixture first=fixture(false);
        Fixture neighbor=fixture(true);
        seed(first,1);
        seed(neighbor,1);
        owner.update("DELETE FROM public.ts_device_command_attempt WHERE project_id=?",first.project());
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink")) {
            insertAttempt(writer,neighbor,first.command());
        }
        Map<String,String> before=snapshot(neighbor);
        ProjectCleanupBatchResult result=batches.execute(telemetryClaim()).orElseThrow();
        assertThat(snapshot(neighbor)).isEqualTo(before);
        assertThat(result.blockedReason()).isEqualTo("TELEMETRY_COMMAND_ATTEMPT_REMAINS");
        assertThat(owner.queryForObject("SELECT count(*) FROM public.ts_device_command WHERE id=?",Long.class,first.command())).isEqualTo(1);
    }

    /** FK入边先持KEY SHARE，清理锁父后用新RC语句看到刚提交的邻居尝试。 */
    @Test
    void seesForeignAttemptCommittedWhileWaitingForParentLock() throws Exception {
        Fixture first=fixture(false);
        Fixture neighbor=fixture(true);
        seed(first,1);
        seed(neighbor,1);
        owner.update("DELETE FROM public.ts_device_command_attempt WHERE project_id=?",first.project());
        ProjectCleanupClaim claim=telemetryClaim();
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             var workers=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            insertAttempt(writer,neighbor,first.command());
            var cleanup=workers.submit(() -> batches.execute(claim));
            try { awaitCleanupLock(); } finally { writer.commit(); }
            assertThat(cleanup.get(3,TimeUnit.SECONDS).orElseThrow().blockedReason()).isEqualTo("TELEMETRY_COMMAND_ATTEMPT_REMAINS");
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM public.ts_device_command_attempt WHERE project_id=?",Long.class,neighbor.project())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM public.ts_device_command WHERE id=?",Long.class,first.command())).isEqualTo(1);
    }

    /** 父先删除并持锁，迟来的邻居入边由真实FK23503拒绝，原邻居所有字段保持。 */
    @Test
    void rejectsForeignAttemptArrivingAfterParentDeletionHasLockedIt() throws Exception {
        Fixture first=fixture(false);
        Fixture neighbor=fixture(true);
        seed(first,1);
        seed(neighbor,1);
        owner.update("DELETE FROM public.ts_device_command_attempt WHERE project_id=?",first.project());
        ProjectCleanupClaim claim=telemetryClaim();
        Map<String,String> before=snapshot(neighbor);
        CountDownLatch deleted=new CountDownLatch(1);
        CountDownLatch commit=new CountDownLatch(1);
        try (var workers=Executors.newFixedThreadPool(2)) {
            var cleanup=workers.submit(() -> batch(c -> {
                ProjectCleanupBatchResult result=telemetry.clean(c);
                assertThat(result.deletedRows()).isEqualTo(1);
                deleted.countDown();
                await(commit);
                return result;
            }).execute(claim));
            try {
                assertThat(deleted.await(3,TimeUnit.SECONDS)).isTrue();
                var insertion=workers.<SQLException>submit(() -> {
                    try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink")) {
                        insertAttempt(writer,neighbor,first.command());
                        return null;
                    } catch (SQLException failure) { return failure; }
                });
                awaitSqlLock("INSERT INTO public.ts_device_command_attempt%");
                commit.countDown();
                assertThat(cleanup.get(3,TimeUnit.SECONDS).orElseThrow().deletedRows()).isEqualTo(1);
                SQLException failure=insertion.get(3,TimeUnit.SECONDS);
                assertThat(failure != null).isTrue();
                assertThat(failure.getSQLState()).isEqualTo("23503");
            } finally { commit.countDown(); }
        }
        assertThat(snapshot(neighbor)).isEqualTo(before);
    }

    /** 旧候选等待行锁时收到了新回复时间，DELETE必须锁后复核并留下事实。 */
    @Test
    void rechecksAttemptTimestampAfterWaitingForConcurrentWriter() throws Exception {
        Fixture first=fixture(false);
        seed(first,1);
        ProjectCleanupClaim claim=telemetryClaim();
        try (Connection writer=DriverManager.getConnection(POSTGRES.getJdbcUrl(),"thingslink","thingslink");
             var workers=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement update=writer.prepareStatement("UPDATE public.ts_device_command_attempt SET updated_at=clock_timestamp() WHERE project_id=?")) {
                update.setObject(1,first.project()); update.executeUpdate();
            }
            var cleanup=workers.submit(() -> batches.execute(claim));
            try { awaitCleanupLock(); } finally { writer.commit(); }
            assertThat(cleanup.get(3,TimeUnit.SECONDS).orElseThrow().blockedReason()).isEqualTo("TELEMETRY_FACT_CHANGED");
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM public.ts_device_command_attempt WHERE project_id=?",Long.class,first.project())).isEqualTo(1);
    }

    /** TEMP空表不能遮蔽真实五表；真实删除后异常必须回滚，再用同token成功执行。 */
    @Test
    void ignoresTemporaryTablesAndRollsBackOnFailureOrInvalidIdentity() {
        Fixture first=fixture(false);
        seed(first,1);
        ProjectCleanupClaim claim=telemetryClaim();
        TransactionTemplate transaction=new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.execute(status -> new JdbcTelemetryProjectCleanupRepository(app).clean(
                new ProjectCleanupClaim(claim.tenantId(),claim.projectId(),claim.generation(),claim.stage(),UUID.randomUUID(),claim.leaseUntil(),false))))
                .hasRootCauseInstanceOf(SQLException.class);
        Map<String,String> before=snapshot(first);
        assertThatThrownBy(() -> batch(c -> {
            for (String table:TABLES) app.execute("CREATE TEMP TABLE "+table+"(LIKE public."+table+") ON COMMIT DROP");
            assertThat(telemetry.clean(c).deletedRows()).isEqualTo(1);
            throw new IllegalStateException("遥测删除后受控失败");
        }).execute(claim)).hasMessage("遥测删除后受控失败");
        assertThat(snapshot(first)).isEqualTo(before);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
    }

    /** 没有遥测事实时完整贡献正常推进DEVICE，不影响设备和项目墓碑。 */
    @Test
    void completesEmptyTelemetryAndRetainsDeviceFoundation() {
        Fixture first=fixture(false);
        assertThat(batches.execute(telemetryClaim()).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(owner.queryForObject("SELECT count(*) FROM public.dev_device WHERE id=?",Long.class,first.device())).isEqualTo(1);
    }

    /** @return 五个历史真实前置和一个看板空域后的TELEMETRY领取 */
    private ProjectCleanupClaim telemetryClaim() {
        for (int i = 0; i < ProjectCleanupStage.TELEMETRY.ordinal(); i++) assertThat(next().complete()).isTrue();
        ProjectCleanupClaim claim=admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("TELEMETRY");
        return claim;
    }

    /** @return 真实数据库下一批 */
    private ProjectCleanupBatchResult next() { return batches.execute(admission.claimNext().orElseThrow()).orElseThrow(); }

    /** @param neighbor 邻居 @param before 完整快照 @return 到完整域结束的剩余DELETE数 */
    private int drain(Fixture neighbor,Map<String,String> before) {
        int count=0;
        for (int i=0;i<40;i++) {
            ProjectCleanupBatchResult result=next();
            assertThat(result.blockedReason()).isNull();
            assertThat(snapshot(neighbor)).isEqualTo(before);
            count+=result.deletedRows();
            if (result.complete()) return count;
        }
        throw new AssertionError("遥测清理未按预期批次收束");
    }

    /** @param operation 真实贡献或故障包装 @return 相同原事务批次 */
    private ProjectCleanupBatchService batch(Function<ProjectCleanupClaim,ProjectCleanupBatchResult> operation) {
        List<ProjectCleanupContributor> contributors=new ArrayList<>(prerequisites);
        contributors.add(new ProjectCleanupContributor() {
            /** 固定完整遥测阶段。 */
            @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.TELEMETRY; }
            /** 真实贡献与项目进度原事务。 */
            @Override public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        });
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, contributors));
    }

    /** @param active 是否可用邻居 @return 完整可信控制面父实体，历史均早于31天前软删除 */
    private Fixture fixture(boolean active) {
        Fixture f=new Fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        owner.update("INSERT INTO public.sys_tenant(id,name) VALUES (?,'遥测清理租户')",f.tenant());
        owner.update("INSERT INTO public.sys_account(id,email,password_hash,display_name) VALUES (?,?,'hash','清理发起人')",f.account(),f.account()+"@example.com");
        owner.update("INSERT INTO public.sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,'遥测项目',?,?,?,CASE WHEN ? THEN NULL ELSE now()-interval '31 days' END)",
                f.project(),f.tenant(),"tele_"+f.project().toString().replace("-",""),active?"ACTIVE":"DELETING",active?0:1,active);
        owner.update("INSERT INTO public.dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'type','类型','STANDARD','DIRECT','DRAFT')",f.type(),f.tenant(),f.project());
        owner.update("INSERT INTO public.dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,'device','设备')",f.device(),f.tenant(),f.project(),f.type());
        owner.update("INSERT INTO public.dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?,?,?,?,'reboot','重启','{}','{}',30)",f.definition(),f.tenant(),f.project(),f.type());
        return f;
    }

    /** @param f 项目 @param count 五表等量历史，每个命令一个attempt避免超出三次重试约束 */
    private void seed(Fixture f,int count) {
        owner.update("""
                INSERT INTO public.ts_device_command(id,tenant_id,project_id,target_device_id,connection_device_id,command_definition_id,
                    command_key,request_payload,status,idempotency_key,requested_by,timeout_seconds,attempt_count,max_attempts,
                    next_attempt_at,deadline_at,trace_id,accepted_at,updated_at,completed_at)
                SELECT CASE WHEN n=1 THEN ?::uuid ELSE gen_random_uuid() END,?,?,?,?,?,'reboot','{}','SUCCEEDED',gen_random_uuid()::text,?,30,1,3,
                    NULL,now()-interval '40 days','purge-test',now()-interval '40 days',now()-interval '40 days',now()-interval '40 days'
                FROM generate_series(1,?) n
                """,f.command(),f.tenant(),f.project(),f.device(),f.device(),f.definition(),f.account(),count);
        owner.update("""
                INSERT INTO public.ts_device_command_attempt(id,tenant_id,project_id,command_id,attempt_no,outbox_event_id,connection_device_id,
                    topic,status,deadline_at,created_at,updated_at,completed_at)
                SELECT gen_random_uuid(),tenant_id,project_id,id,1,gen_random_uuid(),connection_device_id,'tc/test','SUCCEEDED',
                    now()-interval '40 days',now()-interval '40 days',now()-interval '40 days',now()-interval '40 days'
                FROM public.ts_device_command WHERE project_id=?
                """,f.project());
        owner.update("""
                INSERT INTO public.ts_device_message_log(id,project_id,device_id,message_id,tenant_id,protocol,direction,ts,received_at,created_at)
                SELECT gen_random_uuid(),?,?,gen_random_uuid(),?,'MQTT','UP',now()-interval '40 days',now()-interval '40 days',now()-interval '40 days'
                FROM generate_series(1,?)
                """,f.project(),f.device(),f.tenant(),count);
        for (String inbox:List.of("sys_inbox_message","sys_message_log_inbox")) owner.update("INSERT INTO public."+inbox
                +"(message_id,project_id,received_at) SELECT gen_random_uuid(),?,now()-interval '40 days' FROM generate_series(1,?)",f.project(),count);
    }

    /** @param f 项目：真实raw与三个聚合层同时存在，证明完整贡献没有跳过历史端口 */
    private void history(Fixture f) {
        Instant start=owner.queryForObject("SELECT date_trunc('day',clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'-interval '40 days'",Timestamp.class).toInstant();
        owner.update("INSERT INTO public.ts_property_point_internal(project_id,device_id,property_key,ts,message_id,value_double) VALUES (?,?,'temperature',?,gen_random_uuid(),42)",f.project(),f.device(),Timestamp.from(start.plusSeconds(60)));
        for (String aggregate:AGGREGATES) owner.update("CALL public.refresh_continuous_aggregate(?::regclass,?::timestamptz,?::timestamptz,true)",
                "public."+aggregate,Timestamp.from(start),Timestamp.from(start.plusSeconds(86400)));
    }

    /** @param writer 独立owner事务 @param f 入边归属 @param parent 候选命令，可故意跨项目证明FK隔离 */
    private void insertAttempt(Connection writer,Fixture f,UUID parent) throws SQLException {
        try (PreparedStatement insert=writer.prepareStatement("""
                INSERT INTO public.ts_device_command_attempt(id,tenant_id,project_id,command_id,attempt_no,outbox_event_id,connection_device_id,
                    topic,status,deadline_at,created_at,updated_at,completed_at)
                VALUES (gen_random_uuid(),?,?,?,1,gen_random_uuid(),?,'tc/test','SUCCEEDED',now()-interval '40 days',
                    now()-interval '40 days',now()-interval '40 days',now()-interval '40 days')
                """)) {
            insert.setQueryTimeout(4);
            insert.setObject(1,f.tenant()); insert.setObject(2,f.project()); insert.setObject(3,parent); insert.setObject(4,f.device());
            insert.executeUpdate();
        }
    }

    /** @param f 项目 @return 五表及raw/三级聚合完整字段快照 */
    private Map<String,String> snapshot(Fixture f) {
        Map<String,String> result=new java.util.LinkedHashMap<>();
        List<String> tables=new ArrayList<>(TABLES);
        tables.add("ts_property_point_internal"); tables.addAll(AGGREGATES);
        for (String table:tables) result.put(table,owner.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY to_jsonb(d)::text),'[]'::jsonb)::text "
                +"FROM public."+table+" d WHERE project_id=?",String.class,f.project()));
        return result;
    }

    /** @param f 已确权项目，事务局部RLS不会泄漏给其他用例 */
    private void scope(Fixture f) {
        app.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,f.tenant().toString());
        app.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());
    }

    /** @param f 只推进测试退避，不改代次/token */
    private void makeDue(Fixture f) {
        owner.update("UPDATE public.sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",f.project());
    }

    /** 实际SQL锁等待证明后才提交并发写，不以固定延迟猜时序。 */
    private void awaitCleanupLock() {
        awaitSqlLock("SELECT * FROM public.telemetry_project_cleanup_batch%");
    }

    /** @param prefix 明确实际SQL，排除其他维护连接的等待 */
    private void awaitSqlLock(String prefix) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() "
                    +"AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,prefix))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
            }
        }
        throw new AssertionError("未观察到遥测清理锁等待");
    }

    /** @param latch 最多三秒的受控提交屏障，失败时finally释放连接与线程 */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3,TimeUnit.SECONDS)) throw new AssertionError("遥测清理提交屏障超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
        }
    }

    /** @param target 原事务对象 @return 实际Spring注解代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory=new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    /** @param target 固定升级截止 @return 完整工作区迁移 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink")
                .locations("classpath:db/migration/support","classpath:db/migration/project","classpath:db/migration/iam",
                        "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
                        "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/enduser",
                        "classpath:db/migration/export",
                        // 与生产 application.yml 的清单保持一致：enduser 的 dashboard 授权迁移依赖 dash_* 表，
                        // 少了 dashboard 位置时补到最新会在该迁移上失败（本类此前只停在旧版本，没有暴露这一点）。
                        "classpath:db/migration/dashboard")
                .placeholders(Map.of("app_role_password","thingslink"))
                // target 为空表示补到最新：Flyway 的 setter 不接受 null，必须显式给 LATEST。
                .target(target==null?org.flywaydb.core.api.MigrationVersion.LATEST
                        :org.flywaydb.core.api.MigrationVersion.fromVersion(target)).load();
    }

    /** 命令归属、设备/定义和调用人均有持久父实体，避免用不存在的UUID冒充真实外键路径。 */
    private record Fixture(UUID tenant,UUID project,UUID type,UUID device,UUID definition,UUID account,UUID command) { }
}
