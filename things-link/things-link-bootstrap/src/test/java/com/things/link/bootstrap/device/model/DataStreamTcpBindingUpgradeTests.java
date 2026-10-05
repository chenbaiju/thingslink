package com.things.link.bootstrap.device.model;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-033 升级路径测试：证明「已有数据的旧库」应用迁移 {@code V20260820_0200}
 * （冻结 {@code tcp_bound}）后升级成功。
 *
 * <p>与 {@code DatabaseBaselineTests} 等空库测试的区别：Flyway 先以
 * {@code target=202608200100} 停在旧状态（V20260820_0100 是冻结迁移的全局前驱），
 * 插入含 {@code tcp_bound=true} 与旧格式（MODBUS_TCP）的存量行，再升级到最新 ——
 * 覆盖「已有数据升级成功」这一空库测试永远碰不到的路径（D-033 独立核验 P1）。
 *
 * <p>Flyway 配置与生产保持一致（D-033 核验要求）：9 个模块迁移目录（同
 * {@code bootstrap/application.yml} 的 locations）、{@code app_role_password} 占位符
 * （V20260801_0300 行级安全迁移创建 SECURITY DEFINER 角色时使用）、容器超级用户
 * （表 owner，不受 RLS 约束，与 Flyway 在生产以迁移账号执行一致）。
 */
@Testcontainers
@DisplayName("D-033 升级路径：存量 tcp_bound 数据在冻结迁移下可升级")
class DataStreamTcpBindingUpgradeTests {

