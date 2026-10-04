package com.things.link.dashboard.infrastructure.qualification;

import com.things.link.dashboard.application.publication.DashboardHostCompatibility;
import com.things.link.dashboard.application.publication.DashboardHostQualificationDescriptor;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement;
import com.things.link.dashboard.application.publication.DashboardRegisteredHostQualification;
import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实PG的全库投影/权限/预算测试；最小事实夹具只验证查询合同，不冒充业务发布或真实宿主资格。
 * 完整生产迁移及发布围栏由HostDeploymentAdmissionTests另验；真实制品由1b回执证明。
 */
@OwnedTestContainers({"DATABASE"})
class HostCompatibilityInventoryTests {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(DockerImageName
            .parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("host_inventory").withUsername("thingslink").withPassword("thingslink");
    static { DATABASE.start(); }
    private final JdbcHostCompatibilityInventory inventory = new JdbcHostCompatibilityInventory();
    private static DashboardRegisteredHostQualification target(String version) {
        return new DashboardRegisteredHostQualification(new DashboardHostQualificationDescriptor("tc.webapp-host/v1", version,
                Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(DashboardPublicationEligibilityRequirement.ComponentKind.TEXT, version.equals("1.0.0") ? "1.0.0" : "1.0.1"),
                Map.of()), "a".repeat(64), "b".repeat(64));
    }
    @BeforeAll static void schema() throws Exception {
        try (var connection = connection(false)) {
            var sql = connection.createStatement();
            sql.execute("CREATE TABLE app_application(id uuid,tenant_id uuid,project_id uuid,current_version_id uuid,deleted_at timestamptz)");
            sql.execute("CREATE TABLE app_application_version(id uuid,tenant_id uuid,project_id uuid,application_id uuid,snapshot jsonb)");
            sql.execute("CREATE TABLE dash_dashboard(id uuid,tenant_id uuid,project_id uuid,current_version_id uuid,deleted_at timestamptz)");
            sql.execute("CREATE TABLE dash_dashboard_version(id uuid,tenant_id uuid,project_id uuid,dashboard_id uuid,schema_version text,required_components jsonb,required_resources jsonb)");
            sql.execute("CREATE TABLE dash_share_token(id uuid,tenant_id uuid,project_id uuid,dashboard_id uuid,dashboard_version_id uuid,host_compatibility jsonb,revoked_at timestamptz,expires_at timestamptz)");
            sql.execute("CREATE ROLE tc_inventory_reader NOLOGIN");
            sql.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO tc_inventory_reader");
            for (String table : new String[]{"app_application", "app_application_version", "dash_dashboard", "dash_dashboard_version", "dash_share_token"}) {
                sql.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                sql.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            }
            connection.commit();
        }
    }
    @BeforeEach void data() throws Exception {
        try (var connection = connection(false)) {
            connection.createStatement().execute("TRUNCATE app_application,app_application_version,dash_dashboard,dash_dashboard_version,dash_share_token");
            seed(connection); seed(connection); connection.commit();
        }
    }
    @Test void scansAllProjectsAndThreeEntrancesWithoutWriting() throws Exception {
        try (var connection = connection(true)) {
            var report = inventory.inspect(connection, target("1.1.0"));
            assertThat(report.compatible()).isTrue();
            assertThat(report.inspected()).containsExactlyInAnyOrderEntriesOf(Map.of(DashboardHostCompatibility.Kind.APPLICATION, 2L,
                    DashboardHostCompatibility.Kind.DASHBOARD, 2L, DashboardHostCompatibility.Kind.SHARE, 2L));
            assertThat(report.target().artifactDigest()).isEqualTo("a".repeat(64));
            assertThat(connection.isReadOnly()).isTrue();
            connection.rollback();
        }
    }
    @Test void runtimeRoleCannotReportAnRlsFilteredEmptySetAsGlobalPass() throws Exception {
        try (var connection = connection(true)) {
            connection.createStatement().execute("SET LOCAL ROLE tc_inventory_reader");
            var rows = connection.createStatement().executeQuery("SELECT count(*) FROM app_application"); rows.next();
            assertThat(rows.getLong(1)).isZero();
            assertThatThrownBy(() -> inventory.inspect(connection, target("1.1.0")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("HOST_PREFLIGHT_OPERATOR_REQUIRED");
        }
    }
    @Test void limitsNeverYieldPartialPassAndExamplesRemainExplicitlyTruncated() throws Exception {
        try (var connection = connection(true)) {
            var report = new JdbcHostCompatibilityInventory(100, 100000, 2).inspect(connection, target("1.0.0"));
            assertThat(report.compatible()).isFalse(); assertThat(report.incompatibleCount()).isEqualTo(6);
            assertThat(report.examples()).hasSize(2); assertThat(report.examplesTruncated()).isTrue();
            connection.rollback();
            assertThatThrownBy(() -> new JdbcHostCompatibilityInventory(5, 100000, 2).inspect(connection, target("1.1.0")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("HOST_PREFLIGHT_BUDGET_EXCEEDED");
            connection.rollback();
            assertThatThrownBy(() -> new JdbcHostCompatibilityInventory(100, 10, 2).inspect(connection, target("1.1.0")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("HOST_PREFLIGHT_BUDGET_EXCEEDED");
        }
    }
    @Test void noTransactionAndOldSnapshotIsolationAreRejected() throws Exception {
        try (var connection = connection(false)) {
            connection.setAutoCommit(true);
            assertThatThrownBy(() -> inventory.inspect(connection, target("1.1.0"))).isInstanceOf(IllegalArgumentException.class);
            connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThatThrownBy(() -> inventory.inspect(connection, target("1.1.0")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("HOST_PREFLIGHT_TRANSACTION_REQUIRED");
        }
    }
    @Test void inactiveEntrancesAreExcludedButMissingReferencedMetadataFailsClosed() throws Exception {
        try (var connection = connection(false)) {
            var sql = connection.createStatement();
            sql.execute("UPDATE app_application SET deleted_at=now() WHERE id=(SELECT id FROM app_application LIMIT 1)");
            sql.execute("UPDATE dash_share_token SET expires_at=now()-interval '1 minute' WHERE id=(SELECT id FROM dash_share_token LIMIT 1)");
            sql.execute("UPDATE dash_share_token SET revoked_at=now() WHERE expires_at>now()");
            var report = inventory.inspect(connection, target("1.1.1"));
            assertThat(report.inspected().get(DashboardHostCompatibility.Kind.APPLICATION)).isEqualTo(1L);
            assertThat(report.inspected().getOrDefault(DashboardHostCompatibility.Kind.SHARE,0L)).isZero();
            assertThat(report.compatible()).isTrue();
            sql.execute("DELETE FROM app_application_version WHERE id=(SELECT current_version_id FROM app_application WHERE deleted_at IS NULL)");
            var broken = inventory.inspect(connection, target("1.1.1"));
            assertThat(broken.compatible()).isFalse(); assertThat(broken.incompatibleCount()).isEqualTo(1);
            assertThat(broken.examples().getFirst().reason()).isEqualTo(DashboardHostCompatibility.Reason.INVALID_METADATA);
            connection.rollback();
        }
    }
    private static Connection connection(boolean readOnly) throws Exception {
        var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        connection.setAutoCommit(false); connection.setReadOnly(readOnly); return connection;
    }
    private static void seed(Connection connection) throws Exception {
        UUID tenant=UUID.randomUUID(), project=UUID.randomUUID(), app=UUID.randomUUID(), av=UUID.randomUUID(), dash=UUID.randomUUID(), dv=UUID.randomUUID();
        String components="[{\"kind\":\"TEXT\",\"componentVersion\":\"1.0.1\"}]";
        String range="{\"minInclusive\":\"1.1.0\",\"maxExclusive\":\"1.1.2\"}";
        try (var sql=connection.prepareStatement("INSERT INTO app_application VALUES (?,?,?,?,NULL)")) {
            sql.setObject(1,app); sql.setObject(2,tenant); sql.setObject(3,project); sql.setObject(4,av); sql.executeUpdate();
        }
        try (var sql=connection.prepareStatement("INSERT INTO app_application_version VALUES (?,?,?,?,?::jsonb)")) {
            sql.setObject(1,av); sql.setObject(2,tenant); sql.setObject(3,project); sql.setObject(4,app);
            sql.setString(5,"{\"formatVersion\":\"tc.application/v1\",\"hostCompatibility\":"+range+",\"requiredSchemas\":[\"tc.dashboard/v1\"],\"requiredComponents\":"+components+",\"requiredResources\":[]}"); sql.executeUpdate();
        }
        try (var sql=connection.prepareStatement("INSERT INTO dash_dashboard VALUES (?,?,?,?,NULL)")) {
            sql.setObject(1,dash); sql.setObject(2,tenant); sql.setObject(3,project); sql.setObject(4,dv); sql.executeUpdate();
        }
        try (var sql=connection.prepareStatement("INSERT INTO dash_dashboard_version VALUES (?,?,?,?,'tc.dashboard/v1',?::jsonb,'[]')")) {
            sql.setObject(1,dv); sql.setObject(2,tenant); sql.setObject(3,project); sql.setObject(4,dash); sql.setString(5,components); sql.executeUpdate();
        }
        try (var sql=connection.prepareStatement("INSERT INTO dash_share_token VALUES (?,?,?,?,?,?::jsonb,NULL,now()+interval '1 hour')")) {
            sql.setObject(1,UUID.randomUUID()); sql.setObject(2,tenant); sql.setObject(3,project); sql.setObject(4,dash); sql.setObject(5,dv); sql.setString(6,range); sql.executeUpdate();
        }
    }
}
