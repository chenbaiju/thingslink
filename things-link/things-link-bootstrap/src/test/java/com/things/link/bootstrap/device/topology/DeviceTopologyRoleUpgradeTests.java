package com.things.link.bootstrap.device.topology;

import com.things.link.device.application.DeviceService;
import com.things.link.device.application.DeviceTopologyRoleGuard;
import com.things.link.device.application.DeviceTopologyService;
import com.things.link.device.application.DeviceTypeService;
import com.things.link.device.infrastructure.persistence.JdbcDeviceRepository;
import com.things.link.device.infrastructure.persistence.JdbcDeviceTopologyRepository;
import com.things.link.device.infrastructure.persistence.JdbcDeviceTypeRepository;
import com.things.link.device.infrastructure.persistence.JdbcThingModelVersionRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR0057：独立旧库先提交真实分类漂移，再验证运行时拒绝、升级前置拒绝和显式处置后的重试。
 * 不继承启动即迁到最新的公共基类，也不关闭新守卫来伪造旧事实；HTTP认证/错误映射仍由RoleApi测试覆盖。
 */
@Testcontainers
@DisplayName("D-111 旧库升级：漂移拒绝、实际身份和显式处置后重试")
class DeviceTopologyRoleUpgradeTests {

    /** 与现有部署及集成夹具保持同版本，避免用不一致的锁或TimescaleDB行为验证迁移。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";
    /** 集群级约束角色不能在同一PG实例重复创建，因此独立容器而非共享实例中的第二个库。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("topology_role_upgrade")
            .withUsername("thingslink")
            .withPassword("thingslink");
    /** 与bootstrap的生产Flyway配置相同，旧库和升级都不能遗漏任一模块迁移。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/issuer", "classpath:db/migration/device",
            "classpath:db/migration/telemetry", "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser",
            // verify-11：完整升级必须包含授权外键的看板父表，并与生产迁移域保持一致。
            "classpath:db/migration/export", "classpath:db/migration/dashboard", "classpath:db/migration/ota", "classpath:db/migration/integration", "classpath:db/migration/assistant"
    };
    /** 已含D-026修复、尚未包含D-111数据库守卫的实际旧版本。 */
    private static final String LEGACY_TARGET = "20260903.0100";
    /** 失败时不得留下该版本的成功行或失败行。 */
    private static final String GUARD_VERSION = "20260903.0200";
    /** 所有持锁、运行时调用和显式处置均以真实受RLS约束的应用身份执行。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 仅独立测试容器的占位口令，与既有Flyway应用角色占位符一致。 */
    private static final String APP_PASSWORD = "thingslink";
    /** 迁移拒绝必须来自目标preflight，而不是其他SQLSTATE相同的检查约束。 */
    private static final String INVALID_MARKER = "D111_TOPOLOGY_ROLE_INVALID";
    /** 固定原绑定时刻，升级与人工处置不得重新编造绑定历史。 */
    private static final Instant BOUND_AT = Instant.parse("2026-08-20T01:00:00Z");
    /** 历史关闭关系与本次明确解绑必须能区分，不能被迁移统一改写。 */
    private static final Instant HISTORICAL_UNBOUND_AT = Instant.parse("2026-08-24T05:00:00Z");
    /** 精确解析PostgreSQL DETAIL中的JSON字段，不能仅凭异常文本出现某个UUID认定诊断正确。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单一顺序工作流包含旧运行时、升级拒绝、显式处置和成功重试，不依赖JUnit方法排序。 */
    @Test
    void rejectsLegacyRoleDriftBeforeDdlAndUpgradesAfterExplicitResolution() throws Exception {
        legacyFlyway().migrate();
        List<RelationFixture> relations = seedLegacyRelations();
        RelationFixture legacyChild = relations.getFirst();
        String original = businessSnapshot();
        List<String> originalSchema = schemaObjects();

        assertLegacyRuntimeRejectsBeforeChildLock(legacyChild);
        assertThat(businessSnapshot()).isEqualTo(original);
        assertEachTableLockRefusesMigration(legacyChild, original, originalSchema);

        PSQLException refusal = assertMigrationFailure("23514", INVALID_MARKER);
        assertActualDiagnosticIdentities(refusal, relations);
        assertMigrationLeftNoChanges(original, originalSchema);

        // 这是明确的测试操作者决定：恢复指定分类/类型，或关闭指定关系；迁移本身不能替操作者猜测。
        resolveLegacyDrift(relations);
        String resolved = businessSnapshot();
        assertThat(guardFlyway().migrate().migrationsExecuted).isPositive();
        assertThat(businessSnapshot()).as("健康有效关系、原历史和明确处置后的事实均应原样保留").isEqualTo(resolved);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection,
                    "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success", GUARD_VERSION))
                    .isEqualTo(1);
        }
        assertResolvedRelations(relations);
        assertThat(latestFlyway().migrate().migrationsExecuted).isPositive();
        assertResolvedRelations(relations);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_device WHERE webhook_presence_revision<>0")).isZero();
        }
        String latestState = businessSnapshot();

        List<String> upgradedSchema = schemaObjects();
        assertThat(latestFlyway().migrate().migrationsExecuted).isZero();
        assertThat(businessSnapshot()).isEqualTo(latestState);
        assertThat(schemaObjects()).isEqualTo(upgradedSchema);
    }

    /** owner仅用于既有生产迁移；目标旧版本固定，不能使用当前最新库注入违例。 */
    private Flyway legacyFlyway() {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
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
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).load();
    }

    /** 所有场景在旧版本真实约束下提交；每条边独占设备和类型，避免一个修正掩盖另一类漂移。 */
    private List<RelationFixture> seedLegacyRelations() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        List<RelationFixture> fixtures = new ArrayList<>();
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO sys_tenant (id, name) VALUES (?, 'D111旧库租户')", tenantId);
                execute(connection, """
                        INSERT INTO sys_project (id, tenant_id, name, project_key)
                        VALUES (?, ?, 'D111旧库项目', 'd111legacy01')
                        """, projectId, tenantId);
                for (LegacyState state : LegacyState.values()) {
                    fixtures.add(insertRelation(connection, tenantId, projectId, actorId, state));
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return fixtures;
    }

    /** gateway_id与权威关系始终同事务写入；只制造旧库实际允许的漂移，不禁用任何既有触发器或外键。 */
    private RelationFixture insertRelation(Connection connection, UUID tenantId, UUID projectId, UUID actorId,
                                           LegacyState state) throws SQLException {
        RelationFixture fixture = new RelationFixture(UUID.randomUUID(), tenantId, projectId, actorId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), state);
        String key = state.name().toLowerCase(java.util.Locale.ROOT);
        execute(connection, """
                INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                VALUES (?, ?, ?, ?, '旧网关类型', 'GATEWAY', 'STANDARD_GATEWAY', 'ETHERNET'),
                       (?, ?, ?, ?, '旧子设备类型', 'SUB_DEVICE', 'STANDARD', 'ZIGBEE')
                """, fixture.gatewayTypeId(), tenantId, projectId, key + "_gw_type",
                fixture.subTypeId(), tenantId, projectId, key + "_sub_type");
        execute(connection, """
                INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                VALUES (?, ?, ?, ?, ?, '旧网关', 'ONLINE')
                """, fixture.gatewayId(), tenantId, projectId, fixture.gatewayTypeId(), key + "_gw");
        execute(connection, """
                INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, gateway_id, device_key, name, status)
                VALUES (?, ?, ?, ?, ?, ?, '旧子设备', 'INACTIVE')
                """, fixture.subId(), tenantId, projectId, fixture.subTypeId(),
                state == LegacyState.HISTORICAL ? null : fixture.gatewayId(), key + "_sub");
        execute(connection, """
                INSERT INTO dev_topo (id, tenant_id, project_id, gateway_device_id, sub_device_id, bind_source,
                                      online_status, bound_by, bound_at, unbound_by, unbound_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'CONTROL_PLANE', 'UNKNOWN', ?, ?, ?, ?, 7, ?)
                """, fixture.topologyId(), tenantId, projectId, fixture.gatewayId(), fixture.subId(), actorId,
                Timestamp.from(BOUND_AT), state == LegacyState.HISTORICAL ? actorId : null,
                state == LegacyState.HISTORICAL ? Timestamp.from(HISTORICAL_UNBOUND_AT) : null, Timestamp.from(BOUND_AT));
        switch (state) {
            case SUB_DIRECT, HISTORICAL -> execute(connection,
                    "UPDATE dev_type SET device_kind = 'DIRECT' WHERE id = ?", fixture.subTypeId());
            case GATEWAY_SUB -> execute(connection, """
                    UPDATE dev_type SET device_kind = 'SUB_DEVICE', access_protocol = 'STANDARD' WHERE id = ?
                    """, fixture.gatewayTypeId());
            case NULL_SUB_TYPE -> execute(connection,
                    "UPDATE dev_device SET device_type_id = NULL WHERE id = ?", fixture.subId());
            case DELETED_SUB_TYPE -> execute(connection,
                    "UPDATE dev_type SET deleted_at = ? WHERE id = ?", Timestamp.from(HISTORICAL_UNBOUND_AT), fixture.subTypeId());
            case DELETED_SUB_DEVICE -> execute(connection,
                    "UPDATE dev_device SET deleted_at = ? WHERE id = ?", Timestamp.from(HISTORICAL_UNBOUND_AT), fixture.subId());
            case HEALTHY -> { }
        }
        return fixture;
    }

    /**
     * 原legacy API用例的服务级回归：当前生产事务代理连接旧库，持子设备锁时删除/发布必须先报30060。
     * 此处不声称覆盖HTTP认证；RoleApi其余用例继续验证实际HTTP与错误映射。
     */
    private void assertLegacyRuntimeRejectsBeforeChildLock(RelationFixture fixture) throws Exception {
        String before = businessSnapshot();
        try (AnnotationConfigApplicationContext context = runtimeContext(fixture)) {
            DeviceService devices = context.getBean(DeviceService.class);
            DeviceTypeService types = context.getBean(DeviceTypeService.class);
            DeviceTopologyRoleGuard guard = context.getBean(DeviceTopologyRoleGuard.class);
            assertThat(AopUtils.isAopProxy(devices)).isTrue();
            assertThat(AopUtils.isAopProxy(types)).isTrue();
            assertThat(AopUtils.isAopProxy(guard)).isTrue();
            assertThatThrownBy(() -> guard.requireGatewayComponent(fixture.projectId(), fixture.gatewayId()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class))
                    .isEqualTo(APP_ROLE);
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT current_database()", String.class))
                    .isEqualTo(POSTGRES.getDatabaseName());
            var requests = Executors.newSingleThreadExecutor();
            try (Connection holder = appConnection()) {
                holder.setAutoCommit(false);
                try {
                    setProject(holder, fixture.projectId());
                    lockRow(holder, "dev_device", fixture.subId());
                    for (Runnable request : List.<Runnable>of(
                            () -> devices.delete(fixture.projectId(), fixture.gatewayId()),
                            () -> types.publish(fixture.projectId(), fixture.subTypeId()))) {
                        Throwable refusal = requests.submit(() -> {
                            assertThat(TenantContext.current()).isEmpty();
                            TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.actorId()));
                            try {
                                return catchThrowable(request::run);
                            } finally {
                                TenantContext.clear();
                            }
                        }).get(10, TimeUnit.SECONDS);
                        assertThat(refusal).isInstanceOfSatisfying(BusinessException.class,
                                error -> assertThat(error.errorCode().code()).isEqualTo(30060));
                        assertThat(businessSnapshot()).isEqualTo(before);
                    }
                } finally {
                    // NOWAIT/预检若退化为阻塞，先解除真实数据库锁，再限时回收请求线程，不能让失败测试无限挂起。
                    holder.rollback();
                }
            } finally {
                requests.shutdownNow();
                assertThat(requests.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    /**
     * 仅装配此回归需要的生产仓储、角色服务和事务代理，不启动业务调度器或复用全量Spring/Kafka夹具。
     * 授权边界固定为测试项目OWNER，配额依赖不会被删除/发布路径调用；被测角色与持久化不可替换为mock。
     */
    private AnnotationConfigApplicationContext runtimeContext(RelationFixture fixture) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(RuntimeTransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD)));
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(DataSource.class)));
        context.registerBean(PlatformTransactionManager.class,
                () -> new JdbcTransactionManager(context.getBean(DataSource.class)));
        ProjectService projects = mock(ProjectService.class);
        when(projects.requireRoleInProject(fixture.projectId())).thenReturn(ProjectRole.OWNER);
        when(projects.roleInProject(fixture.projectId())).thenReturn(Optional.of(ProjectRole.OWNER));
        context.registerBean(ProjectService.class, () -> projects);
        context.registerBean(EffectiveQuotaPolicyProvider.class, () -> mock(EffectiveQuotaPolicyProvider.class));
        context.registerBean(ProjectQuotaService.class, () -> mock(ProjectQuotaService.class));
        context.registerBean(JdbcDeviceRepository.class);
        context.registerBean(JdbcDeviceTypeRepository.class);
        context.registerBean(JdbcDeviceTopologyRepository.class);
        context.registerBean(JdbcThingModelVersionRepository.class);
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
        context.registerBean(DeviceTypeService.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    /** 只启用生产@Transactional解释，不通过外层TransactionTemplate掩盖服务自身缺失事务的错误。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class RuntimeTransactionConfiguration { }

    /** 三表顺序中每一处真实应用行锁都必须55P03，已取得的前序表锁须随失败事务释放。 */
    private void assertEachTableLockRefusesMigration(RelationFixture fixture, String expectedData,
                                                    List<String> expectedSchema) throws SQLException {
        List<String> tables = List.of("dev_type", "dev_device", "dev_topo");
        List<UUID> ids = List.of(fixture.subTypeId(), fixture.subId(), fixture.topologyId());
        for (int index = 0; index < tables.size(); index++) {
            try (Connection holder = appConnection()) {
                holder.setAutoCommit(false);
                try {
                    assertThat(stringValue(holder, "SELECT current_user")).isEqualTo(APP_ROLE);
                    setProject(holder, fixture.projectId());
                    lockRow(holder, tables.get(index), ids.get(index));
                    assertMigrationFailure("55P03", null);
                    assertMigrationLeftNoChanges(expectedData, expectedSchema);
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

    /** 锁定目标来自常量表名和实际夹具UUID，查询超时只限制夹具持锁准备，不代替被测迁移NOWAIT。 */
    private void lockRow(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
            lock.setQueryTimeout(3);
            lock.setObject(1, id);
            try (ResultSet row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
            }
        }
    }

    /** 拒绝必须来自真实PostgreSQL异常；中止事务25P02或任意装配故障都不能算作预期拒绝。 */
    private PSQLException assertMigrationFailure(String sqlState, String marker) {
        Throwable error = catchThrowable(() -> latestFlyway().migrate());
        assertThat(error).isNotNull();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException sqlException && sqlState.equals(sqlException.getSQLState())) {
                if (marker != null) {
                    assertThat(sqlException.getServerErrorMessage()).isNotNull();
                    assertThat(sqlException.getServerErrorMessage().getMessage()).isEqualTo(marker);
                }
                return sqlException;
            }
        }
        throw new AssertionError("迁移异常链未出现预期SQLSTATE " + sqlState, error);
    }

    /** 每条坏边准确报告数据库中的身份；空类型必须为JSON null，健康或历史关系不能进入失败清单。 */
    private void assertActualDiagnosticIdentities(PSQLException error, List<RelationFixture> fixtures) {
        JsonNode diagnostic = JSON.readTree(error.getServerErrorMessage().getDetail());
        JsonNode detail = diagnostic.path("sample");
        List<RelationFixture> invalid = fixtures.stream()
                .filter(fixture -> fixture.state() != LegacyState.HEALTHY && fixture.state() != LegacyState.HISTORICAL)
                .toList();
        assertThat(detail.isArray()).isTrue();
        assertThat(diagnostic.path("count").asInt()).isEqualTo(invalid.size());
        assertThat(detail.size()).isEqualTo(invalid.size());
        for (RelationFixture fixture : invalid) {
            JsonNode actual = null;
            for (JsonNode entry : detail) {
                if (fixture.topologyId().toString().equals(entry.path("topology_id").asString())) {
                    assertThat(actual).as("诊断不得重复同一条关系").isNull();
                    actual = entry;
                }
            }
            assertThat(actual).as("必须定位关系 %s", fixture.topologyId()).isNotNull();
            assertThat(actual.path("project_id").asString()).isEqualTo(fixture.projectId().toString());
            assertThat(actual.path("gateway_device_id").asString()).isEqualTo(fixture.gatewayId().toString());
            assertThat(actual.path("sub_device_id").asString()).isEqualTo(fixture.subId().toString());
            assertThat(actual.path("gateway_type_id").asString()).isEqualTo(fixture.gatewayTypeId().toString());
            if (fixture.state() == LegacyState.NULL_SUB_TYPE) {
                assertThat(actual.has("sub_type_id")).isTrue();
                assertThat(actual.get("sub_type_id").isNull()).isTrue();
            } else {
                assertThat(actual.path("sub_type_id").asString()).isEqualTo(fixture.subTypeId().toString());
            }
        }
    }

    /** preflight在任何新DDL之前拒绝，因此只证明无DDL残留；不冒称已执行DDL后触发了回滚。 */
    private void assertMigrationLeftNoChanges(String expectedData, List<String> expectedSchema) throws SQLException {
        assertThat(businessSnapshot()).isEqualTo(expectedData);
        assertThat(schemaObjects()).isEqualTo(expectedSchema);
        try (Connection connection = ownerConnection()) {
            assertThat(integerValue(connection,
                    "SELECT count(*) FROM flyway_schema_history WHERE version = ?", GUARD_VERSION)).isZero();
        }
    }

    /** 操作者在旧库中显式恢复角色或解绑；每条处置独立提交，失败升级不能替代这些明确决定。 */
    private void resolveLegacyDrift(List<RelationFixture> fixtures) throws SQLException {
        for (RelationFixture fixture : fixtures) {
            try (Connection connection = appConnection()) {
                connection.setAutoCommit(false);
                try {
                    setProject(connection, fixture.projectId());
                    switch (fixture.state()) {
                        case SUB_DIRECT -> execute(connection,
                                "UPDATE dev_type SET device_kind = 'SUB_DEVICE' WHERE id = ?", fixture.subTypeId());
                        case NULL_SUB_TYPE -> execute(connection,
                                "UPDATE dev_device SET device_type_id = ? WHERE id = ?", fixture.subTypeId(), fixture.subId());
                        case DELETED_SUB_TYPE -> execute(connection,
                                "UPDATE dev_type SET deleted_at = NULL WHERE id = ?", fixture.subTypeId());
                        case GATEWAY_SUB, DELETED_SUB_DEVICE -> {
                            execute(connection, "UPDATE dev_topo SET unbound_at = now(), unbound_by = ? WHERE id = ?",
                                    fixture.actorId(), fixture.topologyId());
                            execute(connection, "UPDATE dev_device SET gateway_id = NULL WHERE id = ?", fixture.subId());
                        }
                        case HEALTHY, HISTORICAL -> { }
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        }
    }

    /** 正常有效关系不重写，关闭关系保留原绑定时刻、人工归因与不兼容类型，不靠迁移偷偷修正历史。 */
    private void assertResolvedRelations(List<RelationFixture> fixtures) throws SQLException {
        try (Connection connection = ownerConnection()) {
            for (RelationFixture fixture : fixtures) {
                assertThat(stringValue(connection, "SELECT bound_at::text FROM dev_topo WHERE id = ?", fixture.topologyId()))
                        .isEqualTo(stringValue(connection, "SELECT ?::timestamptz::text", Timestamp.from(BOUND_AT)));
                boolean closed = fixture.state() == LegacyState.GATEWAY_SUB
                        || fixture.state() == LegacyState.DELETED_SUB_DEVICE || fixture.state() == LegacyState.HISTORICAL;
                assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo WHERE id = ? AND unbound_at IS NULL",
                        fixture.topologyId())).isEqualTo(closed ? 0 : 1);
                if (closed) {
                    assertThat(stringValue(connection, "SELECT unbound_by::text FROM dev_topo WHERE id = ?", fixture.topologyId()))
                            .isEqualTo(fixture.actorId().toString());
                    assertThat(stringValue(connection, "SELECT gateway_id::text FROM dev_device WHERE id = ?", fixture.subId()))
                            .isNull();
                }
            }
            assertThat(integerValue(connection, "SELECT count(*) FROM dev_topo_repair_audit"))
                    .as("D111不执行D026式自动修复或增补历史审计").isZero();
        }
    }

    /** 业务快照含发布可能改写的版本/绑定历史及前片审计，防止只检查拓扑而漏掉其他部分提交。 */
    private String businessSnapshot() throws SQLException {
        try (Connection connection = ownerConnection()) {
            return stringValue(connection, """
                    SELECT jsonb_build_object(
                        'type', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM dev_type t),
                        'device', (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM dev_device d),
                        'topology', (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM dev_topo t),
                        'version', (SELECT jsonb_agg(to_jsonb(v) ORDER BY id) FROM dev_thing_model_version v),
                        'binding', (SELECT jsonb_agg(to_jsonb(b) ORDER BY id) FROM dev_device_model_binding_history b),
                        'repair_audit', (SELECT jsonb_agg(to_jsonb(a) ORDER BY topology_id) FROM dev_topo_repair_audit a))::text
                    """);
        }
    }

    /** 比较对象身份、定义和权限，避免CREATE OR REPLACE或GRANT留下改变却因名称未变而漏检。 */
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
                         UNION ALL
                         SELECT 'role:' || rolname || ':' || rolcanlogin::text || ':' || rolinherit::text
                                    || ':' || rolbypassrls::text
                           FROM pg_roles WHERE rolname = 'thingslink_topology_guard'
                     ) objects ORDER BY description
                     """)) {
            List<String> descriptions = new ArrayList<>();
            while (rows.next()) descriptions.add(rows.getString(1));
            return descriptions;
        }
    }

    /** 当前连接不池化；事务局部项目范围保证失败回滚后不遗留租户会话数据。 */
    private void setProject(Connection connection, UUID projectId) throws SQLException {
        stringValue(connection, "SELECT set_config('app.project_id', ?, true)", projectId.toString());
    }

    /** 迁移和全库审计仅连接本类独立容器，不能借公共基类访问已迁到最新的数据库。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 运行时与显式处置以真实APP_ROLE连接；RLS和所有现有约束始终开启。 */
    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    /** 可变身份和业务值全部参数化，语句关闭不影响调用者明确管理的事务提交边界。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            statement.execute();
        }
    }

    /** 单值查询保留SQL NULL，便于诊断与历史快照精确比较。 */
    private String stringValue(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    /** 计数必须真有非NULL结果，不能把未命中行或SQL NULL静默当作零。 */
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

    /** JDBC绑定保留UUID、时间与NULL的数据库语义，不把诊断用值拼成SQL文本。 */
    private void bindValues(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    /** 旧库真实可达状态；历史关系分类不匹配不属于有效关系升级拒绝集合。 */
    private enum LegacyState {
        /** 子设备被改成DIRECT，用于运行时预检与显式恢复分类。 */
        SUB_DIRECT,
        /** 网关被改成SUB_DEVICE，由操作者选择明确解绑。 */
        GATEWAY_SUB,
        /** 类型字段真实为空，诊断必须保留NULL而不是填入原类型。 */
        NULL_SUB_TYPE,
        /** 类型仍物理存在但已软删除，旧外键不能替代有效性判断。 */
        DELETED_SUB_TYPE,
        /** 前片迁移之后旧代码仍可软删子设备而留下有效关系，当前升级必须拒绝。 */
        DELETED_SUB_DEVICE,
        /** 正常有效关系在升级前后必须逐字段不变。 */
        HEALTHY,
        /** 已关闭的非标准分类保留原历史，不能自动修复或误报。 */
        HISTORICAL
    }

    /**
     * 每条独立旧关系的真实身份，用于精确诊断、锁目标和操作归因。
     * @param topologyId 权威关系ID
     * @param tenantId 归属租户
     * @param projectId RLS项目范围
     * @param actorId 明确处置操作者的测试身份
     * @param gatewayId 网关设备ID
     * @param subId 子设备ID
     * @param gatewayTypeId 网关原类型ID
     * @param subTypeId 子设备原类型ID，NULL场景仅从设备引用移除
     * @param state 旧库真实提交的状态
     */
    private record RelationFixture(UUID topologyId, UUID tenantId, UUID projectId, UUID actorId,
                                   UUID gatewayId, UUID subId, UUID gatewayTypeId, UUID subTypeId, LegacyState state) { }
}
