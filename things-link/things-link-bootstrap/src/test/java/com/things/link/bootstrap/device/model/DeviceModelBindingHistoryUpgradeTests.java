package com.things.link.bootstrap.device.model;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ADR0061 / D-114：真实0500旧库允许APP修改和删除转换事实，0600只保护未来写后变更，不推断或重建旧历史。
 * 独立容器避免共享最新版Spring上下文提前装载守卫；合法服务追加由运行时用例覆盖，本类聚焦升级和数据库边界。
 */
@Testcontainers
@DisplayName("D-114 旧库升级：历史事实保留、撤权、不可变守卫与父级联")
class DeviceModelBindingHistoryUpgradeTests {

    /** 与部署及既有升级用例一致，验证PG17真实权限、触发器和外键级联。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";
    /** 原迁移创建集群级角色，独占实例避免其他测试的角色与数据库状态干扰。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_model_binding_history_upgrade").withUsername("thingslink").withPassword("thingslink");
    /** 与bootstrap生产Flyway相同的九个目录，不重新定义表结构或略过前片约束。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser"
    };
    /** 当前已交付基线仍允许APP原位改删历史，不能在最新库关闭守卫来伪造旧状态。 */
    private static final String LEGACY_TARGET = "20260903.0500";
    /** 精确固定本片版本，避免未来迁移掩盖0600自身行为。 */
    private static final String GUARD_VERSION = "20260903.0600";
    /** 所有旧改写、持锁及设备物理删除均使用实际应用角色。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 仅用于独占容器的测试口令，与Flyway占位符保持一致。 */
    private static final String APP_PASSWORD = "thingslink";
    /** owner误改须命中本片错误，而不是其他同SQLSTATE约束。 */
    private static final String IMMUTABLE_MESSAGE = "D114_MODEL_BINDING_HISTORY_IMMUTABLE";
    /** 结构化标识用于识别行级与TRUNCATE语句级守卫。 */
    private static final String IMMUTABLE_CONSTRAINT = "dev_device_model_binding_history_immutable";
    /** 本片两个触发器共同使用同一安全限定的非特权函数。 */
    private static final String FUNCTION = "public.dev_device_model_binding_history_reject_mutation()";
    /** 历史夹具采用固定时间，升级前后比较不依赖运行耗时或宿主/容器时钟同步。 */
    private static final Instant INITIAL_AT = Instant.parse("2026-08-25T01:00:00Z");
    /** 两设备曾真实记录的转换时间；旧APP随后只改其中一个设备的此字段。 */
    private static final Instant TRANSITION_AT = Instant.parse("2026-08-25T02:00:00Z");
    /** 已提交的旧改写仍是合法timestamptz，迁移没有可信证据把它恢复为原值。 */
    private static final Instant REWRITTEN_AT = Instant.parse("2026-08-25T03:00:00Z");
    /** 软删除保留父行，不能成为直接删除历史的许可。 */
    private static final Instant DELETED_AT = Instant.parse("2026-08-26T03:00:00Z");
    /** 与当前快照profile兼容的最小模型，摘要由真实PG JSONB文本计算。 */
    private static final String MODEL = "{\"properties\":{},\"events\":{},\"commands\":{}}";
    /** 解析完整持久化快照，区分历史变化与设备/版本保持。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 三条修改入口分别需要行级或语句级守卫，不能只测试DELETE就声称TRUNCATE也受保护。 */
    private enum Mutation {
        /** 原位更新历史时间。 */ UPDATE,
        /** 脱离父级联直接删除一条历史。 */ DELETE,
        /** 清表不触发行级DELETE，必须有独立语句触发器。 */ TRUNCATE
    }

