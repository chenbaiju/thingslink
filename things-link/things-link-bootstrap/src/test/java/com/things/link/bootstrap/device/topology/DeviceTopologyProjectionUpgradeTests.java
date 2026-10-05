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
 * ADR0033 / D-113：旧库以真实TEMP遮蔽提交两种投影漂移，升级拒绝后显式恢复并保留原函数/触发器身份。
 * 不关闭旧约束或共享最新版测试上下文；仅验证本片TEMP加固与既有延期双写合同，不扩称其他投影入口已安全。
 */
@Testcontainers
@DisplayName("D-113 旧库升级：TEMP投影漂移、存量拒绝与原触发器加固")
class DeviceTopologyProjectionUpgradeTests {

    /** 与部署及既有升级测试同版本，真实验证PostgreSQL临时schema、延迟约束和目录身份。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";
    /** 既有迁移创建集群级角色，因此独占PG实例而非共享实例中的另一个数据库。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_projection_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 与bootstrap生产Flyway相同的九个模块目录，不能遗漏前置角色和版本约束。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/issuer", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser",
            // verify-11：完整升级必须包含授权外键的看板父表，并与生产迁移域保持一致。
            "classpath:db/migration/export", "classpath:db/migration/dashboard", "classpath:db/migration/ota", "classpath:db/migration/integration", "classpath:db/migration/assistant"
    };
    /** 旧版投影函数仍可被TEMP遮蔽，已包含D111/D112守卫。 */
    private static final String LEGACY_TARGET = "20260903.0300";
    /** 失败时不得留下此版本成功或失败记录，成功后应只执行一次。 */
    private static final String GUARD_VERSION = "20260903.0400";
    /** 所有旁路、持锁及合法双写均以真实应用身份执行，不能以owner代替。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 仅独立容器的测试占位口令，与迁移占位符一致。 */
    private static final String APP_PASSWORD = "thingslink";
    /** 原三函数的专用只读owner，升级不得改为应用角色或迁移超级用户。 */
    private static final String CONSTRAINT_ROLE = "thingslink_constraint";
    /** 新增结构化标识同时用于存量拒绝和加固后的运行时投影拒绝。 */
    private static final String GUARD_CONSTRAINT = "dev_topo_projection_guard";
    /** 原运行时中文消息保持，不能因安全加固改变既有业务失败文本。 */
    private static final String RUNTIME_MESSAGE = "设备网关投影与 dev_topo 有效绑定不一致";
    /** 绑定历史不可被迁移或投影恢复改写。 */
    private static final Instant BOUND_AT = Instant.parse("2026-08-25T02:00:00Z");
    /** 软删无有效关系的投影不在旧helper范围，本片须保留而不是自动修复。 */
    private static final Instant DELETED_AT = Instant.parse("2026-08-26T03:00:00Z");
    /** 三个已存在函数的完整签名，用于OID、owner及有效执行权限检查。 */
    private static final List<String> FUNCTIONS = List.of("dev_topo_check_projection(uuid)",
            "dev_topo_validate_projection()", "dev_device_validate_projection()");
    /** 实际JSON目录与错误DETAIL解析，不以字符串包含UUID代替字段语义验证。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单工作流跨越真实旧库、可达漂移、升级拒绝、精确恢复、加固验证和重跑，不依赖测试排序。 */
    @Test
    void upgradesShadowableProjectionFunctionsAfterExplicitlyRestoringKnownFacts() throws Exception {
        legacyFlyway().migrate();
        Fixture fixture = seedFixture();
        String cleanState = businessSnapshot();
        JsonNode originalFunctions = functionState();
        JsonNode originalTriggers = triggerState();
        createReachableLegacyDrift(fixture);
        String driftedState = businessSnapshot();
        assertThat(driftedState).isNotEqualTo(cleanState);
        assertCommittedDrift(fixture);
        List<String> originalSchema = schemaObjects();

        assertTableLockFailures(fixture, driftedState, originalSchema);
        assertMigrationFailure(repeatableReadFlyway(), "0A000", "D113_PREFLIGHT_REQUIRES_READ_COMMITTED");
        assertMigrationLeftNoChanges(driftedState, originalSchema);
        PSQLException refusal = assertMigrationFailure(latestFlyway(), "23514", "D113_DEVICE_PROJECTION_INVALID");
        assertThat(refusal.getServerErrorMessage().getConstraint()).isEqualTo(GUARD_CONSTRAINT);
        assertDiagnosticIdentities(refusal, fixture);
        assertMigrationLeftNoChanges(driftedState, originalSchema);

        restoreKnownProjections(fixture);
        assertThat(businessSnapshot()).isEqualTo(cleanState);
        assertThat(latestFlyway().migrate().migrationsExecuted).isPositive();
        assertThat(businessSnapshot()).as("升级不得重写投影、拓扑历史、状态或软删范围外事实").isEqualTo(cleanState);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_device WHERE webhook_presence_revision <> 0")).isZero();
            assertThat(integerValue(connection, """
                    SELECT count(*) FROM dev_device
                     WHERE location_point IS NOT NULL OR location_point_version IS DISTINCT FROM 0
                    """)).as("坐标迁移不得猜测回填旧设备，独立版本应从0开始").isZero();
        }
        assertFunctionHardeningPreservesIdentities(originalFunctions, originalTriggers);
        assertExecutionPrivileges(fixture);
        assertSameTempBypassNowFailsAtCommit(fixture);
        assertThat(businessSnapshot()).isEqualTo(cleanState);
        assertLegitimateDeferredDoubleWrites(fixture);

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

