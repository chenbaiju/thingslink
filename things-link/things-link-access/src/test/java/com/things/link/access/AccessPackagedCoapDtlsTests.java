package com.things.link.access;

import static com.things.link.access.AccessTwoPackagedRolesStartupTests.awaitStartup;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.freePort;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.runPython;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.startJar;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.stop;
import static org.assertj.core.api.Assertions.assertThat;

import com.things.link.ingestion.application.access.coap.DeviceAccessCoapAccessService;
import com.things.link.ingestion.infrastructure.protocol.coap.DeviceAccessCoapServer;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTlsContextFactory;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.testing.tls.TestTlsMaterial;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Option;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.option.MapBasedOptionRegistry;
import org.eclipse.californium.core.coap.option.OpaqueOptionDefinition;
import org.eclipse.californium.core.coap.option.StandardOptionRegistry;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConfig;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.CertificateType;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.x509.KeyManagerCertificateProvider;
import org.eclipse.californium.scandium.dtls.x509.StaticNewAdvancedCertificateVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** 同候选双 JAR 与真实 DTLS 客户端核验 CoAP 上报及接入强杀后的幂等恢复。 */
class AccessPackagedCoapDtlsTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "things-link.access.packaged-jar", matches = ".+")
    void coapDtlsReportAcrossAccessKillAndRestart() throws Exception {
        Path accessJar = Path.of(System.getProperty("things-link.access.packaged-jar")).toAbsolutePath();
        Path platformJar = Path.of(System.getProperty("things-link.platform.packaged-jar", "")).toAbsolutePath();
        assertThat(accessJar).isRegularFile();
        assertThat(platformJar).isRegularFile();
        Path checkout = TestTlsMaterial.checkoutRoot(Path.of(""));
        Path tls = TestTlsMaterial.ensure(checkout);
        Path temporary = Files.createTempDirectory("thingslink-packaged-coap-").toRealPath();
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
                int platformPort = freePort(), accessPort = freePort(), coapPort = freePort();
                platform = startJar(platformJar,
                        privateDirectory.resolve("application-acceptance.properties"), platformPort,
                        temporary.resolve("platform.log"), postgres, redis,
                        "--spring.flyway.url=" + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres),
                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                        "--spring.kafka.listener.auto-startup=true",
                        "--things-link.outbox.publisher.enabled=true");
                awaitStartup(platform, temporary.resolve("platform.log"), "Started ThingsLinkApplication");
                UUID deviceId = AccessPackagedHttpsDeviceAuthTests.seedDevice(postgres, TransportProtocol.COAP);
                AccessPackagedTcpTlsTests.seedCommandDefinition(postgres, deviceId, "reboot", 300);
                var manager = AccessPackagedHttpsDeviceAuthTests.seedManager(postgres, deviceId);
                HttpClient http = HttpClient.newHttpClient();
                String bearer = AccessPackagedHttpsDeviceAuthTests.loginAndSelectProject(
                        http, platformPort, manager);
                access = startAccess(accessJar, privateDirectory, temporary.resolve("access.log"),
                        accessPort, coapPort, tls, postgres, redis, kafka);
                awaitStartup(access, temporary.resolve("access.log"), "设备面 CoAP/DTLS 接入已启动");
                UUID messageId = Uuid7.generate();
                String body = report(messageId);
                CoapResponse first = send(coapPort, tls, DeviceAccessCoapAccessService.PROPERTY_REPORT, body);
                assertThat(first).isNotNull();
                assertThat(first.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
                assertThat(JSON.readTree(first.getPayload()).path("status").asString()).isEqualTo("ACCEPTED");
                assertThat(first.advanced().getSourceContext().getPeerIdentity()).isNotNull();
                AccessPackagedHttpsDeviceAuthTests.awaitBusinessFact(postgres, deviceId, 1);
                assertThat(acceptanceCount(postgres, messageId)).isEqualTo(1);
                UUID commandId = AccessPackagedTcpTlsTests.submitCommand(
                        http, platformPort, manager.projectId(), deviceId, bearer);
                AccessPackagedTcpTlsTests.awaitCommandStatus(postgres, commandId, "ACCEPTED");
                long killedPid = access.pid();
                access.destroyForcibly();
                assertThat(access.waitFor(5, TimeUnit.SECONDS)).isTrue();
                access = null;
                assertThat(platform.isAlive()).isTrue();
                access = startAccess(accessJar, privateDirectory, temporary.resolve("access-restarted.log"),
                        accessPort, coapPort, tls, postgres, redis, kafka);
                awaitStartup(access, temporary.resolve("access-restarted.log"),
                        "设备面 CoAP/DTLS 接入已启动");
                assertThat(access.pid()).isNotEqualTo(killedPid);
                CoapResponse replay = send(coapPort, tls, DeviceAccessCoapAccessService.PROPERTY_REPORT, body);
                assertThat(replay).isNotNull();
                assertThat(replay.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
                assertThat(JSON.readTree(replay.getPayload()).path("receivedAt").asString())
                        .isEqualTo(JSON.readTree(first.getPayload()).path("receivedAt").asString());
                assertThat(acceptanceCount(postgres, messageId)).isEqualTo(1);
                UUID freshMessage = Uuid7.generate();
                CoapResponse fresh = send(coapPort, tls, DeviceAccessCoapAccessService.PROPERTY_REPORT,
                        report(freshMessage));
                assertThat(fresh).isNotNull();
                assertThat(fresh.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
                AccessPackagedHttpsDeviceAuthTests.awaitBusinessFact(postgres, deviceId, 2);
                assertThat(acceptanceCount(postgres, freshMessage)).isEqualTo(1);
                CoapResponse claim = send(coapPort, tls, DeviceAccessCoapAccessService.COMMAND_CLAIM, "{}");
                assertThat(claim).isNotNull();
                assertThat(claim.getCode()).isEqualTo(CoAP.ResponseCode.CONTENT);
                var commands = JSON.readTree(claim.getPayload()).path("commands");
                assertThat(commands).hasSize(1);
                assertThat(commands.get(0).path("commandId").asString()).isEqualTo(commandId.toString());
                assertThat(commands.get(0).path("commandKey").asString()).isEqualTo("reboot");
                assertThat(commands.get(0).path("attempt").asInt()).isEqualTo(1);
                assertThat(commands.get(0).path("leaseExpiresAt").asString()).isNotBlank();
                AccessPackagedTcpTlsTests.awaitCommandStatus(postgres, commandId, "DISPATCHED");
                CoapResponse noMore = send(coapPort, tls, DeviceAccessCoapAccessService.COMMAND_CLAIM, "{}");
                assertThat(noMore).isNotNull();
                assertThat(noMore.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
                assertThat(noMore.getPayload()).isEmpty();
                UUID replyId = Uuid7.generate();
                String replyBody = "{\"commandId\":\"" + commandId + "\",\"messageId\":\"" + replyId
                        + "\",\"occurredAt\":\"" + Instant.now()
                        + "\",\"status\":\"SUCCESS\",\"output\":{}}";
                CoapResponse reply = send(coapPort, tls, DeviceAccessCoapAccessService.COMMAND_REPLY, replyBody);
                assertThat(reply).isNotNull();
                assertThat(reply.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
                assertThat(JSON.readTree(reply.getPayload()).path("commandId").asString())
                        .isEqualTo(commandId.toString());
                assertThat(reply.advanced().getType()).isIn(CoAP.Type.CON, CoAP.Type.NON);
                AccessPackagedTcpTlsTests.awaitCommandStatus(postgres, commandId, "SUCCEEDED");
                CoapResponse replayReply = send(coapPort, tls,
                        DeviceAccessCoapAccessService.COMMAND_REPLY, replyBody);
                assertThat(replayReply).isNotNull();
                assertThat(replayReply.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
                assertThat(replyFactCount(postgres, commandId, replyId)).isEqualTo(1);
                CoapResponse conflict = send(coapPort, tls, DeviceAccessCoapAccessService.COMMAND_REPLY,
                        replyBody.replace("\"output\":{}", "\"output\":{\"unexpected\":true}"));
                assertThat(conflict).isNotNull();
                assertThat(conflict.getCode()).isEqualTo(CoAP.ResponseCode.CONFLICT);
                assertThat(replyFactCount(postgres, commandId, replyId)).isEqualTo(1);
                UUID retransmittedMessage = Uuid7.generate();
                try (UdpLossRelay relay = new UdpLossRelay(coapPort)) {
                    CoapResponse retried = send(relay.port(), tls,
                            DeviceAccessCoapAccessService.PROPERTY_REPORT, report(retransmittedMessage));
                    assertThat(retried).isNotNull();
                    assertThat(retried.getCode()).isEqualTo(CoAP.ResponseCode.CHANGED);
                    assertThat(relay.clientApplicationPackets()).isGreaterThanOrEqualTo(2);
                    assertThat(relay.droppedServerApplicationPackets()).isPositive();
                }
                AccessPackagedHttpsDeviceAuthTests.awaitBusinessFact(postgres, deviceId, 3);
                assertThat(acceptanceCount(postgres, retransmittedMessage)).isEqualTo(1);
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

    private static Process startAccess(Path jar, Path directory, Path log, int accessPort, int coapPort,
            Path tls, PostgreSQLContainer<?> postgres, GenericContainer<?> redis, KafkaContainer kafka)
            throws Exception {
        return startJar(jar, directory.resolve("application-device-access.properties"), accessPort,
                log, postgres, redis,
                "--things-link.access.http.port=" + accessPort,
                "--things-link.access.coap.enabled=true",
                "--things-link.access.coap.port=" + coapPort,
                "--things-link.access.budget.auth-per-minute-per-device=20",
                "--things-link.access.tls.certificate=" + tls.resolve(TestTlsMaterial.CERT).toUri(),
                "--things-link.access.tls.private-key=" + tls.resolve(TestTlsMaterial.KEY).toUri(),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--spring.kafka.listener.auto-startup=true");
    }

    private static CoapResponse send(int port, Path tls, String path, String body) throws Exception {
        Configuration configuration = Configuration.createStandardWithoutFile();
        var keyManager = new DeviceAccessTlsContextFactory().serverKeyManager(
                new FileSystemResource(tls.resolve(TestTlsMaterial.CERT).toFile()),
                new FileSystemResource(tls.resolve(TestTlsMaterial.KEY).toFile()), null);
        X509Certificate certificate;
        try (var input = Files.newInputStream(tls.resolve(TestTlsMaterial.CERT))) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        DtlsConnectorConfig dtls = new DtlsConnectorConfig.Builder(configuration)
                .setAsList(DtlsConfig.DTLS_CIPHER_SUITES,
                        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                .setCertificateIdentityProvider(new KeyManagerCertificateProvider(keyManager, CertificateType.X_509))
                .setAdvancedCertificateVerifier(new StaticNewAdvancedCertificateVerifier.Builder()
                        .setTrustedCertificates(certificate)
                        .setSupportedCertificateTypes(List.of(CertificateType.X_509)).build())
                .build();
        CoapEndpoint endpoint = new CoapEndpoint.Builder()
                .setConfiguration(configuration)
                .setOptionRegistry(new MapBasedOptionRegistry(
                        StandardOptionRegistry.getDefaultOptionRegistry(),
                        new OpaqueOptionDefinition(DeviceAccessCoapServer.DEVICE_KEY_OPTION, "TC-Device-Key"),
                        new OpaqueOptionDefinition(DeviceAccessCoapServer.CREDENTIAL_OPTION, "TC-Credential")))
                .setConnector(new DTLSConnector(dtls)).build();
        String uri = "coaps://127.0.0.1:" + port + path;
        CoapClient client = new CoapClient(uri).setEndpoint(endpoint);
        Request request = Request.newPost();
        request.setURI(uri);
        request.setPayload(body.getBytes(StandardCharsets.UTF_8));
        request.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_JSON);
        request.getOptions().addOption(new Option(DeviceAccessCoapServer.DEVICE_KEY_OPTION,
                "cutover_project/cutover_device"));
        request.getOptions().addOption(new Option(DeviceAccessCoapServer.CREDENTIAL_OPTION,
                "device-test-secret"));
        try {
            return client.advanced(request);
        } finally {
            endpoint.destroy();
            client.shutdown();
        }
    }

    private static String report(UUID messageId) {
        return "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + Instant.now()
                + "\",\"payload\":{\"temperature\":26.5}}";
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

    private static int replyFactCount(PostgreSQLContainer<?> postgres, UUID commandId,
            UUID replyId) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("""
                     SELECT count(*) FROM ts_device_command_claim
                      WHERE command_id=? AND reply_message_id=?
                     """)) {
            query.setObject(1, commandId);
            query.setObject(2, replyId);
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    /** 丢失首轮加密业务响应，等客户端自动重传后才转发服务端响应。 */
    private static final class UdpLossRelay implements AutoCloseable {
        private final DatagramSocket socket;
        private final SocketAddress server;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicInteger clientApplicationPackets = new AtomicInteger();
        private final AtomicInteger droppedServerApplicationPackets = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread worker;
        private volatile SocketAddress client;

        private UdpLossRelay(int serverPort) throws Exception {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            socket = new DatagramSocket(new InetSocketAddress(loopback, 0));
            socket.setSoTimeout(250);
            server = new InetSocketAddress(loopback, serverPort);
            worker = Thread.ofVirtual().name("coap-dtls-loss-relay").start(this::forward);
        }

        private int port() {
            return socket.getLocalPort();
        }

        private int clientApplicationPackets() {
            return clientApplicationPackets.get();
        }

        private int droppedServerApplicationPackets() {
            return droppedServerApplicationPackets.get();
        }

        private void forward() {
            byte[] buffer = new byte[65_535];
            while (running.get()) {
                try {
                    DatagramPacket inbound = new DatagramPacket(buffer, buffer.length);
                    socket.receive(inbound);
                    byte[] payload = java.util.Arrays.copyOfRange(inbound.getData(),
                            inbound.getOffset(), inbound.getOffset() + inbound.getLength());
                    boolean fromServer = inbound.getSocketAddress().equals(server);
                    boolean applicationData = payload.length > 0 && (payload[0] & 0xff) == 23;
                    SocketAddress target;
                    if (fromServer) {
                        if (applicationData && clientApplicationPackets.get() < 2) {
                            droppedServerApplicationPackets.incrementAndGet();
                            continue;
                        }
                        target = client;
                    } else {
                        client = inbound.getSocketAddress();
                        if (applicationData) clientApplicationPackets.incrementAndGet();
                        target = server;
                    }
                    if (target != null) {
                        socket.send(new DatagramPacket(payload, payload.length, target));
                    }
                } catch (SocketTimeoutException ignored) {
                    // 定期检查关闭标记，不向握手或业务流量注入超时。
                } catch (SocketException exception) {
                    if (running.get()) failure.compareAndSet(null, exception);
                    return;
                } catch (Exception exception) {
                    failure.compareAndSet(null, exception);
                    return;
                }
            }
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            socket.close();
            worker.join(1_000);
            assertThat(failure.get()).isNull();
        }
    }
}
