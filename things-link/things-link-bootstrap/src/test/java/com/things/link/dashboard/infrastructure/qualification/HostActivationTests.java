package com.things.link.dashboard.infrastructure.qualification;

import com.things.link.testing.OwnedTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG、实际0110/0120迁移与1a制品；最小业务表只测激活合同，不冒充业务发布或静态浏览器交付。 */
@OwnedTestContainers({"DATABASE"})
@EnabledIfSystemProperty(named = "thingslink.test.host-registry", matches = ".+")
class HostActivationTests {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(DockerImageName
            .parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("host_activation_root").withUsername("thingslink").withPassword("test-host-activation-only");
    static { DATABASE.start(); }
    @TempDir Path temporary;
    private String database;
    private String url;
    private JdbcHostActivation activation;

    @BeforeAll static void role() throws Exception {
        try (var root = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            root.createStatement().execute("CREATE ROLE thingslink_app NOLOGIN");
        }
    }
    @BeforeEach void setup() throws Exception {
        database = "host_" + UUID.randomUUID().toString().replace("-", "");
        try (var root = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            root.createStatement().execute("CREATE DATABASE " + database);
        }
        url = "jdbc:postgresql://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(5432) + "/" + database;
        activation = new JdbcHostActivation(new ManagedWebAppHostQualificationAdapter(System.getProperty("thingslink.test.host-registry")));
        try (var connection = connection()) {
            var sql = connection.createStatement();
            sql.execute("CREATE TABLE app_application(id uuid,tenant_id uuid,project_id uuid,current_version_id uuid,deleted_at timestamptz)");
            sql.execute("CREATE TABLE app_application_version(id uuid,tenant_id uuid,project_id uuid,application_id uuid,snapshot jsonb)");
            sql.execute("CREATE TABLE dash_dashboard(id uuid,tenant_id uuid,project_id uuid,current_version_id uuid,deleted_at timestamptz)");
            sql.execute("CREATE TABLE dash_dashboard_version(id uuid,tenant_id uuid,project_id uuid,dashboard_id uuid,schema_version text,required_components jsonb,required_resources jsonb)");
            sql.execute("CREATE TABLE dash_share_token(id uuid,tenant_id uuid,project_id uuid,dashboard_id uuid,dashboard_version_id uuid,host_compatibility jsonb,revoked_at timestamptz,expires_at timestamptz)");
            for (String name : new String[]{"V20260920_0110__host_deployment_admission.sql", "V20260920_0120__host_deployment_history.sql"}) {
                sql.execute(Files.readString(Path.of("../things-link-dashboard/src/main/resources/db/migration/dashboard/" + name)));
            }
            connection.commit();
        }
    }
    @AfterEach void cleanup() throws Exception {
        // 每例独立数据库，连历史一起销毁；不禁用追加保护来伪造历史清理。
        try (var root = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            root.createStatement().execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }

    @Test void realAToBToAAndReplayKeepAtomicHistory() throws Exception {
        UUID first = UUID.randomUUID();
        try (var c = connection()) {
            assertThat(activation.preflight(c, "1.1.0").get("activationAuthorized")).isEqualTo(false); c.rollback();
            assertThat(activation.activate(c, "1.1.0", 0, first).get("replayed")).isEqualTo(false); c.commit();
            activation.activate(c, "1.1.1", 1, UUID.randomUUID()); c.commit();
            activation.activate(c, "1.1.0", 2, UUID.randomUUID()); c.commit();
            var replay = activation.activate(c, "1.1.0", 0, first); c.commit();
            assertThat(replay.get("replayed")).isEqualTo(true);
            assertThat(((Map<?, ?>) replay.get("current")).get("revision")).isEqualTo(3L);
            assertThat(((Map<?, ?>) replay.get("operation")).get("revision")).isEqualTo(1L);
            assertThatThrownBy(() -> activation.activate(c, "1.1.1", 0, first)).hasMessage("OPERATION_CONFLICT"); c.rollback();
            assertThatThrownBy(() -> activation.activate(c, "1.1.1", 2, UUID.randomUUID())).hasMessage("REVISION_CONFLICT"); c.rollback();
            assertThat(count(c, "dash_host_deployment_history")).isEqualTo(3);
            var result = c.createStatement().executeQuery("SELECT count(*) FROM dash_host_deployment_history h JOIN dash_host_deployment d USING(revision,operation_id,host_version,artifact_digest,source_digest,activated_at)");
            result.next(); assertThat(result.getInt(1)).isEqualTo(1);
            assertThat(activation.status(c, UUID.randomUUID()).get("found")).isEqualTo(false); c.rollback();
        }
    }

    @Test void cliReturnsSingleJsonForPreflightActivationAndStatus() throws Exception {
        UUID operation = UUID.randomUUID();
        for (String[] args : new String[][]{{"preflight", "1.1.0"}, {"activate", "1.1.0", "0", operation.toString()}, {"status", operation.toString()}}) {
            var output = new ByteArrayOutputStream();
            var env = new java.util.HashMap<>(environment());
            if (args[0].equals("status")) env.remove("TC_HOST_REGISTRY_DIRECTORY");
            assertThat(HostDeploymentCli.run(args, env, new PrintStream(output), ignored -> connection())).isZero();
            String json = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(json.lines().count()).isEqualTo(1);
            assertThat(json).doesNotContain(DATABASE.getPassword(), url, System.getProperty("thingslink.test.host-registry"));
            var receipt = JsonMapper.builder().build().readTree(json);
            if (args[0].equals("preflight")) assertThat(receipt.get("compatible").asBoolean()).isTrue();
            if (args[0].equals("activate")) assertThat(receipt.get("result").asString()).isEqualTo("COMMITTED");
            if (args[0].equals("status")) assertThat(receipt.get("found").asBoolean()).isTrue();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "thingslink.test.host-tools-jar", matches = ".+")
    void packagedCliRunsWithoutWebOrSmtp() throws Exception {
        UUID operation = UUID.randomUUID();
        for (String[] args : new String[][]{{"preflight", "1.1.0"}, {"activate", "1.1.0", "0", operation.toString()}, {"status", operation.toString()}}) {
            var command = new java.util.ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
            command.add("-DsocksProxyHost=host-activation-regression.invalid");
            command.add("-DsocksProxyPort=1"); command.add("-DsocksNonProxyHosts="); command.add("-jar");
            command.add(System.getProperty("thingslink.test.host-tools-jar")); command.addAll(java.util.List.of(args));
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().clear(); builder.environment().putAll(environment());
            // 即使prod开关存在也不进入Spring/SMTP启动路径；operator工具自校验输入。
            builder.environment().put("SPRING_PROFILES_ACTIVE", "prod");
            Path output = temporary.resolve(args[0] + ".json"); builder.redirectOutput(output.toFile());
            Process process = builder.start();
            try {
                assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
                String json = Files.readString(output);
                assertThat(process.exitValue()).as(json).isZero();
                assertThat(json.lines().count()).isEqualTo(1);
                var result = JsonMapper.builder().build().readTree(json);
                assertThat(result.get("result").asString()).isIn("PREFLIGHT", "COMMITTED", "STATUS");
                assertThat(json).doesNotContain(DATABASE.getPassword(), "Spring Boot", "Tomcat", "SMTP");
            } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
        }
    }

    @Test void incompatibleOrMissingTargetCannotWriteSelection() throws Exception {
        try (var c = connection()) {
            c.createStatement().execute("INSERT INTO app_application VALUES (gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),NULL)");
            c.commit(); // LEFT JOIN缺失版本必须阻塞，不能因查不到关联行跳过。
            assertThatThrownBy(() -> activation.activate(c, "1.1.0", 0, UUID.randomUUID())).hasMessage("INCOMPATIBLE"); c.rollback();
            assertThat(count(c, "dash_host_deployment")).isZero();
            assertThat(count(c, "dash_host_deployment_history")).isZero(); c.rollback();
            assertThatThrownBy(() -> activation.activate(c, "1.0.0", 0, UUID.randomUUID())).hasMessage("HOST_UNAVAILABLE"); c.rollback();
        }
    }

    @Test void corruptedActualArtifactCannotWriteSelection() throws Exception {
        Path source = Path.of(System.getProperty("thingslink.test.host-registry"));
        Path copy = temporary.toRealPath().resolve("registry");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = copy.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
        Files.writeString(copy.resolve("hosts/1.1.0/release/index.html"), "corrupted");
        var corrupted = new JdbcHostActivation(new ManagedWebAppHostQualificationAdapter(copy.toString()));
        try (var c = connection()) {
            assertThatThrownBy(() -> corrupted.activate(c, "1.1.0", 0, UUID.randomUUID())).hasMessage("HOST_UNAVAILABLE"); c.rollback();
            assertThat(count(c, "dash_host_deployment")).isZero();
            assertThat(count(c, "dash_host_deployment_history")).isZero();
        }
    }

    @Test void historyFailureRollsBackSelectionAndHistoricalRowsAreImmutable() throws Exception {
        try (var c = connection()) {
            c.createStatement().execute("CREATE TRIGGER test_history_failure BEFORE INSERT ON dash_host_deployment_history FOR EACH STATEMENT EXECUTE FUNCTION dashboard_host_history_append_only()"); c.commit();
            assertThatThrownBy(() -> activation.activate(c, "1.1.0", 0, UUID.randomUUID())).isInstanceOf(SQLException.class); c.rollback();
            assertThat(count(c, "dash_host_deployment")).isZero();
            c.createStatement().execute("DROP TRIGGER test_history_failure ON dash_host_deployment_history"); c.commit();
            activation.activate(c, "1.1.0", 0, UUID.randomUUID()); c.commit();
            for (String sql : new String[]{"DELETE FROM dash_host_deployment_history", "UPDATE dash_host_deployment_history SET database_actor='changed'", "TRUNCATE dash_host_deployment_history"}) {
                assertThatThrownBy(() -> c.createStatement().execute(sql)).isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("55000")); c.rollback();
            }
            c.createStatement().execute("SET LOCAL ROLE thingslink_app");
            assertThatThrownBy(() -> activation.activate(c, "1.1.1", 1, UUID.randomUUID())).hasMessage("HOST_PREFLIGHT_OPERATOR_REQUIRED"); c.rollback();
            c.createStatement().execute("SET LOCAL ROLE thingslink_app");
            assertThatThrownBy(() -> c.createStatement().executeQuery("SELECT * FROM dash_host_deployment_history")).isInstanceOf(SQLException.class); c.rollback();
        }
    }

    @Test void concurrentOperatorsHaveExactlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String version : new String[]{"1.1.0", "1.1.1"}) tasks.add(workers.submit(() -> {
                try (var c = connection()) {
                    ready.countDown(); assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                    try { activation.activate(c, version, 0, UUID.randomUUID()); c.commit(); return true; }
                    catch (JdbcHostActivation.Rejected rejected) { c.rollback(); assertThat(rejected).hasMessage("REVISION_CONFLICT"); return false; }
                }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(tasks.get(0).get(10, TimeUnit.SECONDS)).isNotEqualTo(tasks.get(1).get(10, TimeUnit.SECONDS));
        }
        try (var c = connection()) { assertThat(count(c, "dash_host_deployment_history")).isEqualTo(1); }
    }

    @Test void commitAcknowledgementLossReportsUnknownThenStatusResolvesBothOutcomes() throws Exception {
        long expected = 0;
        for (boolean reallyCommit : new boolean[]{false, true}) {
            UUID operation = UUID.randomUUID(); var bytes = new ByteArrayOutputStream();
            int code = HostDeploymentCli.run(new String[]{"activate", "1.1.0", Long.toString(expected), operation.toString()},
                    environment(), new PrintStream(bytes), env -> proxyCommit(connection(), reallyCommit));
            assertThat(code).isEqualTo(75);
            var result = JsonMapper.builder().build().readTree(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(result.get("result").asString()).isEqualTo("UNKNOWN");
            try (var c = connection()) {
                assertThat(activation.status(c, operation).get("found")).isEqualTo(reallyCommit); c.rollback();
                assertThat(count(c, "dash_host_deployment_history")).isEqualTo(reallyCommit ? 1 : 0);
            }
            if (reallyCommit) expected++;
        }
    }

    private Map<String, String> environment() {
        return Map.of("TC_HOST_OPERATOR_JDBC_URL", url, "TC_HOST_OPERATOR_USER", DATABASE.getUsername(),
                "TC_HOST_OPERATOR_PASSWORD", DATABASE.getPassword(), "TC_HOST_REGISTRY_DIRECTORY", System.getProperty("thingslink.test.host-registry"));
    }
    private Connection connection() throws SQLException {
        Connection c = DriverManager.getConnection(url, DATABASE.getUsername(), DATABASE.getPassword());
        c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); return c;
    }
    private static int count(Connection c, String table) throws SQLException {
        try (var statement = c.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); return rows.getInt(1); }
    }
    private static Connection proxyCommit(Connection delegate, boolean reallyCommit) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("commit")) {
                if (reallyCommit) delegate.commit();
                throw new SQLException("injected commit acknowledgement loss", "08006");
            }
            try { return method.invoke(delegate, args); } catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
        });
    }
}
