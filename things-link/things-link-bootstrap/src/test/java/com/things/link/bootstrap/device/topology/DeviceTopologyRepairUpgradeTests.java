package com.things.link.bootstrap.device.topology;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ADR 0056 / D-026 的真实旧库升级验证：迁移失败、修复审计、RLS 与只增保留共享一条顺序工作流。
 * 独立容器先停在 20260901.1000，不能继承已在启动时迁到最新的公共集成测试基类。
 */
@Testcontainers
@DisplayName("D-026 旧库升级：软删拓扑修复与只增审计原子提交")
class DeviceTopologyRepairUpgradeTests {

    /** 与部署与公共集成夹具相同的 PostgreSQL / TimescaleDB 镜像，避免用替身验证真实锁和 RLS。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";

    /** 本工作流独占容器，Testcontainers 在方法完成后清理，避免旧库阶段影响其他测试。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("topology_repair_upgrade")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** 与 bootstrap/application.yml 一致的九个模块迁移目录。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support",
            "classpath:db/migration/project",
            "classpath:db/migration/issuer",
            "classpath:db/migration/device",
            "classpath:db/migration/telemetry",
            "classpath:db/migration/alarm",
            "classpath:db/migration/task",
            "classpath:db/migration/rule",
            "classpath:db/migration/iam",
            "classpath:db/migration/enduser",
            // verify-11：完整升级必须包含授权外键的看板父表，并与生产迁移域保持一致。
            "classpath:db/migration/export", "classpath:db/migration/dashboard", "classpath:db/migration/ota", "classpath:db/migration/integration"
    };

    /** D-026 修复迁移的全局前驱；含旧代码可能留下的有效悬挂关系。 */
    private static final String LEGACY_TARGET = "20260901.1000";
    /** 新迁移版本用于断言失败没有留下成功或失败历史行。 */
    private static final String REPAIR_VERSION = "20260903.0100";
    /** 与旧 RLS 迁移创建的应用角色一致，权限验证不能使用迁移 owner。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 仅独立测试容器的本地占位口令，与 Flyway 占位符保持一致。 */
    private static final String APP_PASSWORD = "thingslink";
    /** 旧绑定事实必须保留，不能被实际修复时间覆盖。 */
    private static final Instant BOUND_AT = Instant.parse("2026-08-20T01:00:00Z");
    /** 部分子设备曾在线，修复应保留该历史时间并降为 OFFLINE。 */
    private static final Instant LAST_ONLINE_AT = Instant.parse("2026-08-21T02:00:00Z");
    /** 网关删除时间与子设备删除时间故意不同，防止迁移误以任意一端推导解绑时刻。 */
    private static final Instant GATEWAY_DELETED_AT = Instant.parse("2026-08-22T03:00:00Z");
    /** 子设备原删除时间也是审计证据，实际修复不得重写。 */
    private static final Instant SUB_DELETED_AT = Instant.parse("2026-08-23T04:00:00Z");
    /** 已关闭的历史关系不属于修复集合。 */
    private static final Instant HISTORICAL_UNBOUND_AT = Instant.parse("2026-08-24T05:00:00Z");
    /** 可辨识的测试故障，确认失败发生在投影修复而非无关的建库错误。 */
    private static final String INJECTED_FAILURE = "D026_TEST_REPAIR_FAILURE";

