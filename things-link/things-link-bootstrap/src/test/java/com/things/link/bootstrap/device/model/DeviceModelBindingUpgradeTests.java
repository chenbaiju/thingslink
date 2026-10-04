package com.things.link.bootstrap.device.model;

import com.things.link.device.application.DeviceService;
import com.things.link.device.application.DeviceTopologyRoleGuard;
import com.things.link.device.application.DeviceTopologyService;
import com.things.link.device.infrastructure.persistence.JdbcDeviceRepository;
import com.things.link.device.infrastructure.persistence.JdbcDeviceTopologyRepository;
import com.things.link.device.infrastructure.persistence.JdbcDeviceTypeRepository;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.TenantAwareDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR0059：独立旧库提交版本归属异常，先证明运行时与升级拒绝，再按已知原事实恢复并重试。
 * 不在最新版共享库关闭守卫制造异常；原空pointer有history的API用例在此保留服务级真实事务验证。
 */
@Testcontainers
@DisplayName("D-112 旧库升级：版本归属诊断、失败无残留与精确恢复")
class DeviceModelBindingUpgradeTests {

    /** 与部署及其他真实集成测试同版本，避免用替代数据库验证FK、锁和事务DDL。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";
    /** 旧版本含集群级角色创建，必须独占实例而非在共享PG集群另建数据库。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("device_model_binding_upgrade")
            .withUsername("thingslink").withPassword("thingslink");
    /** 与bootstrap生产Flyway配置相同的九个目录，不能只运行设备模块来绕过依赖迁移。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/issuer", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser",
            // verify-11：完整升级必须包含授权外键的看板父表，并与生产迁移域保持一致。
            "classpath:db/migration/export", "classpath:db/migration/dashboard", "classpath:db/migration/ota", "classpath:db/migration/integration"
    };
    /** 已交付拓扑角色保护、尚未增加设备版本归属守卫的真实旧版本。 */
    private static final String LEGACY_TARGET = "20260903.0200";
    /** 失败时该版本不得留下成功或失败的迁移历史行。 */
    private static final String GUARD_VERSION = "20260903.0300";
    /** 应用调用、行锁与恢复操作均受真实RLS权限约束。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 只用于本类容器的占位口令，和旧迁移创建应用角色时保持一致。 */
    private static final String APP_PASSWORD = "thingslink";
    /** 精确区分目标preflight拒绝与其他同SQLSTATE的普通检查约束。 */
    private static final String INVALID_MARKER = "D112_DEVICE_MODEL_BINDING_INVALID";
    /** 结构化constraint标识必须来自目标守卫，不能凭本地语言错误文本识别。 */
    private static final String GUARD_CONSTRAINT = "dev_device_model_binding_guard";
    /** 既有INITIAL发生时间；恢复与升级不得改写原历史。 */
    private static final Instant BOUND_AT = Instant.parse("2026-08-25T02:00:00Z");
    /** 软删异常仍需扫描，但精确恢复类型不能复活该设备。 */
    private static final Instant DELETED_AT = Instant.parse("2026-08-26T03:00:00Z");
    /** 版本快照遵循现有空定义合同，摘要由实际PostgreSQL JSONB规范文本计算。 */
    private static final String MODEL = "{\"properties\":{},\"events\":{},\"commands\":{}}";
    /** 结构化诊断必须逐字段比较实际身份及NULL，而不是只检查异常包含某个UUID。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单一生命周期工作流不依赖JUnit排序，旧库、失败、恢复和重跑均使用同一真实数据库。 */
    @Test
    void rejectsLegacyBindingDriftAndUpgradesOnlyAfterKnownFactsAreRestored() throws Exception {
        legacyFlyway().migrate();
        List<DeviceFixture> fixtures = seedLegacyDevices();
        DeviceFixture withMissingPointer = fixtures.stream()
                .filter(fixture -> fixture.state() == LegacyState.HISTORY_WITHOUT_POINTER).findFirst().orElseThrow();
        String original = businessSnapshot();
        List<String> originalSchema = schemaObjects();
        assertRuntimeRejectsTypeChangesWithExistingHistory(withMissingPointer);
        assertThat(businessSnapshot()).isEqualTo(original);
        assertEachTableLockRefusesMigration(fixtures.getFirst(), original, originalSchema);
        assertReadersRefuseDdlMigration(fixtures.getFirst(), original, originalSchema);
        assertMigrationFailure(repeatableReadFlyway(), "0A000", "D112_PREFLIGHT_REQUIRES_READ_COMMITTED");
        assertMigrationLeftNoChanges(original, originalSchema);

        PSQLException refusal = assertMigrationFailure("23514", true);
        assertActualDiagnosticIdentities(refusal, fixtures);
        assertMigrationLeftNoChanges(original, originalSchema);

        // 只恢复测试已保存的原类型/原指针；不选最新版、不清历史、不推测操作者意图。
        restoreKnownFacts(fixtures);
        String restored = businessSnapshot();
        assertThat(JSON.readTree(restored).get("history")).isEqualTo(JSON.readTree(original).get("history"));
        assertThat(JSON.readTree(restored).get("version")).isEqualTo(JSON.readTree(original).get("version"));
        assertDdlRollsBackAfterFunctionNameConflict(restored);
        assertThat(guardFlyway().migrate().migrationsExecuted).isPositive();
        assertThat(businessSnapshot()).as("升级不得自动改写健康设备、软删事实、无版本设备或历史").isEqualTo(restored);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection,
                    "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success", GUARD_VERSION)).isEqualTo(1);
        }
        assertRestoredIdentities(fixtures);
        assertThat(latestFlyway().migrate().migrationsExecuted).isPositive();
        assertRestoredIdentities(fixtures);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_device WHERE webhook_presence_revision<>0")).isZero();
        }
        String latestState = businessSnapshot();
        List<String> upgradedSchema = schemaObjects();
        assertThat(latestFlyway().migrate().migrationsExecuted).isZero();
        assertThat(businessSnapshot()).isEqualTo(latestState);
        assertThat(schemaObjects()).isEqualTo(upgradedSchema);
    }

    /** 旧库阶段固定真实目标，不能继承公共Spring基类而被启动流程提前迁到最新版。 */
    private Flyway legacyFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD))
                .target(LEGACY_TARGET).load();
    }

    /** 固定本片守卫目标，先逐字比较原事实，再执行后续完整迁移。 */
    private Flyway guardFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD))
                .target(GUARD_VERSION).load();
    }

    private Flyway latestFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).load();
    }

    /** 每个新Flyway连接设SESSION默认RR；目标迁移应在preflight显式拒绝，不以固定旧快照扫描存量。 */
    private Flyway repeatableReadFlyway() {
        return Flyway.configure().dataSource(migrationUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD))
                .initSql("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL REPEATABLE READ").load();
    }

    /**
     * statement_timeout只为NOWAIT退化时提供有限失败边界：那时必须57014失败，不能用lock_timeout的55P03冒充NOWAIT。
     * 选项只属于本类迁移连接，不改变任何生产配置或公共夹具。
     */
    private String migrationUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "options=-c%20statement_timeout%3D15000";
    }

    /** 先建正确的原A版本/INITIAL，再在旧库正常约束下改成可达异常，保留精确恢复依据。 */
    private List<DeviceFixture> seedLegacyDevices() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID typeA = UUID.randomUUID();
        UUID typeB = UUID.randomUUID();
        UUID draftType = UUID.randomUUID();
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        List<DeviceFixture> fixtures = new ArrayList<>();
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO sys_tenant (id, name) VALUES (?, 'D112旧库租户')", tenantId);
                execute(connection, """
                        INSERT INTO sys_project (id, tenant_id, name, project_key)
                        VALUES (?, ?, 'D112旧库项目', 'd112legacy01')
                        """, projectId, tenantId);
                insertType(connection, tenantId, projectId, typeA, "published_a", "PUBLISHED");
                insertType(connection, tenantId, projectId, typeB, "published_b", "PUBLISHED");
                insertType(connection, tenantId, projectId, draftType, "draft", "DRAFT");
                insertVersion(connection, tenantId, projectId, typeA, versionA);
                insertVersion(connection, tenantId, projectId, typeB, versionB);
                for (LegacyState state : LegacyState.values()) {
                    UUID deviceId = UUID.randomUUID();
                    UUID historyId = UUID.randomUUID();
                    UUID originalType = state == LegacyState.UNVERSIONED ? draftType : typeA;
                    UUID originalPointer = state == LegacyState.UNVERSIONED ? null : versionA;
                    execute(connection, """
                            INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, thing_model_version_id,
                                                    device_key, name, deleted_at)
                            VALUES (?, ?, ?, ?, ?, ?, '旧版本绑定设备', ?)
                            """, deviceId, tenantId, projectId, originalType, originalPointer,
                            state.name().toLowerCase(java.util.Locale.ROOT),
                            state == LegacyState.SOFT_DELETED_WRONG_TYPE ? Timestamp.from(DELETED_AT) : null);
                    if (state != LegacyState.UNVERSIONED) {
                        execute(connection, """
                                INSERT INTO dev_device_model_binding_history
                                    (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                                     transition_key, transition_type, effective_at)
                                VALUES (?, ?, ?, ?, NULL, ?, ?, 'INITIAL', ?)
                                """, historyId, tenantId, projectId, deviceId, versionA,
                                UUID.randomUUID(), Timestamp.from(BOUND_AT));
                    }
                    switch (state) {
                        case WRONG_TYPE, SOFT_DELETED_WRONG_TYPE -> execute(connection,
                                "UPDATE dev_device SET device_type_id = ? WHERE id = ?", typeB, deviceId);
                        case NULL_TYPE -> execute(connection,
                                "UPDATE dev_device SET device_type_id = NULL WHERE id = ?", deviceId);
                        case HISTORY_WITHOUT_POINTER -> execute(connection,
                                "UPDATE dev_device SET thing_model_version_id = NULL WHERE id = ?", deviceId);
                        case HEALTHY, UNVERSIONED -> { }
                    }
                    fixtures.add(new DeviceFixture(deviceId, tenantId, projectId, actorId, originalType,
                            typeB, originalPointer, historyId, state));
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return fixtures;
    }

    /** 夹具都是DIRECT且没有有效拓扑，不把D111角色冲突混入D112版本归属测试。 */
    private void insertType(Connection connection, UUID tenantId, UUID projectId, UUID typeId, String key, String status)
            throws SQLException {
        execute(connection, """
                INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type, status)
                VALUES (?, ?, ?, ?, '版本归属类型', 'DIRECT', 'STANDARD', 'WIFI', ?)
                """, typeId, tenantId, projectId, key, status);
    }

    /** 版本身份与规范摘要从创建起固定，测试不修改或停用不可变版本触发器。 */
    private void insertVersion(Connection connection, UUID tenantId, UUID projectId, UUID typeId, UUID versionId)
            throws SQLException {
        execute(connection, """
                INSERT INTO dev_thing_model_version
                    (id, tenant_id, project_id, device_type_id, version_number, version_major, version_minor,
                     version_patch, change_level, schema_profile, model_snapshot, schema_digest, digest_algorithm)
                VALUES (?, ?, ?, ?, '1.0.0', 1, 0, 0, 'MAJOR', 'TC_PROPERTY_COMPOSITE_V1', ?::jsonb,
                        encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex'), 'PG_JSONB_TEXT_V1_SHA256')
                """, versionId, tenantId, projectId, typeId, MODEL, MODEL);
    }

    /** 旧异常的换型及清类型经真实服务事务拒绝；只保留服务级回归，HTTP映射由其余API测试继续覆盖。 */
    private void assertRuntimeRejectsTypeChangesWithExistingHistory(DeviceFixture fixture) throws SQLException {
        String before = businessSnapshot();
        try (AnnotationConfigApplicationContext context = runtimeContext(fixture)) {
            DeviceService devices = context.getBean(DeviceService.class);
            DeviceTopologyRoleGuard guard = context.getBean(DeviceTopologyRoleGuard.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(AopUtils.isAopProxy(devices)).isTrue();
            assertThat(AopUtils.isAopProxy(guard)).isTrue();
            assertThatThrownBy(() -> guard.requireDeviceRole(fixture.projectId(), fixture.deviceId(), null))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(POSTGRES.getDatabaseName());
            for (UUID target : new UUID[]{fixture.otherTypeId(), null}) {
                assertThat(TenantContext.current()).isEmpty();
                TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.actorId()));
                try {
                    assertThat(jdbc.queryForObject("SELECT thing_model_version_id FROM dev_device WHERE id = ?",
                            UUID.class, fixture.deviceId())).isNull();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_device_model_binding_history WHERE device_id = ?",
                            Integer.class, fixture.deviceId())).isEqualTo(1);
                    assertThatThrownBy(() -> devices.update(fixture.projectId(), fixture.deviceId(), target,
                            "不能绕过历史的名称", "不能提交的描述", "不能提交的位置"))
                            .isInstanceOfSatisfying(BusinessException.class,
                                    error -> assertThat(error.errorCode().code()).isEqualTo(30063));
                } finally {
                    TenantContext.clear();
                }
                assertThat(businessSnapshot()).isEqualTo(before);
            }
        }
    }

    /**
     * 只装配被测生产仓储、角色服务及事务；不复制全量Spring/Kafka设施，也不mock被测SQL或Guard。
     * 项目授权固定OWNER，未触及的配额依赖使用mock；测试不声称替代HTTP认证验证。
     */
    private AnnotationConfigApplicationContext runtimeContext(DeviceFixture fixture) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(RuntimeTransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD)));
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(DataSource.class)));
        context.registerBean(PlatformTransactionManager.class,
                () -> new JdbcTransactionManager(context.getBean(DataSource.class)));
        ProjectService projects = mock(ProjectService.class);
        when(projects.requireRoleInProject(fixture.projectId())).thenReturn(ProjectRole.OWNER);
        context.registerBean(ProjectService.class, () -> projects);
        context.registerBean(EffectiveQuotaPolicyProvider.class, () -> mock(EffectiveQuotaPolicyProvider.class));
        context.registerBean(ProjectQuotaService.class, () -> mock(ProjectQuotaService.class));
        context.registerBean(JdbcDeviceRepository.class);
        context.registerBean(JdbcDeviceTypeRepository.class);
        context.registerBean(JdbcDeviceTopologyRepository.class);
        context.registerBean(DeviceTopologyRoleGuard.class);
        // 本片验证拓扑兼容；公开Webhook关闭时不产生事件，显式提供该边界依赖。
        context.registerBean(com.things.link.device.application.DevicePresenceWebhookSource.class,
                () -> mock(com.things.link.device.application.DevicePresenceWebhookSource.class));
        context.registerBean(DeviceTopologyService.class);
        // 此旧库尚无新接入表；隔离接入关闭与原生类型检查，拓扑角色/模型历史守卫仍为真实事务及SQL。
        context.registerBean(com.things.link.device.application.DeviceAccessControlService.class,
                () -> mock(com.things.link.device.application.DeviceAccessControlService.class));
        context.registerBean(com.things.link.device.application.DeviceAccessTypeGuard.class,
                () -> mock(com.things.link.device.application.DeviceAccessTypeGuard.class));
        context.registerBean(DeviceService.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    /** 限定测试配置避免被其他SpringBoot测试扫描；实际解释生产@Transactional而非手工包裹业务调用。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class RuntimeTransactionConfiguration { }

    /** 四处真实应用行锁均要求55P03；后序锁失败时，前面取得的表锁必须已随迁移事务释放。 */
    private void assertEachTableLockRefusesMigration(DeviceFixture fixture, String before, List<String> schema)
            throws SQLException {
        List<String> tables = List.of("dev_type", "dev_device", "dev_thing_model_version", "dev_device_model_binding_history");
        List<UUID> ids = List.of(fixture.originalTypeId(), fixture.deviceId(), fixture.originalVersionId(), fixture.historyId());
        for (int index = 0; index < tables.size(); index++) {
            try (Connection holder = appConnection()) {
                holder.setAutoCommit(false);
                try {
                    setProject(holder, fixture.projectId());
                    lockRow(holder, tables.get(index), ids.get(index));
                    assertMigrationFailure("55P03", false);
                    assertMigrationLeftNoChanges(before, schema);
                    try (Connection probe = ownerConnection()) {
                        probe.setAutoCommit(false);
                        try (Statement lock = probe.createStatement()) {
                            lock.setQueryTimeout(3);
                            for (String previous : tables.subList(0, index)) {
                                lock.execute("LOCK TABLE " + previous + " IN EXCLUSIVE MODE NOWAIT");
                            }
                        } finally {
                            probe.rollback();
                        }
                    }
                } finally {
                    holder.rollback();
                }
            }
        }
    }

    /** 普通SELECT持有的ACCESS SHARE也应阻止设备/版本DDL，证明所需ACCESS EXCLUSIVE已预先NOWAIT取得。 */
    private void assertReadersRefuseDdlMigration(DeviceFixture fixture, String before, List<String> schema)
            throws SQLException {
        List<String> tables = List.of("dev_device", "dev_thing_model_version");
        List<UUID> ids = List.of(fixture.deviceId(), fixture.originalVersionId());
        for (int index = 0; index < tables.size(); index++) {
            try (Connection reader = appConnection()) {
                reader.setAutoCommit(false);
                try {
                    setProject(reader, fixture.projectId());
                    assertThat(stringValue(reader, "SELECT current_user")).isEqualTo(APP_ROLE);
                    // 无FOR UPDATE/SHARE子句；事务保持打开只为保留普通读取取得的表锁。
                    assertThat(stringValue(reader, "SELECT id::text FROM " + tables.get(index) + " WHERE id = ?", ids.get(index)))
                            .isEqualTo(ids.get(index).toString());
                    assertMigrationFailure("55P03", false);
                    assertMigrationLeftNoChanges(before, schema);
                    try (Connection probe = ownerConnection()) {
                        probe.setAutoCommit(false);
                        try (Statement lock = probe.createStatement()) {
                            lock.setQueryTimeout(3);
                            lock.execute("LOCK TABLE dev_type IN EXCLUSIVE MODE NOWAIT");
                            if (index == 1) lock.execute("LOCK TABLE dev_device IN ACCESS EXCLUSIVE MODE NOWAIT");
                        } finally {
                            probe.rollback();
                        }
                    }
                } finally {
                    reader.rollback();
                }
            }
        }
    }

    /** 行锁必须命中实际身份且使用APP_ROLE；SELECT FOR UPDATE不修改不可变版本内容。 */
    private void lockRow(Connection connection, String table, UUID id) throws SQLException {
        assertThat(stringValue(connection, "SELECT current_user")).isEqualTo(APP_ROLE);
        try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
            lock.setQueryTimeout(3);
            lock.setObject(1, id);
            try (ResultSet row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1)).isEqualTo(id);
            }
        }
    }

    /** 检查真实PG SQLSTATE，25P02/57014等前置或超时错误都不能冒充目标失败。 */
    private PSQLException assertMigrationFailure(String sqlState, boolean requireDiagnostic) {
        return assertMigrationFailure(latestFlyway(), sqlState, requireDiagnostic ? INVALID_MARKER : null);
    }

    /** 每种失败必须命中自己的SQLSTATE与消息，不能把RR拒绝、数据拒绝、DDL冲突混为一种证据。 */
    private PSQLException assertMigrationFailure(Flyway flyway, String sqlState, String marker) {
        Throwable failure = catchThrowable(flyway::migrate);
        assertThat(failure).isNotNull();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sqlException && sqlState.equals(sqlException.getSQLState())) {
                if (marker != null) {
                    assertThat(sqlException.getServerErrorMessage()).isNotNull();
                    assertThat(sqlException.getServerErrorMessage().getMessage()).isEqualTo(marker);
                    if (INVALID_MARKER.equals(marker)) {
                        assertThat(sqlException.getServerErrorMessage().getConstraint()).isEqualTo(GUARD_CONSTRAINT);
                    }
                }
                return sqlException;
            }
        }
        throw new AssertionError("迁移异常链缺少预期SQLSTATE " + sqlState, failure);
    }

    /**
     * 独立故障发生在三个ALTER成功之后的CREATE FUNCTION：相同签名仅作惰性占位且不挂触发器。
     * 42723后确认前序UNIQUE/FK/CHECK及索引都回滚，同时保留占位原函数正文/ACL与全部业务事实。
     */
    private void assertDdlRollsBackAfterFunctionNameConflict(String before) throws SQLException {
        try (Connection connection = ownerConnection()) {
            assertThat(stringValue(connection, "SELECT to_regprocedure('public.dev_device_guard_versioned_type()')::text"))
                    .isNull();
            execute(connection, """
                    CREATE FUNCTION public.dev_device_guard_versioned_type() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        -- D112_TEST_DUPLICATE_FUNCTION：无触发器引用，不改变业务写入。
                        RETURN NEW;
                    END;
                    $$
                    """);
            execute(connection, "REVOKE ALL ON FUNCTION public.dev_device_guard_versioned_type() FROM PUBLIC");
        }
        try {
            List<String> withPlaceholder = schemaObjects();
            PSQLException failure = assertMigrationFailure("42723", false);
            assertThat(failure.getServerErrorMessage()).isNotNull();
            assertThat(failure.getServerErrorMessage().getMessage()).contains("dev_device_guard_versioned_type");
            assertMigrationLeftNoChanges(before, withPlaceholder);
            try (Connection connection = ownerConnection()) {
                assertThat(integerValue(connection, """
                        SELECT count(*) FROM pg_constraint WHERE conname IN
                            ('dev_thing_model_version_project_type_id_uk', 'dev_device_project_type_model_version_fk',
                             'dev_device_model_version_requires_type_ck')
                        """)).isZero();
                assertThat(stringValue(connection, "SELECT to_regclass('public.dev_thing_model_version_project_type_id_uk')::text"))
                        .isNull();
            }
        } finally {
            try (Connection connection = ownerConnection()) {
                execute(connection, "DROP FUNCTION public.dev_device_guard_versioned_type()");
            }
        }
    }

    /** 坏设备含软删项；空type与空pointer相关的版本字段必须保留JSON null，合法项不得误入样本。 */
    private void assertActualDiagnosticIdentities(PSQLException exception, List<DeviceFixture> fixtures) {
        JsonNode diagnostic = JSON.readTree(exception.getServerErrorMessage().getDetail());
        List<DeviceFixture> invalid = fixtures.stream()
                .filter(fixture -> fixture.state() != LegacyState.HEALTHY && fixture.state() != LegacyState.UNVERSIONED).toList();
        JsonNode sample = diagnostic.path("sample");
        assertThat(diagnostic.path("count").asInt()).isEqualTo(invalid.size());
        assertThat(sample.isArray()).isTrue();
        assertThat(sample.size()).isEqualTo(invalid.size());
        for (DeviceFixture fixture : invalid) {
            JsonNode actual = null;
            for (JsonNode entry : sample) {
                if (fixture.deviceId().toString().equals(entry.path("device_id").asString())) {
                    assertThat(actual).as("设备诊断不可重复").isNull();
                    actual = entry;
                }
            }
            assertThat(actual).as("必须报告设备 %s", fixture.deviceId()).isNotNull();
            assertUuidField(actual, "project_id", fixture.projectId());
            UUID actualType = switch (fixture.state()) {
                case WRONG_TYPE, SOFT_DELETED_WRONG_TYPE -> fixture.otherTypeId();
                case NULL_TYPE -> null;
                default -> fixture.originalTypeId();
            };
            boolean missingPointer = fixture.state() == LegacyState.HISTORY_WITHOUT_POINTER;
            assertUuidField(actual, "device_type_id", actualType);
            assertUuidField(actual, "thing_model_version_id", missingPointer ? null : fixture.originalVersionId());
            assertUuidField(actual, "version_project_id", missingPointer ? null : fixture.projectId());
            assertUuidField(actual, "version_device_type_id", missingPointer ? null : fixture.originalTypeId());
            assertThat(actual.has("has_history")).isTrue();
            assertThat(actual.get("has_history").asBoolean()).isTrue();
        }
    }

    /** 缺字段与显式NULL语义不同；诊断不得省略NULL字段或填入推测的旧类型/版本。 */
    private void assertUuidField(JsonNode object, String field, UUID expected) {
        assertThat(object.has(field)).as("诊断字段 %s 必须存在", field).isTrue();
        if (expected == null) assertThat(object.get(field).isNull()).isTrue();
        else assertThat(object.get(field).asString()).isEqualTo(expected.toString());
    }

    /** preflight在新DDL之前拒绝：证明无新对象或历史残留，不声称触发了DDL执行后的中途故障。 */
    private void assertMigrationLeftNoChanges(String before, List<String> schema) throws SQLException {
        assertThat(businessSnapshot()).isEqualTo(before);
        assertThat(schemaObjects()).isEqualTo(schema);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = ?", GUARD_VERSION))
                    .isZero();
        }
    }

    /** 精确恢复在制造异常之前已知的原值；软删时间、INITIAL身份与时间均不在本次恢复范围。 */
    private void restoreKnownFacts(List<DeviceFixture> fixtures) throws SQLException {
        for (DeviceFixture fixture : fixtures) {
            if (fixture.state() == LegacyState.HEALTHY || fixture.state() == LegacyState.UNVERSIONED) continue;
            try (Connection connection = appConnection()) {
                connection.setAutoCommit(false);
                try {
                    setProject(connection, fixture.projectId());
                    execute(connection, """
                            UPDATE dev_device SET device_type_id = ?, thing_model_version_id = ?
                             WHERE project_id = ? AND id = ?
                            """, fixture.originalTypeId(), fixture.originalVersionId(), fixture.projectId(), fixture.deviceId());
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        }
    }

    /** 升级后合法未版本化状态仍为空，绑定设备回到原A身份，软删设备保持删除且历史逐行保留。 */
    private void assertRestoredIdentities(List<DeviceFixture> fixtures) throws SQLException {
        try (Connection connection = ownerConnection()) {
            for (DeviceFixture fixture : fixtures) {
                assertThat(stringValue(connection, "SELECT device_type_id::text FROM dev_device WHERE id = ?", fixture.deviceId()))
                        .isEqualTo(fixture.originalTypeId().toString());
                assertThat(stringValue(connection, "SELECT thing_model_version_id::text FROM dev_device WHERE id = ?", fixture.deviceId()))
                        .isEqualTo(fixture.originalVersionId() == null ? null : fixture.originalVersionId().toString());
                assertThat(integerValue(connection, "SELECT count(*) FROM dev_device_model_binding_history WHERE device_id = ?",
                        fixture.deviceId())).isEqualTo(fixture.state() == LegacyState.UNVERSIONED ? 0 : 1);
                if (fixture.state() == LegacyState.SOFT_DELETED_WRONG_TYPE) {
                    assertThat(stringValue(connection, "SELECT deleted_at::text FROM dev_device WHERE id = ?", fixture.deviceId()))
                            .isEqualTo(stringValue(connection, "SELECT ?::timestamptz::text", Timestamp.from(DELETED_AT)));
                }
            }
        }
    }

    /** 全字段快照覆盖设备、类型、不可变版本及全部转换历史，升级失败不能悄悄修复任何一半事实。 */
    private String businessSnapshot() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return stringValue(connection, """
                    SELECT jsonb_build_object(
                        'device', (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM dev_device d),
                        'type', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM dev_type t),
                        'version', (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dev_thing_model_version v),
                        'history', (SELECT jsonb_agg(to_jsonb(h) ORDER BY id) FROM dev_device_model_binding_history h))::text
                    """);
        }
    }

    /** 名称、定义、权限和约束一起核对，CREATE OR REPLACE/GRANT不能因对象名称没变而漏检。 */
    private List<String> schemaObjects() throws SQLException {
        try (Connection connection = ownerConnection(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("""
                     SELECT description FROM (
                         SELECT 'relation:' || c.relkind::text || ':' || c.relname || ':'
                                    || COALESCE(c.relacl::text, '') AS description
                           FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                         UNION ALL
                         SELECT 'routine:' || p.proname || ':' || pg_get_function_identity_arguments(p.oid) || ':'
                                    || COALESCE(p.proacl::text, '') || ':' || pg_get_functiondef(p.oid)
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

    /** 调用者已开启事务，项目范围在提交/回滚时清理，连接关闭后不影响任何其他测试。 */
    private void setProject(Connection connection, UUID projectId) throws SQLException {
        stringValue(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
    }

    /** 迁移与全库审计只访问本类独立容器，不能错误使用公共基类默认数据库。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 原生应用身份保留RLS和所有既有约束；没有owner替身或禁用触发器。 */
    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    /** 可变字段和身份全部参数化，调用方显式控制每个真实事务的提交。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            statement.execute();
        }
    }

    /** 单值查询保留SQL NULL，供真实指针与诊断字段比较。 */
    private String stringValue(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 计数必须来自实际非NULL结果，不能因RLS未命中或读取失败而被误当成零。 */
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

    /** JDBC绑定保留UUID、时间和NULL的数据库语义，不在SQL字符串中拼接测试值。 */
    private void bindValues(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    /** 旧约束允许的真实状态；跨项目指针已被旧FK拒绝，不停用旧FK捏造该不可达夹具。 */
    private enum LegacyState {
        /** 第一项保持正确绑定，供四张表行锁的可见身份使用。 */
        HEALTHY,
        /** 当前版本仍属A但设备被换成B。 */
        WRONG_TYPE,
        /** 已绑定版本但设备类型被清空。 */
        NULL_TYPE,
        /** 扫描不能跳过软删除的跨类型指针。 */
        SOFT_DELETED_WRONG_TYPE,
        /** 原INITIAL仍在而当前指针已被清空，运行时也不能据此绕过固定类型。 */
        HISTORY_WITHOUT_POINTER,
        /** 合法草稿设备没有版本也没有历史，升级不得擅自初始化。 */
        UNVERSIONED
    }

    /**
     * 制造异常前已知的真实原身份，用于诊断与精确恢复；不根据失败样本推测历史。
     * @param deviceId 设备身份
     * @param tenantId 归属租户
     * @param projectId 项目隔离范围
     * @param actorId 服务授权的测试操作者身份
     * @param originalTypeId 原始正确类型
     * @param otherTypeId 非原类型B
     * @param originalVersionId 原始当前版本，合法未版本化设备为空
     * @param historyId 原INITIAL身份，仅未版本化设备不实际插入
     * @param state 旧库提交的状态
     */
    private record DeviceFixture(UUID deviceId, UUID tenantId, UUID projectId, UUID actorId, UUID originalTypeId,
                                 UUID otherTypeId, UUID originalVersionId, UUID historyId, LegacyState state) { }
}
