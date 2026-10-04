package com.things.link.bootstrap.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 真实独立JVM与共享PG证明原连接下界/墓碑不依赖进程缓存。 */
class MqttConnectionTwoProcessTests extends WebhookFixture {
    @Value("${things-link.security.broker-callback.secret}") String callbackSecret;
    private static final String PASSWORD = "d8".repeat(32);
    private final List<Node> nodes = new ArrayList<>();
    private Path run;
    private String username;
    private record Node(Process process, Path directory, int port) { }

    @AfterEach void stopProcesses() throws Exception {
        for (var node : nodes) {
            stop(node);
            var cp = node.directory().resolve("classpath");
            if (Files.exists(cp)) try (var files = Files.walk(cp)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
            Files.deleteIfExists(node.directory().resolve("ready"));
        }
        for (String table : List.of("dev_connection", "dev_mqtt_connection_ticket", "dev_mqtt_session_cursor", "dev_credential", "sys_outbox_event"))
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
    }
    @Test void separateProcessesRetainOrderAndTombstoneAfterIssuerCrash() throws Exception {
        run = Files.createDirectories(Path.of("../../logs/verify/s14-r8d-2d-6d-3/process-" + UUID.randomUUID()).toAbsolutePath().normalize());
        owner.update("UPDATE dev_device SET status='OFFLINE' WHERE id=?", device);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'node-order')", tenant, project, device, PASSWORD);
        username = owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project) + "/" + owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?", String.class, device);
        var a = start("a"); var b = start("b"); assertThat(a.process().pid()).isNotEqualTo(b.process().pid());
        var old = auth(a); var current = auth(b); assertThat(event(b, "connected", current)).isTrue();
        a.process().destroyForcibly(); assertThat(a.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
        var restarted = start("restarted"); assertThat(restarted.process().pid()).isNotEqualTo(a.process().pid());
        assertThat(event(restarted, "connected", old)).isFalse(); assertThat(event(b, "disconnected", old)).isTrue();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 12; i++) { var node = i % 2 == 0 ? b : restarted; tasks.add(() -> event(node, "connected", current)); }
            for (var result : pool.invokeAll(tasks, 15, TimeUnit.SECONDS)) assertThat(result.get()).isTrue();
        }
        assertThat(rows("dev_connection")).isEqualTo(1); assertThat(sources()).isEqualTo(1);
        assertThat(event(b, "disconnected", current)).isTrue(); assertThat(event(restarted, "connected", current)).isFalse();
        assertThat(event(restarted, "disconnected", current)).isTrue(); assertThat(sources()).isEqualTo(2);
        var pending = auth(restarted); assertThat(event(b, "disconnected", pending)).isTrue();
        assertThat(event(restarted, "connected", pending)).isFalse(); assertThat(rows("dev_connection")).isEqualTo(1);
        var fresh = auth(b); assertThat(event(restarted, "connected", fresh)).isTrue(); assertThat(sources()).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT mqtt_connection_id FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL", UUID.class, device)).isEqualTo(UUID.fromString((String) fresh.get("tc_auth_connection_id")));
    }
    private Map<String, Object> auth(Node node) throws Exception {
        var body = json.readTree(post(node, "auth", Map.of("username", username, "password", PASSWORD, "clientid", "same-original")));
        assertThat(body.path("result").asString()).isEqualTo("allow");
        var result = new LinkedHashMap<String, Object>(); body.path("client_attrs").properties().forEach(e -> result.put(e.getKey(), e.getValue().asString()));
        result.put("username", username); result.put("clientid", body.path("clientid_override").asString()); result.put("reason", "normal");
        return result;
    }
    private boolean event(Node node, String kind, Map<String, Object> body) throws Exception { return json.readTree(post(node, "events/" + kind, body)).path("accepted").asBoolean(); }
    private String post(Node node, String path, Map<String, ?> body) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.port() + "/api/v1/emqx/" + path))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Broker-Callback-Token", callbackSecret)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200); return response.body();
    }
    private int sources() { return owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", Integer.class, project); }
    private Node start(String name) throws Exception {
        Path directory = Files.createDirectories(run.resolve(name)), cpRoot = Files.createDirectories(directory.resolve("classpath"));
        Path target = Files.createDirectories(cpRoot.resolve("com/things/link/bootstrap/integration/fixture"));
        Files.copy(Path.of("target/test-classes/com/things/link/bootstrap/integration/fixture/MqttConnectionNodeProcess.class"), target.resolve("MqttConnectionNodeProcess.class"));
        Files.copy(Path.of("src/test/resources/application-test.yml"), cpRoot.resolve("application-test.yml"));
        var cp = new ArrayList<String>(); cp.add(cpRoot.toString());
        for (var entry : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(java.io.File.pathSeparator))
            if (!entry.replace('\\', '/').contains("/target/test-classes")) cp.add(entry);
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx384m", "-Dmqtt.fixture.directory=" + directory,
                "-cp", String.join(java.io.File.pathSeparator, cp), "com.things.link.bootstrap.integration.fixture.MqttConnectionNodeProcess",
                "--server.port=0", "--spring.flyway.enabled=false", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + APP_ROLE, "--spring.datasource.password=" + APP_ROLE_PASSWORD,
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.kafka.admin.auto-create=false", "--spring.kafka.listener.auto-startup=false",
                "--things-link.security.broker-callback.secret=" + callbackSecret,
                "--things-link.integration.webhook.enabled=true", "--things-link.integration.webhook.current-signing-key-id=a"));
        ProcessBuilder child = new ProcessBuilder(args);
        child.environment().put("THINGS_LINK_INTEGRATION_WEBHOOK_SIGNING_KEYS_JSON", "{\"a\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\"}");
        Process process = child.redirectErrorStream(true).redirectOutput(directory.resolve("node.log").toFile()).start();
        var node = new Node(process, directory, 0); nodes.add(node);
        await().atMost(Duration.ofSeconds(60)).until(() -> { assertThat(process.isAlive()).as("node log %s", directory).isTrue(); return Files.exists(directory.resolve("ready")); });
        return new Node(process, directory, Integer.parseInt(Files.readString(directory.resolve("ready"))));
    }
    private void stop(Node node) throws Exception {
        if (!node.process().isAlive()) return;
        node.process().getOutputStream().write("quit\n".getBytes(StandardCharsets.UTF_8)); node.process().getOutputStream().flush();
        if (!node.process().waitFor(15, TimeUnit.SECONDS)) { node.process().destroyForcibly(); assertThat(node.process().waitFor(10, TimeUnit.SECONDS)).isTrue(); }
    }
}