    /** 单顺序流程覆盖可达旧状态、锁失败、原样升级、未来拒绝与合法级联，不依赖JUnit方法顺序。 */
    @Test
    void upgradesWithoutCertifyingOrReconstructingExistingBindingHistory() throws Exception {
        legacyFlyway().migrate();
        Fixture fixture = seedFixture();
        String original = businessSnapshot();
        createCommittedLegacyMutations(fixture);
        assertLegacyMutationsPersist(fixture);
        String legacyState = businessSnapshot();
        assertThat(legacyState).isNotEqualTo(original);
        for (String field : List.of("project", "device", "type", "version")) {
            assertThat(JSON.readTree(legacyState).get(field)).isEqualTo(JSON.readTree(original).get(field));
        }
        List<String> originalSchema = schemaObjects();
        assertHistoryLockRefusesMigration(fixture, legacyState, originalSchema);

        // 不恢复已改时间，也不补回已删行；成功只代表新保护安装，不能认证原历史真实性或完整性。
        assertThat(latestFlyway().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(businessSnapshot()).isEqualTo(legacyState);
        assertLegacyMutationsPersist(fixture);
        assertCatalogAndPrivileges();
        for (Mutation mutation : Mutation.values()) {
            assertMutationRejected(fixture, false, mutation, fixture.rewritten().upgradeId());
            assertMutationRejected(fixture, true, mutation, fixture.rewritten().upgradeId());
        }
        assertSoftDeletedParentsDoNotPermitHistoryDeletion(fixture);
        assertApplicationDeviceDeletionCascadesOnlyItsHistory(fixture);
        assertOwnerProjectDeletionCascadesRemainingHistory(fixture);

        String finalState = businessSnapshot();
        List<String> finalSchema = schemaObjects();
        assertThat(latestFlyway().migrate().migrationsExecuted).isZero();
        assertThat(businessSnapshot()).isEqualTo(finalState);
        assertThat(schemaObjects()).isEqualTo(finalSchema);
        try (Connection owner = ownerConnection()) {
            assertThat(integerValue(owner,
                    "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success", GUARD_VERSION)).isEqualTo(1);
        }
    }

    /** 旧库目标固定，不能继承公共测试上下文而在取证前自动升级。 */
    private Flyway legacyFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(LEGACY_TARGET).load();
    }

