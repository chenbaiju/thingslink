package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 打包后显式运行：以平台同源迁移库验证真实接入 JAR 的 RLS 拒绝与正向启动。 */
class AccessPackagedStartupQualificationTests {

    private static final String APP_PASSWORD = "access-qualification-password-123456";
    private static final String BROKER_SECRET = "access-qualification-broker-secret-long-enough-123456";

    @Test
    @EnabledIfSystemProperty(named = "things-link.access.packaged-jar", matches = ".+")
    void startsPackagedJarOnlyAfterPlatformMigrationAndRlsReadiness() throws Exception {
        Path jar = Path.of(System.getProperty("things-link.access.packaged-jar")).toAbsolutePath();
        assertThat(jar).isRegularFile();
        try (var postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("access_packaged")
                    .withUsername("thingslink")
                    .withPassword("thingslink");
             var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                     .withExposedPorts(6379)) {
            postgres.start();
            redis.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations(platformMigrationLocations())
                    .placeholders(java.util.Map.of("app_role_password", APP_PASSWORD))
                    .load().migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE public.dev_access_request DISABLE ROW LEVEL SECURITY");
            }
            ProcessResult rejected = runJar(jar, postgres, redis, false, "none");
            assertThat(rejected.exitCode()).isNotZero();
            assertThat(rejected.log()).contains("Device-access database is not ready: public.dev_access_request");

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE public.dev_access_request ENABLE ROW LEVEL SECURITY");
            }
            ProcessResult started = runJar(jar, postgres, redis, true, "none");
            assertThat(started.started()).isTrue();
            assertThat(started.log()).contains("Started ThingsLinkAccessApplication");

            ProcessResult tcpWithoutCertificate = runJar(jar, postgres, redis, false, "tcp");
            assertThat(tcpWithoutCertificate.exitCode()).isNotZero();
            assertThat(tcpWithoutCertificate.log()).contains("TCP 接入已启用但缺少 TLS 材料");

            ProcessResult coapWithoutCertificate = runJar(jar, postgres, redis, false, "coap");
            assertThat(coapWithoutCertificate.exitCode()).isNotZero();
            assertThat(coapWithoutCertificate.log()).contains("CoAP 接入已启用但缺少 DTLS 材料");
        }
    }

    private static String[] platformMigrationLocations() throws IOException {
        var source = new YamlPropertySourceLoader().load("platformDefaults",
                new ClassPathResource("META-INF/things-link/application.yml")).getFirst();
        List<String> locations = new ArrayList<>();
        for (int i = 0; ; i++) {
            String location = (String) source.getProperty("spring.flyway.locations[" + i + "]");
            if (location == null) {
                break;
            }
            locations.add(location);
        }
        assertThat(locations).contains("classpath:db/migration/device", "classpath:db/migration/support");
        return locations.toArray(String[]::new);
    }

    private static ProcessResult runJar(Path jar, PostgreSQLContainer<?> postgres,
            GenericContainer<?> redis, boolean expectStart, String protocol) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path log = Files.createTempFile("thingslink-access-packaged-", ".log");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.net.useSystemProxies=false",
                "-DsocksProxyHost=",
                "-jar", jar.toString(),
                "--server.port=" + port,
                "--things-link.access.http.port=" + port,
                "--things-link.access.http.enabled=false",
                "--things-link.access.tcp.enabled=" + "tcp".equals(protocol),
                "--things-link.access.coap.enabled=" + "coap".equals(protocol),
                "--things-link.kafka.concurrency-guard.enabled=false",
                "--spring.data.redis.host=" + loopback(redis.getHost()),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.data.redis.password=",
                "--logging.level.root=INFO");
        builder.environment().put("SPRING_PROFILES_ACTIVE", "prod");
        builder.environment().put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl().replace("localhost", "127.0.0.1"));
        builder.environment().put("SPRING_DATASOURCE_USERNAME", "thingslink_app");
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", APP_PASSWORD);
        builder.environment().put("THINGS_LINK_SECURITY_BROKER_CALLBACK_SECRET", BROKER_SECRET);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        boolean started = false;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline) {
                String output = Files.readString(log);
                if (output.contains("Started ThingsLinkAccessApplication")) {
                    started = true;
                    break;
                }
                if (!process.isAlive()) {
                    break;
                }
                Thread.sleep(250);
            }
            if (expectStart) {
                assertThat(started).as(Files.readString(log)).isTrue();
            } else {
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as(Files.readString(log)).isTrue();
            }
            return new ProcessResult(started, process.isAlive() ? -1 : process.exitValue(), Files.readString(log));
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }

    private static String loopback(String host) {
        return "localhost".equals(host) ? "127.0.0.1" : host;
    }

    private record ProcessResult(boolean started, int exitCode, String log) { }
}