    /** 与 {@code deploy/.env} / {@code AbstractIntegrationTest} 保持一致的数据镜像。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";

    /** 本测试自有的 PostgreSQL + TimescaleDB 容器（不能复用 AbstractIntegrationTest：它会把库迁到最新）。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("thingslink")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** 生产迁移目录清单，必须与 bootstrap application.yml 的 flyway.locations 保持一致。 */
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
            "classpath:db/migration/export", "classpath:db/migration/dashboard", "classpath:db/migration/ota", "classpath:db/migration/integration", "classpath:db/migration/assistant"
    };

    /** 冻结迁移的全局前驱版本：V20260820_0100（alarm）。Flyway 目标版本用点号分隔。 */
    private static final String BEFORE_FREEZE_TARGET = "20260820.0100";

    /** 存量租户 ID，插入 sys_tenant 后供 FK 引用。 */
    private static UUID tenantId;
    /** 存量项目 ID，插入 sys_project 后供 FK 引用。 */
    private static UUID projectId;
    /** 存量设备类型 ID，插入 dev_type 后供数据流 FK 引用。 */
    private static UUID deviceTypeId;
    /** 存量数据流 ID，升级后校验原值保留与 tcp_bound 归一。 */
    private static UUID streamId;

    /** 以旧目标版本迁移 + 插入存量数据 + 升级到最新，全程与生产同配置。 */
    @BeforeAll
    static void migrateFromLegacyState() throws Exception {
        // 第一阶段：只应用到冻结迁移的前一版，构造「旧库」状态。
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS)
                .placeholders(Map.of("app_role_password", "thingslink"))
                .target(BEFORE_FREEZE_TARGET)
                .load()
                .migrate();

        // 插入合法存量数据：租户 → 项目 → 设备类型 → 数据流（tcp_bound=true + 旧格式 MODBUS_TCP）。
        // 迁移时代只有旧的 format CHECK 与唯一索引，tcp_bound=true 与 MODBUS_TCP 都是合法值。
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        deviceTypeId = UUID.randomUUID();
        streamId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO sys_tenant (id, name) VALUES ('" + tenantId + "', 'legacy')");
            // project_key 由 V20260804_1300 迁移引入：全局唯一、NOT NULL、正则约束，插入时须显式提供。
            statement.execute("INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES ('" + projectId
                    + "', '" + tenantId + "', 'legacy', 'klegacy01')");
            statement.execute("""
                    INSERT INTO dev_type
                        (id, tenant_id, project_id, type_key, name, device_kind, access_protocol, network_type)
                    VALUES ('%s', '%s', '%s', 'legacy_type', 'legacy', 'DIRECT', 'STANDARD', 'WIFI')
                    """.formatted(deviceTypeId, tenantId, projectId));
            statement.execute("""
                    INSERT INTO dev_data_stream
                        (id, tenant_id, project_id, device_type_id, stream_key, name, format,
                         mqtt_topic_advanced, publish_topic, subscribe_topic, tcp_bound)
                    VALUES ('%s', '%s', '%s', '%s', 'legacy_stream', 'legacy', 'MODBUS_TCP',
                            false, NULL, NULL, true)
                    """.formatted(streamId, tenantId, projectId, deviceTypeId));
        }

        // 第二阶段：升级到最新（含 V20260820_0200 冻结迁移）。
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS)
                .placeholders(Map.of("app_role_password", "thingslink"))
                .load()
                .migrate();
    }

    /** @return 只读 JDBC 连接（容器超级用户，绕过 RLS，与迁移执行身份一致） */
    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 冻结迁移已执行成功，且列仍在（冻结而非删除）。 */
    @Test
    @DisplayName("V20260820_0200 执行成功，tcp_bound 列保留（冻结而非物理删除）")
    void freezeMigrationAppliedAndColumnKept() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE script LIKE 'V20260820_0200%'")) {
                assertThat(rs.next()).as("冻结迁移应已记录").isTrue();
                assertThat(rs.getBoolean("success")).as("冻结迁移应执行成功").isTrue();
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_name = 'dev_data_stream' AND column_name = 'tcp_bound'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("tcp_bound 列应保留（D-054 前不物理删除）").isEqualTo(1);
            }
        }
    }

    /** 存量行升级后：ID/标识符/Topic/format 原值保留，tcp_bound 归一 false。 */
    @Test
    @DisplayName("存量行原值保留：id/stream_key/Topic/format 不变，tcp_bound 归一 false")
    void legacyRowPreservedAndTcpBoundNormalized() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT id, stream_key, name, format, publish_topic, subscribe_topic, tcp_bound "
                            + "FROM dev_data_stream WHERE id = '" + streamId + "'")) {
                assertThat(rs.next()).as("存量行应仍存在").isTrue();
                assertThat(rs.getObject("id")).isEqualTo(streamId);
                assertThat(rs.getString("stream_key")).isEqualTo("legacy_stream");
                assertThat(rs.getString("name")).isEqualTo("legacy");
                assertThat(rs.getString("format")).as("format 原值保留，格式运行时接线由 S15（D-057）冻结")
                        .isEqualTo("MODBUS_TCP");
                assertThat(rs.getString("publish_topic")).isNull();
                assertThat(rs.getString("subscribe_topic")).isNull();
                assertThat(rs.getBoolean("tcp_bound")).as("tcp_bound 应被归一为 false").isFalse();
            }
        }
    }

    /** TCP 唯一索引已删除，冻结 CHECK 存在且拒绝再写 true。 */
    @Test
    @DisplayName("TCP 唯一索引已删除，冻结 CHECK 存在且拒绝 tcp_bound=true")
    void tcpIndexDroppedAndCheckRejectsTrue() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM pg_indexes WHERE indexname = 'dev_data_stream_project_type_tcp_uk'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("TCP 唯一索引应已删除").isZero();
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM pg_constraint "
                            + "WHERE conname = 'dev_data_stream_tcp_bound_disabled_check'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("冻结 CHECK 应存在").isEqualTo(1);
            }
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO dev_data_stream
                        (id, tenant_id, project_id, device_type_id, stream_key, name, format, tcp_bound)
                    VALUES ('%s', '%s', '%s', '%s', 'ghost', 'ghost', 'JSON', true)
                    """.formatted(UUID.randomUUID(), tenantId, projectId, deviceTypeId)))
                    .as("CHECK (tcp_bound = false) 应拒绝任何旁路写入 true（幽灵配置不得复活）")
                    .isInstanceOf(SQLException.class);
        }
    }

    /**
     * G1-C1b：控制面下线迁移 {@code V20260820_0300} 只改目录注释，不动结构；
     * 升级后目录注释如实声明「无控制面、无运行时消费者」，存量行仍可读。
     */
    @Test
    @DisplayName("V20260820_0300 控制面下线注释迁移执行成功，目录注释声明下线状态")
    void controlPlaneOfflineCommentMigrationApplied() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE script LIKE 'V20260820_0300%'")) {
                assertThat(rs.next()).as("控制面下线注释迁移应已记录").isTrue();
                assertThat(rs.getBoolean("success")).as("控制面下线注释迁移应执行成功").isTrue();
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT obj_description('dev_data_stream'::regclass, 'pg_class')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("目录注释必须声明控制面已下线").contains("V1 控制面已下线");
            }
        }
    }

    /** 避免 @Container 管理之外残留 JDBC 资源。 */
    @AfterAll
    static void tearDown() {
        POSTGRES.stop();
    }
}
