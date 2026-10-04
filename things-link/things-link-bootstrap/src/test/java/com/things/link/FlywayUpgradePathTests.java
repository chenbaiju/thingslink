package com.things.link;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 存量库升级路径的自动化验收（关闭 D-176）。
 *
 * <h2>为什么单独立一个用例</h2>
 * 其余所有测试都在**空库**上按版本序跑迁移，因此 S14-1a 的目录迁移取 `V20260912_0230/0240`
 * （0220 已被 OTA 占用，Flyway 版本是全仓全局的）与「已发布库已应用到 `20260913.1030`」这组冲突
 * 长期不可见——直到 S14-6d 在真实 deploy 栈上启动后端才以「检测到未获批准的乱序迁移，拒绝启动」暴露。
 * 本用例把那条路径固化成每次构建都会跑的真库断言：
 * <ol>
 *   <li>用**不含**这两条目录迁移的历史迁移集合先迁到 S13 已发布基线 {@value #PUBLISHED_S13_VERSION}
 *       （真实存量库就是这么来的：它们的 1030 先于这两条脚本存在），从而**复现**两条目录迁移被判 IGNORED；</li>
 *   <li>用与生产**同一个** {@link FlywayCompatibilityConfiguration#migrateWithCompatibility(Flyway)} 策略
 *       迁到最新，断言补执行成功、无遗留 IGNORED、且第二次迁移幂等；</li>
 *   <li>断言目录事实（四档 + 不可变修订版 -1 + 维度行）与 S14 新增表都已就位。</li>
 * </ol>
 *
 * <p>用独占容器而不是共享容器：本用例要在同一个库里制造「已发布基线」历史，绝不能污染共享夹具库。
 */
@Testcontainers
class FlywayUpgradePathTests {

    /** 独占迁移容器：本用例需要自己的 flyway_schema_history 起点。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("s14_upgrade_path")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** 与生产 `application.yml` 一致的迁移目录闭集（`db/migration-test` 是测试专用，不参与升级路径）。 */
    private static final String[] LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry",
            "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export",
            "classpath:db/migration/dashboard", "classpath:db/migration/ota", "classpath:db/migration/integration"
    };

    /** S13 已发布库的最末版本：S14 的两条目录迁移在它之前，因而在存量库中被判 IGNORED。 */
    private static final String PUBLISHED_S13_VERSION = "20260913.1030";

    /** 生产占位符（创建应用角色用），与 `application.yml` 同键。 */
    private static final String APP_PASSWORD = "thingslink";

    /** 本用例临时复制的迁移目录名（与生产 locations 的模块名一一对应）。 */
    private static final List<String> MIGRATION_DIRECTORIES = List.of(
            "support", "project", "device", "telemetry", "alarm", "task",
            "rule", "iam", "enduser", "export", "dashboard", "ota", "integration");

    /** 被受控白名单允许补执行的两条 S14 目录迁移（脚本名，用于断言历史行）。 */
    private static final List<String> S14_CATALOG_SCRIPTS = List.of(
            "V20260912_0230__plan_catalog.sql",
            "V20260912_0240__plan_catalog_product_revision_1_seed.sql");

    /** S14 必须新增的事实表（目录、订阅、订单、资源包、退款、支付失败）。 */
    private static final List<String> S14_TABLES = List.of(
            "sys_plan", "sys_plan_revision", "sys_plan_entitlement", "sys_plan_revision_dimension",
            "sys_tenant_subscription", "sys_tenant_order", "sys_tenant_resource_package",
            "sys_tenant_refund", "sys_tenant_order_payment_failure");

    /**
     * 已发布库（20260913.1030）在白名单补执行后升级到最新，且目录与 S14 表全部就位、二次迁移幂等。
     *
     * @throws SQLException 目录观察失败
     */
    @Test
    void publishedDatabaseUpgradesThroughApprovedOutOfOrderMigrations(@TempDir Path staging)
            throws Exception {
        // 1. 用「历史迁移集合」（不含两条 S14 目录迁移）迁到 S13 已发布基线：
        //    真实存量库正是这种历史——它们的 20260913.1030 早于这两条脚本进入源码。
        Flyway published = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(stageHistoricalMigrations(staging))
                .placeholders(Map.of("app_role_password", APP_PASSWORD))
                .target(PUBLISHED_S13_VERSION)
                .load();
        published.migrate();
        // 先证明「历史迁移集合」真的不含这两条：否则基线阶段就会把它们应用掉，本用例失去意义。
        try (Stream<Path> stagedProject = Files.list(staging.resolve("project"))) {
            assertThat(stagedProject.map(file -> file.getFileName().toString()).toList())
                    .as("历史迁移集合不得包含 S14 目录迁移")
                    .doesNotContainAnyElementsOf(S14_CATALOG_SCRIPTS);
        }
        assertThat(appliedScripts())
                .as("基线阶段不得应用 S14 目录迁移")
                .doesNotContainAnyElementsOf(S14_CATALOG_SCRIPTS);
        try (Connection connection = owner()) {
            assertThat(tableExists(connection, "sys_plan"))
                    .as("基线库不应有 S14 目录表：这正是存量库的真实起点")
                    .isFalse();
        }

        // 2. 确认这是真实的「存量库」历史：已应用到 S13 末版，且两条目录迁移尚未出现。
        //    （以 flyway_schema_history 断言，避免依赖 Flyway 对 IGNORED 项的内部访问器。）
        assertThat(maxAppliedVersion())
                .as("基线必须是 S13 已发布末版：否则复现不出乱序")
                .isEqualTo(PUBLISHED_S13_VERSION);

        // 3. 用生产同一策略升级：白名单内的乱序被受控补执行。
        Flyway latest = flyway(null);
        assertThatCode(() -> FlywayCompatibilityConfiguration.migrateWithCompatibility(latest))
                .as("白名单补执行必须成功")
                .doesNotThrowAnyException();

        assertThat(failedMigrationCount())
                .as("升级后不得存在失败迁移")
                .isZero();
        assertThat(appliedScripts())
                .as("两条白名单迁移必须真的写入历史")
                .containsAll(S14_CATALOG_SCRIPTS);

        // 4. 目录事实与 S14 表就位。
        try (Connection connection = owner()) {
            for (String table : S14_TABLES) {
                assertThat(tableExists(connection, table)).as("缺少表 %s", table).isTrue();
            }
            assertThat(number(connection, "SELECT count(*) FROM sys_plan")).isEqualTo(4);
            assertThat(number(connection, """
                    SELECT count(*) FROM sys_plan_revision WHERE revision_code = 'product-revision-1'
                    """)).isEqualTo(4);
            assertThat(number(connection, """
                    SELECT count(*) FROM sys_plan_revision_dimension
                    """)).as("冻结维度行必须随目录一起补执行").isPositive();
        }

        // 5. 幂等：再跑一次不新增迁移历史，也不产生失败迁移。
        long historyBefore = schemaHistoryCount();
        FlywayCompatibilityConfiguration.migrateWithCompatibility(flyway(null));
        assertThat(schemaHistoryCount()).as("重复升级不得新增迁移历史").isEqualTo(historyBefore);
        assertThat(failedMigrationCount()).as("重复升级不得产生失败迁移").isZero();
    }

    /**
     * 把仓库迁移目录复制到临时目录，并剔除两条 S14 目录迁移，构造「历史迁移集合」。
     *
     * <p>迁移文件的校验和只取决于内容，因此这里复制的脚本与被剔除脚本之外的校验和与生产完全一致，
     * 阶段二可以无缝接着用真实 classpath 集合升级。
     *
     * @param staging 临时目录
     * @return Flyway 的 {@code filesystem:} 位置数组
     * @throws Exception 读取或复制失败
     */
    private static String[] stageHistoricalMigrations(Path staging) throws Exception {
        Path repo = repositoryRoot();
        for (String directory : MIGRATION_DIRECTORIES) {
            Path source = repo.resolve("things-link/things-link-" + directory
                    + "/src/main/resources/db/migration/" + directory);
            if (!Files.isDirectory(source)) {
                continue;
            }
            Path target = Files.createDirectories(staging.resolve(directory));
            try (Stream<Path> files = Files.list(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (S14_CATALOG_SCRIPTS.contains(file.getFileName().toString())) {
                        continue;
                    }
                    Files.copy(file, target.resolve(file.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return MIGRATION_DIRECTORIES.stream()
                .map(directory -> "filesystem:" + staging.resolve(directory).toAbsolutePath())
                .toArray(String[]::new);
    }

    /**
     * 同时兼容从仓库根目录、后端 reactor、bootstrap 模块与 IDE 启动测试。
     *
     * @return 同时包含 docs 与 things-link 的仓库根目录
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs"))
                    && Files.isRegularFile(candidate.resolve("things-link/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("无法定位 ThingsLink 仓库根目录");
    }

    /**
     * 构造指向独占容器的 Flyway。
     *
     * @param target 目标版本；{@code null} 表示迁到最新
     * @return Flyway 实例（与生产同样的目录与占位符）
     */
    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(LOCATIONS)
                .placeholders(Map.of("app_role_password", APP_PASSWORD));
        // 注意：`target(null)` 不等于「不设 target」——Flyway 会在 getTarget() 里对 null 调 endsWith 而抛
        // NullPointerException；只有确实要限定版本时才调用它（生产装配同样不设 target）。
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    /**
     * 读取迁移历史中已成功应用的最大版本。
     *
     * @return 最大版本号
     * @throws SQLException 查询失败
     */
    private static String maxAppliedVersion() throws SQLException {
        try (Connection connection = owner();
             PreparedStatement query = connection.prepareStatement("SELECT version FROM flyway_schema_history"
                     + " WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
             ResultSet result = query.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    /**
     * 统计失败迁移条数。
     *
     * @return 失败条数
     * @throws SQLException 查询失败
     */
    private static long failedMigrationCount() throws SQLException {
        return number(owner(), "SELECT count(*) FROM flyway_schema_history WHERE NOT success");
    }

    /**
     * 读取已成功应用的白名单脚本。
     *
     * @return 已应用脚本名
     * @throws SQLException 查询失败
     */
    private static List<String> appliedScripts() throws SQLException {
        try (Connection connection = owner();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank
                     """);
             ResultSet result = query.executeQuery()) {
            List<String> scripts = new ArrayList<>();
            while (result.next()) {
                scripts.add(result.getString(1));
            }
            return scripts;
        }
    }

    /**
     * 读取迁移历史行数（用于幂等断言）。
     *
     * @return 历史行数
     * @throws SQLException 查询失败
     */
    private static long schemaHistoryCount() throws SQLException {
        return number(owner(), "SELECT count(*) FROM flyway_schema_history");
    }

    /** @return 独占容器 owner 连接 */
    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    /**
     * 判断表是否存在。
     *
     * @param connection 连接
     * @param table 表名
     * @return 存在时为 {@code true}
     * @throws SQLException 查询失败
     */
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        // 注意：`SELECT count(*) FROM to_regclass(...)` 永远返回 1 行（标量函数在 FROM 里仍产生一行），
        // 因此必须直接判定返回值是否为 NULL，而不是数行数。
        try (PreparedStatement query = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            query.setQueryTimeout(5);
            query.setString(1, "public." + table);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    /**
     * 单值计数查询。
     *
     * @param connection 连接
     * @param sql SQL
     * @return 结果
     * @throws SQLException 查询失败
     */
    private static long number(Connection connection, String sql) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }
}
