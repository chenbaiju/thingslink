package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.things.link.testing.tls.TestTlsMaterial;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 打包后显式运行：同一迁移库中启动不同的真实平台与接入 JAR，先验证待切流角色。 */
class AccessTwoPackagedRolesStartupTests {

    @Test
    @EnabledIfSystemProperty(named = "things-link.access.packaged-jar", matches = ".+")
    void startsPlatformMigratorThenSeparateAccessJarWithoutTraffic() throws Exception {
        Path accessJar = Path.of(System.getProperty("things-link.access.packaged-jar")).toAbsolutePath();
        Path platformJar = Path.of(System.getProperty("things-link.platform.packaged-jar", "")).toAbsolutePath();
        assertThat(accessJar).isRegularFile();
        assertThat(platformJar).isRegularFile();
        assertThat(accessJar).isNotEqualTo(platformJar);

        Path checkout = TestTlsMaterial.checkoutRoot(Path.of(""));
        Path temporary = Files.createTempDirectory("thingslink-two-roles-").toRealPath();
        Path privateDirectory = temporary.resolve("private");
        Process platform = null;
        Process access = null;
        try {
            runPython(checkout.resolve("deploy/acceptance/scripts/prepare.py"),
                    "--directory", privateDirectory.toString());
            Files.writeString(privateDirectory.resolve(".env"),
                    "MAIL_SMTP_HOST=smtp.example.test\nMAIL_SMTP_PORT=465\n"
                            + "MAIL_SMTP_USERNAME=test@example.test\nMAIL_SMTP_PASSWORD=test-smtp-secret\n"
                            + "MAIL_SMTP_TLS_MODE=ssl\n", java.nio.file.StandardOpenOption.APPEND);
            runPython(checkout.resolve("deploy/acceptance/scripts/prepare-backend.py"),
                    "--directory", privateDirectory.toString(),
                    "--origin", "https://acceptance.example",
                    "--storage-origin", "https://acceptance.example:8066",
                    "--registry-directory", temporary.resolve("registry").toString(),
                    "--log-directory", temporary.resolve("logs").toString());
            Properties generated = new Properties();
            try (var input = Files.newBufferedReader(privateDirectory.resolve(".env"))) {
                generated.load(input);
            }
            try (var postgres = new PostgreSQLContainer<>(
                        DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                                .asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName(generated.getProperty("POSTGRES_DB"))
                        .withUsername(generated.getProperty("POSTGRES_USER"))
                        .withPassword(generated.getProperty("POSTGRES_PASSWORD"));
                 var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                         .withExposedPorts(6379)) {
                postgres.start();
                redis.start();
                int platformPort = freePort();
                int accessPort = freePort();
                Path platformLog = temporary.resolve("platform.log");
                Path accessLog = temporary.resolve("access.log");

                try {
                    platform = startJar(platformJar,
                            privateDirectory.resolve("application-acceptance.properties"),
                            platformPort, platformLog, postgres, redis,
                            "--spring.flyway.url=" + jdbcUrl(postgres));
                    awaitStartup(platform, platformLog, "Started ThingsLinkApplication");
                    assertThat(platform.isAlive()).isTrue();

                    access = startJar(accessJar,
                            privateDirectory.resolve("application-device-access.properties"),
                            accessPort, accessLog, postgres, redis,
                            "--things-link.access.http.port=" + accessPort);
                    awaitStartup(access, accessLog, "Started ThingsLinkAccessApplication");
                    assertThat(access.isAlive()).isTrue();
                    assertThat(platform.isAlive()).isTrue();
                    assertPortOpen(platformPort);
                    assertPortOpen(accessPort);
                    try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                            postgres.getUsername(), postgres.getPassword());
                         var statement = connection.createStatement();
                         var result = statement.executeQuery("SELECT count(*) FROM public.flyway_schema_history")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getInt(1)).isGreaterThan(300);
                    }
                    assertThat(Files.readString(platformLog)).contains("Started ThingsLinkApplication")
                            .doesNotContain("Started ThingsLinkAccessApplication");
                    assertThat(Files.readString(accessLog)).contains("Started ThingsLinkAccessApplication")
                            .doesNotContain("Started ThingsLinkApplication");
                } finally {
                    stop(access);
                    stop(platform);
                    access = null;
                    platform = null;
                }
            }
        } finally {
            stop(access);
            stop(platform);
            try (var entries = Files.walk(temporary)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    static Process startJar(Path jar, Path configuration, int port, Path log,
            PostgreSQLContainer<?> postgres, GenericContainer<?> redis, String... roleOptions) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.net.useSystemProxies=false", "-DsocksProxyHost=",
                "-jar", jar.toString(),
                "--spring.config.additional-location=file:" + configuration,
                "--server.address=127.0.0.1", "--server.port=" + port,
                "--spring.datasource.url=" + jdbcUrl(postgres),
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--spring.data.redis.password=",
                "--spring.kafka.bootstrap-servers=127.0.0.1:9",
                "--spring.kafka.listener.auto-startup=false",
                "--spring.kafka.admin.auto-create=false",
                "--things-link.kafka.concurrency-guard.enabled=false",
                "--things-link.ingress.handoff.enabled=false",
                "--things-link.outbox.publisher.enabled=false",
                "--logging.level.org.apache.kafka=ERROR"));
        for (String option : roleOptions) {
            int equals = option.indexOf('=');
            if (equals > 0) {
                String key = option.substring(0, equals + 1);
                command.removeIf(existing -> existing.startsWith(key));
            }
            command.add(option);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("SPRING_PROFILES_ACTIVE", "prod");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return builder.start();
    }

    static String jdbcUrl(PostgreSQLContainer<?> postgres) {
        return postgres.getJdbcUrl().replace("localhost", "127.0.0.1");
    }

    static void awaitStartup(Process process, Path log, String marker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.readString(log).contains(marker)) return;
            if (!process.isAlive()) break;
            Thread.sleep(250);
        }
        List<String> relevant = Files.readAllLines(log).stream()
                .filter(line -> line.contains("ERROR") || line.contains("Caused by")
                        || line.contains("Exception") || line.contains("Unsafe production"))
                .filter(line -> !line.toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(password|secret|token).*"))
                .toList();
        String diagnostics = String.join("\n", relevant.subList(
                Math.max(0, relevant.size() - 12), relevant.size()));
        throw new IllegalStateException("Packaged role did not start: " + marker
                + ", exit=" + (process.isAlive() ? "running" : process.exitValue())
                + ", diagnostics=" + diagnostics);
    }

    static void runPython(Path script, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(script.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException("Private template generation failed: " + output);
    }

    static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertPortOpen(int port) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            assertThat(socket.isConnected()).isTrue();
        }
    }

    static void stop(Process process) throws InterruptedException {
        if (process == null) return;
        process.destroy();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
