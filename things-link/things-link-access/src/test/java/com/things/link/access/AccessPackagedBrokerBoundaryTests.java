package com.things.link.access;

import static com.things.link.access.AccessTwoPackagedRolesStartupTests.awaitStartup;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.freePort;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.runPython;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.startJar;
import static com.things.link.access.AccessTwoPackagedRolesStartupTests.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/** 显式打包资格：隔离 Broker 实际调用接入 JAR；接入退出时未知设备继续拒绝。 */
class AccessPackagedBrokerBoundaryTests {

    @Test
    @EnabledIfSystemProperty(named = "things-link.access.packaged-jar", matches = ".+")
    void candidateBrokerCallsOnlyAccessJarAndFailsClosedWhenItStops() throws Exception {
        Path accessJar = Path.of(System.getProperty("things-link.access.packaged-jar")).toAbsolutePath();
        Path platformJar = Path.of(System.getProperty("things-link.platform.packaged-jar", "")).toAbsolutePath();
        assertThat(accessJar).isRegularFile();
        assertThat(platformJar).isRegularFile();
        Path checkout = TestTlsMaterial.checkoutRoot(Path.of(""));
        Path temporary = Files.createTempDirectory("thingslink-packaged-broker-").toRealPath();
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
                 var redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379)) {
                postgres.start();
                redis.start();
                int platformPort = freePort();
                int accessPort = freePort();
                try {
                    platform = startJar(platformJar,
                            privateDirectory.resolve("application-acceptance.properties"), platformPort,
                            temporary.resolve("platform.log"), postgres, redis,
                            "--spring.flyway.url=" + AccessTwoPackagedRolesStartupTests.jdbcUrl(postgres));
                    awaitStartup(platform, temporary.resolve("platform.log"), "Started ThingsLinkApplication");
                    MqttFixture fixture = seedMqttDevice(postgres);
                    access = startJar(accessJar,
                            privateDirectory.resolve("application-device-access.properties"), accessPort,
                            temporary.resolve("access.log"), postgres, redis,
                            "--things-link.access.http.port=" + accessPort);
                    awaitStartup(access, temporary.resolve("access.log"), "Started ThingsLinkAccessApplication");
                    String secret = generated.getProperty("THINGS_LINK_SECURITY_BROKER_CALLBACK_SECRET");
                    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                        HttpResponse<String> onPlatform = auth(http, platformPort, secret);
                        assertThat(onPlatform.statusCode()).isNotEqualTo(200);
                        HttpResponse<String> onAccess = auth(http, accessPort, secret);
                        assertThat(onAccess.statusCode()).isEqualTo(200);
                        assertThat(onAccess.body()).contains("\"result\":\"deny\"");
                        assertThat(auth(http, accessPort, "wrong-secret").statusCode()).isEqualTo(401);
                        double before = callbackCount(http, accessPort);

                        String configuration = Files.readString(privateDirectory.resolve(
                                "runtime/emqx-base-device-access.hocon"));
                        assertThat(configuration).contains("http://backend:8081");
                        configuration = configuration.replace("http://backend:8081",
                                "http://host.testcontainers.internal:" + accessPort)
                                + "\napi_key.bootstrap_file = \"/opt/emqx/etc/packaged-test-api-keys\"\n";
                        Testcontainers.exposeHostPorts(accessPort);
                        try (var broker = new GenericContainer<>("emqx/emqx:6.2.3")
                                .withExposedPorts(1883, 18083)
                                .withCopyToContainer(Transferable.of(
                                        configuration.getBytes(StandardCharsets.UTF_8), 0444),
                                        "/opt/emqx/etc/base.hocon")
                                .withCopyToContainer(Transferable.of(
                                        "packaged-test:packaged-test-secret:administrator\n"
                                                .getBytes(StandardCharsets.UTF_8), 0444),
                                        "/opt/emqx/etc/packaged-test-api-keys")
                                .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                                        .withStartupTimeout(Duration.ofSeconds(120)))) {
                            broker.start();
                            assertUnknownDeviceDenied(broker);
                            awaitCallbackCount(http, accessPort, before);
                            try (MqttClient device = new MqttClient(
                                    "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883),
                                    "packaged-valid-" + UUID.randomUUID())) {
                                BlockingQueue<String> received = new LinkedBlockingQueue<>();
                                device.setCallback(new MqttCallback() {
                                    @Override public void connectionLost(Throwable cause) {}
                                    @Override public void messageArrived(String topic, MqttMessage message) {
                                        received.add(new String(message.getPayload(), StandardCharsets.UTF_8));
                                    }
                                    @Override public void deliveryComplete(IMqttDeliveryToken token) {}
                                });
                                MqttConnectOptions options = new MqttConnectOptions();
                                options.setUserName("packaged_project/packaged_device");
                                options.setPassword("packaged-device-secret".toCharArray());
                                options.setKeepAliveInterval(2);
                                options.setAutomaticReconnect(false);
                                device.connect(options);
                                assertThat(device.isConnected()).isTrue();
                                device.subscribe("tc/v1/packaged_project/packaged_device/down/command/#", 1);
                                assertThat(device.isConnected()).isTrue();
                                try {
                                    publishBrokerProbe(http, broker, fixture, "before-failure");
                                    assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("before-failure");
                                    long killedAccessPid = access.pid();
                                    access.destroyForcibly();
                                    assertThat(access.waitFor(5, TimeUnit.SECONDS)).isTrue();
                                    access = null;
                                    assertThat(platform.isAlive()).isTrue();
                                    assertUnknownDeviceDenied(broker);
                                    assertThat(auth(http, platformPort, secret).statusCode()).isNotEqualTo(200);
                                    Thread.sleep(5_000);
                                    assertThat(device.isConnected()).isTrue();
                                    publishBrokerProbe(http, broker, fixture, "access-down");
                                    assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("access-down");
                                    access = startJar(accessJar,
                                            privateDirectory.resolve("application-device-access.properties"),
                                            accessPort, temporary.resolve("access-recovered.log"), postgres, redis,
                                            "--things-link.access.http.port=" + accessPort);
                                    awaitStartup(access, temporary.resolve("access-recovered.log"),
                                            "Started ThingsLinkAccessApplication");
                                    assertThat(access.pid()).isNotEqualTo(killedAccessPid);
                                    assertThat(device.isConnected()).isTrue();
                                    publishBrokerProbe(http, broker, fixture, "access-recovered");
                                    assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("access-recovered");
                                    try (MqttClient reconnected = new MqttClient(
                                            "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883),
                                            "packaged-recovered-" + UUID.randomUUID())) {
                                        try {
                                            reconnected.connect(options);
                                            assertThat(reconnected.isConnected()).isTrue();
                                            reconnected.subscribe(
                                                    "tc/v1/packaged_project/packaged_device/down/command/#", 1);
                                        } finally {
                                            if (reconnected.isConnected()) reconnected.disconnectForcibly();
                                        }
                                    }
                                } finally {
                                    if (device.isConnected()) device.disconnectForcibly();
                                }
                            }
                            stop(access);
                            access = null;
                            assertThat(platform.isAlive()).isTrue();
                            assertUnknownDeviceDenied(broker);
                            assertThat(auth(http, platformPort, secret).statusCode()).isNotEqualTo(200);
                        }
                    }
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

    private static HttpResponse<String> auth(HttpClient http, int port, String secret) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/emqx/auth"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Broker-Callback-Token", secret)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"unknown/device\",\"password\":\"wrong\",\"clientid\":\"candidate-probe\"}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static double callbackCount(HttpClient http, int port) throws Exception {
        String metrics = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        return metrics.lines().filter(line -> line.startsWith("thingslink_emqx_callback_seconds_count{")
                        && line.contains("endpoint=\"/api/v1/emqx/auth\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))).sum();
    }

    static void awaitCallbackCount(HttpClient http, int port, double before) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (callbackCount(http, port) > before) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Broker authentication did not reach the access callback metric");
    }

    static void assertUnknownDeviceDenied(GenericContainer<?> broker) throws Exception {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName("unknown/device");
        options.setPassword("wrong".toCharArray());
        options.setConnectionTimeout(5);
        try (MqttClient mqtt = new MqttClient(
                "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883),
                "candidate-probe-" + System.nanoTime())) {
            assertThatThrownBy(() -> mqtt.connect(options)).isInstanceOf(
                    org.eclipse.paho.client.mqttv3.MqttException.class);
            assertThat(mqtt.isConnected()).isFalse();
        }
    }

    private record MqttFixture(UUID deviceId, long configVersion) {}

    private static MqttFixture seedMqttDevice(PostgreSQLContainer<?> postgres) throws Exception {
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID();
        UUID type = UUID.randomUUID(), device = UUID.randomUUID();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("packaged-device-secret".getBytes(StandardCharsets.UTF_8)));
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sys_tenant(id,name) VALUES ('" + tenant + "','隔离MQTT租户')");
            statement.executeUpdate("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES ('"
                    + project + "','" + tenant + "','隔离MQTT项目','packaged_project')");
            statement.executeUpdate("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,"
                    + "access_protocol,network_type,status) VALUES ('" + type + "','" + tenant + "','"
                    + project + "','packaged_type','隔离MQTT类型','DIRECT','STANDARD','WIFI','PUBLISHED')");
            statement.executeUpdate("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,"
                    + "name,status) VALUES ('" + device + "','" + tenant + "','" + project + "','"
                    + type + "','packaged_device','隔离MQTT设备','ONLINE')");
            statement.executeUpdate("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,"
                    + "credential_hash,display_name) VALUES ('" + UUID.randomUUID() + "','" + tenant + "','"
                    + project + "','" + device + "','ACCESS_TOKEN','" + hash + "','MQTT密钥')");
            statement.executeUpdate("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol)"
                    + " VALUES ('" + device + "','" + tenant + "','" + project + "','MQTT')");
            try (var version = statement.executeQuery("SELECT config_version FROM dev_access_binding"
                    + " WHERE device_id='" + device + "'")) {
                assertThat(version.next()).isTrue();
                return new MqttFixture(device, version.getLong(1));
            }
        }
    }

    private static void publishBrokerProbe(HttpClient http, GenericContainer<?> broker,
            MqttFixture fixture, String payload) throws Exception {
        String publicTopic = "tc/v1/packaged_project/packaged_device/down/command/" + UUID.randomUUID();
        String internalTopic = "tc/private/device/" + fixture.deviceId() + "/"
                + fixture.configVersion() + "/" + publicTopic;
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String body = "{\"topic\":\"" + internalTopic + "\",\"qos\":1,\"retain\":false,"
                + "\"payload_encoding\":\"base64\",\"payload\":\"" + encoded + "\"}";
        String authorization = Base64.getEncoder().encodeToString(
                "packaged-test:packaged-test-secret".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                        "http://" + broker.getHost() + ":" + broker.getMappedPort(18083) + "/api/v5/publish"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Basic " + authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Broker probe publish: %s", response.body())
                .isBetween(200, 299);
    }
}
