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
import com.things.link.alarm.infrastructure.persistence.JdbcAlarmNotificationRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** ADR0080：真实APP验证告警八表清理、隐藏事件级联防护和原事务重放边界。 */
@Testcontainers
class ProjectCleanupAlarmTests {

    /** 独占PG用于全局领取和真实跨项目FK竞争。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_cleanup_alarm").withUsername("thingslink").withPassword("thingslink");
    /** 本域实际八表按显式外键顺序统计，测试不得靠项目根级联清夹具。 */
    private static final List<String> TABLES = List.of("alarm_notification_read", "alarm_notification_delivery", "alarm_event", "alarm_instance",
            "alarm_notification_binding", "alarm_notification_recipient", "alarm_notification_template",
            "alarm_notification_group", "alarm_rule");
    /** owner仅供异常历史夹具和锁观测。 */
    private static JdbcTemplate owner;
    /** 清理始终由受限APP执行。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 与项目批次共享的物理事务。 */
    private DataSourceTransactionManager transactions;
    /** 项目权威进度仓储。 */
    private JdbcProjectCleanupRepository projects;
    /** 真实30天准入及完整租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 原三个阶段真实空域贡献器，不能手改进度绕过。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 本片ALARM实现。 */
    private ProjectCleanupContributor alarms;
    /** 完整围栏与进度提交入口。 */
    private ProjectCleanupBatchService batches;

