package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
import java.util.Comparator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** 隔离 HTTPS 代理顺序加载原件、候选、原件，验证设备入口明确关闭与恢复。 */
class AccessHttpsCutoverRoutingTests {

    private static final String BACKEND_CONFIGURATION = """
            server {
                listen 8080;
                location / { return 200 'platform:$request_uri:$http_x_forwarded_proto'; }
            }
            server {
                listen 8081;
                location / { return 200 'access:$request_uri:$http_x_forwarded_proto'; }
            }
            """;

    @Test
    void httpsDevicePathClosesBeforeCutoverAndAfterRollback() throws Exception {
        Path checkout = TestTlsMaterial.checkoutRoot(Path.of(""));
        Path tls = TestTlsMaterial.ensure(checkout);
        Path temporary = Files.createTempDirectory("thingslink-https-cutover-").toRealPath();
        Path privateDirectory = temporary.resolve("private");
        try {
            AccessCutoverFixture.prepare(checkout, privateDirectory);

            Path original = checkout.resolve("deploy/acceptance/nginx.conf.template");
            Path candidate = privateDirectory.resolve("nginx-device-access.conf.template");
            assertThat(Files.readString(original)).contains("location ^~ /device-access/ { return 404; }");
            assertThat(Files.readString(candidate)).contains("proxy_pass http://172.29.240.1:8081;")
                    .doesNotContain("location ^~ /device-access/ { return 404; }");

            try (Network network = Network.newNetwork();
                 GenericContainer<?> backend = new GenericContainer<>("nginx:1.28-alpine")
                         .withNetwork(network).withNetworkAliases("backend")
                         .withCopyToContainer(bytes(BACKEND_CONFIGURATION),
                                 "/etc/nginx/conf.d/default.conf")
                         .waitingFor(Wait.forLogMessage(".*Configuration complete; ready for start up.*", 1))) {
                backend.start();
                assertRouting(network, tls, original, false);
                assertRouting(network, tls, candidate, true);
                assertRouting(network, tls, original, false);
            }
        } finally {
            try (var entries = Files.walk(temporary)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void assertRouting(Network network, Path tls, Path template,
            boolean cutover) throws Exception {
        String rendered = Files.readString(template)
                // Windows 检出原件为 CRLF；先统一换行再应用原隔离网络替换。
                .replace("\r\n", "\n")
                .replace("@HOST@", "localhost")
                .replace("@ORIGIN@", "https://localhost")
                .replace("@HTTPS_PORT@", "443")
                .replace("@BASE@", "/tmp/site")
                // 生产 proxy_bind 使用宿主桥网关；隔离 Docker 网络没有该宿主地址。
                .replace("        proxy_bind 172.29.240.1;\n", "")
                .replace("http://172.29.240.1:808", "http://backend:808");
        assertThat(rendered).doesNotContain("@HOST@", "@ORIGIN@", "@BASE@", "proxy_bind 172.29.240.1");
        try (GenericContainer<?> proxy = new GenericContainer<>("nginx:1.28-alpine")
                .withNetwork(network).withExposedPorts(443)
                .withCopyToContainer(bytes(rendered), "/etc/nginx/conf.d/default.conf")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(tls.resolve(TestTlsMaterial.CERT)), 0444),
                        "/tmp/site/tls/fullchain.pem")
                .withCopyToContainer(Transferable.of(Files.readAllBytes(tls.resolve(TestTlsMaterial.KEY)), 0444),
                        "/tmp/site/tls/privkey.pem")
                .withCopyToContainer(bytes("console"), "/tmp/site/console/index.html")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)))) {
            proxy.start();
            String base = "https://localhost:" + proxy.getMappedPort(443);
            try (HttpClient client = HttpClient.newBuilder().sslContext(trust(tls))
                    .connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpResponse<String> management = request(client, base + "/api/v1/projects", "GET");
                assertThat(management.statusCode()).isEqualTo(200);
                assertThat(management.body()).isEqualTo("platform:/api/v1/projects:https");
                HttpResponse<String> broker = request(client, base + "/api/v1/emqx/auth", "GET");
                assertThat(broker.statusCode()).isEqualTo(404);
                HttpResponse<String> device = request(client,
                        base + "/device-access/v1/property/report", "POST");
                if (cutover) {
                    assertThat(device.statusCode()).isEqualTo(200);
                    assertThat(device.body()).isEqualTo("access:/device-access/v1/property/report:https");
                } else {
                    assertThat(device.statusCode()).isEqualTo(404);
                    assertThat(device.body()).doesNotContain("platform:", "access:", "console");
                }
            }
        }
    }

    private static HttpResponse<String> request(HttpClient client, String url, String method) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
        if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofString("{}"));
        else builder.GET();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static SSLContext trust(Path tls) throws Exception {
        java.security.cert.Certificate certificate;
        try (var input = Files.newInputStream(tls.resolve(TestTlsMaterial.CERT))) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        store.setCertificateEntry("fixture", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private static Transferable bytes(String value) {
        return Transferable.of(value.getBytes(StandardCharsets.UTF_8), 0444);
    }

}
