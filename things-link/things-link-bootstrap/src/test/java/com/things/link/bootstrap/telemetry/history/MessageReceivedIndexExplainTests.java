package com.things.link.bootstrap.telemetry.history;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1-C3c / D-048 高成本升级验收：90 个真实 Timescale 日 chunk、目标项目 100 万行的迁移前后执行计划。
 *
 * <p>该测试仅在 {@code -Dd048.explain=true} 时执行，避免每次常规单测重复生成百万行；C3c 审计必须记录实际命令、
 * 输入规模和输出哈希。禁止通过 {@code enable_seqscan=off} 强迫优化器选择索引。</p>
 */
@Testcontainers
@EnabledIfSystemProperty(named = "d048.explain", matches = "true")
@DisplayName("D-048 received_at 索引：90 chunk / 百万行真实计划")
class MessageReceivedIndexExplainTests {
    /** 与生产和其他数据库升级测试一致的 TimescaleDB 镜像。 */
    private static final String POSTGRES_IMAGE = "timescale/timescaledb-ha:pg17.4-ts2.18.2";
    /** received_at 索引迁移的直接前驱。 */
    private static final String BEFORE_INDEX_TARGET = "20260822.0100";
    /** 目标项目行数，略高于一百万以避免边界取整争议。 */
    private static final int TARGET_ROWS = 1_000_080;
    /** 另一个项目在最近窗口的行数，使项目等值条件在热 chunk 上具有真实选择性。 */
    private static final int OTHER_ROWS = 100_000;
    /** 生产迁移目录，与 bootstrap application.yml 同步。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry",
            "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser"
    };
    /** 本测试独占的真实 TimescaleDB。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("thingslink")
            .withUsername("thingslink")
            .withPassword("thingslink");
    /** JSON 计划解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 目标项目 ID。 */
    private static final UUID TARGET_PROJECT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    /** 其他项目 ID。 */
    private static final UUID OTHER_PROJECT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    /** 目标租户 ID。 */
    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000003");
    /** 固定设备 ID；消息日志当前不以外键绑定设备。 */
    private static final UUID DEVICE = UUID.fromString("40000000-0000-0000-0000-000000000004");
    /** 迁移前 JSON 计划。 */
    private static String beforePlan;
    /** 迁移后 JSON 计划。 */
    private static String afterPlan;
    /** 查询事实计数。 */
    private static long expectedCount;
    /** 查询事实字节数。 */
    private static long expectedBytes;