    /**
     * 单次旧库升级按真实生命周期验证；阶段顺序由此方法确定，不依赖 JUnit 测试排序或多次重建容器。
     */
    @Test
    void upgradesLegacyTopologyAtomicallyAndRetainsImmutableRepairEvidence() throws Exception {
        legacyFlyway().migrate();
        List<RelationFixture> relations = seedLegacyRelations();
        RelationFixture target = relations.getFirst();
        String legacyState = businessSnapshot();
        List<String> cleanSchema = schemaObjects();

        // 分别验证首表锁失败与已获首表锁后的第二表失败；旧 worker 的行锁均来自真实应用身份。
        for (String table : List.of("dev_device", "dev_topo")) {
            try (Connection worker = appConnection()) {
                worker.setAutoCommit(false);
                try {
                    assertThat(stringValue(worker, "SELECT current_user")).isEqualTo(APP_ROLE);
                    setProject(worker, target.projectId());
                    try (PreparedStatement lock = worker.prepareStatement(
                            "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
                        lock.setQueryTimeout(3);
                        lock.setObject(1, table.equals("dev_device") ? target.subId() : target.topologyId());
                        try (ResultSet row = lock.executeQuery()) {
                            assertThat(row.next()).isTrue();
                        }
                    }
                    assertMigrationFailure("55P03", null);
                    assertMigrationRolledBack(legacyState, cleanSchema);
                    if (table.equals("dev_topo")) {
                        assertFirstTableLockReleased();
                    }
                } finally {
                    worker.rollback();
                }
            }
        }

        // 第二次在真正的 projection 写入处失败，证明先前 DDL、审计和关系修改全部由同一事务回滚。
        installProjectionFailure();
        List<String> faultSchema = schemaObjects();
        assertMigrationFailure("P0001", INJECTED_FAILURE);
        assertMigrationRolledBack(legacyState, faultSchema);
        removeProjectionFailure();

        Timestamp upgradeStarted = databaseTime();
        // 先验D026精确修复，再验证后续MQTT身份升级的显式离线转换。
        assertThat(repairFlyway().migrate().migrationsExecuted).isPositive();
        Timestamp upgradeFinished = databaseTime();
        assertRepairContents(relations, upgradeStarted, upgradeFinished);
        assertProjectReadIsolation(relations);
        assertAuditWriteProtection(target);

        String d026Audit = auditSnapshot();
        assertThat(latestFlyway().migrate().migrationsExecuted).isPositive();
        assertThat(auditSnapshot()).as("后续升级不得改写D026审计").isEqualTo(d026Audit);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo WHERE unbound_at IS NULL AND online_status='ONLINE'")).isZero();
        }
        String repairedState = businessSnapshot();
        String repairedAudit = auditSnapshot();
        assertThat(latestFlyway().migrate().migrationsExecuted).isZero();
        assertThat(businessSnapshot()).isEqualTo(repairedState);
        assertThat(auditSnapshot()).isEqualTo(repairedAudit);
        assertAuditSurvivesProjectDeletion(relations.getLast());
    }

