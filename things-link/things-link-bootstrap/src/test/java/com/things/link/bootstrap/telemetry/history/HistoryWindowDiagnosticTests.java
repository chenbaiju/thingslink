package com.things.link.bootstrap.telemetry.history;

import com.things.link.telemetry.domain.HistoryAggregation;
import com.things.link.telemetry.domain.HistoryGranularity;
import com.things.link.telemetry.domain.PropertyPoint;
import com.things.link.telemetry.infrastructure.persistence.JdbcPropertyPointRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2-A4c-Q5 观察性反例：以实际历史仓储和迁移中的隔离视图证明冻结查询窗口与场后零计数的边界。
 *
 * <p>只启动独立 PostgreSQL 容器，不启动 Spring、Kafka 或正式旅程，不读取共享连接配置。
 * 使用原失败的非敏感设备/消息/时点作为夹具；通过测试表示反例可复现，不表示旅程 6 已修复。</p>
 */
class HistoryWindowDiagnosticTests {
    /** 与仓库测试基线及共享环境相同版本，随机端口、独立数据卷由 Testcontainers 管理。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"));
    /** 原失败项目，只复制标识符，不接触共享业务数据。 */
    private static final UUID PROJECT = UUID.fromString("dbbf3a97-daac-4737-8f3d-255b207ce2e3");
    /** 原失败子设备。 */
    private static final UUID DEVICE = UUID.fromString("01a06352-bbc9-744c-8d24-67f2812105fd");
    /** 同一已持久化消息，用于排除“看到了其它设备或其它消息”的假阳性。 */
    private static final UUID MESSAGE = UUID.fromString("01a06352-dbb6-7c46-acd7-e674dd32c735");
    /** 写入时冻结的物模型引用，与原数据点一致。 */
    private static final UUID MODEL = UUID.fromString("d591a7ef-6153-40ff-893a-bed4f149b6d6");
    /** 首次 HTTP history 请求已经固定的排他结束时间。 */
    private static final Instant ORIGINAL_TO = Instant.parse("2026-09-02T18:12:48.948Z");
    /** 唯一原始点的设备发生时间，比首次窗口结束晚约 770 ms。 */
    private static final Instant POINT_AT = Instant.parse("2026-09-02T18:12:49.718386Z");
    /** 仅连接独立容器。 */
    private static JdbcTemplate jdbc;
    /** 保证 local GUC 与查询使用同一物理事务。 */
    private static TransactionTemplate transaction;
    /** 实际生产仓储，禁止在反例里改写历史查询谓词。 */
    private static JdbcPropertyPointRepository repository;

    /** 从受版本控制的迁移读取函数与视图原文，其余最小字段只为调用实际仓储。 */
    @BeforeAll
    static void startIsolatedDatabase() throws Exception {
        DATABASE.start();
        DriverManagerDataSource source = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new JdbcPropertyPointRepository(jdbc);
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("docs/PROGRESS.md"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("无法定位仓库根目录");
        }
        String scopeMigration = Files.readString(root.resolve("things-link/things-link-support/src/main/resources/"
                + "db/migration/support/V20260801_1300__project_row_level_security.sql"));
        String viewMigration = Files.readString(root.resolve("things-link/things-link-telemetry/src/main/resources/"
                + "db/migration/telemetry/V20260808_1000__property_history_aggregates.sql"));
        jdbc.execute(extract(scopeMigration, "CREATE OR REPLACE FUNCTION app_current_project\\(\\).*?\\$\\$;"));
        jdbc.execute("""
                CREATE TABLE ts_property_point_internal (
                    project_id uuid, device_id uuid, property_key varchar, ts timestamptz,
                    message_id uuid, data_type varchar, thing_model_version_id uuid, model_version varchar,
                    value_double double precision, value_text text, value_bool boolean, value_json jsonb,
                    quality smallint)
                """);
        jdbc.execute(extract(viewMigration, "CREATE VIEW ts_property_point WITH .*?WITH LOCAL CHECK OPTION;"));
    }

    /** 缺少完整迁移片段时失败关闭，防止复制一个已经过期的宽松替身。 */
    private static String extract(String source, String regex) {
        var matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(source);
        if (!matcher.find()) throw new IllegalStateException("所需迁移合同不完整");
        return matcher.group();
    }

    /** 只清本类创建的独立容器表；此数据源从未指向共享环境。 */
    @BeforeEach
    void resetIsolatedFixture() {
        jdbc.execute("TRUNCATE ts_property_point_internal");
    }

    /** 即使消息已经成功提交，旧窗口永远不能包含发生于 to 之后的点，等待不改变冻结请求。 */
    @Test
    void committedLaterPointCannotEnterFrozenFirstResponseWindow() {
        int capturedFirstResponse = countHistory(ORIGINAL_TO);
        persistOriginalPoint();
        assertThat(capturedFirstResponse).isZero();
        assertThat(countHistory(ORIGINAL_TO)).isZero();
        assertThat(countHistory(POINT_AT.plusNanos(1_000))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ts_property_point_internal WHERE message_id = ?",
                Integer.class, MESSAGE)).isEqualTo(1);
        System.out.println("Q5_REPRO frozen-window: first=0, persisted=1, same-window-after-commit=0, expanded-window=1");
    }

    /** 显式视图 WHERE 不属于可被 superuser 绕过的 RLS；旧诊断的无 scope 零计数不能证明物理表无点。 */
    @Test
    void superuserDoesNotBypassExplicitSecurityViewPredicate() {
        persistOriginalPoint();
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ts_property_point WHERE device_id = ?",
                Integer.class, DEVICE)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ts_property_point_internal WHERE device_id = ?",
                Integer.class, DEVICE)).isEqualTo(1);
        assertThat(countHistory(POINT_AT.plusNanos(1_000))).isEqualTo(1);
        System.out.println("Q5_REPRO security-view: superuser=true, unscoped=0, physical=1, scoped=1");
    }

    /** 原仓储采用 [from,to)；另一项目仍查不到点，诊断不能通过放宽隔离边界造绿。 */
    @Test
    void exclusiveEndAndOtherProjectRemainFailClosed() {
        persistOriginalPoint();
        assertThat(countHistory(POINT_AT)).isZero();
        int otherProjectCount = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class, UUID.randomUUID().toString());
            return repository.findHistory(PROJECT, DEVICE, "temperature", ORIGINAL_TO.minusSeconds(86400),
                    POINT_AT.plusSeconds(1), HistoryGranularity.RAW, HistoryAggregation.AVG, 2000).size();
        });
        assertThat(otherProjectCount).isZero();
        System.out.println("Q5_REPRO boundaries: exclusive-end=0, other-project=0");
    }

    /** 在正确项目的真实事务中调用生产写入端口；返回时提交已完成。 */
    private void persistOriginalPoint() {
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class, PROJECT.toString());
            repository.save(new PropertyPoint(PROJECT, DEVICE, "temperature", POINT_AT, MESSAGE, "NUMBER", MODEL,
                    "1.0.0", 22.0, null, null, null, (short) 0));
        });
    }

    /** 查询确切设备/属性/范围，并在事务结束自动释放 local 项目身份。 */
    private int countHistory(Instant to) {
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class, PROJECT.toString());
            return repository.findHistory(PROJECT, DEVICE, "temperature", ORIGINAL_TO.minusSeconds(86400), to,
                    HistoryGranularity.RAW, HistoryAggregation.AVG, 2000).size();
        });
    }

    /** 显式回收独立容器，不等待后续测试触发清理。 */
    @AfterAll
    static void stopIsolatedDatabase() {
        DATABASE.stop();
    }
}