    /** 旧库迁移、百万行装载、计划采集与升级全部发生在隔离容器内。 */
    @BeforeAll
    static void prepareUpgradeEvidence() throws Exception {
        flyway().target(BEFORE_INDEX_TARGET).load().migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO ts_device_message_log
                        (id, project_id, device_id, message_id, tenant_id, protocol, direction,
                         topic, payload_summary, raw_bytes, ts, received_at, created_at)
                    SELECT '50000000-0000-0000-0000-000000000005'::uuid,
                           '%s'::uuid, '%s'::uuid, '60000000-0000-0000-0000-000000000006'::uuid,
                           '%s'::uuid, 'MQTT', 'UP', '/probe', 'target', 80,
                           date_trunc('day', now()) - ((n %% 90)::text || ' days')::interval
                               + ((n / 90)::text || ' seconds')::interval,
                           date_trunc('day', now()) - ((n %% 90)::text || ' days')::interval
                               + ((n / 90)::text || ' seconds')::interval,
                           now()
                      FROM generate_series(0, %d) AS series(n)
                    """.formatted(TARGET_PROJECT, DEVICE, TENANT, TARGET_ROWS - 1));
            statement.execute("""
                    INSERT INTO ts_device_message_log
                        (id, project_id, device_id, message_id, tenant_id, protocol, direction,
                         topic, payload_summary, raw_bytes, ts, received_at, created_at)
                    SELECT md5('other-' || n)::uuid,
                           '%s'::uuid, '%s'::uuid, md5('other-message-' || n)::uuid,
                           '%s'::uuid, 'MQTT', 'UP', '/probe', 'other', 40,
                           date_trunc('day', now()) + n * interval '0.4 seconds',
                           date_trunc('day', now()) + n * interval '0.4 seconds', now()
                      FROM generate_series(0, %d) AS series(n)
                    """.formatted(OTHER_PROJECT, DEVICE, TENANT, OTHER_ROWS - 1));
            statement.execute("ANALYZE ts_device_message_log");
            beforePlan = explain(statement);
        }

        flyway().load().migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("ANALYZE ts_device_message_log");
            afterPlan = explain(statement);
            try (ResultSet result = statement.executeQuery(statisticsSql())) {
                assertThat(result.next()).isTrue();
                expectedCount = result.getLong(1);
                expectedBytes = result.getLong(2);
            }
        }
        Path evidence = Path.of("target", "d048-explain");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("before.json"), beforePlan);
        Files.writeString(evidence.resolve("after.json"), afterPlan);
    }

    /** 升级前后聚合事实一致，且目标规模与 90 个实际 chunk 均达冻结下限。 */
    @Test
    void preservesFactsAcrossNinetyChunksAndMillionRows() throws Exception {
        assertThat(expectedCount).isPositive();
        assertThat(expectedBytes).isEqualTo(expectedCount * 80L);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(queryLong(statement, "SELECT count(*) FROM ts_device_message_log WHERE project_id = '"
                    + TARGET_PROJECT + "'" )).isEqualTo(TARGET_ROWS);
            assertThat(queryLong(statement, """
                    SELECT count(*) FROM timescaledb_information.chunks
                     WHERE hypertable_schema = 'public' AND hypertable_name = 'ts_device_message_log'
                    """)).isGreaterThanOrEqualTo(90L);
        }
    }

    /** 迁移后所有 chunk 索引有效，执行计划不含 hypertable chunk 顺序扫描。 */
    @Test
    void usesReceivedIndexOnEveryScannedChunk() throws Exception {
        JsonNode root = JSON.readTree(afterPlan).get(0).get("Plan");
        List<JsonNode> scans = new ArrayList<>();
        collectChunkScans(root, scans);
        assertThat(scans).as("计划必须实际扫描 Timescale chunk").isNotEmpty();
        for (JsonNode scan : scans) {
            assertThat(scan.get("Node Type").asString()).isIn("Index Scan", "Index Only Scan");
            assertThat(scan.path("Index Name").asString()).contains("project_received_idx");
            String condition = scan.path("Index Cond").asString();
            assertThat(condition).contains("project_id").contains("received_at");
        }
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(queryLong(statement, """
                    SELECT count(*)
                      FROM _timescaledb_catalog.hypertable hypertable
                      JOIN _timescaledb_catalog.chunk chunk
                        ON chunk.hypertable_id = hypertable.id AND NOT chunk.dropped
                      LEFT JOIN _timescaledb_catalog.chunk_index chunk_index
                        ON chunk_index.chunk_id = chunk.id
                       AND chunk_index.hypertable_index_name = 'ts_device_message_log_project_received_idx'
                      LEFT JOIN pg_index index_state
                        ON index_state.indexrelid = to_regclass(format('%I.%I', chunk.schema_name, chunk_index.index_name))
                     WHERE hypertable.schema_name = 'public'
                       AND hypertable.table_name = 'ts_device_message_log'
                       AND (chunk_index.chunk_id IS NULL OR index_state.indexrelid IS NULL OR NOT index_state.indisvalid)
                    """)).isZero();
        }
    }

    /** @return 与生产看板相同 received_at 语义的 JSON 执行计划 */
    private static String explain(Statement statement) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statisticsSql())) {
            result.next();
            return result.getString(1);
        }
    }

    /** @return 固定 24 小时窗口的聚合 SQL；不添加 ts 条件伪造 chunk pruning */
    private static String statisticsSql() {
        return "SELECT count(*), coalesce(sum(raw_bytes), 0) FROM ts_device_message_log "
                + "WHERE project_id = '" + TARGET_PROJECT + "' "
                + "AND received_at >= now() - interval '24 hours' AND received_at < now()";
    }

    /** 递归收集真实 chunk 的扫描节点，不把父 Append 当作索引命中。 */
    private static void collectChunkScans(JsonNode node, List<JsonNode> scans) {
        if (node.has("Relation Name") && node.get("Relation Name").asString().startsWith("_hyper_")) {
            scans.add(node);
        }
        for (JsonNode child : node.path("Plans")) {
            collectChunkScans(child, scans);
        }
    }

    /** @return 单列 long 查询结果 */
    private static long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    /** @return 与生产相同 locations 和占位符的 Flyway builder */
    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS)
                .placeholders(Map.of("app_role_password", "thingslink"));
    }

    /** @return 容器 owner 连接；迁移与证据装载不经过应用 RLS */
    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
