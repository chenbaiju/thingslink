package com.things.link.bootstrap.device.topology;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 * ADR0060 / D-115：从0400旧库的端点覆盖和设备INSERT两条真实入口制造漂移，再验证0500拒绝与明确恢复。
 * 已被覆盖的端点不是可推断历史；本测试保留现有G→B身份，仅按当前权威清理无关系设备投影。
 */
@Testcontainers
@DisplayName("D-115 旧库升级：身份固定与设备INSERT延期投影覆盖")
class DeviceTopologyProjectionCoverageUpgradeTests {

    /** 与现有部署相同的PG17版本，验证真实DDL锁、延期触发器与目录身份。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";
    /** 历史迁移创建集群级角色，必须独占实例以避免共享Spring上下文或其他旧库污染。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_projection_coverage_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 完整使用bootstrap的九个生产迁移目录，不重写表结构或略过前片守卫。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser"
    };
    /** 0400已限定public和安全搜索路径，仍未覆盖端点更换与设备INSERT。 */
    private static final String LEGACY_TARGET = "20260903.0400";
    /** 精确固定本片目标，后续迁移不能掩盖0500自身行为。 */
    private static final String GUARD_VERSION = "20260903.0500";
    /** 可达违例、持锁、显式恢复与新运行时验证都必须使用真实APP。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 仅用于独占测试容器的迁移占位口令。 */
    private static final String APP_PASSWORD = "thingslink";
    /** 投影拒绝延续0400的结构化约束名。 */
    private static final String PROJECTION_CONSTRAINT = "dev_topo_projection_guard";
    /** 五个身份字段由即时守卫拒绝，不将其他23514当作身份覆盖证据。 */
    private static final String IDENTITY_CONSTRAINT = "dev_topo_identity_guard";
    /** INSERT复用原函数，因此运行时中文错误保持原合同。 */
    private static final String RUNTIME_MESSAGE = "设备网关投影与 dev_topo 有效绑定不一致";
    /** 关系历史具有显式固定值，恢复和升级均不得重写。 */
    private static final Instant BOUND_AT = Instant.parse("2026-08-25T02:00:00Z");
    /** 软删设备不在既有投影helper范围，必须保留而不能自动清理。 */
    private static final Instant DELETED_AT = Instant.parse("2026-08-26T03:00:00Z");
    /** 解析真实错误DETAIL与系统目录，避免用UUID子串代替字段语义。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 同一顺序工作流证明旧状态可提交、失败不改事实、显式恢复后升级及新拒绝，不依赖测试执行顺序。 */
    @Test
    void upgradesAfterRestoringOnlyProjectionFactsWithoutInventingOriginalTopologyHistory() throws Exception {
        legacyFlyway().migrate();
        Fixture fixture = seedFixture();
        String originalState = businessSnapshot();
        JsonNode originalFunctions = functionState();
        JsonNode originalTriggers = triggerState();
        createReachableLegacyDrift(fixture);
        assertCommittedDrift(fixture);
        String driftedState = businessSnapshot();
        assertThat(driftedState).isNotEqualTo(originalState);
        String retainedIdentityState = nonProjectionSnapshot();
        List<String> originalSchema = schemaObjects();

        assertTableLockFailures(fixture, driftedState, originalSchema);
        assertMigrationFailure(repeatableReadFlyway(), "0A000", "D115_PREFLIGHT_REQUIRES_READ_COMMITTED");
        assertMigrationLeftNoChanges(driftedState, originalSchema);
        PSQLException refusal = assertMigrationFailure(latestFlyway(), "23514", "D115_DEVICE_PROJECTION_INVALID");
        assertThat(refusal.getServerErrorMessage().getConstraint()).isEqualTo(PROJECTION_CONSTRAINT);
        assertDiagnosticIdentities(refusal, fixture);
        assertMigrationLeftNoChanges(driftedState, originalSchema);

        restoreCurrentAuthorityProjections(fixture);
        assertThat(nonProjectionSnapshot()).as("不能推回旧端点，也不能改关系历史或其他设备字段").isEqualTo(retainedIdentityState);
        assertRepairedFacts(fixture);
        String repairedState = businessSnapshot();
        assertThat(repairedState).as("原位变更已发生，恢复投影不是恢复一段没有审计证据的历史").isNotEqualTo(originalState);
        assertThat(latestFlyway().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(businessSnapshot()).isEqualTo(repairedState);
        assertOriginalObjectsAndNewTriggerContracts(originalFunctions, originalTriggers);
        assertEndpointUpdateNowFailsImmediately(fixture);
        assertOrphanInsertNowFailsOnlyAtCommit(fixture);
        assertThat(businessSnapshot()).isEqualTo(repairedState);
        assertLegitimateDeviceThenTopologyInsert(fixture);

        String finalState = businessSnapshot();
        List<String> finalSchema = schemaObjects();
        assertThat(latestFlyway().migrate().migrationsExecuted).isZero();
        assertThat(businessSnapshot()).isEqualTo(finalState);
        assertThat(schemaObjects()).isEqualTo(finalSchema);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection,
                    "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success", GUARD_VERSION)).isEqualTo(1);
        }
    }

    /** 旧库停在0400，所有真实违例在未上线新守卫的合法历史版本上形成。 */
    private Flyway legacyFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(LEGACY_TARGET).load();
    }

    /** 升级及重试只执行本片目标，不repair历史记录或替换前片迁移。 */
    private Flyway latestFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(GUARD_VERSION).load();
    }

    /** 迁移连接显式采用RR，验证固定快照前置拒绝，不臆造运行时隔离策略。 */
    private Flyway repeatableReadFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(GUARD_VERSION)
                .initSql("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL REPEATABLE READ").load();
    }

    /** NOWAIT退化阻塞时以有限57014失败，不能用lock_timeout产生55P03使缺失NOWAIT仍然假绿。 */
    private String migrationUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "options=-c%20statement_timeout%3D15000";
    }

    /** 旧库起始为G→A、独立健康关系及未绑定B；全部草稿类型避免物模型版本约束干扰投影合同。 */
    private Fixture seedFixture() throws SQLException {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID gatewayType = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO sys_tenant (id, name) VALUES (?, 'D115旧库租户')", fixture.tenantId());
                execute(connection, """
                        INSERT INTO sys_project (id, tenant_id, name, project_key)
                        VALUES (?, ?, 'D115旧库项目', 'd115legacy01')
                        """, fixture.projectId(), fixture.tenantId());
                insertType(connection, fixture, gatewayType, "gateway", "GATEWAY", "STANDARD_GATEWAY");
                insertType(connection, fixture, fixture.subTypeId(), "sub", "SUB_DEVICE", "STANDARD");
                insertDevice(connection, fixture, fixture.gatewayId(), gatewayType, "gateway", null, false);
                insertDevice(connection, fixture, fixture.oldSubId(), fixture.subTypeId(), "old_sub", fixture.gatewayId(), false);
                insertDevice(connection, fixture, fixture.newSubId(), fixture.subTypeId(), "new_sub", null, false);
                insertDevice(connection, fixture, fixture.healthySubId(), fixture.subTypeId(), "healthy_sub", fixture.gatewayId(), false);
                insertDevice(connection, fixture, fixture.deletedId(), fixture.subTypeId(), "deleted", fixture.gatewayId(), true);
                insertTopology(connection, fixture, fixture.topologyId(), fixture.oldSubId());
                insertTopology(connection, fixture, fixture.healthyTopologyId(), fixture.healthySubId());
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
        return fixture;
    }

    /** 真实分类与协议满足既有D111守卫，不能由无关非法类型抢先拒绝夹具。 */
    private void insertType(Connection connection, Fixture fixture, UUID typeId, String key, String kind, String protocol)
            throws SQLException {
        execute(connection, """
                INSERT INTO public.dev_type (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                VALUES (?, ?, ?, ?, '投影覆盖升级类型', ?, ?, 'ETHERNET')
                """, typeId, fixture.tenantId(), fixture.projectId(), key, kind, protocol);
    }

    /** gateway非空的INSERT故意保留到提交阶段；软删范围外夹具沿用原helper范围。 */
    private void insertDevice(Connection connection, Fixture fixture, UUID deviceId, UUID typeId, String key,
                              UUID gatewayId, boolean deleted) throws SQLException {
        assertThat(execute(connection, """
                INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, device_key, name, gateway_id, deleted_at)
                VALUES (?, ?, ?, ?, ?, '投影覆盖升级设备', ?, ?)
                """, deviceId, fixture.tenantId(), fixture.projectId(), typeId, key, gatewayId,
                deleted ? Timestamp.from(DELETED_AT) : null)).isEqualTo(1);
    }

    /** 固定历史字段且保持合法两端分类，原投影约束始终开启。 */
    private void insertTopology(Connection connection, Fixture fixture, UUID topologyId, UUID subId) throws SQLException {
        assertThat(execute(connection, """
                INSERT INTO public.dev_topo
                    (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source, online_status,
                     bound_by, bound_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE', 'UNKNOWN', ?, ?, 1, ?)
                """, topologyId, fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), subId,
                fixture.actorId(), Timestamp.from(BOUND_AT), Timestamp.from(BOUND_AT))).isEqualTo(1);
    }

    /** 两条独立APP事务真实提交：仅同步新端点B遗漏A，以及INSERT带投影却不创建关系。 */
    private void createReachableLegacyDrift(Fixture fixture) throws SQLException {
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                assertThat(execute(application, "UPDATE public.dev_topo SET sub_device_id = ? WHERE id = ?",
                        fixture.newSubId(), fixture.topologyId())).isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = ? WHERE id = ?",
                        fixture.gatewayId(), fixture.newSubId())).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                insertDevice(application, fixture, fixture.orphanId(), fixture.subTypeId(), "orphan", fixture.gatewayId(), false);
                application.commit();
            } finally {
                application.rollback();
            }
        }
    }

    /** 独立连接确认两个持久坏态均无当前权威，而现在真实关系属于B；不能把旧A端点当成诊断事实。 */
    private void assertCommittedDrift(Fixture fixture) throws SQLException {
        try (Connection connection = ownerConnection()) {
            for (UUID deviceId : List.of(fixture.oldSubId(), fixture.orphanId())) {
                assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", deviceId))
                        .isEqualTo(fixture.gatewayId().toString());
                assertThat(integerValue(connection,
                        "SELECT count(*) FROM public.dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL", deviceId)).isZero();
            }
            assertThat(stringValue(connection, "SELECT sub_device_id::text FROM public.dev_topo WHERE id = ?", fixture.topologyId()))
                    .isEqualTo(fixture.newSubId().toString());
            assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.newSubId()))
                    .isEqualTo(fixture.gatewayId().toString());
        }
    }

    /** 两处应用行锁分别使迁移NOWAIT拒绝，第二处失败还要证明已取得的设备表锁被释放。 */
    private void assertTableLockFailures(Fixture fixture, String before, List<String> schema) throws SQLException {
        List<String> tables = List.of("dev_device", "dev_topo");
        List<UUID> ids = List.of(fixture.oldSubId(), fixture.topologyId());
        for (int index = 0; index < tables.size(); index++) {
            try (Connection holder = appConnection()) {
                holder.setAutoCommit(false);
                try {
                    setProject(holder, fixture.projectId());
                    try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM public." + tables.get(index) + " WHERE id = ? FOR UPDATE")) {
                        lock.setQueryTimeout(3);
                        lock.setObject(1, ids.get(index));
                        try (ResultSet row = lock.executeQuery()) {
                            assertThat(row.next()).isTrue();
                            assertThat(row.getObject(1)).isEqualTo(ids.get(index));
                        }
                    }
                    assertMigrationFailure(latestFlyway(), "55P03", null);
                    assertMigrationLeftNoChanges(before, schema);
                    if (index == 1) {
                        try (Connection probe = ownerConnection()) {
                            probe.setAutoCommit(false);
                            try (Statement lock = probe.createStatement()) {
                                lock.setQueryTimeout(3);
                                lock.execute("LOCK TABLE public.dev_device IN EXCLUSIVE MODE NOWAIT");
                            } finally {
                                probe.rollback();
                            }
                        }
                    }
                } finally {
                    holder.rollback();
                }
            }
        }
    }

    /** 真实PG状态和目标消息都必须命中；不将超时、事务已中止或无关约束错误计为预期拒绝。 */
    private PSQLException assertMigrationFailure(Flyway flyway, String sqlState, String marker) {
        Throwable failure = catchThrowable(flyway::migrate);
        assertThat(failure).isNotNull();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sqlException && sqlState.equals(sqlException.getSQLState())) {
                if (marker != null) {
                    assertThat(sqlException.getServerErrorMessage()).isNotNull();
                    assertThat(sqlException.getServerErrorMessage().getMessage()).isEqualTo(marker);
                }
                return sqlException;
            }
        }
        throw new AssertionError("迁移异常链没有预期SQLSTATE " + sqlState, failure);
    }

    /** 两条坏态均报告真实NULL权威；当前G→B、健康关系与软删设备不能进入样本。 */
    private void assertDiagnosticIdentities(PSQLException exception, Fixture fixture) {
        JsonNode diagnostic = JSON.readTree(exception.getServerErrorMessage().getDetail());
        JsonNode sample = diagnostic.path("sample");
        assertThat(diagnostic.path("count").asInt()).isEqualTo(2);
        assertThat(sample.isArray()).isTrue();
        assertThat(sample.size()).isEqualTo(2);
        for (UUID deviceId : List.of(fixture.oldSubId(), fixture.orphanId())) {
            JsonNode actual = null;
            for (JsonNode item : sample) {
                if (deviceId.toString().equals(item.path("device_id").asString())) {
                    assertThat(actual).as("同一设备不能重复报告").isNull();
                    actual = item;
                }
            }
            assertThat(actual).as("必须报告实际设备 %s", deviceId).isNotNull();
            assertUuid(actual, "project_id", fixture.projectId());
            assertUuid(actual, "tenant_id", fixture.tenantId());
            assertUuid(actual, "projected_gateway_id", fixture.gatewayId());
            assertUuid(actual, "topology_id", null);
            assertUuid(actual, "authoritative_gateway_id", null);
        }
    }

    /** NULL必须显式出现，缺字段不等于诊断给出了空事实。 */
    private void assertUuid(JsonNode object, String field, UUID expected) {
        assertThat(object.has(field)).as("诊断字段 %s", field).isTrue();
        if (expected == null) assertThat(object.get(field).isNull()).isTrue();
        else assertThat(object.get(field).asString()).isEqualTo(expected.toString());
    }

    /** 本片只有DDL前拒绝证据，不声称已创建部分新守卫后发生DDL回滚。 */
    private void assertMigrationLeftNoChanges(String before, List<String> schema) throws SQLException {
        assertThat(businessSnapshot()).isEqualTo(before);
        assertThat(schemaObjects()).isEqualTo(schema);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = ?", GUARD_VERSION)).isZero();
        }
    }

    /** 以当前权威为依据只清A和孤立设备投影；不把已被覆盖的关系端点推回，也不改关系历史。 */
    private void restoreCurrentAuthorityProjections(Fixture fixture) throws SQLException {
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                for (UUID deviceId : List.of(fixture.oldSubId(), fixture.orphanId())) {
                    assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL WHERE id = ?", deviceId)).isEqualTo(1);
                }
                application.commit();
            } finally {
                application.rollback();
            }
        }
    }

    /** 明确恢复后的存活设备符合当前权威，B身份和软删范围外原投影仍保持。 */
    private void assertRepairedFacts(Fixture fixture) throws SQLException {
        try (Connection connection = ownerConnection()) {
            for (UUID deviceId : List.of(fixture.oldSubId(), fixture.orphanId())) {
                assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", deviceId)).isNull();
            }
            assertThat(stringValue(connection, "SELECT sub_device_id::text FROM public.dev_topo WHERE id = ?", fixture.topologyId()))
                    .isEqualTo(fixture.newSubId().toString());
            assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ? AND deleted_at IS NOT NULL",
                    fixture.deletedId())).isEqualTo(fixture.gatewayId().toString());
            assertThat(integerValue(connection, """
                    SELECT count(*) FROM public.dev_device d
                      LEFT JOIN public.dev_topo t ON t.sub_device_id = d.id AND t.unbound_at IS NULL
                     WHERE d.deleted_at IS NULL AND d.gateway_id IS DISTINCT FROM t.gateway_device_id
                    """)).isZero();
        }
    }

    /** 原三函数和原两触发器完全保持；新延期事件同时覆盖INSERT与后续改id，不能仅检查旧ID而漏掉最终行。 */
    private void assertOriginalObjectsAndNewTriggerContracts(JsonNode functions, JsonNode triggers) throws SQLException {
        assertThat(functions).hasSize(3);
        assertThat(triggers).hasSize(2);
        assertThat(functionState()).isEqualTo(functions);
        assertThat(triggerState()).isEqualTo(triggers);
        try (Connection owner = ownerConnection(); Connection application = appConnection()) {
            assertThat(integerValue(owner, """
                    SELECT count(*) FROM pg_trigger t
                     WHERE t.tgrelid = 'public.dev_device'::regclass
                       AND t.tgname = 'dev_device_gateway_projection_insert_id_trg'
                       AND t.tgfoid = 'public.dev_device_validate_projection()'::regprocedure
                       AND t.tgdeferrable AND t.tginitdeferred AND t.tgenabled = 'O'
                    """)).isEqualTo(1);
            assertThat(stringValue(owner, """
                    SELECT pg_get_triggerdef(oid) FROM pg_trigger
                     WHERE tgrelid = 'public.dev_device'::regclass AND tgname = 'dev_device_gateway_projection_insert_id_trg'
                    """)).contains("AFTER INSERT OR UPDATE OF id", "DEFERRABLE INITIALLY DEFERRED", "FOR EACH ROW");
            assertThat(integerValue(owner, """
                    SELECT count(*) FROM pg_trigger t
                     WHERE t.tgrelid = 'public.dev_topo'::regclass AND t.tgname = 'dev_topo_identity_guard_trg'
                       AND t.tgfoid = 'public.dev_topo_guard_identity()'::regprocedure
                       AND NOT t.tgdeferrable AND NOT t.tginitdeferred AND t.tgenabled = 'O'
                    """)).isEqualTo(1);
            assertThat(stringValue(owner, """
                    SELECT pg_get_triggerdef(oid) FROM pg_trigger
                     WHERE tgrelid = 'public.dev_topo'::regclass AND tgname = 'dev_topo_identity_guard_trg'
                    """)).contains("BEFORE UPDATE OF id, tenant_id, project_id, gateway_device_id, sub_device_id");
            assertThat(stringValue(owner, """
                    SELECT prosecdef::text FROM pg_proc WHERE oid = 'public.dev_topo_guard_identity()'::regprocedure
                    """)).isEqualTo("false");
            assertThat(stringValue(application, """
                    SELECT has_function_privilege(current_user, 'public.dev_topo_guard_identity()', 'EXECUTE')::text
                    """)).isEqualTo("false");
            assertThat(integerValue(owner, """
                    SELECT count(*) FROM pg_proc p,
                        LATERAL aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) a
                     WHERE p.oid = 'public.dev_topo_guard_identity()'::regprocedure
                       AND a.grantee = 0 AND a.privilege_type = 'EXECUTE'
                    """)).isZero();
        }
    }

    /** 新身份守卫在UPDATE语句本身拒绝合法分类的端点变化，整笔事务连先写的设备名称都必须回滚。 */
    private void assertEndpointUpdateNowFailsImmediately(Fixture fixture) throws SQLException {
        String before = businessSnapshot();
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                assertThat(execute(application, "UPDATE public.dev_device SET name = '必须回滚' WHERE id = ?", fixture.newSubId())).isEqualTo(1);
                assertThatThrownBy(() -> execute(application, "UPDATE public.dev_topo SET sub_device_id = ? WHERE id = ?",
                        fixture.oldSubId(), fixture.topologyId())).isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage()).isNotNull();
                    assertThat(error.getServerErrorMessage().getMessage()).isEqualTo("D115_TOPOLOGY_IDENTITY_IMMUTABLE");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(IDENTITY_CONSTRAINT);
                });
            } finally {
                application.rollback();
            }
        }
        assertThat(businessSnapshot()).isEqualTo(before);
    }

    /** 相同孤立INSERT可执行但COMMIT必须拒绝，证明新增检查延期且事务失败不留下设备。 */
    private void assertOrphanInsertNowFailsOnlyAtCommit(Fixture fixture) throws SQLException {
        String before = businessSnapshot();
        UUID deviceId = UUID.randomUUID();
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                insertDevice(application, fixture, deviceId, fixture.subTypeId(), "rejected_orphan", fixture.gatewayId(), false);
                assertThat(integerValue(application, "SELECT count(*) FROM public.dev_device WHERE id = ?", deviceId)).isEqualTo(1);
                assertThatThrownBy(application::commit).isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage()).isNotNull();
                    assertThat(error.getServerErrorMessage().getMessage()).isEqualTo(RUNTIME_MESSAGE);
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(PROJECTION_CONSTRAINT);
                });
            } finally {
                application.rollback();
            }
        }
        assertThat(businessSnapshot()).isEqualTo(before);
    }

    /** 数据面既有顺序先设备后关系必须同事务提交，不能把新增INSERT触发器改成即时检查。 */
    private void assertLegitimateDeviceThenTopologyInsert(Fixture fixture) throws SQLException {
        UUID deviceId = UUID.randomUUID();
        UUID topologyId = UUID.randomUUID();
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                insertDevice(application, fixture, deviceId, fixture.subTypeId(), "legal_insert", fixture.gatewayId(), false);
                insertTopology(application, fixture, topologyId, deviceId);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection connection = ownerConnection()) {
            assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", deviceId))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(integerValue(connection, """
                    SELECT count(*) FROM public.dev_topo
                     WHERE id = ? AND sub_device_id = ? AND gateway_device_id = ? AND unbound_at IS NULL
                    """, topologyId, deviceId, fixture.gatewayId())).isEqualTo(1);
        }
    }

    /** 只排除本次明确恢复的投影字段；关系全字段及设备其他字段均参与比较，不能掩盖身份/历史改写。 */
    private String nonProjectionSnapshot() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return stringValue(connection, """
                    SELECT jsonb_build_object(
                        'device', (SELECT jsonb_agg(to_jsonb(d) - 'gateway_id' ORDER BY id) FROM public.dev_device d),
                        'type', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.dev_type t),
                        'topology', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.dev_topo t))::text
                    """);
        }
    }

    /** 保存实际pg_proc身份与安全属性；OID比较不能被仅同名重建函数蒙混通过。 */
    private JsonNode functionState() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return JSON.readTree(stringValue(connection, """
                    SELECT jsonb_agg(jsonb_build_object('oid', p.oid, 'name', p.proname,
                        'owner_oid', p.proowner, 'owner_name', pg_get_userbyid(p.proowner),
                        'security_definer', p.prosecdef, 'config', p.proconfig, 'acl', p.proacl,
                        'source', p.prosrc) ORDER BY p.proname)
                      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                     WHERE n.nspname = 'public' AND p.proname IN
                        ('dev_topo_check_projection', 'dev_topo_validate_projection', 'dev_device_validate_projection')
                    """));
        }
    }

    /** 原两约束触发器OID、引用和延迟属性完整保持，不把新建同名触发器当成身份保留。 */
    private JsonNode triggerState() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return JSON.readTree(stringValue(connection, """
                    SELECT jsonb_agg(jsonb_build_object('oid', t.oid, 'name', t.tgname, 'function_oid', t.tgfoid,
                        'deferrable', t.tgdeferrable, 'initially_deferred', t.tginitdeferred,
                        'enabled', t.tgenabled, 'definition', pg_get_triggerdef(t.oid)) ORDER BY t.tgname)
                      FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND t.tgname IN
                        ('dev_topo_projection_check_trg', 'dev_device_gateway_projection_trg')
                    """));
        }
    }

    /** 精确快照保留设备全字段、类型与全部关系历史，避免只看gateway_id忽略状态/时间被部分改写。 */
    private String businessSnapshot() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return stringValue(connection, """
                    SELECT jsonb_build_object(
                        'device', (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM public.dev_device d),
                        'type', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.dev_type t),
                        'topology', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.dev_topo t))::text
                    """);
        }
    }

    /** 失败前后比较公开对象定义、owner与ACL；CREATE OR REPLACE不能因名字不变而逃过检查。 */
    private List<String> schemaObjects() throws SQLException {
        try (Connection connection = ownerConnection(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("""
                     SELECT description FROM (
                         SELECT 'relation:' || c.relkind::text || ':' || c.relname || ':' || COALESCE(c.relacl::text, '') AS description
                           FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                         UNION ALL
                         SELECT 'routine:' || p.proname || ':' || pg_get_function_identity_arguments(p.oid) || ':'
                                    || p.proowner::text || ':' || COALESCE(p.proacl::text, '') || ':' || pg_get_functiondef(p.oid)
                           FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                          WHERE n.nspname = 'public' AND p.prokind IN ('f', 'p')
                         UNION ALL
                         SELECT 'trigger:' || c.relname || ':' || t.tgname || ':' || pg_get_triggerdef(t.oid)
                           FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                           JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                         UNION ALL
                         SELECT 'constraint:' || c.relname || ':' || k.conname || ':' || pg_get_constraintdef(k.oid)
                           FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
                           JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                     ) objects ORDER BY description
                     """)) {
            List<String> descriptions = new ArrayList<>();
            while (rows.next()) descriptions.add(rows.getString(1));
            return descriptions;
        }
    }

    /** 调用方先开启事务，project范围随提交/回滚清理，不污染任何其他连接。 */
    private void setProject(Connection connection, UUID projectId) throws SQLException {
        stringValue(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
    }

    /** 独立APP物理连接核实真实角色，所有范围均由调用方以事务本地配置设置。 */
    private Connection appConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
        try {
            assertThat(stringValue(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
            return connection;
        } catch (SQLException | RuntimeException | Error exception) {
            connection.close();
            throw exception;
        }
    }

    /** 迁移审计和夹具只访问本类容器；被测旧违例及正常写入不使用此owner连接。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 可变身份和值全部参数化；返回更新行数防止空操作被误当成成功覆盖。 */
    private int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            return statement.executeUpdate();
        }
    }

    /** 文本单值保留SQL NULL，诊断与持久化读取不会静默补值。 */
    private String stringValue(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 计数必须来自实际非NULL结果，不以RLS未授权等前提错误冒充零条异常。 */
    private int integerValue(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                int value = row.getInt(1);
                assertThat(row.wasNull()).isFalse();
                return value;
            }
        }
    }

    /** JDBC保留UUID、时间和NULL语义，SQL不拼接业务值。 */
    private void bindValues(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    /**
     * 一套独立旧库事实，A和B的命名描述本测试已观察到的顺序，不让迁移推断未记录历史。
     * @param tenantId 所属租户
     * @param projectId 真实APP的RLS范围
     * @param actorId 原关系操作者
     * @param subTypeId 合法草稿子设备类型
     * @param gatewayId 权威网关G
     * @param oldSubId 起初关系指向A，后续无关系但保留旧投影
     * @param newSubId 已被旧SQL换成当前端点的B
     * @param topologyId 原位换端点的现存关系ID
     * @param healthySubId 始终正常的对照设备
     * @param healthyTopologyId 始终正常的对照关系
     * @param orphanId 由APP单独INSERT且没有权威关系的设备
     * @param deletedId 原helper扫描范围外的软删设备
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID actorId, UUID subTypeId, UUID gatewayId,
                           UUID oldSubId, UUID newSubId, UUID topologyId, UUID healthySubId,
                           UUID healthyTopologyId, UUID orphanId, UUID deletedId) { }
}