    /** 本片没有真假历史preflight或RR准入规则，正常升级原样安装ACL和触发器。 */
    private Flyway latestFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(GUARD_VERSION).load();
    }

    /** NOWAIT若退化阻塞则以有限57014失败，不能用lock_timeout产生55P03使缺失NOWAIT假绿。 */
    private String migrationUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "options=-c%20statement_timeout%3D15000";
    }

    /** 合法同类型两版本和三个设备；两设备有INITIAL/UPGRADE，第三个保留INITIAL供软删父边界验证。 */
    private Fixture seedFixture() throws SQLException {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), deviceHistory(), deviceHistory(), deviceHistory());
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            try {
                execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'D114旧库租户')", fixture.tenantId());
                execute(owner, """
                        INSERT INTO sys_project (id, tenant_id, name, project_key)
                        VALUES (?, ?, 'D114旧库项目', 'd114legacy01')
                        """, fixture.projectId(), fixture.tenantId());
                execute(owner, """
                        INSERT INTO public.dev_type
                            (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                        VALUES (?, ?, ?, 'history_type', '历史守卫类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                        """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
                insertVersion(owner, fixture, fixture.versionA(), 0);
                insertVersion(owner, fixture, fixture.versionB(), 1);
                owner.commit();
            } finally {
                owner.rollback();
            }
        }
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                insertDeviceAndHistory(application, fixture, fixture.rewritten(), "rewritten", true);
                insertDeviceAndHistory(application, fixture, fixture.erased(), "erased", true);
                insertDeviceAndHistory(application, fixture, fixture.softDeleted(), "soft_parent", false);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        return fixture;
    }

    /** 随机ID仅用于独立夹具，后续所有更新和读回均精确命中真实已插入行。 */
    private DeviceHistory deviceHistory() {
        return new DeviceHistory(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    /** 版本从创建开始就满足既有摘要、语义版本和归属约束，不关闭不可变版本触发器。 */
    private void insertVersion(Connection connection, Fixture fixture, UUID versionId, int patch) throws SQLException {
        execute(connection, """
                INSERT INTO public.dev_thing_model_version
                    (id, tenant_id, project_id, device_type_id, version_number, version_major, version_minor,
                     version_patch, change_level, schema_profile, model_snapshot, schema_digest, digest_algorithm)
                VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?, 'TC_PROPERTY_COMPOSITE_V1', ?::jsonb,
                        encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex'), 'PG_JSONB_TEXT_V1_SHA256')
                """, versionId, fixture.tenantId(), fixture.projectId(), fixture.typeId(), "1.0." + patch,
                patch, patch == 0 ? "MAJOR" : "PATCH", MODEL, MODEL);
    }

    /** 普通APP先记录INITIAL，再同事务追加转换和推进当前指针，夹具不依赖无关版本归属异常。 */
    private void insertDeviceAndHistory(Connection connection, Fixture fixture, DeviceHistory device, String key,
                                       boolean upgraded) throws SQLException {
        execute(connection, """
                INSERT INTO public.dev_device
                    (id, tenant_id, project_id, device_type_id, thing_model_version_id, device_key, name)
                VALUES (?, ?, ?, ?, ?, ?, '历史升级设备')
                """, device.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId(), fixture.versionA(), key);
        insertHistory(connection, fixture, device.deviceId(), device.initialId(), null, fixture.versionA(), "INITIAL", INITIAL_AT);
        if (upgraded) {
            insertHistory(connection, fixture, device.deviceId(), device.upgradeId(), fixture.versionA(), fixture.versionB(),
                    "UPGRADE", TRANSITION_AT);
            assertThat(execute(connection, "UPDATE public.dev_device SET thing_model_version_id = ? WHERE id = ?",
                    fixture.versionB(), device.deviceId())).isEqualTo(1);
        }
    }

    /** 仅追加真实转换行，时间固定方便比较；本类不以手写夹具冒充运行时服务资格验证。 */
    private void insertHistory(Connection connection, Fixture fixture, UUID deviceId, UUID historyId,
                               UUID from, UUID to, String kind, Instant effectiveAt) throws SQLException {
        assertThat(execute(connection, """
                INSERT INTO public.dev_device_model_binding_history
                    (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                     transition_key, transition_type, effective_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, historyId, fixture.tenantId(), fixture.projectId(), deviceId, from, to,
                UUID.randomUUID(), kind, Timestamp.from(effectiveAt))).isEqualTo(1);
    }

    /** 两个独立APP事务真实提交旧UPDATE/DELETE，不将仅语句成功当成持久化证据。 */
    private void createCommittedLegacyMutations(Fixture fixture) throws SQLException {
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(application, """
                        UPDATE public.dev_device_model_binding_history SET effective_at = ? WHERE id = ?
                        """, Timestamp.from(REWRITTEN_AT), fixture.rewritten().upgradeId())).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(application, "DELETE FROM public.dev_device_model_binding_history WHERE id = ?",
                        fixture.erased().upgradeId())).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
    }

    /** 新APP连接观察改写时间及缺失UPGRADE；旧被删行仍缺失，不能把升级成功解释成历史已恢复。 */
    private void assertLegacyMutationsPersist(Fixture fixture) throws SQLException {
        try (Connection application = scopedConnection(fixture.projectId())) {
            assertThat(stringValue(application, """
                    SELECT (effective_at = ?::timestamptz)::text FROM public.dev_device_model_binding_history WHERE id = ?
                    """, Timestamp.from(REWRITTEN_AT), fixture.rewritten().upgradeId())).isEqualTo("true");
            assertThat(integerValue(application, "SELECT count(*) FROM public.dev_device_model_binding_history WHERE id = ?",
                    fixture.erased().upgradeId())).isZero();
            assertThat(integerValue(application, "SELECT count(*) FROM public.dev_device_model_binding_history WHERE device_id = ?",
                    fixture.erased().deviceId())).isEqualTo(1);
            assertThat(stringValue(application, "SELECT transition_type FROM public.dev_device_model_binding_history WHERE id = ?",
                    fixture.erased().initialId())).isEqualTo("INITIAL");
        }
    }

    /** APP行锁持有ROW SHARE，与迁移EXCLUSIVE冲突；失败不能先撤权或留下部分新对象。 */
    private void assertHistoryLockRefusesMigration(Fixture fixture, String before, List<String> schema) throws SQLException {
        try (Connection holder = scopedConnection(fixture.projectId())) {
            try {
                try (PreparedStatement lock = holder.prepareStatement("""
                        SELECT id FROM public.dev_device_model_binding_history WHERE id = ? FOR UPDATE
                        """)) {
                    lock.setQueryTimeout(3);
                    lock.setObject(1, fixture.rewritten().upgradeId());
                    try (ResultSet row = lock.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getObject(1)).isEqualTo(fixture.rewritten().upgradeId());
                    }
                }
                assertMigrationFailure("55P03");
                assertThat(businessSnapshot()).isEqualTo(before);
                assertThat(schemaObjects()).isEqualTo(schema);
                try (Connection owner = ownerConnection()) {
                    assertThat(integerValue(owner,
                            "SELECT count(*) FROM flyway_schema_history WHERE version = ?", GUARD_VERSION)).isZero();
                    assertThat(stringValue(owner, """
                            SELECT has_table_privilege(?::name, 'public.dev_device_model_binding_history', 'UPDATE')::text
                            """, APP_ROLE)).isEqualTo("true");
                }
            } finally {
                holder.rollback();
            }
        }
    }

    /** 只接受真实PG的指定SQLSTATE，不把配置错误、语法失败或超时57014冒充NOWAIT证据。 */
    private void assertMigrationFailure(String sqlState) {
        Throwable failure = catchThrowable(() -> latestFlyway().migrate());
        assertThat(failure).isNotNull();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sqlException && sqlState.equals(sqlException.getSQLState())) return;
        }
        throw new AssertionError("迁移异常链没有预期SQLSTATE " + sqlState, failure);
    }

    /** 目录同时验证行/语句两个事件、非特权函数安全设置和精确APP权限，不以功能用例代替ACL检查。 */
    private void assertCatalogAndPrivileges() throws SQLException {
        try (Connection owner = ownerConnection(); Connection application = appConnection()) {
            assertThat(integerValue(owner, """
                    SELECT count(*) FROM pg_trigger
                     WHERE tgrelid = 'public.dev_device_model_binding_history'::regclass AND NOT tgisinternal
                    """)).isEqualTo(2);
            assertTrigger(owner, "dev_device_model_binding_history_immutable_trg", "BEFORE DELETE OR UPDATE", "FOR EACH ROW");
            assertTrigger(owner, "dev_device_model_binding_history_truncate_guard_trg", "BEFORE TRUNCATE", "FOR EACH STATEMENT");
            try (PreparedStatement query = owner.prepareStatement("""
                    SELECT p.prosecdef, p.provolatile, p.proconfig::text AS config, pg_get_userbyid(p.proowner) AS owner_name,
                           p.prorettype = 'trigger'::regtype AS returns_trigger
                      FROM pg_proc p WHERE p.oid = ?::regprocedure
                    """)) {
                query.setString(1, FUNCTION);
                try (ResultSet function = query.executeQuery()) {
                    assertThat(function.next()).isTrue();
                    assertThat(function.getBoolean("prosecdef")).isFalse();
                    assertThat(function.getString("provolatile")).isEqualTo("v");
                    assertThat(function.getString("config")).contains("search_path=pg_catalog, public, pg_temp", "row_security=off");
                    assertThat(function.getString("owner_name")).isEqualTo(POSTGRES.getUsername());
                    assertThat(function.getBoolean("returns_trigger")).isTrue();
                }
            }
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(stringValue(application, """
                        SELECT has_table_privilege(current_user, 'public.dev_device_model_binding_history', ?)::text
                        """, privilege)).as("APP %s权限", privilege)
                        .isEqualTo(List.of("SELECT", "INSERT").contains(privilege) ? "true" : "false");
            }
            assertThat(stringValue(application, """
                    SELECT has_any_column_privilege(current_user, 'public.dev_device_model_binding_history', 'UPDATE')::text
                    """)).isEqualTo("false");
            assertThat(stringValue(application, "SELECT has_function_privilege(current_user, ?, 'EXECUTE')::text", FUNCTION))
                    .isEqualTo("false");
            assertThat(integerValue(owner, """
                    SELECT count(*) FROM pg_proc p,
                        LATERAL aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) a
                     WHERE p.oid = ?::regprocedure AND a.grantee = 0 AND a.privilege_type = 'EXECUTE'
                    """, FUNCTION)).isZero();
            assertThat(integerValue(owner, """
                    SELECT count(*) FROM pg_class c,
                        LATERAL aclexplode(COALESCE(c.relacl, acldefault('r', c.relowner))) a
                     WHERE c.oid = 'public.dev_device_model_binding_history'::regclass
                       AND a.grantee = 0 AND a.privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
                    """)).isZero();
        }
    }

    /** 核对事件定义、执行粒度及函数引用，不能用同名但挂错函数的触发器取绿。 */
    private void assertTrigger(Connection owner, String name, String event, String granularity) throws SQLException {
        assertThat(integerValue(owner, """
                SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.dev_device_model_binding_history'::regclass
                   AND tgname = ? AND tgfoid = ?::regprocedure AND tgenabled = 'O'
                   AND NOT tgdeferrable AND NOT tginitdeferred
                """, name, FUNCTION)).isEqualTo(1);
        assertThat(stringValue(owner, """
                SELECT pg_get_triggerdef(oid) FROM pg_trigger
                 WHERE tgrelid = 'public.dev_device_model_binding_history'::regclass AND tgname = ?
                """, name)).contains(event, granularity);
    }

    /** APP应在授权层42501失败，owner应精确命中23514；先写设备名称也必须随失败事务回滚。 */
    private void assertMutationRejected(Fixture fixture, boolean asOwner, Mutation mutation, UUID historyId) throws SQLException {
        String before = businessSnapshot();
        try (Connection connection = asOwner ? ownerConnection() : appConnection()) {
            connection.setAutoCommit(false);
            try {
                setProject(connection, fixture.projectId());
                assertThat(execute(connection, "UPDATE public.dev_device SET name = '必须整体回滚' WHERE id = ?",
                        fixture.erased().deviceId())).isEqualTo(1);
                Throwable failure = catchThrowable(() -> mutateHistory(connection, mutation, historyId));
                assertThat(failure).isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo(asOwner ? "23514" : "42501");
                    if (asOwner) {
                        assertThat(error.getServerErrorMessage()).isNotNull();
                        assertThat(error.getServerErrorMessage().getMessage()).isEqualTo(IMMUTABLE_MESSAGE);
                        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(IMMUTABLE_CONSTRAINT);
                    }
                });
            } finally {
                connection.rollback();
            }
        }
        assertThat(businessSnapshot()).isEqualTo(before);
    }

    /** 操作本身就须拒绝，禁止提交后再用业务快照差异掩盖具体错误来源。 */
    private void mutateHistory(Connection connection, Mutation mutation, UUID historyId) throws SQLException {
        switch (mutation) {
            case UPDATE -> execute(connection,
                    "UPDATE public.dev_device_model_binding_history SET effective_at = ? WHERE id = ?",
                    Timestamp.from(INITIAL_AT), historyId);
            case DELETE -> execute(connection, "DELETE FROM public.dev_device_model_binding_history WHERE id = ?", historyId);
            case TRUNCATE -> execute(connection, "TRUNCATE TABLE public.dev_device_model_binding_history");
        }
    }

    /** 设备软删和项目软删分别保留真实父行，不能被DELETE守卫误当成父实体已物理消失。 */
    private void assertSoftDeletedParentsDoNotPermitHistoryDeletion(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE public.dev_device SET deleted_at = ? WHERE id = ?",
                    Timestamp.from(DELETED_AT), fixture.softDeleted().deviceId())).isEqualTo(1);
        }
        assertMutationRejected(fixture, true, Mutation.DELETE, fixture.softDeleted().initialId());
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE public.sys_project SET deleted_at = ? WHERE id = ?",
                    Timestamp.from(DELETED_AT), fixture.projectId())).isEqualTo(1);
        }
        assertMutationRejected(fixture, true, Mutation.DELETE, fixture.rewritten().upgradeId());
    }

    /** APP已无历史DELETE权仍可物理删设备，真实FK须按owner执行且只级联该设备历史。 */
    private void assertApplicationDeviceDeletionCascadesOnlyItsHistory(Fixture fixture) throws SQLException {
        try (Connection application = scopedConnection(fixture.projectId())) {
            try {
                assertThat(execute(application, "DELETE FROM public.dev_device WHERE id = ?", fixture.rewritten().deviceId())).isEqualTo(1);
                application.commit();
            } finally {
                application.rollback();
            }
        }
        try (Connection owner = ownerConnection()) {
            assertThat(integerValue(owner, "SELECT count(*) FROM public.dev_device WHERE id = ?", fixture.rewritten().deviceId())).isZero();
            assertThat(integerValue(owner, "SELECT count(*) FROM public.dev_device_model_binding_history WHERE device_id = ?",
                    fixture.rewritten().deviceId())).isZero();
            assertThat(integerValue(owner, "SELECT count(*) FROM public.dev_device_model_binding_history WHERE device_id = ?",
                    fixture.erased().deviceId())).isEqualTo(1);
            assertThat(integerValue(owner, "SELECT count(*) FROM public.dev_device_model_binding_history WHERE device_id = ?",
                    fixture.softDeleted().deviceId())).isEqualTo(1);
            assertThat(integerValue(owner, "SELECT count(*) FROM public.dev_thing_model_version WHERE project_id = ?",
                    fixture.projectId())).isEqualTo(2);
        }
    }

    /** 项目物理级联沿用既有清理合同，剩余历史及设备/版本均删除而租户本身仍保留。 */
    private void assertOwnerProjectDeletionCascadesRemainingHistory(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            try {
                assertThat(execute(owner, "DELETE FROM public.sys_project WHERE id = ?", fixture.projectId())).isEqualTo(1);
                owner.commit();
            } finally {
                owner.rollback();
            }
        }
        try (Connection owner = ownerConnection()) {
            for (String table : List.of("dev_device_model_binding_history", "dev_device", "dev_thing_model_version", "dev_type")) {
                assertThat(integerValue(owner, "SELECT count(*) FROM public." + table + " WHERE project_id = ?", fixture.projectId()))
                        .as("项目删除后%s应随真实FK清理", table).isZero();
            }
            assertThat(integerValue(owner, "SELECT count(*) FROM public.sys_project WHERE id = ?", fixture.projectId())).isZero();
            assertThat(integerValue(owner, "SELECT count(*) FROM public.sys_tenant WHERE id = ?", fixture.tenantId())).isEqualTo(1);
        }
    }

    /** 包含全部历史、设备、版本及归属字段，升级不能悄悄改写剩余旧事实或修补已删除行。 */
    private String businessSnapshot() throws SQLException {
        try (Connection owner = ownerConnection()) {
            return stringValue(owner, """
                    SELECT jsonb_build_object(
                        'project', (SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM public.sys_project p),
                        'device', (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM public.dev_device d),
                        'type', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM public.dev_type t),
                        'version', (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM public.dev_thing_model_version v),
                        'history', (SELECT jsonb_agg(to_jsonb(h) ORDER BY id) FROM public.dev_device_model_binding_history h))::text
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

    /** 每条APP事务的项目范围随提交/回滚清理，不能污染其他物理连接。 */
    private Connection scopedConnection(UUID projectId) throws SQLException {
        Connection connection = appConnection();
        try {
            connection.setAutoCommit(false);
            setProject(connection, projectId);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close();
            throw failure;
        }
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

    /** owner仅用于隔离夹具、迁移审计及明确的owner误操作/项目清理验证，不能冒充APP旁路。 */
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
     * 单设备已有历史身份；第三个设备仅创建INITIAL，不伪造未发生的UPGRADE。
     * @param deviceId 真实设备
     * @param initialId 初始转换记录
     * @param upgradeId 已升级设备的转换记录，未升级设备不插入该ID
     */
    private record DeviceHistory(UUID deviceId, UUID initialId, UUID upgradeId) { }

    /**
     * 一套隔离旧库身份，三个设备分别承载时间改写、历史删除和软删父边界。
     * @param tenantId 归属租户
     * @param projectId 真实APP项目范围
     * @param typeId 三个设备共用的合法DIRECT类型
     * @param versionA 初始版本
     * @param versionB 同类型已发布目标版本
     * @param rewritten 曾被旧APP原位改写UPGRADE时间的设备
     * @param erased 曾被旧APP删除UPGRADE的设备
     * @param softDeleted 保留INITIAL供软删父存在性验证的设备
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID typeId, UUID versionA, UUID versionB,
                           DeviceHistory rewritten, DeviceHistory erased, DeviceHistory softDeleted) { }
}
