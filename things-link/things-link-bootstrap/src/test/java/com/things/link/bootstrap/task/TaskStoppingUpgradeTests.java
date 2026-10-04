package com.things.link.bootstrap.task;

import com.things.link.task.domain.TaskExecution;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ADR0066决策4：真实0710旧库升级到0100，原六种执行状态及完整历史行不被改写。
 * 独立物理容器不继承最新Spring上下文，禁止删除约束后伪造旧schema；本类只验迁移、ACL与真实领取SQL。
 */
@Testcontainers
class TaskStoppingUpgradeTests {
    /** 与AbstractIntegrationTest及部署一致，防止不同PG版本产生权限或索引假阳性。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";
    /** Flyway含集群角色创建，必须实例隔离，不只更换database。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("task_stopping_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 与生产完整Flyway目录一致，不裁掉其他领域已生效约束。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser"
    };
    /** 前一已交付数据库基线，未来新增迁移不能改写本例历史前置。 */
    private static final String LEGACY_VERSION = "20260903.0710";
    /** 本片明确新增版本，只验此次升级，不让未来修复掩盖当前缺陷。 */
    private static final String STOPPING_VERSION = "20260904.0100";
    /** 实际运行角色，不能使用owner证明受限函数权限。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 仅专用容器测试凭据，与迁移角色占位符匹配。 */
    private static final String APP_PASSWORD = "thingslink";
    /** 固定PG精度时间保证升级前后完整字段可逐字比较。 */
    private static final Instant FIXED_AT = Instant.parse("2026-08-11T00:00:00Z");
    /** 六种旧状态必须全部原样保留，包括已经完成的历史。 */
    private static final List<String> LEGACY_STATES = List.of("EXPANDING", "DISPATCHING", "RUNNING", "SUCCEEDED", "PARTIAL_FAILED", "FAILED");
    /** 与运行时持久验收相同的四张物理事实表。 */
    private static final List<String> TABLES = List.of("task_job", "task_schedule", "task_execution", "task_target");

    /** 顺序完整升级流程不依赖JUnit排序；schema版本和数据保持分别验证。 */
    @Test
    void upgradesOldRowsWithoutRewriteAndEnablesOnlyRestrictedStoppingClaims() throws Exception {
        flyway(LEGACY_VERSION).migrate();
        Fixture fixture = seedLegacy();
        Map<String, List<String>> before = facts(fixture.projectId());
        assertStateRejected(fixture.firstExecutionId(), "STOPPING");
        assertThat(facts(fixture.projectId())).isEqualTo(before);
        assertThat(flyway(STOPPING_VERSION).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(facts(fixture.projectId())).isEqualTo(before);
        assertThat(TaskExecution.Status.valueOf("STOPPING").name()).isEqualTo("STOPPING");
        assertCatalog();
        UUID stoppingId = UUID.randomUUID();
        try (Connection owner = ownerConnection()) { insertExecution(owner, fixture, stoppingId, "STOPPING"); }
        assertStateRejected(stoppingId, "NOT_A_STATE");
        try (Connection app = appConnection(fixture.projectId())) {
            assertThat(text(app, "SELECT current_database()" )).isEqualTo(POSTGRES.getDatabaseName());
            assertThat(text(app, "SELECT current_user")).isEqualTo(APP_ROLE);
            assertThat(integer(app, "SELECT count(*) FROM pg_roles WHERE rolname=current_user AND NOT rolsuper AND NOT rolbypassrls")).isEqualTo(1);
            List<UUID> claimed = claim(app);
            assertThat(claimed).contains(stoppingId).hasSize(4);
            assertThat(claimed).doesNotHaveDuplicates();
            app.commit();
        }
        try (Connection app = appConnection(UUID.randomUUID())) {
            assertThat(integer(app, "SELECT count(*) FROM task_execution WHERE project_id=?", fixture.projectId())).isZero();
            assertThat(integer(app, "SELECT count(*) FROM task_target WHERE project_id=?", fixture.projectId())).isZero();
            app.rollback();
        }
        Map<String, List<String>> afterClaim = facts(fixture.projectId());
        assertThat(flyway(STOPPING_VERSION).migrate().migrationsExecuted).isZero();
        assertThat(facts(fixture.projectId())).isEqualTo(afterClaim);
        try (Connection owner = ownerConnection()) {
            assertThat(integer(owner, "SELECT count(*) FROM flyway_schema_history WHERE version=? AND success", STOPPING_VERSION)).isEqualTo(1);
        }
    }

    /** 固定版本的原Flyway迁移，不使用复制schema或手工重建约束。 */
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(target).load();
    }

    /** 原CHECK先拒绝STOPPING，升级后仍拒绝非法状态；错误必须是23514而不是连接或权限失败。 */
    private void assertStateRejected(UUID executionId, String state) throws SQLException {
        try (Connection owner = ownerConnection()) {
            Throwable failure = catchThrowable(() -> execute(owner, "UPDATE task_execution SET status=? WHERE id=?", state, executionId));
            assertThat(failure).isInstanceOf(SQLException.class);
            assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514");
        }
    }

