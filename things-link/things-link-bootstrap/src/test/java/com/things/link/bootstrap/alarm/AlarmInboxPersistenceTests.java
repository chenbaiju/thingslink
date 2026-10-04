package com.things.link.bootstrap.alarm;

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
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
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

/** ADR0093：真实APP验证账号回执的完整归属、幂等及每轮500行清理边界。 */
@Testcontainers
class AlarmInboxPersistenceTests {

    /** 独占PG用于全局领取和真实跨项目FK竞争。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("alarm_inbox_persistence").withUsername("thingslink").withPassword("thingslink");
    /** 本域实际九表按显式外键顺序统计，测试不得靠项目根级联清夹具。 */
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
        flyway("20260905.1500").migrate();
        assertThat(flyway("20260905.1600").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.1600").migrate().migrationsExecuted).isZero();
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

    /** 全部列有目录注释，归属FK与账号FK都拒绝隐式级联，RLS使用现有项目边界。 */
    @Test
    void declaresDocumentedColumnsNoActionKeysAndProjectPolicy() {
        assertThat(owner.queryForObject("SELECT obj_description('alarm_notification_read'::regclass)",String.class)).isNotBlank();
        assertThat(owner.queryForObject("SELECT pg_get_indexdef('alarm_event_notification_window_idx'::regclass)",String.class))
                .contains("tenant_id, project_id, received_at DESC, id DESC","ACTIVATED");
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='alarm_notification_read'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NOT NULL",Long.class)).isEqualTo(6);
        assertThat(owner.queryForList("SELECT confdeltype::text FROM pg_constraint WHERE conrelid='alarm_notification_read'::regclass AND contype='f'",String.class)).containsExactlyInAnyOrder("a","a");
        assertThat(owner.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid='alarm_notification_read'::regclass",Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename='alarm_notification_read' AND policyname='project_isolation' AND qual LIKE '%app_current_project%' AND with_check LIKE '%app_current_project%'",Long.class)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='alarm_notification_read_event_scope_fk'",String.class)).contains("FOREIGN KEY (tenant_id, project_id, alarm_event_id)","alarm_event(tenant_id, project_id, id)");
    }

    /** 同一事件的两个账号分别存储；重复插入不能改首次已读时间，事件删除必须显式先清回执。 */
    @Test
    void retainsIndependentAccountReceiptsAndPreventsImplicitEventDeletion() {
        Fixture f=fixture(false); events(f,f.instance(),1); UUID first=account(); UUID second=account();
        receipt(f,first); receipt(f,second);
        Object timestamp=owner.queryForObject("SELECT read_at FROM alarm_notification_read WHERE account_id=?",Object.class,first);
        assertThatThrownBy(()->receipt(f,first)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(owner.queryForObject("SELECT read_at FROM alarm_notification_read WHERE account_id=?",Object.class,first)).isEqualTo(timestamp);
        assertThatThrownBy(()->owner.update("DELETE FROM alarm_event WHERE id=?",f.event())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->owner.update("DELETE FROM sys_account WHERE id=?",first)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rows("alarm_notification_read",f)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM alarm_notification_read WHERE read_at<=clock_timestamp() AND read_at>clock_timestamp()-interval '1 minute'",Long.class)).isEqualTo(2);
    }

    /** 三元归属和全局账号不是可猜测的声明，错任一字段均由真实外键拒绝。 */
    @ParameterizedTest
    @ValueSource(strings={"tenant","project","event","account"})
    void rejectsInvalidReceiptOwnership(String changed) {
        Fixture f=fixture(false); events(f,f.instance(),1); Fixture other=fixture(true); events(other,other.instance(),1);
        UUID reader=account();
        assertThatThrownBy(()->owner.update("INSERT INTO alarm_notification_read(id,tenant_id,project_id,account_id,alarm_event_id) VALUES (gen_random_uuid(),?,?,?,?)",
                changed.equals("tenant")?other.tenant():f.tenant(),changed.equals("project")?other.project():f.project(),
                changed.equals("account")?UUID.randomUUID():reader,changed.equals("event")?other.event():f.event()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rows("alarm_notification_read",f)).isZero();
    }

    /** 项目RLS无上下文拒绝可见性；账号范围仍由仓储承担，禁止宣称新增了账号RLS。 */
    @Test
    void isolatesProjectsAndRejectsCrossProjectWrites() {
        Fixture f=fixture(false); events(f,f.instance(),1); Fixture other=fixture(true); events(other,other.instance(),1);
        UUID first=account(); UUID second=account(); receipt(f,first); receipt(f,second); receipt(other,first);
        assertThat(app.queryForObject("SELECT count(*) FROM alarm_notification_read",Long.class)).isZero();
        assertThat(new TransactionTemplate(transactions).<Long>execute(status->{
            scope(f); return app.queryForObject("SELECT count(*) FROM alarm_notification_read",Long.class);
        })).isEqualTo(2);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            scope(f); return app.update("INSERT INTO alarm_notification_read(id,tenant_id,project_id,account_id,alarm_event_id) VALUES (gen_random_uuid(),?,?,?,?)",other.tenant(),other.project(),second,other.event());
        })).isInstanceOf(DataAccessException.class);
        assertThat(rows("alarm_notification_read",other)).isEqualTo(1);
    }

    /** 回执不引用成员行，退出后重入不会重置个人已读或删除共享账号。 */
    @Test
    void preservesReceiptAcrossMembershipRemovalAndRejoin() {
        Fixture f=fixture(false); events(f,f.instance(),1); UUID reader=account(); receipt(f,reader);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (gen_random_uuid(),?,?,'VIEWER')",f.project(),reader);
        owner.update("DELETE FROM sys_project_member WHERE project_id=? AND account_id=?",f.project(),reader);
        assertThat(rows("alarm_notification_read",f)).isEqualTo(1);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (gen_random_uuid(),?,?,'VIEWER')",f.project(),reader);
        assertThat(rows("alarm_notification_read",f)).isEqualTo(1);
        owner.update("DELETE FROM sys_project_member WHERE project_id=?",f.project());
    }

    /** 一个事件对应1001账号，三轮只删500/500/1回执，任何隐式级联都将使实际预算断言失败。 */
    @Test
    void cleansOneEventWithManyReadersInBoundedBatchesAndPreservesNeighbor() {
        Fixture f=fixture(false); events(f,f.instance(),1); Fixture other=fixture(true); events(other,other.instance(),1);
        UUID shared=account(); receipt(other,shared);
        owner.update("""
                WITH readers AS (
                    INSERT INTO sys_account(id,email,password_hash,display_name)
                    SELECT id,id::text||'@inbox.test','hash','阅读账号'
                    FROM (SELECT gen_random_uuid() id FROM generate_series(1,1001)) identities RETURNING id
                )
                INSERT INTO alarm_notification_read(id,tenant_id,project_id,account_id,alarm_event_id)
                SELECT gen_random_uuid(),?,?,id,? FROM readers
                """,f.tenant(),f.project(),f.event());
        ProjectCleanupClaim claim=alarmClaim();
        for (int expected:List.of(500,500,1)) {
            long before=rows("alarm_notification_read",f);
            ProjectCleanupBatchResult result=batches.execute(claim).orElseThrow();
            assertThat(result.deletedRows()).isEqualTo(expected); assertThat(result.complete()).isFalse();
            assertThat(before-rows("alarm_notification_read",f)).isEqualTo(expected);
            assertThat(rows("alarm_event",f)).isEqualTo(1); assertThat(rows("alarm_notification_read",other)).isEqualTo(1);
            claim=admission.claimNext().orElseThrow();
        }
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",Long.class,f.project())).isEqualTo(1001);
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
        assertThat(drain()).isEqualTo(2);
        assertThat(rows("alarm_notification_read",other)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_account WHERE id=?",Long.class,shared)).isEqualTo(1);
    }

    /** 回执无外发重放语义，先收束阅读行；下一轮仍保留旧域的八天最近事实保护。 */
    @Test
    void deletesReceiptBeforeApplyingExistingAlarmReplayWindow() {
        Fixture f=fixture(false); events(f,f.instance(),1); receipt(f,account());
        owner.update("UPDATE alarm_event SET received_at=clock_timestamp() WHERE id=?",f.event());
        assertThat(batches.execute(alarmClaim()).orElseThrow().deletedRows()).isEqualTo(1);
        assertThat(next().blockedReason()).isEqualTo("ALARM_REPLAY_WINDOW");
        assertThat(rows("alarm_notification_read",f)).isZero(); assertThat(rows("alarm_event",f)).isEqualTo(1);
    }

    /** 项目原事务在回执删除后失败，行数和进度必须一起回滚。 */
    @Test
    void rollsBackReceiptDeletionAndProgressOnFailure() {
        Fixture f=fixture(false); events(f,f.instance(),1); receipt(f,account()); ProjectCleanupClaim claim=alarmClaim();
        ProjectCleanupBatchService broken=batch(contributor(c->{
            assertThat(alarms.clean(c).deletedRows()).isEqualTo(1); throw new IllegalStateException("回执删除后故障");
        }));
        assertThatThrownBy(()->broken.execute(claim)).hasMessage("回执删除后故障");
        assertThat(rows("alarm_notification_read",f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",Long.class,f.project())).isZero();
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
    }

    /** 完整能力的任一组成不符都不能直接调用SECURITY DEFINER函数删除已读。 */
    @ParameterizedTest
    @ValueSource(strings={"tenant","project","generation","token","stage"})
    void rejectsInvalidCleanupCapability(String changed) {
        Fixture f=fixture(false); events(f,f.instance(),1); receipt(f,account()); ProjectCleanupClaim claim=alarmClaim();
        if (changed.equals("stage")) owner.update("UPDATE sys_project SET cleanup_stage='ENDUSER' WHERE id=?",f.project());
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->app.queryForMap(
                "SELECT * FROM public.alarm_project_cleanup_batch(?,?,?,?)",changed.equals("tenant")?UUID.randomUUID():f.tenant(),
                changed.equals("project")?UUID.randomUUID():f.project(),changed.equals("generation")?claim.generation()+1:claim.generation(),
                changed.equals("token")?UUID.randomUUID():claim.leaseToken())))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(rows("alarm_notification_read",f)).isEqualTo(1);
    }

    /** 回执行锁等待跨过租约截止，实际出口重验将已做DELETE回滚，不能借事务起点时间越权。 */
    @Test
    void rollsBackWhenLeaseExpiresDuringReceiptRowLock() throws Exception {
        Fixture f=fixture(false); events(f,f.instance(),1); UUID reader=account(); receipt(f,reader); ProjectCleanupClaim claim=alarmClaim();
        owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()+interval '2 seconds' WHERE id=?",f.project());
        try (Connection writer=owner.getDataSource().getConnection();var executor=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement sql=writer.prepareStatement("SELECT id FROM alarm_notification_read WHERE account_id=? FOR UPDATE")) {
                sql.setObject(1,reader); sql.executeQuery().close();
            }
            var cleanup=executor.submit(()->batches.execute(claim));
            awaitLock("%public.alarm_project_cleanup_batch%");
            owner.execute("SELECT pg_sleep(2.1)"); writer.commit();
            assertThatThrownBy(()->cleanup.get(5,TimeUnit.SECONDS)).hasRootCauseInstanceOf(SQLException.class);
        }
        assertThat(rows("alarm_notification_read",f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?",Long.class,f.project())).isZero();
    }

    /** 回执插入先持事件FK锁，清理等待事件锁后必须看到刚提交的回执，显式阻塞而非删除失败。 */
    @Test
    void rechecksNewlyCommittedReceiptAfterEventLockWait() throws Exception {
        Fixture f=fixture(false); events(f,f.instance(),1); UUID reader=account(); ProjectCleanupClaim claim=alarmClaim();
        try (Connection writer=owner.getDataSource().getConnection();var executor=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false); insertReceipt(writer,f,reader);
            var cleanup=executor.submit(()->batches.execute(claim).orElseThrow());
            awaitLock("%public.alarm_project_cleanup_batch%"); writer.commit();
            assertThat(cleanup.get(5,TimeUnit.SECONDS).blockedReason()).isEqualTo("ALARM_READ_REMAINS");
        }
        assertThat(rows("alarm_notification_read",f)).isEqualTo(1); assertThat(rows("alarm_event",f)).isEqualTo(1);
    }

    /** 清理先锁事件，迟到的回执插入只能等待并被NO ACTION父引用约束拒绝，不穿透候选复核。 */
    @Test
    void rejectsReceiptInsertedAfterCleanupLocksEvent() throws Exception {
        Fixture f=fixture(false); events(f,f.instance(),1); UUID reader=account(); ProjectCleanupClaim claim=alarmClaim();
        try (var executor=Executors.newSingleThreadExecutor()) {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>> insertion=new java.util.concurrent.atomic.AtomicReference<>();
            ProjectCleanupBatchService controlled=batch(contributor(c->{
                app.queryForObject("SELECT id FROM public.alarm_event WHERE id=? FOR UPDATE",UUID.class,f.event());
                insertion.set(executor.submit(()->{
                    try (Connection writer=owner.getDataSource().getConnection()) { insertReceipt(writer,f,reader); return "INSERTED"; }
                    catch (SQLException failure) { return failure.getSQLState(); }
                }));
                awaitLock("%INSERT INTO alarm_notification_read%"); return alarms.clean(c);
            }));
            assertThat(controlled.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
            assertThat(insertion.get().get(5,TimeUnit.SECONDS)).isEqualTo("23503");
        }
        assertThat(rows("alarm_notification_read",f)).isZero();
    }

    /** @return 独立全局账号；不伪造需要项目成员FK的账号范围 */
    private UUID account() {
        UUID id=UUID.randomUUID();
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'hash','通知账号')",id,id+"@example.test");
        return id;
    }

    /** @param f 事件完整归属 @param reader Console全局账号 */
    private void receipt(Fixture f,UUID reader) {
        owner.update("INSERT INTO alarm_notification_read(id,tenant_id,project_id,account_id,alarm_event_id) VALUES (gen_random_uuid(),?,?,?,?)",f.tenant(),f.project(),reader,f.event());
    }

    /** @param writer 独立数据库连接 @param f 事件完整归属 @param reader 先创建的全局账号 */
    private void insertReceipt(Connection writer,Fixture f,UUID reader) throws SQLException {
        try (PreparedStatement sql=writer.prepareStatement("INSERT INTO alarm_notification_read(id,tenant_id,project_id,account_id,alarm_event_id) VALUES (gen_random_uuid(),?,?,?,?)")) {
            sql.setObject(1,f.tenant()); sql.setObject(2,f.project()); sql.setObject(3,reader); sql.setObject(4,f.event());
            sql.setQueryTimeout(4); sql.executeUpdate();
        }
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