    /** 旧库目标固定，不能借已迁到最新的公共Spring数据源制造违例。 */
    private Flyway legacyFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(LEGACY_TARGET).load();
    }

    /** 正常升级与失败重试使用同一完整迁移集合，不repair或修改既有迁移。 */
    private Flyway latestFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).load();
    }

    /** 只改变本次迁移连接默认隔离级别，验证preflight拒绝固定旧快照而非冒称运行时新隔离策略。 */
    private Flyway repeatableReadFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD))
                .initSql("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL REPEATABLE READ").load();
    }

    /** NOWAIT若退化阻塞则有限时间57014失败，不能用lock_timeout产生55P03来伪造NOWAIT证据。 */
    private String migrationUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "options=-c%20statement_timeout%3D15000";
    }

    /**
     * 两条正确有效关系、一个无关系存活设备及一个无关系软删设备。
     * 类型全部草稿、版本指针为空，避免D112或首次版本绑定掩盖投影合同。
     */
    private Fixture seedFixture() throws SQLException {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID gatewayType = UUID.randomUUID();
        UUID subType = UUID.randomUUID();
        UUID directType = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO sys_tenant (id, name) VALUES (?, 'D113旧库租户')", fixture.tenantId());
                execute(connection, """
                        INSERT INTO sys_project (id, tenant_id, name, project_key)
                        VALUES (?, ?, 'D113旧库项目', 'd113legacy01')
                        """, fixture.projectId(), fixture.tenantId());
                insertType(connection, fixture, gatewayType, "gateway", "GATEWAY", "STANDARD_GATEWAY");
                insertType(connection, fixture, subType, "sub", "SUB_DEVICE", "STANDARD");
                insertType(connection, fixture, directType, "direct", "DIRECT", "STANDARD");
                insertDevice(connection, fixture, fixture.gatewayId(), gatewayType, "gateway", null, false);
                insertDevice(connection, fixture, fixture.driftSubId(), subType, "drift_sub", fixture.gatewayId(), false);
                insertDevice(connection, fixture, fixture.healthySubId(), subType, "healthy_sub", fixture.gatewayId(), false);
                insertDevice(connection, fixture, fixture.orphanId(), directType, "orphan", null, false);
                insertDevice(connection, fixture, fixture.deletedId(), directType, "deleted", fixture.gatewayId(), true);
                insertTopology(connection, fixture, fixture.driftTopologyId(), fixture.driftSubId(), Timestamp.from(BOUND_AT));
                insertTopology(connection, fixture, fixture.healthyTopologyId(), fixture.healthySubId(), Timestamp.from(BOUND_AT));
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return fixture;
    }

    /** 合法分类和协议满足已上线角色守卫，不禁用任何前片约束。 */
    private void insertType(Connection connection, Fixture fixture, UUID typeId, String key, String kind, String protocol)
            throws SQLException {
        execute(connection, """
                INSERT INTO public.dev_type (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                VALUES (?, ?, ?, ?, '投影升级类型', ?, ?, 'ETHERNET')
                """, typeId, fixture.tenantId(), fixture.projectId(), key, kind, protocol);
    }

    /** 软删范围外夹具从开始就保留原投影，不能被升级误当成活设备而覆盖。 */
    private void insertDevice(Connection connection, Fixture fixture, UUID deviceId, UUID typeId, String key,
                              UUID gatewayId, boolean deleted) throws SQLException {
        execute(connection, """
                INSERT INTO public.dev_device (id, tenant_id, project_id, device_type_id, device_key, name, gateway_id, deleted_at)
                VALUES (?, ?, ?, ?, ?, '投影升级设备', ?, ?)
                """, deviceId, fixture.tenantId(), fixture.projectId(), typeId, key, gatewayId,
                deleted ? Timestamp.from(DELETED_AT) : null);
    }

    /** 权威关系与设备投影由调用方同事务写入，原DEFERRABLE触发器一直启用。 */
    private void insertTopology(Connection connection, Fixture fixture, UUID topologyId, UUID subId, Timestamp boundAt)
            throws SQLException {
        execute(connection, """
                INSERT INTO public.dev_topo
                    (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source, online_status,
                     bound_by, bound_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE', 'UNKNOWN', ?, ?, 1, ?)
                """, topologyId, fixture.tenantId(), fixture.projectId(), fixture.gatewayId(), subId,
                fixture.actorId(), boundAt, boundAt);
    }

    /** 新APP物理连接首次执行旧helper前先建立空TEMP，避免旧后端已缓存public计划而掩盖真实漏洞。 */
    private void createReachableLegacyDrift(Fixture fixture) throws SQLException {
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                createEmptyShadow(application);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL WHERE id = ?", fixture.driftSubId()))
                        .isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = ? WHERE id = ?",
                        fixture.gatewayId(), fixture.orphanId())).isEqualTo(1);
                // 原型漏洞必须真实提交；此处不把“语句未报错、但尚未触发延期检查”当成证据。
                application.commit();
            } catch (SQLException exception) {
                application.rollback();
                throw exception;
            }
        }
    }

    /** TEMP默认保留至连接关闭，检查发生前不DROP；只有该临时表授权给原constraint角色。 */
    private void createEmptyShadow(Connection connection) throws SQLException {
        execute(connection, "CREATE TEMP TABLE dev_device AS SELECT * FROM public.dev_device WITH NO DATA");
        execute(connection, "GRANT SELECT ON pg_temp.dev_device TO thingslink_constraint");
        assertThat(integerValue(connection, "SELECT count(*) FROM pg_temp.dev_device")).isZero();
    }

    /** 独立连接观察提交后的两个坏态，权威关系仍在且无关系设备没有被偷偷补出一条关系。 */
    private void assertCommittedDrift(Fixture fixture) throws SQLException {
        try (Connection connection = ownerConnection()) {
            assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.driftSubId())).isNull();
            assertThat(stringValue(connection,
                    "SELECT gateway_device_id::text FROM public.dev_topo WHERE id = ? AND unbound_at IS NULL", fixture.driftTopologyId()))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.orphanId()))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(integerValue(connection,
                    "SELECT count(*) FROM public.dev_topo WHERE sub_device_id = ? AND unbound_at IS NULL", fixture.orphanId())).isZero();
        }
    }

    /** 两处应用行锁分别使迁移NOWAIT拒绝，第二处失败还要证明已取得的设备表锁被释放。 */
    private void assertTableLockFailures(Fixture fixture, String before, List<String> schema) throws SQLException {
        List<String> tables = List.of("dev_device", "dev_topo");
        List<UUID> ids = List.of(fixture.driftSubId(), fixture.driftTopologyId());
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

    /** 两种真实坏态给出原始NULL；健康关系和软删范围外设备不能误入升级失败样本。 */
    private void assertDiagnosticIdentities(PSQLException exception, Fixture fixture) {
        JsonNode diagnostic = JSON.readTree(exception.getServerErrorMessage().getDetail());
        JsonNode sample = diagnostic.path("sample");
        assertThat(diagnostic.path("count").asInt()).isEqualTo(2);
        assertThat(sample.isArray()).isTrue();
        assertThat(sample.size()).isEqualTo(2);
        for (UUID deviceId : List.of(fixture.driftSubId(), fixture.orphanId())) {
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
            boolean bound = deviceId.equals(fixture.driftSubId());
            assertUuid(actual, "projected_gateway_id", bound ? null : fixture.gatewayId());
            assertUuid(actual, "topology_id", bound ? fixture.driftTopologyId() : null);
            assertUuid(actual, "authoritative_gateway_id", bound ? fixture.gatewayId() : null);
        }
    }

    /** NULL必须显式出现，缺字段不等于诊断给出了空事实。 */
    private void assertUuid(JsonNode object, String field, UUID expected) {
        assertThat(object.has(field)).as("诊断字段 %s", field).isTrue();
        if (expected == null) assertThat(object.get(field).isNull()).isTrue();
        else assertThat(object.get(field).asString()).isEqualTo(expected.toString());
    }

    /** 本片只有DDL前拒绝证据，不声称已替换部分函数后发生DDL回滚。 */
    private void assertMigrationLeftNoChanges(String before, List<String> schema) throws SQLException {
        assertThat(businessSnapshot()).isEqualTo(before);
        assertThat(schemaObjects()).isEqualTo(schema);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = ?", GUARD_VERSION)).isZero();
        }
    }

    /** 全新应用连接没有TEMP，按已知权威事实恢复原投影，不改绑定、状态、删除时间或历史。 */
    private void restoreKnownProjections(Fixture fixture) throws SQLException {
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = ? WHERE id = ?",
                        fixture.gatewayId(), fixture.driftSubId())).isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL WHERE id = ?", fixture.orphanId())).isEqualTo(1);
                application.commit();
            } catch (SQLException exception) {
                application.rollback();
                throw exception;
            }
        }
    }

    /** CREATE OR REPLACE须保留原OID/owner及触发器引用，仅函数正文、安全路径与执行授权按本片变化。 */
    private void assertFunctionHardeningPreservesIdentities(JsonNode before, JsonNode triggers) throws SQLException {
        JsonNode after = functionState();
        assertThat(before).hasSize(3);
        assertThat(after).hasSize(3);
        for (int index = 0; index < before.size(); index++) {
            JsonNode oldFunction = before.get(index);
            JsonNode current = after.get(index);
            for (String field : List.of("oid", "owner_oid", "name")) {
                assertThat(current.get(field)).as("原函数%s必须保留", field).isEqualTo(oldFunction.get(field));
            }
            assertThat(current.get("owner_name").asString()).isEqualTo(CONSTRAINT_ROLE);
            assertThat(current.get("security_definer").asBoolean()).isTrue();
            assertThat(current.get("config").toString()).contains("search_path=pg_catalog, public, pg_temp");
            String source = current.get("source").asString();
            if (current.get("name").asString().equals("dev_topo_check_projection")) {
                assertThat(source).contains("public.dev_device", "public.dev_topo");
            } else {
                assertThat(source).contains("public.dev_topo_check_projection");
            }
        }
        assertThat(triggerState()).isEqualTo(triggers);
        assertThat(triggers).hasSize(2);
        for (JsonNode trigger : triggers) {
            assertThat(trigger.get("deferrable").asBoolean()).isTrue();
            assertThat(trigger.get("initially_deferred").asBoolean()).isTrue();
        }
    }

    /** 应用和PUBLIC不再手工执行BYPASSRLS函数，原owner仍可由约束触发器内部调用。 */
    private void assertExecutionPrivileges(Fixture fixture) throws SQLException {
        try (Connection application = appConnection(); Connection owner = ownerConnection()) {
            for (String signature : FUNCTIONS) {
                String qualified = "public." + signature;
                assertThat(stringValue(application, "SELECT has_function_privilege(current_user, ?, 'EXECUTE')::text", qualified))
                        .isEqualTo("false");
                assertThat(stringValue(owner, "SELECT has_function_privilege(?::name, ?, 'EXECUTE')::text", CONSTRAINT_ROLE, qualified))
                        .isEqualTo("true");
                assertThat(integerValue(owner, """
                        SELECT count(*) FROM pg_proc p,
                            LATERAL aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) a
                         WHERE p.oid = ?::regprocedure AND a.grantee = 0 AND a.privilege_type = 'EXECUTE'
                        """, qualified)).isZero();
            }
            assertThatThrownBy(() -> execute(application, "SELECT public.dev_topo_check_projection(?)", fixture.driftSubId()))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    /** 相同新物理APP/TEMP前置下UPDATE可执行但COMMIT必须失败，证明原延期触发器实际读取public事实。 */
    private void assertSameTempBypassNowFailsAtCommit(Fixture fixture) throws SQLException {
        String before = businessSnapshot();
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                createEmptyShadow(application);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL WHERE id = ?", fixture.driftSubId()))
                        .isEqualTo(1);
                assertThatThrownBy(application::commit).isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage()).isNotNull();
                    assertThat(error.getServerErrorMessage().getMessage()).isEqualTo(RUNTIME_MESSAGE);
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(GUARD_CONSTRAINT);
                });
            } finally {
                application.rollback();
            }
        }
        assertThat(businessSnapshot()).isEqualTo(before);
    }

    /** 授权收紧后APP仍能先改权威再改投影并一次提交；正常解绑/重绑保持历史且不要求直接函数EXECUTE。 */
    private void assertLegitimateDeferredDoubleWrites(Fixture fixture) throws SQLException {
        UUID reboundId = UUID.randomUUID();
        try (Connection application = appConnection()) {
            application.setAutoCommit(false);
            try {
                setProject(application, fixture.projectId());
                assertThat(execute(application, "UPDATE public.dev_topo SET unbound_at = now(), unbound_by = ? WHERE id = ?",
                        fixture.actorId(), fixture.healthyTopologyId())).isEqualTo(1);
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = NULL WHERE id = ?", fixture.healthySubId()))
                        .isEqualTo(1);
                application.commit();
                setProject(application, fixture.projectId());
                insertTopology(application, fixture, reboundId, fixture.healthySubId(), Timestamp.from(Instant.now()));
                assertThat(execute(application, "UPDATE public.dev_device SET gateway_id = ? WHERE id = ?",
                        fixture.gatewayId(), fixture.healthySubId())).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM public.dev_topo WHERE sub_device_id = ?", fixture.healthySubId())).isEqualTo(2);
            assertThat(integerValue(connection, "SELECT count(*) FROM public.dev_topo WHERE id = ? AND unbound_at IS NOT NULL",
                    fixture.healthyTopologyId())).isEqualTo(1);
            assertThat(stringValue(connection,
                    "SELECT gateway_device_id::text FROM public.dev_topo WHERE id = ? AND unbound_at IS NULL", reboundId))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ?", fixture.healthySubId()))
                    .isEqualTo(fixture.gatewayId().toString());
            assertThat(stringValue(connection, "SELECT gateway_id::text FROM public.dev_device WHERE id = ? AND deleted_at IS NOT NULL",
                    fixture.deletedId())).isEqualTo(fixture.gatewayId().toString());
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

    /** 精确快照保留旧设备字段与全部关系历史；后续新增列的默认事实在升级后独立断言。 */
    private String businessSnapshot() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return stringValue(connection, """
                    SELECT jsonb_build_object(
                        'device', (SELECT jsonb_agg(to_jsonb(d) - ARRAY['webhook_presence_revision', 'location_point', 'location_point_version'] ORDER BY id) FROM public.dev_device d),
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

    /** 每次旁路都新建真实APP后端，禁止复用已有PL/pgSQL查询计划掩盖TEMP的解析路径。 */
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

    /** 迁移审计和夹具只访问本类容器；被测旁路及正常写入不使用此owner连接。 */
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
     * 一套独立旧库事实，各设备承担单一验证角色。
     * @param tenantId 所属租户
     * @param projectId RLS项目范围
     * @param actorId 明确绑定/解绑的测试操作者
     * @param gatewayId 已知权威网关
     * @param driftSubId 有有效关系但被清投影的设备
     * @param driftTopologyId 该设备原权威关系
     * @param healthySubId 保持健康并用于升级后合法双写的设备
     * @param healthyTopologyId 健康设备原权威关系
     * @param orphanId 无关系但被补假投影的存活设备
     * @param deletedId 软删无关系的范围外保持项
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID actorId, UUID gatewayId, UUID driftSubId,
                           UUID driftTopologyId, UUID healthySubId, UUID healthyTopologyId, UUID orphanId, UUID deletedId) { }
}