    /** 迁移身份和占位符与生产一致，只将目标限制在存量状态。 */
    private Flyway legacyFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD))
                .target(LEGACY_TARGET).load();
    }

    /** 固定D026目标以隔离该修复与后续合法状态迁移的验收责任。 */
    private Flyway repairFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD))
                .target(REPAIR_VERSION).load();
    }

    /** 升级、失败重试与无变化重跑都使用同一实际迁移配置。 */
    private Flyway latestFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).load();
    }

    /** 旧库含只删网关、只删子设备、双删、正常有效、已有历史，以及同租户另一项目的修复对象。 */
    private List<RelationFixture> seedLegacyRelations() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        List<RelationFixture> relations = new ArrayList<>();
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO sys_tenant (id, name) VALUES (?, 'D026旧库租户')", tenantId);
                insertProject(connection, tenantId, projectA, "d026legacy01");
                insertProject(connection, tenantId, projectB, "d026legacy02");
                relations.add(insertRelation(connection, tenantId, projectA, "gateway_deleted", true, false, true, false));
                relations.add(insertRelation(connection, tenantId, projectA, "sub_deleted", false, true, true, false));
                relations.add(insertRelation(connection, tenantId, projectA, "both_deleted", true, true, false, false));
                relations.add(insertRelation(connection, tenantId, projectA, "healthy", false, false, true, false));
                relations.add(insertRelation(connection, tenantId, projectA, "historical", true, true, true, true));
                relations.add(insertRelation(connection, tenantId, projectB, "other_project", true, false, false, false));
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return relations;
    }

    /** 项目 key 明确合法且全局唯一，避免升级测试在无关的项目字段约束处失败。 */
    private void insertProject(Connection connection, UUID tenantId, UUID projectId, String key) throws SQLException {
        execute(connection, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, ?, ?)",
                projectId, tenantId, "D026旧库项目", key);
    }

    /**
     * 权威关系与 gateway_id 同事务种入，真实 DEFERRABLE 触发器一直启用；软删除本身在旧库不关闭关系。
     * 每个场景使用独立两端，避免一条子设备关系的修复掩盖另一场景。
     */
    private RelationFixture insertRelation(Connection connection, UUID tenantId, UUID projectId, String key,
                                            boolean gatewayDeleted, boolean subDeleted, boolean everOnline,
                                            boolean historicallyClosed) throws SQLException {
        UUID gatewayType = UUID.randomUUID();
        UUID subType = UUID.randomUUID();
        UUID gatewayId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID topologyId = UUID.randomUUID();
        UUID boundBy = UUID.randomUUID();
        UUID unboundBy = historicallyClosed ? UUID.randomUUID() : null;
        execute(connection, """
                INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                VALUES (?, ?, ?, ?, ?, 'GATEWAY', 'STANDARD_GATEWAY', 'ETHERNET')
                """, gatewayType, tenantId, projectId, key + "_gw_type", key);
        execute(connection, """
                INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                VALUES (?, ?, ?, ?, ?, 'SUB_DEVICE', 'STANDARD', 'ZIGBEE')
                """, subType, tenantId, projectId, key + "_sub_type", key);
        execute(connection, """
                INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status,
                                        last_online_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ONLINE', ?, ?)
                """, gatewayId, tenantId, projectId, gatewayType, key + "_gw", key,
                Timestamp.from(LAST_ONLINE_AT), gatewayDeleted ? Timestamp.from(GATEWAY_DELETED_AT) : null);
        execute(connection, """
                INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, gateway_id, device_key, name,
                                        status, last_online_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, subId, tenantId, projectId, subType, historicallyClosed ? null : gatewayId,
                key + "_sub", key, everOnline ? "ONLINE" : "INACTIVE",
                everOnline ? Timestamp.from(LAST_ONLINE_AT) : null,
                subDeleted ? Timestamp.from(SUB_DELETED_AT) : null);
        execute(connection, """
                INSERT INTO dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source,
                                      online_status, last_online_at, status_changed_at, bound_by, bound_at,
                                      unbound_by, unbound_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE', ?, ?, ?, ?, ?, ?, ?, 7, ?)
                """, topologyId, tenantId, projectId, gatewayId, subId, everOnline ? "ONLINE" : "UNKNOWN",
                everOnline ? Timestamp.from(LAST_ONLINE_AT) : null,
                everOnline ? Timestamp.from(LAST_ONLINE_AT) : null, boundBy, Timestamp.from(BOUND_AT),
                unboundBy, historicallyClosed ? Timestamp.from(HISTORICAL_UNBOUND_AT) : null, Timestamp.from(BOUND_AT));
        String beforeState = expectedBeforeState(connection, topologyId);
        return new RelationFixture(topologyId, tenantId, projectId, gatewayId, subId,
                !historicallyClosed && (gatewayDeleted || subDeleted), everOnline, beforeState);
    }

    /** 以完整原关系和合同规定的设备字段保存期望值；NULL 键必须显式存在，不能被序列化器省略。 */
    private String expectedBeforeState(Connection connection, UUID topologyId) throws SQLException {
        return stringValue(connection, """
                SELECT jsonb_build_object(
                    'topology', to_jsonb(t),
                    'gateway_device', jsonb_build_object('id', g.id, 'deleted_at', g.deleted_at,
                        'gateway_id', g.gateway_id, 'status', g.status, 'last_online_at', g.last_online_at),
                    'sub_device', jsonb_build_object('id', s.id, 'deleted_at', s.deleted_at,
                        'gateway_id', s.gateway_id, 'status', s.status, 'last_online_at', s.last_online_at))::text
                  FROM dev_topo t JOIN dev_device g ON g.id = t.gateway_device_id
                                  JOIN dev_device s ON s.id = t.sub_device_id WHERE t.id = ?
                """, topologyId);
    }

    /** 按 cause 链核对真正 SQLSTATE，避免把容器、认证或其他迁移失败当作预期锁/注入故障。 */
    private void assertMigrationFailure(String sqlState, String marker) {
        Throwable error = catchThrowable(() -> latestFlyway().migrate());
        assertThat(error).isNotNull();
        boolean matched = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlState.equals(sqlException.getSQLState())) {
                matched = true;
            }
        }
        assertThat(matched).as("迁移异常链必须含 SQLSTATE %s", sqlState).isTrue();
        if (marker != null) {
            assertThat(error).hasStackTraceContaining(marker);
        }
    }

    /** 数据、公开 schema 对象与 Flyway 记录均回到失败前，失败不允许留一张空审计表冒充原子性。 */
    private void assertMigrationRolledBack(String expectedData, List<String> expectedSchema) throws SQLException {
        assertThat(businessSnapshot()).isEqualTo(expectedData);
        assertThat(schemaObjects()).isEqualTo(expectedSchema);
        try (Connection connection = ownerConnection()) {
            assertThat(stringValue(connection, "SELECT to_regclass('public.dev_topo_repair_audit')::text")).isNull();
            assertThat(integerValue(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = ?",
                    REPAIR_VERSION)).isZero();
        }
    }

    /** 第二张表锁失败后，另一连接能 NOWAIT 获得首张表锁，证明迁移事务没有残留已取得的锁。 */
    private void assertFirstTableLockReleased() throws SQLException {
        try (Connection probe = ownerConnection()) {
            probe.setAutoCommit(false);
            try (Statement lock = probe.createStatement()) {
                lock.setQueryTimeout(3);
                lock.execute("LOCK TABLE dev_device IN EXCLUSIVE MODE NOWAIT");
            } finally {
                probe.rollback();
            }
        }
    }

    /** 故障在实际清投影时抛出，此时前序 DDL/审计/关系写入必须一并回滚。 */
    private void installProjectionFailure() throws SQLException {
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION d026_test_fail_projection() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF OLD.gateway_id IS NOT NULL AND NEW.gateway_id IS NULL THEN
                            RAISE EXCEPTION 'D026_TEST_REPAIR_FAILURE' USING ERRCODE = 'P0001';
                        END IF;
                        RETURN NEW;
                    END;
                    $$
                    """);
            statement.execute("""
                    CREATE TRIGGER d026_test_fail_projection_trg
                    BEFORE UPDATE OF gateway_id ON dev_device
                    FOR EACH ROW EXECUTE FUNCTION d026_test_fail_projection()
                    """);
        }
    }

    /** 故障仅属于测试环境，正常升级前显式移除函数与触发器。 */
    private void removeProjectionFailure() throws SQLException {
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER d026_test_fail_projection_trg ON dev_device");
            statement.execute("DROP FUNCTION d026_test_fail_projection()");
        }
    }

    /** 修复集完整且仅限悬挂有效关系，时间为实际事务时间，绑定历史与 NULL 证据完整保留。 */
    private void assertRepairContents(List<RelationFixture> relations, Timestamp started, Timestamp finished)
            throws SQLException {
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit"))
                    .isEqualTo((int) relations.stream().filter(RelationFixture::needsRepair).count());
            assertThat(integerValue(connection, "SELECT count(DISTINCT repaired_at) FROM dev_topo_repair_audit"))
                    .as("同一实际修复事务时刻").isEqualTo(1);
            for (RelationFixture fixture : relations) {
                if (!fixture.needsRepair()) {
                    assertThat(expectedBeforeState(connection, fixture.topologyId())).isEqualTo(fixture.beforeState());
                    assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit WHERE topology_id = ?",
                            fixture.topologyId())).isZero();
                    continue;
                }
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT a.tenant_id, a.project_id, a.repair_source, a.repaired_at,
                               a.before_state = ?::jsonb AS exact_before_state,
                               to_jsonb(t) - 'unbound_at' - 'unbound_by'
                                   = (a.before_state -> 'topology') - 'unbound_at' - 'unbound_by' AS history_unchanged,
                               t.unbound_at, t.unbound_by, s.gateway_id, s.status, s.last_online_at,
                               to_jsonb(g) -> 'deleted_at' = a.before_state -> 'gateway_device' -> 'deleted_at'
                                   AS gateway_delete_preserved,
                               to_jsonb(s) -> 'deleted_at' = a.before_state -> 'sub_device' -> 'deleted_at'
                                   AS sub_delete_preserved
                          FROM dev_topo_repair_audit a JOIN dev_topo t ON t.id = a.topology_id
                          JOIN dev_device g ON g.id = t.gateway_device_id
                          JOIN dev_device s ON s.id = t.sub_device_id WHERE a.topology_id = ?
                        """)) {
                    query.setString(1, fixture.beforeState());
                    query.setObject(2, fixture.topologyId());
                    try (ResultSet row = query.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getObject("tenant_id")).isEqualTo(fixture.tenantId());
                        assertThat(row.getObject("project_id")).isEqualTo(fixture.projectId());
                        assertThat(row.getString("repair_source")).isEqualTo("D-026");
                        assertThat(row.getBoolean("exact_before_state")).isTrue();
                        assertThat(row.getBoolean("history_unchanged")).isTrue();
                        assertThat(row.getBoolean("gateway_delete_preserved")).isTrue();
                        assertThat(row.getBoolean("sub_delete_preserved")).isTrue();
                        assertThat(row.getTimestamp("repaired_at")).isBetween(started, finished);
                        assertThat(row.getTimestamp("unbound_at")).isEqualTo(row.getTimestamp("repaired_at"));
                        assertThat(row.getObject("unbound_by")).isNull();
                        assertThat(row.getObject("gateway_id")).isNull();
                        assertThat(row.getString("status")).isEqualTo(fixture.everOnline() ? "OFFLINE" : "INACTIVE");
                        assertThat(row.getTimestamp("last_online_at"))
                                .isEqualTo(fixture.everOnline() ? Timestamp.from(LAST_ONLINE_AT) : null);
                    }
                }
            }
        }
    }

    /** 同租户的两个项目仍相互不可见；无项目范围 fail-closed，明确证明访问轴是 project_id。 */
    private void assertProjectReadIsolation(List<RelationFixture> relations) throws SQLException {
        UUID projectA = relations.getFirst().projectId();
        UUID projectB = relations.getLast().projectId();
        try (Connection connection = appConnection()) {
            assertThat(stringValue(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit")).isZero();
            setProject(connection, projectA);
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit")).isEqualTo(3);
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit WHERE project_id = ?",
                    projectB)).isZero();
            setProject(connection, projectB);
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit")).isEqualTo(1);
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit WHERE project_id = ?",
                    projectA)).isZero();
        }
    }

    /** 应用无写权限；即使迁移 owner 的误操作也被只增触发器阻止，TRUNCATE 不能绕过行级规则。 */
    private void assertAuditWriteProtection(RelationFixture fixture) throws SQLException {
        String before = auditSnapshot();
        try (Connection application = appConnection()) {
            setProject(application, fixture.projectId());
            assertSqlRejected(application, "42501", """
                    INSERT INTO dev_topo_repair_audit
                        (topology_id, tenant_id, project_id, repair_source, repaired_at, before_state)
                    SELECT ?, tenant_id, project_id, repair_source, repaired_at, before_state
                      FROM dev_topo_repair_audit WHERE topology_id = ?
                    """, UUID.randomUUID(), fixture.topologyId());
            assertSqlRejected(application, "42501", "UPDATE dev_topo_repair_audit SET repaired_at = now() WHERE topology_id = ?",
                    fixture.topologyId());
            assertSqlRejected(application, "42501", "DELETE FROM dev_topo_repair_audit WHERE topology_id = ?", fixture.topologyId());
            assertSqlRejected(application, "42501", "TRUNCATE TABLE dev_topo_repair_audit");
        }
        try (Connection owner = ownerConnection()) {
            assertSqlRejected(owner, "23514", "UPDATE dev_topo_repair_audit SET repaired_at = now() WHERE topology_id = ?",
                    fixture.topologyId());
            assertSqlRejected(owner, "23514", "DELETE FROM dev_topo_repair_audit WHERE topology_id = ?", fixture.topologyId());
            assertSqlRejected(owner, "23514", "TRUNCATE TABLE dev_topo_repair_audit");
            assertThat(integerValue(owner, """
                    SELECT count(*) FROM pg_constraint
                     WHERE conrelid = 'dev_topo_repair_audit'::regclass AND contype = 'f'
                    """)).as("审计不得依赖业务表外键").isZero();
        }
        assertThat(auditSnapshot()).isEqualTo(before);
    }

    /** 独立项目硬删会清理其设备与拓扑，但不能级联删除已修复审计；其他场景留供诊断。 */
    private void assertAuditSurvivesProjectDeletion(RelationFixture fixture) throws SQLException {
        String before = auditSnapshot();
        try (Connection connection = ownerConnection()) {
            execute(connection, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
            assertThat(integerValue(connection, "SELECT count(*) FROM sys_project WHERE id = ?", fixture.projectId())).isZero();
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_device WHERE project_id = ?", fixture.projectId())).isZero();
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo WHERE project_id = ?", fixture.projectId())).isZero();
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit WHERE topology_id = ?",
                    fixture.topologyId())).isEqualTo(1);
        }
        assertThat(auditSnapshot()).isEqualTo(before);
    }

    /** 每次使用独立 autocommit 语句，拒绝后连接仍可继续验证下一种误写，避免事务已中止造成假阳性。 */
    private void assertSqlRejected(Connection connection, String expectedSqlState, String sql, Object... values) {
        assertThatThrownBy(() -> execute(connection, sql, values))
                .isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo(expectedSqlState));
    }

    /** 只设置项目 RLS 轴；应用角色不需要 owner 权限或伪造租户来读取自身修复事实。 */
    private void setProject(Connection connection, UUID projectId) throws SQLException {
        stringValue(connection, "SELECT set_config('app.project_id', ?, false)", projectId.toString());
    }

    /** 全量业务快照同时含拓扑和双方设备，能够发现失败后只回滚了一半投影的错误。 */
    private String businessSnapshot() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return stringValue(connection, """
                    SELECT jsonb_build_object(
                        'topology', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM dev_topo t),
                        'device', (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM dev_device d))::text
                    """);
        }
    }

    /** 审计全字段快照用于拒绝写入和二次迁移之后的精确不变检查。 */
    private String auditSnapshot() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return stringValue(connection,
                    "SELECT jsonb_agg(to_jsonb(a) ORDER BY topology_id)::text FROM dev_topo_repair_audit a");
        }
    }

    /** 公共 schema 中的表、索引、例程和触发器清单；回滚后连孤立的新函数也不能残留。 */
    private List<String> schemaObjects() throws SQLException {
        try (Connection connection = ownerConnection(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("""
                     SELECT name FROM (
                         SELECT 'relation:' || c.relkind::text || ':' || c.relname AS name
                           FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                         UNION ALL
                         SELECT 'routine:' || p.proname || ':' || pg_get_function_identity_arguments(p.oid)
                           FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
                         UNION ALL
                         SELECT 'trigger:' || c.relname || ':' || t.tgname
                           FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                           JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                     ) objects ORDER BY name
                     """)) {
            List<String> names = new ArrayList<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return names;
        }
    }

    /** 用同一数据库时钟圈定升级时段，避免宿主机与容器时钟差造成时间断言噪声。 */
    private Timestamp databaseTime() throws SQLException {
        try (Connection connection = ownerConnection(); Statement query = connection.createStatement();
             ResultSet row = query.executeQuery("SELECT clock_timestamp()")) {
            assertThat(row.next()).isTrue();
            return row.getTimestamp(1);
        }
    }

    /** 所有 DDL、夹具和 owner 验证都连接本类容器，不能接触共享 deploy 数据库。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 原生受 RLS 约束的应用连接，关闭时连同 session 范围一起销毁。 */
    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    /** 参数化写语句避免测试 UUID/文本拼接误伤其他行，语句资源始终随调用关闭。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            statement.execute();
        }
    }

    /** 单值文本读取保留 SQL NULL，供 JSON 和目录对象的精确检查。 */
    private String stringValue(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            bindValues(query, values);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 计数查询统一读取，避免 SQL NULL 被误作零行。 */
    private int integerValue(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            bindValues(query, values);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                int result = row.getInt(1);
                assertThat(row.wasNull()).isFalse();
                return result;
            }
        }
    }

    /** 所有可变值由 JDBC 绑定，NULL 也以数据库参数语义保留。 */
    private void bindValues(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    /**
     * 一条旧关系的可验证身份与原始证据。
     * @param topologyId 原关系主键
     * @param tenantId 原租户归属
     * @param projectId 项目隔离范围
     * @param gatewayId 网关身份
     * @param subId 子设备身份
     * @param needsRepair 是否属于 ADR 0056 的有效悬挂关系集合
     * @param everOnline 是否具有最后在线历史
     * @param beforeState 原关系与两端设备的完整合同快照
     */
    private record RelationFixture(UUID topologyId, UUID tenantId, UUID projectId, UUID gatewayId, UUID subId,
                                   boolean needsRepair, boolean everOnline, String beforeState) { }
}
