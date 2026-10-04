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

/** ADR0079：真实受限APP验证规则静默、有界删除、引用环、重放及原事务竞争。 */
@Testcontainers
class ProjectCleanupRuleTests {

    /** 独占PG，避免全局项目领取及锁观测混入别的测试夹具。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_cleanup_rule").withUsername("thingslink").withPassword("thingslink");
    /** 固定十表列表仅用于测试清理和计数，不是生产动态SQL入口。 */
    private static final List<String> TABLES = List.of("rule_device_action_delivery", "rule_notification_delivery",
            "rule_execution_log", "rule_execution_receipt", "rule_debug_event", "rule_scene_execution",
            "rule_version", "rule_scene_version", "rule_message", "rule_scene");
    /** owner只建夹具和观察持久结果。 */
    private static JdbcTemplate owner;
    /** 所有业务清理使用真实RLS受限角色。 */
    private JdbcTemplate app;
    /** 与生产构造器一致，所有批次代理复用一个事务局部范围组件实例。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 原事务连接管理器。 */
    private DataSourceTransactionManager transactions;
    /** 项目进度所有者。 */
    private JdbcProjectCleanupRepository projects;
    /** 真实30天准入与租约。 */
    private ProjectCleanupAdmissionService admission;
    /** 本片真实RULE贡献。 */
    private ProjectCleanupContributor rules;
    /** 两个已验收前置不手改阶段跳过。 */
    private List<ProjectCleanupContributor> prerequisites;
    /** 包含原事务最终围栏的执行入口。 */
    private ProjectCleanupBatchService batches;

    /** 旧库只新增本片固定函数及索引，重复迁移不重写历史。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260905.0200").migrate();
        assertThat(flyway("20260905.0300").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260905.0300").migrate().migrationsExecuted).isZero();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_proc p, LATERAL aclexplode(p.proacl) a
                 WHERE p.oid='public.rule_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0
                """, Long.class)).isZero();
    }

    /** 只清隔离测试库，先解除指针再删子行；审计继续保留。 */
    @BeforeEach
    void setup() {
        owner.update("UPDATE rule_message SET status='DRAFT',active_version_id=NULL");
        owner.update("UPDATE rule_scene SET status='DRAFT',active_version_id=NULL");
        for (String table : TABLES) owner.update("DELETE FROM " + table);
        for (String table : List.of("dev_device", "dev_type", "sys_project")) owner.update("DELETE FROM " + table);
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(source);
        transactionLocalRlsScope = new TransactionLocalRlsScope(app);
        transactions = new DataSourceTransactionManager(source);
        projects = new JdbcProjectCleanupRepository(app);
        admission = proxy(new ProjectCleanupAdmissionService(projects, new AuditLogService(app, new ObjectMapper()), app));
        prerequisites = List.of(proxy(new ProjectExportPurgeContributor(new JdbcProjectExportPurgeRepository(app))),
                proxy(new TaskProjectCleanupContributor(new JdbcTaskProjectCleanupRepository(app))));
        rules = proxy(new RuleProjectCleanupContributor(new JdbcRuleProjectCleanupRepository(app)));
        batches = batch(rules);
    }

    /** 十表、两种来源和1001条日志真实跨批；重建批次服务后继续，同项目版本环解除且邻居全部保留。 */
    @Test
    void cleansAllTablesInBoundedResumableBatchesAndPreservesNeighbor() {
        Fixture f = fixture(false);
        history(f, 1001);
        Fixture other = fixture(true);
        history(other, 1);
        ProjectCleanupClaim claim = ruleClaim();
        assertThat(batches.execute(claim).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.deleted(0));
        assertThat(owner.queryForMap("SELECT status,active_version_id FROM rule_message WHERE id=?", f.rule()))
                .containsEntry("status", "DRAFT").containsEntry("active_version_id", null);
        batches = batch(rules);
        assertThat(drain()).isEqualTo(1012);
        for (String table : TABLES) {
            assertThat(rows(table, f)).isZero();
            assertThat(rows(table, other)).isEqualTo(table.contains("delivery") ? 2L : 1L);
        }
        assertThat(owner.queryForMap("SELECT cleanup_stage,cleanup_rows,cleanup_batches FROM sys_project WHERE id=?", f.project()))
                .containsEntry("cleanup_stage", "ALARM").containsEntry("cleanup_rows", 1012L).containsEntry("cleanup_batches", 12L);
        assertThat(rows("dev_device", f)).isEqualTo(1);
        assertThat(rows("dev_type", f)).isEqualTo(1);
        // 父版本已消失，旧回执claim不能建立事实，更不能重置动作许可。
        assertThat(app.queryForObject("SELECT public.rule_execution_receipt_claim_state(?,?,?,?,?,ARRAY[?]::uuid[],1,now())", String.class,
                f.tenant(), f.project(), f.message(), f.rule(), f.version(), f.version())).isEqualTo("BUSY");
        assertThat(replayNotification(f)).isEqualTo("REJECTED");
        assertThat(rows("rule_notification_delivery", f)).isZero();
    }