    /** 索引/函数保留公平租约和SKIP LOCKED，并禁止PUBLIC任意调用特权领取函数。 */
    private void assertCatalog() throws SQLException {
        try (Connection owner = ownerConnection()) {
            String predicate = text(owner, "SELECT pg_get_expr(indpred,indrelid) FROM pg_index WHERE indexrelid='task_execution_claim_idx'::regclass");
            assertThat(predicate).contains("STOPPING", "EXPANDING", "DISPATCHING", "RUNNING");
            String definition = text(owner, "SELECT pg_get_functiondef('claim_due_task_executions(integer)'::regprocedure)");
            assertThat(definition).contains("STOPPING", "row_number()", "SKIP LOCKED", "30 seconds");
            assertThat(text(owner, "SELECT prosecdef::text FROM pg_proc WHERE oid='claim_due_task_executions(integer)'::regprocedure")).isEqualTo("true");
            assertThat(text(owner, "SELECT has_function_privilege(?, 'claim_due_task_executions(integer)', 'EXECUTE')::text", APP_ROLE)).isEqualTo("true");
            assertThat(integer(owner, "SELECT count(*) FROM pg_proc p, LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.oid='claim_due_task_executions(integer)'::regprocedure AND a.grantee=0 AND a.privilege_type='EXECUTE'")).isZero();
            assertThat(text(owner, "SELECT proconfig::text FROM pg_proc WHERE oid='claim_due_task_executions(integer)'::regprocedure")).contains("search_path=pg_catalog, public");
        }
    }

    /** 旧库全部原状态带稳定时间、快照与计数；目标只是明确的持久种子，不声称命令真实发出。 */
    private Fixture seedLegacy() throws SQLException {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '停止升级租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '停止升级账号')", fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?, ?, '停止升级项目', 'task_stopping_upgrade')", fixture.projectId(), fixture.tenantId());
            execute(owner, "INSERT INTO task_job(id,tenant_id,project_id,name,status,target_type,command_key,input,created_by,created_at,updated_at) VALUES (?, ?, ?, '旧任务', 'ACTIVE', 'ALL_DEVICES', 'reboot', '{}'::jsonb, ?, ?, ?)", fixture.jobId(), fixture.tenantId(), fixture.projectId(), fixture.accountId(), Timestamp.from(FIXED_AT), Timestamp.from(FIXED_AT));
            execute(owner, "INSERT INTO task_schedule(id,tenant_id,project_id,job_id,schedule_type,run_at,timezone,next_run_at,created_at,updated_at) VALUES (?, ?, ?, ?, 'ONCE', ?, 'Asia/Shanghai', ?, ?, ?)", UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), fixture.jobId(), Timestamp.from(FIXED_AT), Timestamp.from(FIXED_AT), Timestamp.from(FIXED_AT), Timestamp.from(FIXED_AT));
            for (String state : LEGACY_STATES) insertExecution(owner, fixture, state.equals("EXPANDING") ? fixture.firstExecutionId() : UUID.randomUUID(), state);
            execute(owner, "INSERT INTO task_target(execution_id,tenant_id,project_id,device_id,status,created_at,updated_at) VALUES (?, ?, ?, ?, 'PENDING', ?, ?)", fixture.firstExecutionId(), fixture.tenantId(), fixture.projectId(), UUID.randomUUID(), Timestamp.from(FIXED_AT), Timestamp.from(FIXED_AT));
            execute(owner, "UPDATE task_execution SET total_targets=1 WHERE id=?", fixture.firstExecutionId());
            owner.commit();
        }
        return fixture;
    }

    /** 同一个参数化插入路径跨旧/新schema验证状态合法性，保留最后成功游标。 */
    private void insertExecution(Connection owner, Fixture fixture, UUID id, String state) throws SQLException {
        execute(owner, "INSERT INTO task_execution(id,tenant_id,project_id,job_id,target_type,command_key,input,requested_by,trigger_type,status,expansion_cursor,failure_summary,started_at,created_at,updated_at) VALUES (?, ?, ?, ?, 'ALL_DEVICES', 'reboot', '{}'::jsonb, ?, 'MANUAL', ?, 'legacy-cursor', '旧执行摘要', ?, ?, ?)", id, fixture.tenantId(), fixture.projectId(), fixture.jobId(), fixture.accountId(), state, Timestamp.from(FIXED_AT), Timestamp.from(FIXED_AT), Timestamp.from(FIXED_AT));
    }

    /** 真实APP连接执行特权领取函数，租约在提交后才成为后续工作依据。 */
    private List<UUID> claim(Connection app) throws SQLException {
        List<UUID> result = new ArrayList<>();
        try (PreparedStatement query = app.prepareStatement("SELECT execution_id FROM claim_due_task_executions(100)")) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) result.add(rows.getObject(1, UUID.class)); }
        }
        return result;
    }

    /** 所有旧/新业务行完整比较，数据库迁移历史本身不混入业务快照。 */
    private Map<String, List<String>> facts(UUID projectId) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection owner = ownerConnection()) {
            for (String table : TABLES) {
                List<String> rows = new ArrayList<>();
                try (PreparedStatement query = owner.prepareStatement("SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=? ORDER BY row_to_json(f)::text")) {
                    query.setQueryTimeout(5); query.setObject(1, projectId);
                    try (ResultSet values = query.executeQuery()) { while (values.next()) rows.add(values.getString(1)); }
                }
                result.put(table, List.copyOf(rows));
            }
        }
        return result;
    }

    /** 真实应用连接设置局部RLS轴，连接自身不是表owner或superuser。 */
    private Connection appConnection(UUID projectId) throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
        connection.setAutoCommit(false);
        text(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
        return connection;
    }

    /** 升级夹具和独立事实观察使用同一独占容器owner。 */
    private Connection ownerConnection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }

    /** 参数化辅助查询保持5秒界限；只返回非敏感目录值。 */
    private String text(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) query.setObject(index + 1, values[index]);
            try (ResultSet rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
        }
    }

    /** 计数查询用于ACL/迁移次数/RLS边界，不承担业务终态推断。 */
    private int integer(Connection connection, String sql, Object... values) throws SQLException { return Integer.parseInt(text(connection, sql, values)); }

    /** 所有写均绑定参数，测试不关闭任何原约束/权限守卫。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 已持久旧任务的完整外键身份；firstExecution用于比较旧CHECK和升级后目标保全。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID jobId, UUID firstExecutionId) { }
}
