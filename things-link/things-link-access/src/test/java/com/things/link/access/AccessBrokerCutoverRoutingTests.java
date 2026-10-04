package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.things.link.testing.tls.TestTlsMaterial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** 真实 EMQX 顺序加载基线、切流和回退文件，检查 HTTP 回调的实际接收端。 */
class AccessBrokerCutoverRoutingTests {

    private static final String MOCK_CONFIGURATION = """
            log_format callback '$server_port $request_uri';
            access_log /dev/stdout callback;
            server {
                listen 8080;
                location = /api/v1/emqx/auth {
                    default_type application/json;
                    return 200 '{"result":"allow","is_superuser":false,"client_attrs":{"tc_auth_mountpoint":""}}';
                }
                location = /api/v1/emqx/acl {
                    default_type application/json;
                    return 200 '{"result":"allow"}';
                }
                location /api/v1/emqx/events/ { return 204; }
            }
            server {
                listen 8081;
                location = /api/v1/emqx/auth {
                    default_type application/json;
                    return 200 '{"result":"allow","is_superuser":false,"client_attrs":{"tc_auth_mountpoint":""}}';
                }
                location = /api/v1/emqx/acl {
                    default_type application/json;
                    return 200 '{"result":"allow"}';
                }
                location /api/v1/emqx/events/ { return 204; }
            }
            """;

    @Test
    void brokerUsesOnlySelectedCallbackOwnerAcrossCutoverAndRollback() throws Exception {
        Path checkout = TestTlsMaterial.checkoutRoot(Path.of(""));
        Path temporary = Files.createTempDirectory("thingslink-broker-cutover-").toRealPath();
        Path privateDirectory = temporary.resolve("private");
        try {
            AccessCutoverFixture.prepare(checkout, privateDirectory);

            Path baseline = privateDirectory.resolve("runtime/emqx-base.hocon");
            Path candidate = privateDirectory.resolve("runtime/emqx-base-device-access.hocon");
            assertThat(Files.readString(baseline)).contains("http://backend:8080");
            assertThat(Files.readString(candidate)).contains("http://backend:8081")
                    .doesNotContain("http://backend:8080");

            try (Network network = Network.newNetwork();
                 GenericContainer<?> callbacks = new GenericContainer<>("nginx:1.28-alpine")
                         .withNetwork(network).withNetworkAliases("backend")
                         .withCopyToContainer(Transferable.of(
                                 MOCK_CONFIGURATION.getBytes(StandardCharsets.UTF_8), 0444),
                                 "/etc/nginx/conf.d/default.conf")
                         .waitingFor(Wait.forLogMessage(".*Configuration complete; ready for start up.*", 1))) {
                callbacks.start();
                assertBrokerRoute(network, callbacks, baseline, 8080);
                assertBrokerRoute(network, callbacks, candidate, 8081);
                assertBrokerRoute(network, callbacks, baseline, 8080);
            }
        } finally {
            try (var entries = Files.walk(temporary)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void assertBrokerRoute(Network network, GenericContainer<?> callbacks,
            Path configuration, int expectedPort) throws Exception {
        String before = callbacks.getLogs();
        try (GenericContainer<?> broker = new GenericContainer<>("emqx/emqx:6.2.3")
                .withNetwork(network).withExposedPorts(1883, 18083)
                .withCopyToContainer(Transferable.of(Files.readAllBytes(configuration), 0444),
                        "/opt/emqx/etc/base.hocon")
                .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(120)))) {
            broker.start();
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName("cutover-device");
            options.setPassword("cutover-secret".toCharArray());
            options.setConnectionTimeout(8);
            try (MqttClient mqtt = new MqttClient(
                    "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883),
                    "cutover-test-" + expectedPort + "-" + System.nanoTime())) {
                mqtt.connect(options);
                assertThat(mqtt.isConnected()).isTrue();
                mqtt.subscribe("tc/cutover/check", 1);
                mqtt.disconnect();
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            String newLogs;
            do {
                newLogs = callbacks.getLogs().substring(before.length());
                if (newLogs.contains(expectedPort + " /api/v1/emqx/auth")
                        && newLogs.contains(expectedPort + " /api/v1/emqx/acl")
                        && newLogs.contains(expectedPort + " /api/v1/emqx/events/connected")
                        && newLogs.contains(expectedPort + " /api/v1/emqx/events/disconnected")) break;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertThat(newLogs).contains(expectedPort + " /api/v1/emqx/auth")
                    .contains(expectedPort + " /api/v1/emqx/acl")
                    .contains(expectedPort + " /api/v1/emqx/events/connected")
                    .contains(expectedPort + " /api/v1/emqx/events/disconnected")
                    .doesNotContain((expectedPort == 8080 ? 8081 : 8080) + " /api/v1/emqx/");
        }
    }

}
