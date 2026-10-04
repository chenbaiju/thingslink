package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.infrastructure.qualification.ManagedWebAppHostQualificationAdapter;
import com.things.link.testing.OwnedTestContainers;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 完整生产上下文/普通运行角色/真实HTTP与1a制品；不以此代替浏览器业务旅程。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
@EnabledIfSystemProperty(named = "thingslink.test.host-registry", matches = ".+")
class DashboardPublicHostHttpTests {
    /** 专用数据库随容器销毁，不能污染开发或生产事实。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(DockerImageName
            .parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("public_host_http").withUsername("thingslink").withPassword("thingslink");
    /** 完整安全链的真实限流/会话依赖，不使用开发Redis。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 复制已封存制品，坏字节反例与清理均不触碰1a原件。 */
    private static final Path REGISTRY = copyRegistry();
    static { DATABASE.start(); REDIS.start(); }
    /** 实际Servlet端口由Spring分配，不假设服务已运行。 */
    @Value("${local.server.port}") private int port;
    /** 绕过本机系统代理，直接探测本测试随机端口。 */
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .proxy(new java.net.ProxySelector() {
                @Override public java.util.List<java.net.Proxy> select(URI uri) { return java.util.List.of(java.net.Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException failure) { }
            }).build();

    /** 实际HTTP证明公开边界、精确字节、历史保留和损坏失败关闭。 */
    @Test void realHttpUsesSelectedBytesAndRetainsOldDigestWithoutIdentity() throws Exception {
        var owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        var inspector = new ManagedWebAppHostQualificationAdapter(REGISTRY.toString());
        var a = inspector.findRegistered("1.1.0").orElseThrow();
        var b = inspector.findRegistered("1.1.1").orElseThrow();
        assertThat(get("/app/").statusCode()).isEqualTo(503); // 仅有新A/B，绝不凭最新目录默认激活。
        owner.update("INSERT INTO dash_host_deployment VALUES (1,1,'1.1.0',?,?,?,clock_timestamp())", a.artifactDigest(), a.sourceDigest(), UUID.randomUUID());
        var home = get("/app/");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).isEqualTo(Files.readAllBytes(REGISTRY.resolve("hosts/1.1.0/release/index.html")));
        assertThat(home.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(home.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(get("/app").statusCode()).isEqualTo(308);
        var head = client.send(request("/app/").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(head.statusCode()).isEqualTo(200); assertThat(head.body()).isEmpty();
        var anonymous = client.send(request("/app/").header("Authorization", "Bearer invalid")
                .header("Cookie", "JSESSIONID=invalid; refresh=invalid").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(anonymous.statusCode()).isEqualTo(200); assertThat(anonymous.headers().firstValue("Set-Cookie")).isEmpty();
        for (String path : new String[]{"/app/private.json", "/app/webapp-host.zip", "/app/sw.js", "/app/?token=x",
                "/app/assets/source.map", "/app/releases/" + "a".repeat(64) + "/index.html"}) {
            assertThat(get(path).statusCode()).as(path).isEqualTo(404);
        }
        for (String path : new String[]{"/app/assets/%2e%2e/host-candidate.json", "/app/assets/%6dain.js", "/app//host-candidate.json"}) {
            var rejected = get(path);
            // 容器ERROR分派可能由既有私有/error安全链返回401；不得为了统一状态码开放它。
            assertThat(rejected.statusCode()).as(path).isIn(400, 401, 404);
            assertThat(rejected.headers().firstValue("Set-Cookie")).isEmpty();
            assertThat(new String(rejected.body(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("tc.webapp-host/v1");
        }
        assertThat(get("/api/v1/system/status").statusCode()).isEqualTo(401);
        assertThat(client.send(request("/app/").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        String immutable = "/app/releases/" + a.artifactDigest();
        var worker = get(immutable + "/sw.js");
        assertThat(worker.statusCode()).isEqualTo(200);
        assertThat(worker.headers().firstValue("Service-Worker-Allowed")).contains("/app/");
        assertThat(worker.headers().firstValue("Cache-Control")).contains("public, max-age=31536000, immutable");
        owner.update("UPDATE dash_host_deployment SET revision=2,host_version='1.1.1',artifact_digest=?,source_digest=?", b.artifactDigest(), b.sourceDigest());
        assertThat(get("/app/").body()).isEqualTo(Files.readAllBytes(REGISTRY.resolve("hosts/1.1.1/release/index.html")));
        assertThat(get(immutable + "/index.html").body()).isEqualTo(home.body());
        owner.update("UPDATE dash_host_deployment SET source_digest=?", "b".repeat(64));
        assertThat(get("/app/").statusCode()).isEqualTo(503);
        assertThat(get(immutable + "/index.html").statusCode()).isEqualTo(200);
        owner.update("UPDATE dash_host_deployment SET source_digest=?", b.sourceDigest());
        Path file = REGISTRY.resolve("hosts/1.1.1/release/index.html"); byte[] original = Files.readAllBytes(file);
        try {
            Files.writeString(file, "corrupt");
            assertThat(get("/app/").statusCode()).isEqualTo(503);
            assertThat(get("/app/assets/icon-192.png").statusCode()).isEqualTo(503);
        } finally { Files.write(file, original); }
        assertThat(get("/app/").statusCode()).isEqualTo(200);
    }
    /** 有界原始URI请求，编码路径保留交由真实安全链拒绝。 */
    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)); }
    /** 原始响应字节用于与封存文件比较，不能仅凭HTTP200认领成功。 */
    private HttpResponse<byte[]> get(String path) throws Exception { return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray()); }
    /** 只复制显式提供的真实登记，不自动重新构建或覆盖版本。 */
    private static Path copyRegistry() {
        try {
            Path source = Path.of(System.getProperty("thingslink.test.host-registry"));
            Path copy = Files.createTempDirectory("public-host-http-").toRealPath();
            try (var paths = Files.walk(source)) {
                for (Path path : paths.toList()) {
                    Path target = copy.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
                }
            }
            return copy;
        } catch (Exception failure) { throw new IllegalStateException("真实制品夹具准备失败", failure); }
    }
    /** 仅删除本测试创建的注册副本；日志保留用于审计。 */
    @AfterAll static void cleanup() throws Exception {
        try (var files = Files.walk(REGISTRY)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    /** 运行连接为普通APP，owner仅作迁移/身份夹具或显式平台操作。 */
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl); registry.add("spring.datasource.username", () -> "thingslink_app");
        registry.add("spring.datasource.password", () -> "thingslink"); registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
        registry.add("spring.flyway.user", DATABASE::getUsername); registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> "thingslink");
        registry.add("spring.data.redis.host", REDIS::getHost); registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.dashboard.host.registry-directory", REGISTRY::toString);
        registry.add("things-link.outbox.publisher.enabled", () -> false); registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.notification.retry.enabled", () -> false);
    }
}