    /** 最近事实独立于31天项目窗口，四种后台事实与场景执行均不能提前清掉。 */
    @ParameterizedTest
    @ValueSource(strings = {"rule_execution_receipt", "rule_execution_log", "rule_notification_delivery",
            "rule_device_action_delivery", "rule_scene_execution"})
    void waitsForRecentlyChangedReplayFacts(String table) {
        Fixture f = fixture(false);
        history(f, 1);
        String column = table.equals("rule_execution_receipt") || table.equals("rule_notification_delivery")
                ? "updated_at" : "created_at";
        owner.update("UPDATE " + table + " SET " + column + "=clock_timestamp() WHERE project_id=?", f.project());
        ProjectCleanupBatchResult result = batches.execute(ruleClaim()).orElseThrow();
        assertThat(result.blockedReason()).isEqualTo("RULE_REPLAY_WINDOW");
        assertThat(rows(table, f)).isEqualTo(table.contains("delivery") ? 2L : 1L);
        assertThat(owner.queryForObject("SELECT deleted_at IS NULL FROM rule_message WHERE id=?", Boolean.class, f.rule())).isTrue();
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?", Long.class, f.project())).isZero();
    }

    /** 调试保留与有效回执租约是独立等待边界，不从项目已删除推断可以取消原工作。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForDebugRetentionAndActiveLease(boolean inFlight) {
        Fixture f = fixture(false);
        history(f, 1);
        if (inFlight) {
            owner.update("UPDATE rule_execution_receipt SET status='IN_PROGRESS',completed_at=NULL,lease_until=now()+interval '1 minute' WHERE project_id=?", f.project());
        } else {
            owner.update("UPDATE rule_debug_event SET expires_at=now()+interval '1 day' WHERE project_id=?", f.project());
        }
        assertThat(batches.execute(ruleClaim()).orElseThrow().blockedReason())
                .isEqualTo(inFlight ? "RULE_WORK_IN_FLIGHT" : "RULE_DEBUG_RETENTION");
        assertThat(rows("rule_version", f)).isEqualTo(1);
    }

    /** 1001条定义的状态整理本身也必须500封顶，零删除不能冒充完成或累计真实删除。 */
    @Test
    void boundsDefinitionPreparationAndDoesNotCountItAsDeletion() {
        Fixture f = fixture(false);
        owner.update("""
                INSERT INTO rule_message(id,tenant_id,project_id,name,created_by,created_at,updated_at)
                SELECT gen_random_uuid(),?,?, '定义_'||n,?,now(),now() FROM generate_series(1,1000) n
                """, f.tenant(), f.project(), f.account());
        assertThat(batches.execute(ruleClaim()).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.deleted(0));
        assertThat(prepared(f)).isEqualTo(500);
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(0));
        assertThat(prepared(f)).isEqualTo(1000);
        assertThat(next()).isEqualTo(ProjectCleanupBatchResult.deleted(0));
        assertThat(prepared(f)).isEqualTo(1001);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?", Long.class, f.project())).isZero();
        assertThat(drain()).isEqualTo(1004);
    }

    /** 错token或错阶段即使绕过Java围栏直接调用领域端口也拒绝，普通APP仍不能直接DELETE历史。 */
    @Test
    void rejectsWrongIdentityAndPreservesDirectDeleteBan() {
        Fixture f = fixture(false);
        ProjectCleanupClaim claim = ruleClaim();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                app.queryForMap("SELECT * FROM public.rule_project_cleanup_batch(?,?,?,?)", f.tenant(), f.project(), claim.generation(), UUID.randomUUID())))
                .hasRootCauseInstanceOf(SQLException.class);
        owner.update("UPDATE sys_project SET cleanup_stage='ALARM' WHERE id=?", f.project());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> rules.clean(claim)))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> app.update("DELETE FROM public.rule_version WHERE id=?", f.version()))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(rows("rule_version", f)).isEqualTo(1);
    }

    /** 临时空表不能遮蔽真实版本；贡献后的异常必须同时回滚删除和项目累计。 */
    @Test
    void ignoresTemporaryTablesAndRollsBackFailedContribution() {
        Fixture f = fixture(false);
        normalizeDefinitions();
        ProjectCleanupClaim claim = ruleClaim();
        ProjectCleanupBatchService broken = batch(contributor(c -> {
            for (String table : TABLES) app.execute("CREATE TEMP TABLE " + table + " (LIKE public." + table + ") ON COMMIT DROP");
            assertThat(rules.clean(c).deletedRows()).isEqualTo(1);
            throw new IllegalStateException("本片受控贡献后故障");
        }));
        assertThatThrownBy(() -> broken.execute(claim)).hasMessage("本片受控贡献后故障");
        assertThat(rows("rule_version", f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?", Long.class, f.project())).isZero();
        assertThat(batches.execute(claim).orElseThrow().deletedRows()).isEqualTo(1);
        assertThat(drain()).isEqualTo(3);
    }

    /** 初始静默检查后等待子行锁，刚提交的交付更新必须由删除条件重新核验并保留。 */
    @Test
    void preservesARecentlyUpdatedDeliveryAfterWaitingForItsRowLock() throws Exception {
        Fixture f = fixture(false);
        notification(f, false);
        normalizeDefinitions();
        ProjectCleanupClaim claim = ruleClaim();
        try (Connection writer = owner.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement sql = writer.prepareStatement("UPDATE rule_notification_delivery SET updated_at=clock_timestamp() WHERE id=?")) {
                sql.setObject(1, f.notification());
                sql.executeUpdate();
            }
            var cleanup = executor.submit(() -> batches.execute(claim).orElseThrow());
            awaitLock("%public.rule_project_cleanup_batch%");
            writer.commit();
            assertThat(cleanup.get(5, TimeUnit.SECONDS).blockedReason()).isEqualTo("RULE_FACT_CHANGED");
        }
        assertThat(rows("rule_notification_delivery", f)).isEqualTo(1);
        assertThat(rows("rule_version", f)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT cleanup_rows FROM sys_project WHERE id=?", Long.class, f.project())).isZero();
    }

    /** 空领域也由已装配的贡献明确证明，而不是跳过缺失步骤。 */
    @Test
    void completesAnEmptyRuleDomain() {
        Fixture f = fixture(false);
        normalizeDefinitions();
        for (String table : TABLES) owner.update("DELETE FROM " + table);
        assertThat(batches.execute(ruleClaim()).orElseThrow()).isEqualTo(ProjectCleanupBatchResult.done());
        assertThat(owner.queryForObject("SELECT cleanup_stage FROM sys_project WHERE id=?", String.class, f.project())).isEqualTo("ALARM");
    }

    /** @return 真实导出与任务空域推进后的RULE租约 */
    private ProjectCleanupClaim ruleClaim() {
        assertThat(next().complete()).isTrue();
        assertThat(next().complete()).isTrue();
        ProjectCleanupClaim claim = admission.claimNext().orElseThrow();
        assertThat(claim.stage()).isEqualTo("RULE");
        return claim;
    }

    /** @return 下一真实批次 */
    private ProjectCleanupBatchResult next() {
        return batches.execute(admission.claimNext().orElseThrow()).orElseThrow();
    }

    /** @return 最多60次调用的真实累计删除量，异常等待不得冒充已经清空 */
    private int drain() {
        int deleted = 0;
        for (int i = 0; i < 60; i++) {
            ProjectCleanupBatchResult result = next();
            assertThat(result.blockedReason()).isNull();
            assertThat(result.deletedRows()).isBetween(0, 500);
            deleted += result.deletedRows();
            if (result.complete()) return deleted;
        }
        throw new AssertionError("规则清理没有在预期有界批次内完成");
    }

    /** @param f 项目 @return 已处理定义条数，与真正DELETE计数区分 */
    private long prepared(Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM rule_message WHERE project_id=? AND deleted_at IS NOT NULL", Long.class, f.project());
    }

    /** 设置已经完成的指针前置，只用于定位删除/行锁反例，不用来伪造项目阶段。 */
    private void normalizeDefinitions() {
        for (String table : List.of("rule_message", "rule_scene")) {
            owner.update("UPDATE " + table + " SET status='DRAFT',active_version_id=NULL,deleted_at=now()");
        }
    }

    /** @param recent 邻居是否保持不满足准入 @return 真实同属设备及双向规则/场景版本骨架 */
    private Fixture fixture(boolean recent) {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'规则清理租户')", f.tenant());
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'hash','责任账号')", f.account(), f.account()+"@test.example");
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at)
                VALUES (?,?,'规则清理项目',?,'DELETING',1,clock_timestamp()-?::interval)
                """, f.project(), f.tenant(), "rule_"+f.project().toString().replace("-", ""), recent ? "1 day" : "31 days");
        owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'rule-type','类型','STANDARD','DIRECT','DRAFT')", f.type(),f.tenant(),f.project());
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES (?,?,?,?,'rule-device','设备')", f.device(),f.tenant(),f.project(),f.type());
        owner.update("INSERT INTO rule_message(id,tenant_id,project_id,name,created_by,created_at,updated_at) VALUES (?,?,?,'规则',?,now(),now())", f.rule(),f.tenant(),f.project(),f.account());
        owner.update("""
                INSERT INTO rule_version(id,tenant_id,project_id,rule_id,version_number,source,source_sha256,created_by,created_at)
                VALUES (?,?,?,?,1,'input => input',repeat('a',64),?,now())
                """, f.version(),f.tenant(),f.project(),f.rule(),f.account());
        owner.update("UPDATE rule_message SET status='ACTIVE',active_version_id=? WHERE id=?", f.version(), f.rule());
        owner.update("INSERT INTO rule_scene(id,tenant_id,project_id,name,created_by,created_at,updated_at) VALUES (?,?,?,'场景',?,now(),now())", f.scene(),f.tenant(),f.project(),f.account());
        owner.update("INSERT INTO rule_scene_version(id,tenant_id,project_id,scene_id,version_number,created_by,created_at) VALUES (?,?,?,?,1,?,now())", f.sceneVersion(),f.tenant(),f.project(),f.scene(),f.account());
        owner.update("UPDATE rule_scene SET status='ACTIVE',active_version_id=? WHERE id=?", f.sceneVersion(),f.scene());
        return f;
    }

    /** @param f 完整归属 @param logs 有界批次验收的真实日志数量 */
    private void history(Fixture f, int logs) {
        owner.update("""
                INSERT INTO rule_execution_log(id,tenant_id,project_id,message_id,rule_id,rule_version_id,attempt,status,result_code,duration_millis,input_bytes,output_bytes,created_at)
                SELECT gen_random_uuid(),?,?,gen_random_uuid(),?,?,1,'SUCCESS','SUCCESS',1,1,1,now()-interval '9 days' FROM generate_series(1,?)
                """, f.tenant(),f.project(),f.rule(),f.version(),logs);
        owner.update("""
                INSERT INTO rule_execution_receipt(tenant_id,project_id,message_id,rule_id,rule_version_id,status,attempt,first_enqueued_at,updated_at,completed_at,plan_version_ids)
                VALUES (?,?,?,?,?,'COMPLETED',1,now()-interval '9 days',now()-interval '9 days',now()-interval '9 days',ARRAY[?]::uuid[])
                """, f.tenant(),f.project(),f.message(),f.rule(),f.version(),f.version());
        owner.update("""
                INSERT INTO rule_debug_event(id,tenant_id,project_id,rule_id,version_id,status,result_code,duration_millis,input_bytes,output_bytes,input_summary,created_by,created_at,expires_at)
                VALUES (gen_random_uuid(),?,?,?,?,'SUCCESS','SUCCESS',1,1,1,'{}',?,now()-interval '9 days',now()-interval '2 days')
                """, f.tenant(),f.project(),f.rule(),f.version(),f.account());
        owner.update("""
                INSERT INTO rule_scene_execution(id,tenant_id,project_id,scene_id,scene_version_id,idempotency_key,request_digest,device_id,status,operator_account_id,trace_id,occurred_at,created_at,completed_at)
                VALUES (?,?,?,?,?,'key',repeat('a',64),?,'DISPATCHED',?,'trace',now()-interval '9 days',now()-interval '9 days',now()-interval '9 days')
                """, f.sceneExecution(),f.tenant(),f.project(),f.scene(),f.sceneVersion(),f.device(),f.account());
        for (boolean scene : List.of(false, true)) {
            notification(f, scene);
            owner.update("""
                    INSERT INTO rule_device_action_delivery(id,tenant_id,project_id,rule_id,rule_version_id,message_id,scene_id,scene_version_id,scene_execution_id,
                        device_id,operation_type,status,failure_code,trace_id,created_at,completed_at)
                    VALUES (gen_random_uuid(),?,?,?,?,?,?,?,?,?,'COMMAND','REJECTED','PROJECT_FROZEN','trace',now()-interval '9 days',now()-interval '9 days')
                    """, f.tenant(),f.project(),scene?null:f.rule(),scene?null:f.version(),scene?null:f.message(),
                    scene?f.scene():null,scene?f.sceneVersion():null,scene?f.sceneExecution():null,f.device());
        }
    }

    /** @param f 规则/场景同属 @param scene 是否使用互斥的场景来源列 */
    private void notification(Fixture f, boolean scene) {
        owner.update("""
                INSERT INTO rule_notification_delivery(id,tenant_id,project_id,rule_id,rule_version_id,message_id,scene_id,scene_version_id,scene_execution_id,
                    device_id,channel,recipient,subject,body,status,trace_id,attempt_count,created_at,updated_at,terminal_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'EMAIL','test@example.com','subject','body','DEAD_LETTER','trace',1,
                    now()-interval '9 days',now()-interval '9 days',now()-interval '9 days')
                """, scene?UUID.randomUUID():f.notification(),f.tenant(),f.project(),scene?null:f.rule(),scene?null:f.version(),scene?null:f.message(),
                scene?f.scene():null,scene?f.sceneVersion():null,scene?f.sceneExecution():null,f.device());
    }

    /** @param f 已清理的原身份 @return 原首次通知再次到达的真实数据库接纳裁决 */
    private String replayNotification(Fixture f) {
        return app.queryForObject("""
                SELECT public.rule_notification_delivery_acceptance(?,?,?,?,?,?,NULL,NULL,NULL,?,'EMAIL','test@example.com','subject','body','trace',1,now())
                """, String.class, f.notification(),f.tenant(),f.project(),f.rule(),f.version(),f.message(),f.device());
    }

    /** @param table 本类固定表 @param f 项目 @return owner可见真实数量 */
    private long rows(String table, Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND project_id=?", Long.class,f.tenant(),f.project());
    }

    /** @param contributor 真实或受控RULE贡献 @return 原事务完整批次 */
    private ProjectCleanupBatchService batch(ProjectCleanupContributor contributor) {
        return proxy(new ProjectCleanupBatchService(admission, projects, transactionLocalRlsScope, List.of(prerequisites.get(0),prerequisites.get(1),contributor)));
    }

    /** @param operation 原事务内受控交错 @return 保持RULE身份的测试贡献器 */
    private ProjectCleanupContributor contributor(Function<ProjectCleanupClaim,ProjectCleanupBatchResult> operation) {
        return new ProjectCleanupContributor() {
            /** 仅接管本片RULE阶段。 */
            @Override
            public ProjectCleanupStage stage() { return ProjectCleanupStage.RULE; }
            /** 故障和真实贡献共享原物理事务。 */
            @Override
            public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) { return operation.apply(claim); }
        };
    }

    /** @param target 注解服务 @return 使用生产事务语义的代理 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** @param pattern 本片SQL唯一识别模式，必须真正观察到PG锁等待 */
    private void awaitLock(String pattern) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,pattern))) return;
            try { Thread.sleep(10); } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }
        throw new AssertionError("未观察到规则清理行锁等待");
    }

    /** @param target 固定升级截止 @return 全部工作区迁移的可重复装配 */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),"thingslink","thingslink")
                .locations("classpath:db/migration/support","classpath:db/migration/project","classpath:db/migration/iam",
                        "classpath:db/migration/device","classpath:db/migration/telemetry","classpath:db/migration/alarm",
                        "classpath:db/migration/task","classpath:db/migration/rule","classpath:db/migration/enduser","classpath:db/migration/export")
                .placeholders(Map.of("app_role_password","thingslink")).target(target).load();
    }

    /** 实际持久身份；来源UUID不靠随机值代替同属约束验证。 */
    private record Fixture(UUID tenant,UUID account,UUID project,UUID type,UUID device,UUID rule,UUID version,
                           UUID scene,UUID sceneVersion,UUID sceneExecution,UUID message,UUID notification) { }
}
