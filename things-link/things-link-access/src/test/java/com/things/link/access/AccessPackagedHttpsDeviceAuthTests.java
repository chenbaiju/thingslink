package com.things.link.access;

import static com.things.link.access.AccessTwoPackagedRolesStartupTests.awaitStartup;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.freePort;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.runPython;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.startJar;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.stop;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** 显式打包资格：候选 HTTPS 反代到真实接入 JAR，验证设备认证与代理信任。 */
class AccessPackagedHttpsDeviceAuthTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "things-link.access.packaged-jar", matches = ".+")
    void candidateHttpsRouteAuthenticatesRealDeviceOnAccessJar() throws Exception {
        Path accessJar = Path.of(System.getProperty("things-link.access.packaged-jar")).toAbsolutePath();
        Path platformJar = Path.of(System.getProperty("things-link.platform.packaged-jar", "")).toAbsolutePath();
        assertThat(accessJar).isRegularFile();
        assertThat(platformJar).isRegularFile();
        Path checkout = TestTlsMaterial.checkoutRoot(Path.of(""));
        Path tls = TestTlsMaterial.ensure(checkout);
        Path temporary = Files.createTempDirectory("thingslink-packaged-https-").toRealPath();
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
            runPython(scripts.resolve("prepare-backend.py"),
                    "--directory", privateDirectory.toString(),
                    "--origin", "https://acceptance.example",
                    "--storage-origin", "https://acceptance.example:8066",
                    "--registry-directory", temporary.resolve("registry").toString(),
                    "--log-directory", temporary.resolve("logs").toString());
            runPython(scripts.resolve("prepare-cutover.py"),
                    "--directory", privateDirectory.toString());
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
                 Network network = Network.newNetwork()) {
                postgres.start();
                redis.start();
                int platformPort = freePort();
                int accessPort = freePort();
                Testcontainers.exposeHostPorts(platformPort, accessPort);
                String nginx = Files.readString(privateDirectory.resolve("nginx-device-access.conf.template"))
                        .replace("@HOST@", "localhost")
                        .replace("@ORIGIN@", "https://localhost")
                        .replace("@HTTPS_PORT@", "443")
                        .replace("@BASE@", "/tmp/site")
                        .replace("        proxy_bind 172.29.240.1;\n", "")
                        .replace("http://172.29.240.1:8080",
                                "http://host.testcontainers.internal:" + platformPort)
                        .replace("http://172.29.240.1:8081",
                                "http://host.testcontainers.internal:" + accessPort);
                try (var proxy = new GenericContainer<>("nginx:1.28-alpine")
                        .withNetwork(network).withExposedPorts(443)
                        .withCopyToContainer(Transferable.of(nginx.getBytes(StandardCharsets.UTF_8), 0444),
                                "/etc/nginx/conf.d/default.conf")
                        .withCopyToContainer(Transferable.of(Files.readAllBytes(tls.resolve(TestTlsMaterial.CERT)),
                                0444), "/tmp/site/tls/fullchain.pem")
                        .withCopyToContainer(Transferable.of(Files.readAllBytes(tls.resolve(TestTlsMaterial.KEY)),
                                0444), "/tmp/site/tls/privkey.pem")
                        .withCopyToContainer(Transferable.of("console".getBytes(StandardCharsets.UTF_8), 0444),
                                "/tmp/site/console/index.html")
                        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)))) {
                    proxy.start();
                    try {
                        platform = startJar(platformJar,
                                privateDirectory.resolve("application-acceptance.properties"), platformPort,
                                temporary.resolve("platform.log"), postgres, redis,
                                "--spring.flyway.url=" + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres));
                        awaitStartup(platform, temporary.resolve("platform.log"), "Started ThingsLinkApplication");
                        UUID deviceId = seedDevice(postgres);
                        ManagerFixture manager = seedManager(postgres, deviceId);
                        UUID mqttDeviceId = seedMqttSibling(postgres, deviceId);
                        access = startJar(accessJar,
                                privateDirectory.resolve("application-device-access.properties"), accessPort,
                                temporary.resolve("access.log"), postgres, redis,
                                "--things-link.access.http.port=" + accessPort,
                                "--things-link.access.budget.auth-per-minute-per-device=20",
                                // Testcontainers 的宿主端口转发以回环地址连接 JVM，生产宿主须核对实际来源。
                                "--things-link.security.trusted-proxy-addresses=127.0.0.1");
                        awaitStartup(access, temporary.resolve("access.log"), "Started ThingsLinkAccessApplication");
                        try (HttpClient http = HttpClient.newBuilder()
                                .sslContext(AccessHttpsCutoverRoutingTests.trust(tls))
                                .connectTimeout(Duration.ofSeconds(5)).build()) {
                            String url = "https://localhost:" + proxy.getMappedPort(443)
                                    + "/device-access/v1/property/report";
                            HttpResponse<String> missing = post(http, url, null, "");
                            assertThat(missing.statusCode()).isEqualTo(401);
                            assertThat(missing.body()).contains("AUTH_REQUIRED");
                            HttpResponse<String> valid = post(http, url, "device-test-secret", "");
                            assertThat(valid.statusCode()).isEqualTo(400);
                            assertThat(valid.body()).contains("PAYLOAD_INVALID")
                                    .doesNotContain("AUTH_FAILED", "AUTH_REQUIRED");
                            UUID messageId = com.things.link.shared.id.Uuid7.generate();
                            String report = "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\""
                                    + Instant.now() + "\",\"payload\":{\"temperature\":26.5}}";
                            HttpResponse<String> unavailable = post(http, url, "device-test-secret", report);
                            assertThat(unavailable.statusCode()).isEqualTo(503);
                            assertThat(unavailable.body()).contains("HANDOFF_UNAVAILABLE");
                            assertThat(acceptanceCount(postgres, messageId)).isZero();
                            try (KafkaContainer kafka = new KafkaContainer(
                                    DockerImageName.parse("apache/kafka:4.1.0"))
                                    .withStartupTimeout(Duration.ofMinutes(2))) {
                                kafka.start();
                                List<NewTopic> topics = Files.readAllLines(checkout.resolve(
                                                "deploy/acceptance/scripts/topics.tsv")).stream()
                                        .filter(row -> !row.isBlank())
                                        .map(row -> row.split("\\t"))
                                        .map(parts -> new NewTopic(parts[0], Integer.parseInt(parts[1]), (short) 1))
                                        .toList();
                                assertThat(topics).hasSize(24);
                                try (AdminClient admin = AdminClient.create(Map.of(
                                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                        kafka.getBootstrapServers()))) {
                                    admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
                                }
                                stop(access);
                                access = startJar(accessJar,
                                        privateDirectory.resolve("application-device-access.properties"), accessPort,
                                        temporary.resolve("access-recovered.log"), postgres, redis,
                                        "--things-link.access.http.port=" + accessPort,
                                        "--things-link.access.budget.auth-per-minute-per-device=20",
                                        "--things-link.security.trusted-proxy-addresses=127.0.0.1",
                                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());
                                awaitStartup(access, temporary.resolve("access-recovered.log"),
                                        "Started ThingsLinkAccessApplication");
                                HttpResponse<String> accepted = post(http, url, "device-test-secret", report);
                                assertThat(accepted.statusCode()).isEqualTo(202);
                                assertThat(accepted.body()).contains(messageId.toString());
                                assertThat(acceptanceCount(postgres, messageId)).isEqualTo(1);
                                assertKafkaRecord(kafka, deviceId, messageId);
                                stop(platform);
                                platform = startJar(platformJar,
                                        privateDirectory.resolve("application-acceptance.properties"), platformPort,
                                        temporary.resolve("platform-consuming.log"), postgres, redis,
                                        "--spring.flyway.url=" + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres),
                                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                                        "--spring.kafka.listener.auto-startup=true");
                                awaitStartup(platform, temporary.resolve("platform-consuming.log"),
                                        "Started ThingsLinkApplication");
                                awaitBusinessFact(postgres, deviceId, 1);
                                List<UUID> concurrentMessages = new ArrayList<>();
                                List<CompletableFuture<HttpResponse<String>>> deviceResponses = new ArrayList<>();
                                List<CompletableFuture<HttpResponse<String>>> managementResponses = new ArrayList<>();
                                for (int index = 0; index < 4; index++) {
                                    UUID concurrentMessage = com.things.link.shared.id.Uuid7.generate();
                                    concurrentMessages.add(concurrentMessage);
                                    String body = "{\"messageId\":\"" + concurrentMessage
                                            + "\",\"occurredAt\":\"" + Instant.now()
                                            + "\",\"payload\":{\"temperature\":26.5}}";
                                    deviceResponses.add(http.sendAsync(HttpRequest.newBuilder(URI.create(url))
                                                    .timeout(Duration.ofSeconds(10))
                                                    .header("Content-Type", "application/json")
                                                    .header("X-TC-Device-Key", "cutover_project/cutover_device")
                                                    .header("X-TC-Device-Secret", "device-test-secret")
                                                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                                            HttpResponse.BodyHandlers.ofString()));
                                    managementResponses.add(http.sendAsync(HttpRequest.newBuilder(URI.create(
                                                    "https://localhost:" + proxy.getMappedPort(443)
                                                            + "/api/v1/projects"))
                                                    .timeout(Duration.ofSeconds(10)).GET().build(),
                                            HttpResponse.BodyHandlers.ofString()));
                                }
                                CompletableFuture.allOf(deviceResponses.toArray(CompletableFuture[]::new))
                                        .get(15, TimeUnit.SECONDS);
                                CompletableFuture.allOf(managementResponses.toArray(CompletableFuture[]::new))
                                        .get(15, TimeUnit.SECONDS);
                                for (int index = 0; index < 4; index++) {
                                    assertThat(deviceResponses.get(index).join().statusCode()).isEqualTo(202);
                                    assertThat(deviceResponses.get(index).join().body())
                                            .contains(concurrentMessages.get(index).toString());
                                    assertThat(managementResponses.get(index).join().statusCode()).isIn(401, 403);
                                    assertThat(acceptanceCount(postgres, concurrentMessages.get(index)))
                                            .isEqualTo(1);
                                }
                                awaitBusinessFact(postgres, deviceId, 5);
                                String managementBearer = loginAndSelectProject(http, platformPort, manager);
                                String projectUrl = "https://localhost:" + proxy.getMappedPort(443)
                                        + "/api/v1/projects";
                                UUID managementMessageId = com.things.link.shared.id.Uuid7.generate();
                                String managementReport = "{\"messageId\":\"" + managementMessageId
                                        + "\",\"occurredAt\":\"" + Instant.now()
                                        + "\",\"payload\":{\"temperature\":26.5}}";
                                CompletableFuture<HttpResponse<String>> managementRead = http.sendAsync(
                                        HttpRequest.newBuilder(URI.create(projectUrl))
                                                .timeout(Duration.ofSeconds(10))
                                                .header("Authorization", managementBearer).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                                CompletableFuture<HttpResponse<String>> managementWrite = http.sendAsync(
                                        HttpRequest.newBuilder(URI.create(projectUrl + "/" + manager.projectId()))
                                                .timeout(Duration.ofSeconds(10))
                                                .header("Authorization", managementBearer)
                                                .header("Content-Type", "application/json")
                                                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                                        "{\"name\":\"并发读写项目\"}"))
                                                .build(), HttpResponse.BodyHandlers.ofString());
                                CompletableFuture<HttpResponse<String>> managementDevice = http.sendAsync(
                                        HttpRequest.newBuilder(URI.create(url))
                                                .timeout(Duration.ofSeconds(10))
                                                .header("Content-Type", "application/json")
                                                .header("X-TC-Device-Key", "cutover_project/cutover_device")
                                                .header("X-TC-Device-Secret", "device-test-secret")
                                                .POST(HttpRequest.BodyPublishers.ofString(managementReport)).build(),
                                        HttpResponse.BodyHandlers.ofString());
                                CompletableFuture.allOf(managementRead, managementWrite, managementDevice)
                                        .get(15, TimeUnit.SECONDS);
                                assertThat(managementRead.join().statusCode())
                                        .as("Authenticated project read: %s", managementRead.join().body())
                                        .isEqualTo(200);
                                assertThat(managementRead.join().body())
                                        .contains(manager.projectId().toString());
                                assertThat(managementWrite.join().statusCode())
                                        .as("Authenticated project update: %s", managementWrite.join().body())
                                        .isEqualTo(200);
                                assertThat(managementWrite.join().body()).contains("并发读写项目");
                                assertThat(managementDevice.join().statusCode())
                                        .as("Concurrent device report: %s", managementDevice.join().body())
                                        .isEqualTo(202);
                                assertThat(acceptanceCount(postgres, managementMessageId)).isEqualTo(1);
                                awaitBusinessFact(postgres, deviceId, 6);
                                HttpResponse<String> updatedProject = http.send(HttpRequest.newBuilder(
                                                URI.create(projectUrl)).timeout(Duration.ofSeconds(5))
                                        .header("Authorization", managementBearer).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                                assertThat(updatedProject.statusCode()).isEqualTo(200);
                                assertThat(updatedProject.body()).contains("并发读写项目");
                                long killedPlatformPid = platform.pid();
                                platform.destroyForcibly();
                                assertThat(platform.waitFor(5, TimeUnit.SECONDS)).isTrue();
                                platform = null;
                                assertThat(access.isAlive()).isTrue();
                                UUID platformFailureMessageId = com.things.link.shared.id.Uuid7.generate();
                                String platformFailureReport = "{\"messageId\":\""
                                        + platformFailureMessageId + "\",\"occurredAt\":\""
                                        + Instant.now() + "\",\"payload\":{\"temperature\":26.5}}";
                                HttpResponse<String> queuedWhilePlatformDown = post(
                                        http, url, "device-test-secret", platformFailureReport);
                                assertThat(queuedWhilePlatformDown.statusCode()).isEqualTo(202);
                                assertThat(acceptanceCount(postgres, platformFailureMessageId)).isEqualTo(1);
                                assertKafkaRecord(kafka, deviceId, platformFailureMessageId);
                                awaitBusinessFact(postgres, deviceId, 6);
                                HttpResponse<String> managementWhilePlatformDown = http.send(
                                        HttpRequest.newBuilder(URI.create(projectUrl))
                                                .timeout(Duration.ofSeconds(5))
                                                .header("Authorization", managementBearer).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                                assertThat(managementWhilePlatformDown.statusCode()).isEqualTo(502);
                                platform = startJar(platformJar,
                                        privateDirectory.resolve("application-acceptance.properties"), platformPort,
                                        temporary.resolve("platform-after-kill.log"), postgres, redis,
                                        "--spring.flyway.url="
                                                + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres),
                                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                                        "--spring.kafka.listener.auto-startup=true");
                                awaitStartup(platform, temporary.resolve("platform-after-kill.log"),
                                        "Started ThingsLinkApplication");
                                assertThat(platform.pid()).isNotEqualTo(killedPlatformPid);
                                awaitBusinessFact(postgres, deviceId, 7, Duration.ofSeconds(90));
                                HttpResponse<String> managementAfterPlatformRestart = http.send(
                                        HttpRequest.newBuilder(URI.create(projectUrl))
                                                .timeout(Duration.ofSeconds(5))
                                                .header("Authorization", managementBearer).GET().build(),
                                        HttpResponse.BodyHandlers.ofString());
                                assertThat(managementAfterPlatformRestart.statusCode()).isEqualTo(200);
                                assertThat(managementAfterPlatformRestart.body()).contains("并发读写项目");
                                HttpResponse<String> wrong = post(http, url, "wrong", "");
                                assertThat(wrong.statusCode()).isEqualTo(401);
                                assertThat(wrong.body()).contains("AUTH_FAILED");

                                String brokerConfiguration = Files.readString(privateDirectory.resolve(
                                        "runtime/emqx-base-device-access.hocon"))
                                        .replace("http://backend:8081",
                                                "http://host.testcontainers.internal:" + accessPort)
                                        + "\napi_key.bootstrap_file = \"/opt/emqx/etc/packaged-test-api-keys\"\n";
                                try (var broker = new GenericContainer<>("emqx/emqx:6.2.3")
                                        .withExposedPorts(1883, 18083)
                                        .withCopyToContainer(Transferable.of(
                                                brokerConfiguration.getBytes(StandardCharsets.UTF_8), 0444),
                                                "/opt/emqx/etc/base.hocon")
                                        .withCopyToContainer(Transferable.of(
                                                "packaged-test:packaged-test-secret:administrator\n"
                                                        .getBytes(StandardCharsets.UTF_8), 0444),
                                                "/opt/emqx/etc/packaged-test-api-keys")
                                        .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                                                .withStartupTimeout(Duration.ofSeconds(120)))) {
                                    broker.start();
                                    double callbackBefore = AccessPackagedBrokerBoundaryTests.callbackCount(
                                            http, accessPort);
                                    AccessPackagedBrokerBoundaryTests.assertUnknownDeviceDenied(broker);
                                    AccessPackagedBrokerBoundaryTests.awaitCallbackCount(
                                            http, accessPort, callbackBefore);
                                    try (MqttClient mqtt = new MqttClient(
                                            "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883),
                                            "packaged-command-" + UUID.randomUUID())) {
                                        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
                                        mqtt.setCallback(new MqttCallback() {
                                            @Override public void connectionLost(Throwable cause) {}
                                            @Override public void messageArrived(String topic, MqttMessage message) {
                                                commands.add(topic + "\n" + new String(
                                                        message.getPayload(), StandardCharsets.UTF_8));
                                            }
                                            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
                                        });
                                        MqttConnectOptions mqttOptions = new MqttConnectOptions();
                                        mqttOptions.setUserName("cutover_project/cutover_mqtt");
                                        mqttOptions.setPassword("cutover-mqtt-secret".toCharArray());
                                        try {
                                            mqtt.connect(mqttOptions);
                                            mqtt.subscribe("tc/v1/cutover_project/cutover_mqtt/down/command/#", 1);
                                            stop(platform);
                                            platform = startJar(platformJar,
                                                    privateDirectory.resolve("application-acceptance.properties"),
                                                    platformPort, temporary.resolve("platform-command.log"),
                                                    postgres, redis,
                                                    "--spring.flyway.url="
                                                            + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres),
                                                    "--spring.kafka.bootstrap-servers="
                                                            + kafka.getBootstrapServers(),
                                                    "--spring.kafka.listener.auto-startup=true",
                                                    "--things-link.outbox.publisher.enabled=true",
                                                    "--things-link.ingestion.emqx-api.base-url=http://"
                                                            + broker.getHost() + ":" + broker.getMappedPort(18083),
                                                    "--things-link.ingestion.emqx-api.api-key=packaged-test",
                                                    "--things-link.ingestion.emqx-api.api-secret=packaged-test-secret");
                                            awaitStartup(platform, temporary.resolve("platform-command.log"),
                                                    "Started ThingsLinkApplication");
                                            UUID commandId = submitMqttCommand(http, projectUrl,
                                                    manager.projectId(), mqttDeviceId, managementBearer);
                                            String delivered = commands.poll(90, TimeUnit.SECONDS);
                                            assertThat(delivered).as("Platform MQTT command must reach device")
                                                    .isNotNull().contains(commandId.toString(), "\"commandKey\":\"reboot\"");
                                            awaitCommandStatus(postgres, commandId, "DISPATCHED");
                                            stop(access);
                                            access = startJar(accessJar,
                                                    privateDirectory.resolve("application-device-access.properties"),
                                                    accessPort, temporary.resolve("access-command-reply.log"),
                                                    postgres, redis,
                                                    "--things-link.access.http.port=" + accessPort,
                                                    "--things-link.access.budget.auth-per-minute-per-device=20",
                                                    "--things-link.security.trusted-proxy-addresses=127.0.0.1",
                                                    "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                                                    "--things-link.ingress.handoff.enabled=true",
                                                    "--things-link.ingress.handoff.broker-uri=tcp://"
                                                            + broker.getHost() + ":" + broker.getMappedPort(1883));
                                            awaitStartup(access, temporary.resolve("access-command-reply.log"),
                                                    "Broker durable handoff ingress 已连接");
                                            UUID replyMessageId = Uuid7.generate();
                                            String replyTopic = "tc/v1/cutover_project/cutover_mqtt/up/command/"
                                                    + commandId + "/reply";
                                            MqttMessage reply = new MqttMessage(("{\"messageId\":\""
                                                    + replyMessageId + "\",\"occurredAt\":\"" + Instant.now()
                                                    + "\",\"status\":\"SUCCESS\",\"output\":{}}")
                                                    .getBytes(StandardCharsets.UTF_8));
                                            reply.setQos(1);
                                            mqtt.publish(replyTopic, reply);
                                            awaitCommandStatus(postgres, commandId, "SUCCEEDED");
                                            assertReplyMessageId(postgres, commandId, replyMessageId);
                                            awaitHandoffResult(http, accessPort, "accepted");
                                            mqtt.publish(replyTopic, reply);
                                            awaitHandoffResult(http, accessPort, "duplicate");
                                            awaitCommandStatus(postgres, commandId, "SUCCEEDED");
                                            assertReplyMessageId(postgres, commandId, replyMessageId);
                                            long outageAccessPid = access.pid();
                                            access.destroyForcibly();
                                            assertThat(access.waitFor(5, TimeUnit.SECONDS)).isTrue();
                                            access = null;
                                            assertThat(platform.isAlive()).isTrue();
                                            UUID outageCommandId = submitMqttCommand(http, projectUrl,
                                                    manager.projectId(), mqttDeviceId, managementBearer);
                                            String outageDelivery = commands.poll(90, TimeUnit.SECONDS);
                                            assertThat(outageDelivery).as("Command downlink while access is stopped")
                                                    .isNotNull().contains(outageCommandId.toString(),
                                                            "\"commandKey\":\"reboot\"");
                                            awaitCommandStatus(postgres, outageCommandId, "DISPATCHED");
                                            access = startJar(accessJar,
                                                    privateDirectory.resolve("application-device-access.properties"),
                                                    accessPort, temporary.resolve("access-command-after-outage.log"),
                                                    postgres, redis,
                                                    "--things-link.access.http.port=" + accessPort,
                                                    "--things-link.access.budget.auth-per-minute-per-device=20",
                                                    "--things-link.security.trusted-proxy-addresses=127.0.0.1",
                                                    "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                                                    "--things-link.ingress.handoff.enabled=true",
                                                    "--things-link.ingress.handoff.broker-uri=tcp://"
                                                            + broker.getHost() + ":" + broker.getMappedPort(1883));
                                            awaitStartup(access, temporary.resolve("access-command-after-outage.log"),
                                                    "Broker durable handoff ingress 已连接");
                                            assertThat(access.pid()).isNotEqualTo(outageAccessPid);
                                            UUID outageReplyMessageId = Uuid7.generate();
                                            MqttMessage outageReply = new MqttMessage(("{\"messageId\":\""
                                                    + outageReplyMessageId + "\",\"occurredAt\":\"" + Instant.now()
                                                    + "\",\"status\":\"SUCCESS\",\"output\":{}}")
                                                    .getBytes(StandardCharsets.UTF_8));
                                            outageReply.setQos(1);
                                            mqtt.publish("tc/v1/cutover_project/cutover_mqtt/up/command/"
                                                    + outageCommandId + "/reply", outageReply);
                                            awaitCommandStatus(postgres, outageCommandId, "SUCCEEDED");
                                            assertReplyMessageId(postgres, outageCommandId, outageReplyMessageId);
                                            awaitHandoffResult(http, accessPort, "accepted");
                                            UUID platformOutageCommandId = submitMqttCommand(http, projectUrl,
                                                    manager.projectId(), mqttDeviceId, managementBearer);
                                            String platformOutageDelivery = commands.poll(90, TimeUnit.SECONDS);
                                            assertThat(platformOutageDelivery)
                                                    .as("Command must reach device before platform outage")
                                                    .isNotNull().contains(platformOutageCommandId.toString(),
                                                            "\"commandKey\":\"reboot\"");
                                            awaitCommandStatus(postgres, platformOutageCommandId, "DISPATCHED");
                                            long outagePlatformPid = platform.pid();
                                            platform.destroyForcibly();
                                            assertThat(platform.waitFor(5, TimeUnit.SECONDS)).isTrue();
                                            platform = null;
                                            assertThat(access.isAlive()).isTrue();
                                            HttpResponse<String> platformUnavailable = http.send(HttpRequest.newBuilder(
                                                            URI.create(projectUrl + "/" + manager.projectId()
                                                                    + "/devices/" + mqttDeviceId + "/commands/"
                                                                    + platformOutageCommandId))
                                                    .timeout(Duration.ofSeconds(5))
                                                    .header("Authorization", managementBearer).GET().build(),
                                                    HttpResponse.BodyHandlers.ofString());
                                            assertThat(platformUnavailable.statusCode()).isEqualTo(502);
                                            UUID platformOutageReplyMessageId = Uuid7.generate();
                                            MqttMessage platformOutageReply = new MqttMessage(("{\"messageId\":\""
                                                    + platformOutageReplyMessageId + "\",\"occurredAt\":\""
                                                    + Instant.now() + "\",\"status\":\"SUCCESS\",\"output\":{}}")
                                                    .getBytes(StandardCharsets.UTF_8));
                                            platformOutageReply.setQos(1);
                                            mqtt.publish("tc/v1/cutover_project/cutover_mqtt/up/command/"
                                                    + platformOutageCommandId + "/reply", platformOutageReply);
                                            awaitCommandStatus(postgres, platformOutageCommandId, "SUCCEEDED");
                                            assertReplyMessageId(postgres, platformOutageCommandId,
                                                    platformOutageReplyMessageId);
                                            platform = startJar(platformJar,
                                                    privateDirectory.resolve("application-acceptance.properties"),
                                                    platformPort, temporary.resolve("platform-command-after-outage.log"),
                                                    postgres, redis,
                                                    "--spring.flyway.url="
                                                            + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres),
                                                    "--spring.kafka.bootstrap-servers="
                                                            + kafka.getBootstrapServers(),
                                                    "--spring.kafka.listener.auto-startup=true",
                                                    "--things-link.outbox.publisher.enabled=true",
                                                    "--things-link.ingestion.emqx-api.base-url=http://"
                                                            + broker.getHost() + ":" + broker.getMappedPort(18083),
                                                    "--things-link.ingestion.emqx-api.api-key=packaged-test",
                                                    "--things-link.ingestion.emqx-api.api-secret=packaged-test-secret");
                                            awaitStartup(platform,
                                                    temporary.resolve("platform-command-after-outage.log"),
                                                    "Started ThingsLinkApplication");
                                            assertThat(platform.pid()).isNotEqualTo(outagePlatformPid);
                                            HttpResponse<String> recoveredCommand = http.send(HttpRequest.newBuilder(
                                                            URI.create(projectUrl + "/" + manager.projectId()
                                                                    + "/devices/" + mqttDeviceId + "/commands/"
                                                                    + platformOutageCommandId))
                                                    .timeout(Duration.ofSeconds(10))
                                                    .header("Authorization", managementBearer).GET().build(),
                                                    HttpResponse.BodyHandlers.ofString());
                                            assertThat(recoveredCommand.statusCode()).isEqualTo(200);
                                            assertThat(JSON.readTree(recoveredCommand.body()).path("status").asString())
                                                    .isEqualTo("SUCCEEDED");
                                            stop(platform);
                                            platform = startJar(platformJar,
                                                    privateDirectory.resolve("application-acceptance.properties"),
                                                    platformPort, temporary.resolve("platform-outbox-paused.log"),
                                                    postgres, redis,
                                                    "--spring.flyway.url="
                                                            + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres),
                                                    "--spring.kafka.bootstrap-servers="
                                                            + kafka.getBootstrapServers(),
                                                    "--spring.kafka.listener.auto-startup=true",
                                                    "--things-link.outbox.publisher.enabled=false");
                                            awaitStartup(platform, temporary.resolve("platform-outbox-paused.log"),
                                                    "Started ThingsLinkApplication");
                                            UUID pendingCommandId = submitMqttCommand(http, projectUrl,
                                                    manager.projectId(), mqttDeviceId, managementBearer);
                                            awaitCommandStatus(postgres, pendingCommandId, "ACCEPTED");
                                            assertCommandOutboxStatus(postgres, pendingCommandId, "PENDING");
                                            assertThat(commands.poll(1, TimeUnit.SECONDS))
                                                    .as("Paused Outbox must not publish before platform kill")
                                                    .isNull();
                                            long pendingPlatformPid = platform.pid();
                                            platform.destroyForcibly();
                                            assertThat(platform.waitFor(5, TimeUnit.SECONDS)).isTrue();
                                            platform = null;
                                            assertCommandOutboxStatus(postgres, pendingCommandId, "PENDING");
                                            platform = startJar(platformJar,
                                                    privateDirectory.resolve("application-acceptance.properties"),
                                                    platformPort, temporary.resolve("platform-outbox-recovered.log"),
                                                    postgres, redis,
                                                    "--spring.flyway.url="
                                                            + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres),
                                                    "--spring.kafka.bootstrap-servers="
                                                            + kafka.getBootstrapServers(),
                                                    "--spring.kafka.listener.auto-startup=true",
                                                    "--things-link.outbox.publisher.enabled=true",
                                                    "--things-link.ingestion.emqx-api.base-url=http://"
                                                            + broker.getHost() + ":" + broker.getMappedPort(18083),
                                                    "--things-link.ingestion.emqx-api.api-key=packaged-test",
                                                    "--things-link.ingestion.emqx-api.api-secret=packaged-test-secret");
                                            awaitStartup(platform,
                                                    temporary.resolve("platform-outbox-recovered.log"),
                                                    "Started ThingsLinkApplication");
                                            assertThat(platform.pid()).isNotEqualTo(pendingPlatformPid);
                                            String recoveredDelivery = commands.poll(90, TimeUnit.SECONDS);
                                            assertThat(recoveredDelivery).as("Recovered Outbox command downlink")
                                                    .isNotNull().contains(pendingCommandId.toString(),
                                                            "\"commandKey\":\"reboot\"", "\"attempt\":1");
                                            awaitCommandStatus(postgres, pendingCommandId, "DISPATCHED");
                                            assertCommandOutboxStatus(postgres, pendingCommandId, "PUBLISHED");
                                            assertCommandAttemptCount(postgres, pendingCommandId, 1);
                                            UUID pendingReplyMessageId = Uuid7.generate();
                                            MqttMessage pendingReply = new MqttMessage(("{\"messageId\":\""
                                                    + pendingReplyMessageId + "\",\"occurredAt\":\""
                                                    + Instant.now() + "\",\"status\":\"SUCCESS\",\"output\":{}}")
                                                    .getBytes(StandardCharsets.UTF_8));
                                            pendingReply.setQos(1);
                                            mqtt.publish("tc/v1/cutover_project/cutover_mqtt/up/command/"
                                                    + pendingCommandId + "/reply", pendingReply);
                                            awaitCommandStatus(postgres, pendingCommandId, "SUCCEEDED");
                                            assertReplyMessageId(postgres, pendingCommandId, pendingReplyMessageId);
                                        } finally {
                                            if (mqtt.isConnected()) mqtt.disconnectForcibly();
                                        }
                                    }
                                    long killedAccessPid = access.pid();
                                    access.destroyForcibly();
                                    assertThat(access.waitFor(5, TimeUnit.SECONDS)).isTrue();
                                    access = null;
                                    assertThat(platform.isAlive()).isTrue();
                                    AccessPackagedBrokerBoundaryTests.assertUnknownDeviceDenied(broker);
                                    HttpResponse<String> unavailableHttps = post(
                                            http, url, "device-test-secret", report);
                                    assertThat(unavailableHttps.statusCode()).isEqualTo(502);
                                    HttpResponse<String> managementDuringFailure = http.send(
                                            HttpRequest.newBuilder(URI.create(projectUrl))
                                                    .timeout(Duration.ofSeconds(5))
                                                    .header("Authorization", managementBearer).GET().build(),
                                            HttpResponse.BodyHandlers.ofString());
                                    assertThat(managementDuringFailure.statusCode()).isEqualTo(200);
                                    assertThat(managementDuringFailure.body()).contains("并发读写项目");
                                    access = startJar(accessJar,
                                            privateDirectory.resolve("application-device-access.properties"),
                                            accessPort, temporary.resolve("access-after-kill.log"), postgres, redis,
                                            "--things-link.access.http.port=" + accessPort,
                                            "--things-link.access.budget.auth-per-minute-per-device=20",
                                            "--things-link.security.trusted-proxy-addresses=127.0.0.1",
                                            "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());
                                    awaitStartup(access, temporary.resolve("access-after-kill.log"),
                                            "Started ThingsLinkAccessApplication");
                                    assertThat(access.pid()).isNotEqualTo(killedAccessPid);
                                    HttpResponse<String> replay = post(http, url, "device-test-secret", report);
                                    assertThat(replay.statusCode()).isEqualTo(202);
                                    assertThat(replay.body()).contains(messageId.toString());
                                    assertThat(acceptanceCount(postgres, messageId)).isEqualTo(1);
                                    awaitBusinessFact(postgres, deviceId, 7);
                                    UUID afterKillMessageId = com.things.link.shared.id.Uuid7.generate();
                                    String afterKillReport = "{\"messageId\":\"" + afterKillMessageId
                                            + "\",\"occurredAt\":\"" + Instant.now()
                                            + "\",\"payload\":{\"temperature\":26.5}}";
                                    HttpResponse<String> continued = post(
                                            http, url, "device-test-secret", afterKillReport);
                                    assertThat(continued.statusCode()).isEqualTo(202);
                                    assertThat(acceptanceCount(postgres, afterKillMessageId)).isEqualTo(1);
                                    awaitBusinessFact(postgres, deviceId, 8);
                                    double recoveredCallbackBefore = AccessPackagedBrokerBoundaryTests.callbackCount(
                                            http, accessPort);
                                    AccessPackagedBrokerBoundaryTests.assertUnknownDeviceDenied(broker);
                                    AccessPackagedBrokerBoundaryTests.awaitCallbackCount(
                                            http, accessPort, recoveredCallbackBefore);
                                    stop(access);
                                    access = null;
                                }
                                String originalBroker = Files.readString(privateDirectory.resolve(
                                        "runtime/emqx-base.hocon"));
                                assertThat(originalBroker).contains("http://backend:8080")
                                        .doesNotContain("http://backend:8081");
                                originalBroker = originalBroker.replace("http://backend:8080",
                                        "http://host.testcontainers.internal:" + platformPort);
                                try (var rollbackBroker = new GenericContainer<>("emqx/emqx:6.2.3")
                                        .withExposedPorts(1883, 18083)
                                        .withCopyToContainer(Transferable.of(
                                                originalBroker.getBytes(StandardCharsets.UTF_8), 0444),
                                                "/opt/emqx/etc/base.hocon")
                                        .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                                                .withStartupTimeout(Duration.ofSeconds(120)))) {
                                    rollbackBroker.start();
                                    AccessPackagedBrokerBoundaryTests.assertUnknownDeviceDenied(rollbackBroker);
                                }
                                String originalNginx = Files.readString(checkout.resolve(
                                        "deploy/acceptance/nginx.conf.template"))
                                        .replace("@HOST@", "localhost")
                                        .replace("@ORIGIN@", "https://localhost")
                                        .replace("@HTTPS_PORT@", "443")
                                        .replace("@BASE@", "/tmp/site")
                                        .replace("        proxy_bind 172.29.240.1;\n", "")
                                        .replace("http://172.29.240.1:8080",
                                                "http://host.testcontainers.internal:" + platformPort);
                                assertThat(originalNginx).contains("location ^~ /device-access/ { return 404; }")
                                        .doesNotContain("http://172.29.240.1:8081");
                                try (var rollbackProxy = new GenericContainer<>("nginx:1.28-alpine")
                                        .withNetwork(network).withExposedPorts(443)
                                        .withCopyToContainer(Transferable.of(
                                                originalNginx.getBytes(StandardCharsets.UTF_8), 0444),
                                                "/etc/nginx/conf.d/default.conf")
                                        .withCopyToContainer(Transferable.of(
                                                Files.readAllBytes(tls.resolve(TestTlsMaterial.CERT)), 0444),
                                                "/tmp/site/tls/fullchain.pem")
                                        .withCopyToContainer(Transferable.of(
                                                Files.readAllBytes(tls.resolve(TestTlsMaterial.KEY)), 0444),
                                                "/tmp/site/tls/privkey.pem")
                                        .withCopyToContainer(Transferable.of(
                                                "console".getBytes(StandardCharsets.UTF_8), 0444),
                                                "/tmp/site/console/index.html")
                                        .waitingFor(Wait.forListeningPort()
                                                .withStartupTimeout(Duration.ofSeconds(30)))) {
                                    rollbackProxy.start();
                                    String rollbackBase = "https://localhost:" + rollbackProxy.getMappedPort(443);
                                    HttpResponse<String> closed = post(http,
                                            rollbackBase + "/device-access/v1/property/report",
                                            "device-test-secret", report);
                                    assertThat(closed.statusCode()).isEqualTo(404);
                                    assertThat(closed.body()).doesNotContain("console", "HANDOFF_UNAVAILABLE");
                                    int platformManagementStatus = http.send(HttpRequest.newBuilder(
                                                    URI.create("http://127.0.0.1:" + platformPort + "/api/v1/projects"))
                                            .timeout(Duration.ofSeconds(5)).GET().build(),
                                            HttpResponse.BodyHandlers.ofString()).statusCode();
                                    int rollbackManagementStatus = http.send(HttpRequest.newBuilder(
                                                    URI.create(rollbackBase + "/api/v1/projects"))
                                            .timeout(Duration.ofSeconds(5)).GET().build(),
                                            HttpResponse.BodyHandlers.ofString()).statusCode();
                                    assertThat(rollbackManagementStatus).isEqualTo(platformManagementStatus)
                                            .isIn(401, 403);
                                    assertThat(acceptanceCount(postgres, messageId)).isEqualTo(1);
                                    awaitBusinessFact(postgres, deviceId, 8);
                                    assertThat(platform.isAlive()).isTrue();
                                }
                            }
                            assertThat(platform.isAlive()).isTrue();
                        }
                    } finally {
                        stop(access);
                        stop(platform);
                        access = null;
                        platform = null;
                    }
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

    private static HttpResponse<String> post(HttpClient http, String url, String secret, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (secret != null) request.header("X-TC-Device-Key", "cutover_project/cutover_device")
                .header("X-TC-Device-Secret", secret);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int acceptanceCount(PostgreSQLContainer<?> postgres, UUID messageId) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM dev_access_request WHERE message_id = ?")) {
            statement.setObject(1, messageId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private static UUID submitMqttCommand(HttpClient http, String projectUrl, UUID projectId,
            UUID deviceId, String bearer) throws Exception {
        HttpResponse<String> submitted = http.send(HttpRequest.newBuilder(URI.create(
                        projectUrl + "/" + projectId + "/devices/"
                                + deviceId + "/commands"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", bearer)
                .header("Idempotency-Key", "packaged-command-" + UUID.randomUUID())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"commandKey\":\"reboot\",\"input\":{}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(submitted.statusCode()).as("MQTT command admission: %s", submitted.body())
                .isEqualTo(202);
        return UUID.fromString(JSON.readTree(submitted.body()).path("id").asString());
    }

    private static void awaitCommandStatus(PostgreSQLContainer<?> postgres, UUID commandId,
            String status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String observed = "missing";
        while (System.nanoTime() < deadline) {
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                 var query = connection.prepareStatement(
                         "SELECT status FROM ts_device_command WHERE id = ?")) {
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
        throw new AssertionError("MQTT command status=" + observed + ", expected=" + status);
    }

    private static void assertReplyMessageId(PostgreSQLContainer<?> postgres, UUID commandId,
            UUID replyMessageId) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement(
                     "SELECT reply_message_id FROM ts_device_command_attempt WHERE command_id = ?")) {
            query.setObject(1, commandId);
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1, UUID.class)).isEqualTo(replyMessageId);
                assertThat(row.next()).isFalse();
            }
        }
    }

    private static void assertCommandOutboxStatus(PostgreSQLContainer<?> postgres, UUID commandId,
            String status) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("""
                     SELECT event.status, event.published_at
                       FROM ts_device_command_attempt attempt
                       JOIN sys_outbox_event event ON event.id = attempt.outbox_event_id
                      WHERE attempt.command_id = ? AND attempt.attempt_no = 1
                     """)) {
            query.setObject(1, commandId);
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(status);
                if ("PENDING".equals(status)) assertThat(row.getTimestamp(2)).isNull();
                else assertThat(row.getTimestamp(2)).isNotNull();
                assertThat(row.next()).isFalse();
            }
        }
    }

    private static void assertCommandAttemptCount(PostgreSQLContainer<?> postgres, UUID commandId,
            int expected) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement(
                     "SELECT attempt_count FROM ts_device_command WHERE id = ?")) {
            query.setObject(1, commandId);
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(expected);
                assertThat(row.next()).isFalse();
            }
        }
    }

    private static void awaitHandoffResult(HttpClient http, int port, String disposition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> metrics = http.send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/actuator/prometheus"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(metrics.statusCode()).isEqualTo(200);
            for (String line : metrics.body().split("\\R")) {
                if (line.startsWith("thingslink_ingress_handoff_total{")
                        && line.contains("result=\"" + disposition + "\"")) {
                    if (Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) >= 1.0) return;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("MQTT handoff result missing: " + disposition);
    }

    static String loginAndSelectProject(HttpClient http, int platformPort,
            ManagerFixture manager) throws Exception {
        String base = "http://127.0.0.1:" + platformPort + "/api/v1/auth/";
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "login"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + manager.email()
                        + "\",\"password\":\"" + manager.password() + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as("Manager login status").isEqualTo(200);
        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.split(";", 2)[0]).findFirst().orElseThrow();
        String loginToken = JSON.readTree(login.body()).path("accessToken").asString();
        HttpResponse<String> selected = http.send(HttpRequest.newBuilder(URI.create(base + "switch-project"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + loginToken).header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"projectId\":\"" + manager.projectId() + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(selected.statusCode()).as("Manager project selection status").isEqualTo(200);
        return "Bearer " + JSON.readTree(selected.body()).path("accessToken").asString();
    }

    record ManagerFixture(UUID projectId, String email, String password) {}

    static ManagerFixture seedManager(PostgreSQLContainer<?> postgres, UUID deviceId) throws Exception {
        UUID tenantId, projectId;
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var lookup = connection.prepareStatement(
                     "SELECT tenant_id,project_id FROM dev_device WHERE id = ?")) {
            lookup.setObject(1, deviceId);
            try (var result = lookup.executeQuery()) {
                assertThat(result.next()).isTrue();
                tenantId = result.getObject(1, UUID.class);
                projectId = result.getObject(2, UUID.class);
            }
            UUID accountId = UUID.randomUUID();
            String email = "cutover-" + accountId + "@example.test";
            String password = "Cutover-Manager-2026!";
            try (var account = connection.prepareStatement("INSERT INTO sys_account"
                    + " (id,email,password_hash,display_name,email_verified_at)"
                    + " VALUES (?,?,?,'隔离切流管理员',now())")) {
                account.setObject(1, accountId);
                account.setString(2, email);
                account.setString(3, PasswordEncoderFactories.createDelegatingPasswordEncoder()
                        .encode(password));
                account.executeUpdate();
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES ('"
                        + UUID.randomUUID() + "','" + tenantId + "','" + accountId + "')");
                statement.executeUpdate("INSERT INTO sys_project_member(id,project_id,account_id,role)"
                        + " VALUES ('" + UUID.randomUUID() + "','" + projectId + "','" + accountId
                        + "','OWNER')");
            }
            return new ManagerFixture(projectId, email, password);
        }
    }

    private static void assertKafkaRecord(KafkaContainer kafka, UUID deviceId, UUID messageId) {
        Properties consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "packaged-handoff-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of("tc.device.uplink.normalized"));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofSeconds(1))) {
                    if (record.value().contains(messageId.toString())) {
                        assertThat(record.key()).isEqualTo(deviceId.toString());
                        return;
                    }
                }
            }
            throw new AssertionError("Accepted message was absent from the normalized Kafka topic");
        }
    }

    static void awaitBusinessFact(PostgreSQLContainer<?> postgres, UUID deviceId,
            int expectedPoints) throws Exception {
        awaitBusinessFact(postgres, deviceId, expectedPoints, Duration.ofSeconds(30));
    }

    private static void awaitBusinessFact(PostgreSQLContainer<?> postgres, UUID deviceId,
            int expectedPoints, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String observed = "no database response";
        while (System.nanoTime() < deadline) {
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                 var shadow = connection.prepareStatement(
                         "SELECT reported::text FROM dev_shadow WHERE device_id = ?");
                 var points = connection.prepareStatement(
                         "SELECT count(*) FROM public.ts_property_point_internal WHERE device_id = ?")) {
                shadow.setObject(1, deviceId);
                points.setObject(1, deviceId);
                try (var current = shadow.executeQuery(); var history = points.executeQuery()) {
                    boolean shadowReady = current.next() && current.getString(1).contains("26.5");
                    boolean pointReady = history.next();
                    int pointCount = pointReady ? history.getInt(1) : -1;
                    observed = "shadowReady=" + shadowReady + ", points=" + pointCount
                            + ", expectedPoints=" + expectedPoints;
                    pointReady = pointCount == expectedPoints;
                    if (shadowReady && pointReady) return;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Platform consumer fact mismatch: " + observed);
    }

    static UUID seedDevice(PostgreSQLContainer<?> postgres) throws Exception {
        return seedDevice(postgres, TransportProtocol.HTTP);
    }

    static UUID seedDevice(PostgreSQLContainer<?> postgres, TransportProtocol protocol) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("device-test-secret".getBytes(StandardCharsets.UTF_8)));
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), type = UUID.randomUUID(), device = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sys_tenant (id,name) VALUES ('" + tenant + "','隔离切流租户')");
            statement.executeUpdate("INSERT INTO sys_project (id,tenant_id,name,project_key) VALUES ('"
                    + project + "','" + tenant + "','隔离切流项目','cutover_project')");
            statement.executeUpdate("INSERT INTO dev_type (id,tenant_id,project_id,type_key,name,device_kind,"
                    + "access_protocol,network_type,status) VALUES ('" + type + "','" + tenant + "','"
                    + project + "','cutover_type','隔离切流类型','DIRECT','STANDARD','WIFI','PUBLISHED')");
            statement.executeUpdate("INSERT INTO dev_device (id,tenant_id,project_id,device_type_id,device_key,"
                    + "name,status) VALUES ('" + device + "','" + tenant + "','" + project + "','"
                    + type + "','cutover_device','隔离切流设备','ONLINE')");
            statement.executeUpdate("INSERT INTO dev_credential (id,tenant_id,project_id,device_id,auth_type,"
                    + "credential_hash,display_name) VALUES ('" + UUID.randomUUID() + "','" + tenant + "','"
                    + project + "','" + device + "','ACCESS_TOKEN','" + hash + "','设备密钥')");
            statement.executeUpdate("INSERT INTO dev_access_binding (device_id,tenant_id,project_id,protocol)"
                    + " VALUES ('" + device + "','" + tenant + "','" + project + "','" + protocol.name() + "')");
            UUID modelVersion = UUID.randomUUID();
            String snapshot = "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\","
                    + "\"accessType\":\"REPORT\",\"minimum\":-40,\"maximum\":125}},"
                    + "\"events\":{},\"commands\":{}}";
            statement.executeUpdate("INSERT INTO dev_thing_model_version (id,tenant_id,project_id,device_type_id,"
                    + "version_number,version_major,version_minor,version_patch,change_level,schema_profile,"
                    + "model_snapshot,schema_digest,digest_algorithm) VALUES ('" + modelVersion + "','"
                    + tenant + "','" + project + "','" + type + "','1.0.0',1,0,0,'MAJOR',"
                    + "'TC_PROPERTY_COMPOSITE_V1','" + snapshot + "'::jsonb,"
                    + "encode(digest(convert_to('" + snapshot
                    + "'::jsonb::text,'UTF8'),'sha256'),'hex'),'PG_JSONB_TEXT_V1_SHA256')");
            statement.executeUpdate("UPDATE dev_device SET thing_model_version_id='" + modelVersion
                    + "' WHERE id='" + device + "'");
            statement.executeUpdate("INSERT INTO dev_device_model_binding_history (id,tenant_id,project_id,"
                    + "device_id,from_model_version_id,to_model_version_id,transition_key,transition_type,"
                    + "effective_at) VALUES ('" + UUID.randomUUID() + "','" + tenant + "','" + project
                    + "','" + device + "',NULL,'" + modelVersion + "','" + UUID.randomUUID()
                    + "','INITIAL',now())");
        }
        return device;
    }

    private static UUID seedMqttSibling(PostgreSQLContainer<?> postgres, UUID httpDeviceId) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("cutover-mqtt-secret".getBytes(StandardCharsets.UTF_8)));
        UUID mqttDevice = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var lookup = connection.prepareStatement(
                     "SELECT tenant_id,project_id,device_type_id FROM dev_device WHERE id = ?")) {
            lookup.setObject(1, httpDeviceId);
            UUID tenant, project, type;
            try (var row = lookup.executeQuery()) {
                assertThat(row.next()).isTrue();
                tenant = row.getObject(1, UUID.class);
                project = row.getObject(2, UUID.class);
                type = row.getObject(3, UUID.class);
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO dev_command_definition(id,tenant_id,project_id,"
                        + "device_type_id,command_key,name,input_schema,output_schema,timeout_seconds)"
                        + " VALUES ('" + UUID.randomUUID() + "','" + tenant + "','" + project + "','"
                        + type + "','reboot','重启','{}','{}',300)");
                statement.executeUpdate("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,"
                        + "device_key,name,status) VALUES ('" + mqttDevice + "','" + tenant + "','"
                        + project + "','" + type + "','cutover_mqtt','隔离MQTT命令设备','ONLINE')");
                statement.executeUpdate("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,"
                        + "auth_type,credential_hash,display_name) VALUES ('" + UUID.randomUUID() + "','"
                        + tenant + "','" + project + "','" + mqttDevice + "','ACCESS_TOKEN','" + hash
                        + "','MQTT命令密钥')");
                statement.executeUpdate("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol)"
                        + " VALUES ('" + mqttDevice + "','" + tenant + "','" + project + "','MQTT')");
            }
        }
        return mqttDevice;
    }
}
