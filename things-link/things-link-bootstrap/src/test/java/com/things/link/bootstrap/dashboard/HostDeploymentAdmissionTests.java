package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.infrastructure.qualification.JdbcDashboardHostDeployment;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0147真实PG准入/角色/多连接锁/升级；临时探针只测围栏，不冒充真实业务发布。 */
class HostDeploymentAdmissionTests extends AbstractIntegrationTest {
    private static final String EXCLUSIVE = "SELECT pg_advisory_xact_lock(hashtextextended('dashboard-host-deployment-v1',148))";
    private static final String ADMIT = "SELECT * FROM public.dashboard_host_admit_current()";
    @Autowired private JdbcDashboardHostDeployment deployment;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private JdbcTemplate owner;
    @Autowired private com.things.link.dashboard.infrastructure.qualification.ManagedWebAppHostQualificationAdapter host;
    @org.springframework.test.context.DynamicPropertySource
    static void hostRegistry(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("things-link.dashboard.host.registry-directory",
                () -> System.getProperty("thingslink.test.host-registry", ""));
    }

    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_host_deployment", Integer.class)).isZero();
    }
    @AfterEach void cleanup() { owner.update("DELETE FROM dash_host_deployment"); }

    @Test void admissionRequiresTransactionAndReturnsExactSelection() {
        assertThatThrownBy(deployment::admitCurrent).isInstanceOf(RuntimeException.class);
        var template = new TransactionTemplate(transactions);
        template.executeWithoutResult(status -> {
            assertThat(deployment.admitCurrent()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT current_setting('thingslink.host_revision',true)", String.class)).isEqualTo("0");
        });
        selectHost(owner, 1);
        template.setReadOnly(true);
        template.executeWithoutResult(status -> {
            var selection = deployment.admitCurrent().orElseThrow();
            assertThat(selection.revision()).isEqualTo(1);
            assertThat(selection.hostVersion()).isEqualTo("1.1.0");
            assertThat(selection.artifactDigest()).isEqualTo("a".repeat(64));
            assertThat(selection.sourceDigest()).isEqualTo("b".repeat(64));
        });
        assertThat(jdbc.queryForObject("SELECT current_setting('thingslink.host_revision',true)", String.class)).isNullOrEmpty();
    }

    /** 真实1a制品经生产装配与运行角色准入读取；不把该用例认作业务发布或浏览器激活。 */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "thingslink.test.host-registry", matches = ".+")
    void actualRegisteredIdentityIsSelectedThroughSpringTransaction() {
        var actual = host.findRegistered("1.1.0").orElseThrow();
        owner.update("INSERT INTO dash_host_deployment VALUES (1,1,'1.1.0',?,?,?,clock_timestamp())",
                actual.artifactDigest(), actual.sourceDigest(), UUID.randomUUID());
        var template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        template.executeWithoutResult(status -> {
            assertThat(host.current().orElseThrow().hostVersion()).isEqualTo("1.1.0");
            assertThat(jdbc.queryForObject("SELECT current_setting('thingslink.host_revision',true)", String.class)).isEqualTo("1");
        });
        owner.update("UPDATE dash_host_deployment SET source_digest=?", "b".repeat(64));
        template.executeWithoutResult(status -> assertThat(host.current()).isEmpty());
        assertThat(jdbc.queryForObject("SELECT current_setting('thingslink.host_revision',true)", String.class)).isNullOrEmpty();
    }

    @Test void runtimeRoleCannotChangeSelectionAndTriggersCoverAllEntrances() {
        selectHost(owner, 1);
        for (String command : new String[]{"DELETE FROM dash_host_deployment", "TRUNCATE dash_host_deployment",
                "UPDATE dash_host_deployment SET revision=2"}) {
            assertThatThrownBy(() -> jdbc.execute(command)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname IN ('app_application_host_fence_trg',"
                + "'dash_dashboard_host_fence_trg','dash_share_token_host_fence_trg') AND tgenabled='O'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'dash_host_deployment','INSERT')", Boolean.class)).isFalse();
    }

    @Test void sharedAdmissionAndExclusiveTransitionBlockEachOtherUntilTransactionEnds() throws Exception {
        try (Connection publisher = connection(true); Connection operator = connection(false)) {
            publisher.createStatement().execute(ADMIT);
            operator.createStatement().execute("SET LOCAL lock_timeout='150ms'");
            assertThatThrownBy(() -> operator.createStatement().execute(EXCLUSIVE))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
            operator.rollback(); publisher.commit();
            operator.createStatement().execute(EXCLUSIVE);
            publisher.createStatement().execute("SET LOCAL ROLE thingslink_app");
            publisher.createStatement().execute("SET LOCAL lock_timeout='150ms'");
            assertThatThrownBy(() -> publisher.createStatement().execute(ADMIT))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
            publisher.rollback(); operator.commit();
            publisher.createStatement().execute("SET LOCAL ROLE thingslink_app");
            assertThat(publisher.createStatement().execute(ADMIT)).isTrue();
            publisher.commit();
        }
    }

    @Test void legacyWriterIsRejectedAfterActivationButWithdrawalStillWorks() throws Exception {
        try (Connection writer = connection(true)) {
            writer.createStatement().execute("CREATE TEMP TABLE host_fence_probe(current_version_id uuid)");
            writer.createStatement().execute("CREATE TRIGGER host_fence_probe_trg BEFORE INSERT OR UPDATE ON host_fence_probe "
                    + "FOR EACH ROW EXECUTE FUNCTION public.dashboard_host_publication_fence()");
            writer.createStatement().execute("INSERT INTO host_fence_probe VALUES ('00000000-0000-0000-0000-000000000001')");
            writer.commit(); // 无选择行时旧代码兼容；事务结束也没有残留协议标记。
            selectHost(owner, 1);
            writer.createStatement().execute("SET LOCAL ROLE thingslink_app");
            assertThatThrownBy(() -> writer.createStatement().execute("UPDATE host_fence_probe SET current_version_id='00000000-0000-0000-0000-000000000002'"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("55000"));
            writer.rollback();
            writer.createStatement().execute("SET LOCAL ROLE thingslink_app");
            writer.createStatement().execute(ADMIT);
            writer.createStatement().execute("UPDATE host_fence_probe SET current_version_id='00000000-0000-0000-0000-000000000002'");
            writer.commit();
            writer.createStatement().execute("SET LOCAL ROLE thingslink_app");
            writer.createStatement().execute("UPDATE host_fence_probe SET current_version_id=NULL");
            writer.commit();
        }
    }

    @Test void migrationKeepsPopulatedLegacyFactsAndCreatesNoImplicitActivation() throws Exception {
        Path migration = Path.of("../things-link-dashboard/src/main/resources/db/migration/dashboard/V20260920_0110__host_deployment_admission.sql");
        String schema = "host_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection(false)) {
            var statement = connection.createStatement();
            statement.execute("CREATE SCHEMA " + schema);
            for (String table : new String[]{"app_application", "dash_dashboard"}) {
                statement.execute("CREATE TABLE " + schema + "." + table + "(current_version_id uuid)");
                statement.execute("INSERT INTO " + schema + "." + table + " VALUES ('00000000-0000-0000-0000-000000000001')");
            }
            statement.execute("CREATE TABLE " + schema + ".dash_share_token(id uuid)");
            statement.execute("INSERT INTO " + schema + ".dash_share_token VALUES ('00000000-0000-0000-0000-000000000002')");
            statement.execute(Files.readString(migration).replace("public.", schema + "."));
            for (String table : new String[]{"app_application", "dash_dashboard", "dash_share_token"}) {
                var result = statement.executeQuery("SELECT count(*) FROM " + schema + "." + table);
                result.next(); assertThat(result.getInt(1)).isEqualTo(1);
            }
            var result = statement.executeQuery("SELECT count(*) FROM " + schema + ".dash_host_deployment");
            result.next(); assertThat(result.getInt(1)).isZero();
            connection.rollback(); // 连同临时schema、函数及授权全部回滚。
        }
    }

    private Connection connection(boolean runtime) throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        connection.createStatement().execute("SET LOCAL statement_timeout='5s'");
        if (runtime) connection.createStatement().execute("SET LOCAL ROLE thingslink_app");
        return connection;
    }
    private static void selectHost(JdbcTemplate owner, long revision) {
        owner.update("INSERT INTO dash_host_deployment(slot,revision,host_version,artifact_digest,source_digest,operation_id,activated_at) "
                + "VALUES (1,?,'1.1.0',?,?,?,clock_timestamp())", revision, "a".repeat(64), "b".repeat(64), UUID.randomUUID());
    }
}
