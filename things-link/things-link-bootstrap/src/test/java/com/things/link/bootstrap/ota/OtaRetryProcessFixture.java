package com.things.link.bootstrap.ota;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 独占子进程和能力文件，结束后只保留日志，不留下后台节点。 */
final class OtaRetryProcessFixture implements AutoCloseable {
    private final String jdbcUrl, redisHost;
    private final int redisPort;
    final Path directory;
    private final List<Node> nodes = new ArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    record Node(Process process, Path directory) { }
    OtaRetryProcessFixture(String jdbcUrl, String redisHost, int redisPort) throws Exception {
        this.jdbcUrl = jdbcUrl.replace("://localhost:", "://127.0.0.1:");
        this.redisHost = "localhost".equals(redisHost) ? "127.0.0.1" : redisHost; this.redisPort = redisPort;
        directory = Files.createDirectories(Path.of("../../logs/verify/s14-r8d-3b-2a-3/process-" + UUID.randomUUID()).toAbsolutePath().normalize());
    }
    Node start(String name) throws Exception {
        Path nodeDirectory = Files.createDirectories(directory.resolve(name));
        Path cpRoot = Files.createDirectories(nodeDirectory.resolve("classpath"));
        String resource = "com/things/link/bootstrap/ota/fixture/OtaRetryNodeProcess.class";
        Files.createDirectories(cpRoot.resolve(resource).getParent());
        Files.copy(Path.of("target/test-classes").resolve(resource), cpRoot.resolve(resource));
        Files.copy(Path.of("src/test/resources/application-test.yml"), cpRoot.resolve("application-test.yml"));
        var cp = new ArrayList<String>(); cp.add(cpRoot.toString());
        for (String entry : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(java.io.File.pathSeparator)) {
            if (!entry.contains("/target/test-classes")) cp.add(entry);
        }
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx384m",
                "-Dota.retry.fixture.directory=" + nodeDirectory, "-cp", String.join(java.io.File.pathSeparator, cp),
                "com.things.link.bootstrap.ota.fixture.OtaRetryNodeProcess", "--server.port=0",
                "--spring.flyway.enabled=false", "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=thingslink_app", "--spring.datasource.password=thingslink",
                "--spring.data.redis.host=" + redisHost, "--spring.data.redis.port=" + redisPort,
                "--spring.kafka.admin.auto-create=false", "--spring.kafka.listener.auto-startup=false",
                "--things-link.ota.retry.runtime-enabled=false", "--things-link.ota.campaign.runtime-enabled=false",
                "--things-link.ota.notification.enabled=false", "--things-link.ota.download-authorization.enabled=false"));
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(nodeDirectory.resolve("node.log").toFile()).start();
        var node = new Node(process, nodeDirectory); nodes.add(node);
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            assertThat(process.isAlive()).as("node log %s", nodeDirectory).isTrue();
            return Files.exists(nodeDirectory.resolve("ready"));
        });
        return node;
    }
    String command(Node node, String operation, Path input) throws Exception {
        int id = sequence.incrementAndGet();
        node.process().getOutputStream().write((operation + " " + id + (input == null ? "" : " " + input) + "\n").getBytes(StandardCharsets.UTF_8));
        node.process().getOutputStream().flush();
        Path response = node.directory().resolve("done-" + id);
        await().atMost(Duration.ofSeconds(12)).until(() -> {
            assertThat(node.process().isAlive()).as("node log %s", node.directory()).isTrue();
            return Files.exists(response);
        });
        String result = Files.readString(response);
        assertThat(result).doesNotStartWith("FAIL:");
        return result;
    }
    void kill(Node node) throws Exception {
        node.process().destroyForcibly(); assertThat(node.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
    }
    @Override public void close() throws Exception {
        for (var node : nodes) {
            if (node.process().isAlive()) {
                node.process().getOutputStream().write("quit\n".getBytes(StandardCharsets.UTF_8));
                node.process().getOutputStream().flush();
                if (!node.process().waitFor(15, TimeUnit.SECONDS)) kill(node);
            }
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(path) && !path.getFileName().toString().equals("node.log")) Files.delete(path);
                else if (Files.isDirectory(path)) {
                    try (var children = Files.list(path)) { if (children.findAny().isEmpty()) Files.delete(path); }
                }
            }
        }
    }
}
