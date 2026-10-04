package com.things.link.access;

import static com.things.link.access.AccessTwoPackagedRolesStartupTests.awaitStartup;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.freePort;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.runPython;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.startJar;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.stop;
import static org.assertj.core.api.Assertions.assertThat;

import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.testing.tls.TestTlsMaterial;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** 同候选双 JAR 与真实 TLS 客户端核验 TCP 上报、接入强杀和重连去重。 */
class AccessPackagedTcpTlsTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "things-link.access.packaged-jar", matches = ".+")
    void tcpTlsDeviceReportsAcrossAccessKillAndReconnect() throws Exception {
        Path accessJar = Path.of(System.getProperty("things-link.access.packaged-jar")).toAbsolutePath();
        Path platformJar = Path.of(System.getProperty("things-link.platform.packaged-jar", "")).toAbsolutePath();
        assertThat(accessJar).isRegularFile();
        assertThat(platformJar).isRegularFile();
        Path checkout = TestTlsMaterial.checkoutRoot(Path.of(""));
        Path tls = TestTlsMaterial.ensure(checkout);
        SSLContext clientTls = clientTls(tls.resolve(TestTlsMaterial.CERT));
        Path temporary = Files.createTempDirectory("thingslink-packaged-tcp-").toRealPath();
        Path privateDirectory = temporary.resolve("private");
        Process platform = null;
        Process access = null;
        try {
            Path scripts = checkout.resolve("deploy/acceptance/scripts");
            runPython(scripts.resolve("prepare.py"), "--directory", privateDirectory.toString());
            Files.writeString(privateDirectory.resolve(".env"),
                    "MAIL_SMTP_HOST=smtp.example.test\nMAIL_SMTP_PORT=465\n"
                            + "MAIL_SMTP_USERNAME=test@example.test\nMAIL_SMTP_PASSWORD=test-smtp-secret\n"
                            + "MAIL_SMTP_TLS_MODE=ssl\n", java.nio.file.StandardOpenOption.APPEND);
            runPython(scripts.resolve("prepare-backend.py"), "--directory", privateDirectory.toString(),
                    "--origin", "https://acceptance.example", "--storage-origin", "https://acceptance.example:8066",
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
                 var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
                 var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))
                         .withStartupTimeout(Duration.ofMinutes(2))) {
                postgres.start();
                redis.start();
                kafka.start();
                List<NewTopic> topics = Files.readAllLines(checkout.resolve(
                                "deploy/acceptance/scripts/topics.tsv")).stream()
                        .filter(row -> !row.isBlank()).map(row -> row.split("\\t"))
                        .map(parts -> new NewTopic(parts[0], Integer.parseInt(parts[1]), (short) 1)).toList();
                assertThat(topics).hasSize(24);
                try (AdminClient admin = AdminClient.create(Map.of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
                    admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
                }
                int platformPort = freePort(), accessPort = freePort(), tcpPort = freePort();
                platform = startJar(platformJar,
                        privateDirectory.resolve("application-acceptance.properties"), platformPort,
                        temporary.resolve("platform.log"), postgres, redis,
                        "--spring.flyway.url=" + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres),
                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                        "--spring.kafka.listener.auto-startup=true",
                        "--things-link.outbox.publisher.enabled=true");
                awaitStartup(platform, temporary.resolve("platform.log"), "Started ThingsLinkApplication");
                UUID deviceId = AccessPackagedHttpsDeviceAuthTests.seedDevice(postgres, TransportProtocol.TCP);
                seedCommandDefinition(postgres, deviceId, "reboot", 300);
                seedCommandDefinition(postgres, deviceId, "reboot_fast", 10);
                var manager = AccessPackagedHttpsDeviceAuthTests.seedManager(postgres, deviceId);
                HttpClient http = HttpClient.newHttpClient();
                String bearer = AccessPackagedHttpsDeviceAuthTests.loginAndSelectProject(
                        http, platformPort, manager);
                access = startAccess(accessJar, privateDirectory, temporary.resolve("access.log"),
                        accessPort, tcpPort, tls, postgres, redis, kafka);
                awaitStartup(access, temporary.resolve("access.log"), "设备面 TCP/TLS 接入已启动");
                assertProcessMetrics(http, platformPort, "things-link");
                assertProcessMetrics(http, accessPort, "things-link-access");
                UUID firstMessage = Uuid7.generate();
                String firstReport = report(firstMessage);
                UUID pendingCommand;
                UUID pendingReply = Uuid7.generate();
                long killedPid;
                long originalGeneration;
                try (SSLSocket socket = authenticated(clientTls, tcpPort)) {
                    assertAccepted(socket, firstReport, firstMessage);
                    UUID commandId = submitCommand(http, platformPort, manager.projectId(), deviceId, bearer);
                    assertThat(read(socket, DeviceAccessTcpFrameType.DOWNLINK)
                            .path("commandId").asString()).isEqualTo(commandId.toString());
                    UUID replyId = Uuid7.generate();
                    write(socket, DeviceAccessTcpFrameType.REPLY,
                            "{\"commandId\":\"" + commandId + "\",\"messageId\":\"" + replyId
                                    + "\",\"occurredAt\":\"" + Instant.now()
                                    + "\",\"status\":\"SUCCESS\",\"output\":{}}");
                    assertThat(read(socket, DeviceAccessTcpFrameType.ACCEPTED)
                            .path("commandId").asString()).isEqualTo(commandId.toString());
                    awaitCommandStatus(postgres, commandId, "SUCCEEDED");
                    pendingCommand = submitCommand(http, platformPort, manager.projectId(), deviceId, bearer);
                    assertThat(read(socket, DeviceAccessTcpFrameType.DOWNLINK)
                            .path("commandId").asString()).isEqualTo(pendingCommand.toString());
                    awaitCommandStatus(postgres, pendingCommand, "DISPATCHED");
                    originalGeneration = sessionGeneration(postgres, deviceId);
                    killedPid = access.pid();
                    access.destroyForcibly();
                    assertThat(access.waitFor(5, TimeUnit.SECONDS)).isTrue();
                    access = null;
                }
                AccessPackagedHttpsDeviceAuthTests.awaitBusinessFact(postgres, deviceId, 1);
                assertThat(acceptanceCount(postgres, firstMessage)).isEqualTo(1);
                assertThat(platform.isAlive()).isTrue();
                UUID offlineCommand = submitCommand(http, platformPort, manager.projectId(),
                        deviceId, bearer, "reboot_fast");
                HttpResponse<String> management = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + platformPort + "/api/v1/projects"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(management.statusCode()).isIn(401, 403);
                access = startAccess(accessJar, privateDirectory, temporary.resolve("access-restarted.log"),
                        accessPort, tcpPort, tls, postgres, redis, kafka);
                awaitStartup(access, temporary.resolve("access-restarted.log"),
                        "设备面 TCP/TLS 接入已启动");
                assertThat(access.pid()).isNotEqualTo(killedPid);
                awaitAttemptFailure(postgres, offlineCommand, 1, "DISPATCH_PENDING_TIMEOUT");
                try (SSLSocket socket = authenticated(clientTls, tcpPort)) {
                    assertThat(sessionGeneration(postgres, deviceId)).isGreaterThan(originalGeneration);
                    var redelivery = read(socket, DeviceAccessTcpFrameType.DOWNLINK);
                    assertThat(redelivery.path("commandId").asString())
                            .isEqualTo(offlineCommand.toString());
                    assertThat(redelivery.path("attempt").asInt()).isEqualTo(2);
                    UUID offlineReply = Uuid7.generate();
                    write(socket, DeviceAccessTcpFrameType.REPLY,
                            "{\"commandId\":\"" + offlineCommand + "\",\"messageId\":\"" + offlineReply
                                    + "\",\"occurredAt\":\"" + Instant.now()
                                    + "\",\"status\":\"SUCCESS\",\"output\":{}}");
                    assertThat(read(socket, DeviceAccessTcpFrameType.ACCEPTED)
                            .path("commandId").asString()).isEqualTo(offlineCommand.toString());
                    awaitCommandStatus(postgres, offlineCommand, "SUCCEEDED");
                    String pendingReplyBody = "{\"commandId\":\"" + pendingCommand
                            + "\",\"messageId\":\"" + pendingReply
                            + "\",\"occurredAt\":\"" + Instant.now()
                            + "\",\"status\":\"SUCCESS\",\"output\":{}}";
                    write(socket, DeviceAccessTcpFrameType.REPLY, pendingReplyBody);
                    assertThat(read(socket, DeviceAccessTcpFrameType.ACCEPTED)
                            .path("commandId").asString()).isEqualTo(pendingCommand.toString());
                    awaitCommandStatus(postgres, pendingCommand, "SUCCEEDED");
                    write(socket, DeviceAccessTcpFrameType.REPLY, pendingReplyBody);
                    assertThat(read(socket, DeviceAccessTcpFrameType.ACCEPTED)
                            .path("messageId").asString()).isEqualTo(pendingReply.toString());
                    assertThat(replyFactCount(postgres, pendingCommand, pendingReply)).isEqualTo(1);
                    assertAccepted(socket, firstReport, firstMessage);
                    UUID secondMessage = Uuid7.generate();
                    assertAccepted(socket, report(secondMessage), secondMessage);
                    AccessPackagedHttpsDeviceAuthTests.awaitBusinessFact(postgres, deviceId, 2);
                    assertThat(acceptanceCount(postgres, secondMessage)).isEqualTo(1);
                }
                assertThat(acceptanceCount(postgres, firstMessage)).isEqualTo(1);
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

    private static Process startAccess(Path jar, Path directory, Path log, int accessPort, int tcpPort,
            Path tls, PostgreSQLContainer<?> postgres, GenericContainer<?> redis, KafkaContainer kafka)
            throws Exception {
        return startJar(jar, directory.resolve("application-device-access.properties"), accessPort,
                log, postgres, redis,
                "--things-link.access.http.port=" + accessPort,
                "--things-link.access.tcp.enabled=true",
                "--things-link.access.tcp.port=" + tcpPort,
                "--things-link.access.tls.certificate=" + tls.resolve(TestTlsMaterial.CERT).toUri(),
                "--things-link.access.tls.private-key=" + tls.resolve(TestTlsMaterial.KEY).toUri(),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--spring.kafka.listener.auto-startup=true");
    }

    private static SSLSocket authenticated(SSLContext tls, int port) throws Exception {
        SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket("localhost", port);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.setSoTimeout(10_000);
        socket.startHandshake();
        String auth = "{\"projectKey\":\"cutover_project\",\"deviceKey\":\"cutover_device\","
                + "\"secret\":\"device-test-secret\"}";
        write(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, auth);
        assertThat(read(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE).path("status").asString())
                .isEqualTo("OK");
        return socket;
    }

    private static void assertAccepted(SSLSocket socket, String report, UUID messageId) throws Exception {
        write(socket, DeviceAccessTcpFrameType.UPLINK, report);
        assertThat(read(socket, DeviceAccessTcpFrameType.ACCEPTED).path("messageId").asString())
                .isEqualTo(messageId.toString());
    }

    private static String report(UUID messageId) {
        return "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + Instant.now()
                + "\",\"payload\":{\"temperature\":26.5}}";
    }

    private static void write(SSLSocket socket, DeviceAccessTcpFrameType type, String body) throws Exception {
        socket.getOutputStream().write(DeviceAccessTcpFrameCodec.encode(
                type, body.getBytes(StandardCharsets.UTF_8)));
        socket.getOutputStream().flush();
    }

    private static tools.jackson.databind.JsonNode read(SSLSocket socket,
            DeviceAccessTcpFrameType expected) throws Exception {
        var frame = new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame();
        assertThat(frame).isNotNull();
        assertThat(frame.type()).isEqualTo(expected);
        return JSON.readTree(frame.payload());
    }

    private static int acceptanceCount(PostgreSQLContainer<?> postgres, UUID messageId) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement(
                     "SELECT count(*) FROM dev_access_request WHERE message_id=?")) {
            query.setObject(1, messageId);
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private static void assertProcessMetrics(HttpClient http, int port, String application) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("application=\"" + application + "\"");
        if ("things-link-access".equals(application)) {
            assertThat(response.body()).contains("device_access_tcp_consumer_ready{");
        } else {
            assertThat(response.body()).doesNotContain("device_access_tcp_consumer_ready{");
        }
    }

    private static int replyFactCount(PostgreSQLContainer<?> postgres, UUID commandId,
            UUID replyMessageId) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("""
                     SELECT count(*) FROM ts_device_command_attempt
                      WHERE command_id=? AND reply_message_id=?
                     """)) {
            query.setObject(1, commandId);
            query.setObject(2, replyMessageId);
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private static long sessionGeneration(PostgreSQLContainer<?> postgres, UUID deviceId) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("""
                     SELECT generation FROM dev_connection
                      WHERE device_id=? AND protocol='TCP'
                      ORDER BY generation DESC LIMIT 1
                     """)) {
            query.setObject(1, deviceId);
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    static void seedCommandDefinition(PostgreSQLContainer<?> postgres, UUID deviceId,
            String key, int timeoutSeconds) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var lookup = connection.prepareStatement(
                     "SELECT tenant_id,project_id,device_type_id FROM dev_device WHERE id=?")) {
            lookup.setObject(1, deviceId);
            try (var row = lookup.executeQuery()) {
                assertThat(row.next()).isTrue();
                try (var insert = connection.prepareStatement("""
                        INSERT INTO dev_command_definition
                          (id,tenant_id,project_id,device_type_id,command_key,name,
                           input_schema,output_schema,timeout_seconds)
                        VALUES (?,?,?,?,?,'重启','{}','{}',?)
                        """)) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, row.getObject(1, UUID.class));
                    insert.setObject(3, row.getObject(2, UUID.class));
                    insert.setObject(4, row.getObject(3, UUID.class));
                    insert.setString(5, key);
                    insert.setInt(6, timeoutSeconds);
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
            }
        }
    }

    static UUID submitCommand(HttpClient http, int platformPort, UUID projectId,
            UUID deviceId, String bearer) throws Exception {
        return submitCommand(http, platformPort, projectId, deviceId, bearer, "reboot");
    }

    private static UUID submitCommand(HttpClient http, int platformPort, UUID projectId,
            UUID deviceId, String bearer, String commandKey) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + platformPort + "/api/v1/projects/" + projectId
                                + "/devices/" + deviceId + "/commands"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", bearer)
                .header("Idempotency-Key", "tcp-packaged-" + UUID.randomUUID())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"commandKey\":\"" + commandKey + "\",\"input\":{}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return UUID.fromString(JSON.readTree(response.body()).path("id").asString());
    }

    static void awaitCommandStatus(PostgreSQLContainer<?> postgres, UUID commandId,
            String status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String observed = "missing";
        while (System.nanoTime() < deadline) {
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                 var query = connection.prepareStatement(
                         "SELECT status FROM ts_device_command WHERE id=?")) {
                query.setObject(1, commandId);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        observed = row.getString(1);
                        if (status.equals(observed)) return;
                    }
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("TCP command status=" + observed + ", expected=" + status);
    }

    private static void awaitAttemptFailure(PostgreSQLContainer<?> postgres, UUID commandId,
            int attempt, String code) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String observed = "missing";
        while (System.nanoTime() < deadline) {
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                 var query = connection.prepareStatement("""
                         SELECT status,error_code FROM ts_device_command_attempt
                          WHERE command_id=? AND attempt_no=?
                         """)) {
                query.setObject(1, commandId);
                query.setInt(2, attempt);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        observed = row.getString(1) + "/" + row.getString(2);
                        if (("FAILED/" + code).equals(observed)) return;
                    }
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("TCP command attempt=" + observed + ", expected=FAILED/" + code);
    }

    private static SSLContext clientTls(Path certificate) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null);
        try (var input = Files.newInputStream(certificate)) {
            trust.setCertificateEntry("loopback", CertificateFactory.getInstance("X.509")
                    .generateCertificate(input));
        }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }
}