    /** 旧库增量及重复运行均覆盖，PUBLIC没有清理执行权。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink"));
        flyway("20260905.0300").migrate();
        assertThat(flyway("20260905.0400").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0400").migrate().migrationsExecuted).isZero();
        flyway("20260905.1600").migrate();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a
                WHERE p.oid='public.alarm_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0
                """,Long.class)).isZero();
    }

    /** 不删除保留的审计；先删全部本域子行再删设备和项目，隔离失败现场不污染后续测试。 */
    @BeforeEach
    void setup() {
        for (String table : TABLES) owner.update("DELETE FROM " + table);
        for (String table : List.of("dev_device","dev_type","sys_project")) owner.update("DELETE FROM " + table);
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"thingslink_app","thingslink");
        app = new JdbcTemplate(source);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions = new DataSourceTransactionManager(source);
        projects = new JdbcProjectCleanupRepository(app);
        admission = proxy(new ProjectCleanupAdmissionService(projects,new AuditLogService(app,new ObjectMapper()),app));
        prerequisites = List.of(proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app))),
                proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app))),
                proxy(new RuleProjectCleanupContributor(new JdbcRuleProjectCleanupRepository(app))));
        alarms = proxy(new AlarmProjectCleanupContributor(new JdbcAlarmProjectCleanupRepository(app)));
        batches = batch(alarms);
    }

    /** 1001事件三批、三渠道通知与配置均真实清空；重建服务继续，邻居每张表保持不变。 */
    @Test
    void cleansEightTablesAndThreeChannelsWithBoundedResume() {
        Fixture f = fixture(false);
        events(f,f.instance(),1001);
        notifications(f);
        Fixture other = fixture(true);
        events(other,other.instance(),1);
        notifications(other);
        assertThat(batches.execute(alarmClaim()).orElseThrow().deletedRows()).isEqualTo(3);
        batches = batch(alarms);
        assertThat(drain()).isEqualTo(1012);
        for (String table : TABLES) assertThat(rows(table,f)).isZero();
        Map<String,Long> expected=Map.of("alarm_notification_delivery",3L,"alarm_event",1L,"alarm_instance",1L,
                "alarm_notification_binding",3L,"alarm_notification_recipient",2L,"alarm_notification_template",3L,
                "alarm_notification_group",1L,"alarm_rule",1L);
        expected.forEach((table,count)->assertThat(rows(table,other)).isEqualTo(count));
        assertThat(owner.queryForMap("SELECT cleanup_stage,cleanup_rows,cleanup_batches FROM sys_project WHERE id=?",f.project()))
                .containsEntry("cleanup_stage","ENDUSER").containsEntry("cleanup_rows",1015L).containsEntry("cleanup_batches",10L);
        assertThat(rows("dev_device",f)).isEqualTo(1);
        assertThat(rows("dev_type",f)).isEqualTo(1);
        // 原仓储只能更新已有投递，清后同一Kafka身份不能重建或取得发送资格。
        Boolean accepted=new TransactionTemplate(transactions).execute(status->{
            scope(f);
            JdbcAlarmNotificationRepository repository=new JdbcAlarmNotificationRepository(app);
            assertThat(repository.findDelivery(f.project(),f.delivery())).isEmpty();
            return repository.acceptQueuedDelivery(f.project(),f.delivery(),f.delivery(),1);
        });
        assertThat(accepted).isFalse();
        assertThat(rows("alarm_notification_delivery",f)).isZero();
    }

    /** 其他项目事件可通过旧单列FK指向本实例，不能被RLS隐藏后遭父级联误删。 */
    @Test
    void blocksHiddenCrossProjectEventsBeforeDeletingInstance() {
        Fixture f=fixture(false);
        Fixture other=fixture(true);
        events(other,f.instance(),1);
        ProjectCleanupClaim claim=alarmClaim();
        assertThat(new TransactionTemplate(transactions).<Long>execute(status->{
            scope(f);
            return app.queryForObject("SELECT count(*) FROM alarm_event WHERE instance_id=?",Long.class,f.instance());
        })).isEqualTo(0L);
        ProjectCleanupBatchResult result=batches.execute(claim).orElseThrow();
        assertThat(rows("alarm_event",other)).isEqualTo(1);
        assertThat(result.blockedReason()).isEqualTo("ALARM_EVENT_REMAINS");
        assertThat(rows("alarm_instance",f)).isEqualTo(1);
    }

    /** 实例指向规则同样只有单列FK，跨项目错误归属不能被清理推断为本项目子行。 */
    @Test
    void blocksHiddenCrossProjectInstancesBeforeDeletingRule() {
        Fixture f=fixture(false);
        Fixture other=fixture(true);
        owner.update("UPDATE alarm_instance SET rule_id=? WHERE id=?",f.rule(),other.instance());
        assertThat(batches.execute(alarmClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        assertThat(next().blockedReason()).isEqualTo("ALARM_INSTANCE_REMAINS");
        assertThat(rows("alarm_rule",f)).isEqualTo(1);
        assertThat(rows("alarm_instance",other)).isEqualTo(1);
    }

    /** 子FK先得锁，父清理等待后必须看到刚提交的隐藏事件。 */
    @Test
    void seesHiddenEventCommittedWhileWaitingForParentLock() throws Exception {
        Fixture f=fixture(false);
        Fixture other=fixture(true);
        ProjectCleanupClaim claim=alarmClaim();
        try (Connection writer=owner.getDataSource().getConnection();var executor=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            insertEvent(writer,other,f.instance());
            var cleanup=executor.submit(()->batches.execute(claim).orElseThrow());
            awaitLock("%public.alarm_project_cleanup_batch%");
            writer.commit();
            assertThat(cleanup.get(5,TimeUnit.SECONDS).blockedReason()).isEqualTo("ALARM_EVENT_REMAINS");
        }
        assertThat(rows("alarm_event",other)).isEqualTo(1);
        assertThat(rows("alarm_instance",f)).isEqualTo(1);
    }

    /** 父清理先持锁，后来的隐藏子事件只能等待并按23503拒绝，不能穿入检查和删除之间。 */
    @Test
    void rejectsNewHiddenReferenceAfterParentCleanupHasLocked() throws Exception {
        Fixture f=fixture(false);
        Fixture other=fixture(true);
        ProjectCleanupClaim claim=alarmClaim();
        try (var executor=Executors.newSingleThreadExecutor()) {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>> insertion=new java.util.concurrent.atomic.AtomicReference<>();
            ProjectCleanupBatchService controlled=batch(contributor(c->{
                app.queryForObject("SELECT id FROM public.alarm_instance WHERE id=? FOR UPDATE",UUID.class,f.instance());
                insertion.set(executor.submit(()->{
                    try (Connection writer=owner.getDataSource().getConnection()) {
                        insertEvent(writer,other,f.instance());
                        return "INSERTED";
                    } catch (SQLException failure) { return failure.getSQLState(); }
                }));
                awaitLock("%INSERT INTO alarm_event%");
                return alarms.clean(c);
            }));
            assertThat(controlled.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
            assertThat(insertion.get().get(5,TimeUnit.SECONDS)).isEqualTo("23503");
        }
        assertThat(rows("alarm_event",other)).isZero();
        assertThat(rows("alarm_instance",f)).isZero();
    }

    /** 各类事实的最近时间独立于31天软删窗口；清理不取消真实在途发送租约。 */
    @ParameterizedTest
    @ValueSource(strings={"delivery","event","instance","lease"})
    void waitsForRecentFactsAndActiveDispatch(String changed) {
        Fixture f=fixture(false);
        events(f,f.instance(),1);
        notifications(f);
        switch (changed) {
            case "delivery" -> owner.update("UPDATE alarm_notification_delivery SET updated_at=now() WHERE id=?",f.delivery());
            case "event" -> owner.update("UPDATE alarm_event SET received_at=now() WHERE id=?",f.event());
            case "instance" -> owner.update("UPDATE alarm_instance SET updated_at=now() WHERE id=?",f.instance());
            case "lease" -> owner.update("UPDATE alarm_notification_delivery SET dispatch_lease_token=?,dispatch_leased_until=now()+interval '1 minute' WHERE id=?",UUID.randomUUID(),f.delivery());
            default -> throw new AssertionError(changed);
        }
        assertThat(batches.execute(alarmClaim()).orElseThrow().blockedReason())
                .isEqualTo(changed.equals("lease")?"ALARM_WORK_IN_FLIGHT":"ALARM_REPLAY_WINDOW");
        assertThat(rows("alarm_notification_delivery",f)).isEqualTo(3);
        assertThat(rows("alarm_event",f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",Long.class,f.project())).isZero();
    }

    /** 旧事实在本批初查后刚更新，DELETE必须在行锁等待后拒绝其新时间，不能推进父事件。 */
    @Test
    void rechecksDeliveryTimeAfterWaitingForRowLock() throws Exception {
        Fixture f=fixture(false);
        events(f,f.instance(),1);
        notifications(f);
        // 只留目标通知，避免本轮先删另一条而使返回计数与等待断言混在一起。
        owner.update("DELETE FROM alarm_notification_delivery WHERE id<>?",f.delivery());
        ProjectCleanupClaim claim=alarmClaim();
        try (Connection writer=owner.getDataSource().getConnection();var executor=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement sql=writer.prepareStatement("UPDATE alarm_notification_delivery SET updated_at=clock_timestamp() WHERE id=?")) {
                sql.setObject(1,f.delivery());
                sql.executeUpdate();
            }
            var cleanup=executor.submit(()->batches.execute(claim).orElseThrow());
            awaitLock("%public.alarm_project_cleanup_batch%");
            writer.commit();
            assertThat(cleanup.get(5,TimeUnit.SECONDS).blockedReason()).isEqualTo("ALARM_FACT_CHANGED");
        }
        assertThat(rows("alarm_notification_delivery",f)).isEqualTo(1);
        assertThat(rows("alarm_event",f)).isEqualTo(1);
    }

    /** 错token/阶段的直接函数调用均拒绝，同名临时表不能遮蔽实际实例；故障与进度一并回滚。 */
    @Test
    void rejectsBadIdentityAndRollsBackRealWorkDespiteTemporaryTables() {
        Fixture f=fixture(false);
        ProjectCleanupClaim claim=alarmClaim();
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->app.queryForMap(
                "SELECT * FROM public.alarm_project_cleanup_batch(?,?,?,?)",f.tenant(),f.project(),claim.generation(),UUID.randomUUID())))
                .hasRootCauseInstanceOf(SQLException.class);
        ProjectCleanupBatchService broken=batch(contributor(c->{
            for (String table:TABLES) app.execute("CREATE TEMP TABLE "+table+" (LIKE public."+table+") ON COMMIT DROP");
            assertThat(alarms.clean(c).deletedRows()).isEqualTo(1);
            throw new IllegalStateException("告警清理受控失败");
        }));
        assertThatThrownBy(()->broken.execute(claim)).hasMessage("告警清理受控失败");
        assertThat(rows("alarm_instance",f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",Long.class,f.project())).isZero();
        owner.update("UPDATE sys_project SET cleanup_stage='ENDUSER' WHERE id=?",f.project());
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->alarms.clean(claim)))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    /** 空表仍由ALARM贡献明确证明，不能省略领域实现。 */
    @Test
    void advancesEmptyDomainWithoutDeletingDevice() {
        Fixture f=fixture(false);
        owner.update("DELETE FROM alarm_instance WHERE id=?",f.instance());
        owner.update("DELETE FROM alarm_rule WHERE id=?",f.rule());
        assertThat(batches.execute(alarmClaim()).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(rows("dev_device",f)).isEqualTo(1);
    }

    /** @return 三个真实空前置后的ALARM领取 */
    private ProjectCleanupClaim alarmClaim() {
        for (int i=0;i<3;i++) assertThat(next().complete()).isTrue();
        ProjectCleanupClaim claim=admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("ALARM");
        return claim;
    }

    /** @return 下一实际领取和提交结果 */
    private ProjectCleanupBatchResult next() { return batches.execute(admission.claimNext().orElseThrow()).orElseThrow(); }

    /** @return 有界调用内的累计真实DELETE；等待不能冒称完成 */
    private int drain() {
        int count=0;
        for (int i=0;i<40;i++) {
            ProjectCleanupBatchResult result=next();
            assertThat(result.blockedReason()).isNull();
            assertThat(result.deletedRows()).isBetween(0,500);
            count+=result.deletedRows();
            if (result.complete()) return count;
        }
        throw new AssertionError("告警清理超出预计批次");
    }

    /** @param recent 不到30天的邻居 @return 项目、设备、规则和旧实例骨架 */
    private Fixture fixture(boolean recent) {
        Fixture f=new Fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'告警清理租户')",f.tenant());
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at)
                VALUES (?,?,'告警清理项目',?,'DELETING',1,clock_timestamp()-?::interval)
                """,f.project(),f.tenant(),"alarm_"+f.project().toString().replace("-",""),recent?"1 day":"31 days");
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'type','类型','STANDARD','DIRECT','DRAFT')",f.type(),f.tenant(),f.project());
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,'device','设备')",f.device(),f.tenant(),f.project(),f.type());
        owner.update("""
                INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                VALUES (?,?,?,'告警规则','HIGH_TEMPERATURE',?,'temperature','GT',30,'LT',25,'MAJOR')
                """,f.rule(),f.tenant(),f.project(),f.device());
        owner.update("""
                INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,
                    condition_state,first_condition_at,last_received_at,last_value,created_at,updated_at)
                VALUES (?,?,?,?,'DEVICE',?,'HIGH_TEMPERATURE','MAJOR','ACTIVE',now()-interval '9 days',now()-interval '9 days',31,
                    now()-interval '9 days',now()-interval '9 days')
                """,f.instance(),f.tenant(),f.project(),f.rule(),f.device());
        return f;
    }

    /** @param f 事件归属 @param instance 实际父引用，允许构造旧库跨项目异常 @param count 真实事件数 */
    private void events(Fixture f,UUID instance,int count) {
        owner.update("""
                INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,trace_id,received_at,condition_state,ack_state)
                SELECT CASE WHEN n=1 THEN ? ELSE gen_random_uuid() END,?,?,?,'ACTIVATED',gen_random_uuid(),'trace',
                    now()-interval '9 days','ACTIVE','UNACKNOWLEDGED' FROM generate_series(1,?) n
                """,f.event(),f.tenant(),f.project(),instance,count);
    }

    /** @param f 完整项目与来源事件；PUSH稳定引用按ADR0038不设跨域FK，不伪造收件人 */
    private void notifications(Fixture f) {
        owner.update("INSERT INTO alarm_notification_group(id,tenant_id,project_id,name) VALUES (?,?,?,'通知组')",f.group(),f.tenant(),f.project());
        for (String channel:List.of("EMAIL","WEBHOOK","PUSH")) {
            UUID template=UUID.randomUUID();
            UUID binding=UUID.randomUUID();
            UUID recipient=channel.equals("PUSH")?null:UUID.randomUUID();
            owner.update("INSERT INTO alarm_notification_template(id,tenant_id,project_id,name,channel,subject_template,body_template) VALUES (?,?,?,?,?,'主题','正文')",template,f.tenant(),f.project(),channel,channel);
            owner.update("INSERT INTO alarm_notification_binding(id,tenant_id,project_id,rule_id,group_id,template_id,channel) VALUES (?,?,?,?,?,?,?)",binding,f.tenant(),f.project(),f.rule(),f.group(),template,channel);
            if (recipient!=null) owner.update("INSERT INTO alarm_notification_recipient(id,tenant_id,project_id,group_id,channel,target) VALUES (?,?,?,?,?,'target')",recipient,f.tenant(),f.project(),f.group(),channel);
            owner.update("""
                    INSERT INTO alarm_notification_delivery(id,tenant_id,project_id,instance_id,alarm_event_id,binding_id,recipient_id,
                        channel,target_snapshot,body_snapshot,template_version,status,attempt_count,created_at,updated_at,terminal_at,app_user_id,push_token_id)
                    VALUES (?,?,?,?,?,?,?,?,?,'正文',0,'DEAD_LETTER',1,now()-interval '9 days',now()-interval '9 days',now()-interval '9 days',?,?)
                    """,channel.equals("EMAIL")?f.delivery():UUID.randomUUID(),f.tenant(),f.project(),f.instance(),f.event(),binding,recipient,
                    channel,channel.equals("PUSH")?"PUSH":"target",channel.equals("PUSH")?UUID.randomUUID():null,channel.equals("PUSH")?UUID.randomUUID():null);
        }
    }

    /** @param writer 独立事务 @param f 事件归属 @param instance 被清理的父实例 */
    private void insertEvent(Connection writer,Fixture f,UUID instance) throws SQLException {
        try (PreparedStatement sql=writer.prepareStatement("INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,trace_id,received_at,condition_state,ack_state) VALUES (gen_random_uuid(),?,?,?,'ACTIVATED',gen_random_uuid(),'trace',now()-interval '9 days','ACTIVE','UNACKNOWLEDGED')")) {
            sql.setObject(1,f.tenant());
            sql.setObject(2,f.project());
            sql.setObject(3,instance);
            sql.setQueryTimeout(4);
            sql.executeUpdate();
        }
    }

    /** @param f 仅对本事务生效的APP范围 */
    private void scope(Fixture f) {
        app.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,f.tenant().toString());
        app.queryForObject("SELECT set_config('app.project_id',?,true)",String.class,f.project().toString());
    }

    /** @param table 本类固定表 @param f 项目 @return owner可见真实行数 */
    private long rows(String table,Fixture f) { return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND project_id=?",Long.class,f.tenant(),f.project()); }

    /** @param contributor ALARM真实或受控贡献 @return 既有三前置加本片的原事务编排 */
    private ProjectCleanupBatchService batch(ProjectCleanupContributor contributor) {
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, List.of(prerequisites.get(0),prerequisites.get(1),prerequisites.get(2),contributor)));
    }

    /** @param operation 原事务受控交错 @return 仍保持ALARM阶段的贡献 */
    private ProjectCleanupContributor contributor(Function<ProjectCleanupClaim,ProjectCleanupBatchResult> operation) {
        return new ProjectCleanupContributor() {
            /** 仅本片ALARM。 */
            @Override
            public ProjectCleanupStage stage() { return ProjectCleanupStage.ALARM; }
            /** 与真实贡献共享物理事务。 */
            @Override
            public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        };
    }

    /** @param target 注解服务 @return 原生产事务语义 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory=new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }

    /** @param pattern 本测试SQL识别符，只在PG确实等待锁时放行交错 */
    private void awaitLock(String pattern) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,pattern))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }
        throw new AssertionError("没有观察到告警清理锁等待");
    }

    /** @param target 明确旧库/新迁移截止 @return 全部当前工作区迁移 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink")
                .locations("classpath:db/migration/support","classpath:db/migration/project","classpath:db/migration/iam",
                        "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
                        "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/enduser","classpath:db/migration/export")
                .placeholders(Map.of("app_role_password","thingslink")).target(target).load();
    }

    /** 持久项目、设备、规则、实例及通知源身份，跨项目反例仅改变显式子引用。 */
    private record Fixture(UUID tenant,UUID project,UUID type,UUID device,UUID rule,UUID instance,UUID event,UUID group,UUID delivery) { }
}
