package com.things.link.assistant.infrastructure.transport;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class InternalAgentHealthClientTests {
    static final String PASSWORD = "synthetic-test-password";
    @TempDir static Path directory;
    static Path serverKeys, clientKeys, otherKeys, trust, wrongTrust;
    static SSLContext serverTLS;
    HttpsServer server;
    AtomicInteger visits = new AtomicInteger();
    volatile String response = "{\"status\":\"ok\",\"analysisAvailable\":false}";
    volatile String contentType = "application/json";
    volatile int status = 200;
    volatile String redirect;
    volatile Headers requestHeaders;
    volatile String requestMethod, requestPath;
    volatile CountDownLatch bodyGate;
    final CountDownLatch bodyStarted = new CountDownLatch(1);

    static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        command.addAll(List.of("-storepass:env", "TC_SYNTHETIC_KEYSTORE_PASSWORD", "-noprompt"));
        var builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(directory.resolve("keytool.log").toFile());
        builder.environment().put("TC_SYNTHETIC_KEYSTORE_PASSWORD", PASSWORD);
        Process process = builder.start();
        try { assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    static Path identity(String name, String purpose) throws Exception {
        Path store = directory.resolve(name + ".p12"), csr = directory.resolve(name + ".csr"), cert = directory.resolve(name + ".pem");
        keytool("-genkeypair", "-alias", "identity", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "7",
                "-dname", "CN=" + name, "-storetype", "PKCS12", "-keystore", store.toString());
        keytool("-certreq", "-alias", "identity", "-keystore", store.toString(), "-file", csr.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", directory.resolve("ca.p12").toString(), "-infile", csr.toString(),
                "-outfile", cert.toString(), "-rfc", "-validity", "7", "-ext", "BC=ca:false", "-ext", "KU=digitalSignature",
                "-ext", "EKU=" + purpose, "-ext", "SAN=dns:localhost");
        keytool("-importcert", "-alias", "root", "-keystore", store.toString(), "-file", directory.resolve("ca.pem").toString());
        keytool("-importcert", "-alias", "identity", "-keystore", store.toString(), "-file", cert.toString());
        keytool("-delete", "-alias", "root", "-keystore", store.toString());
        return store;
    }
    static KeyStore load(Path path) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) { store.load(input, PASSWORD.toCharArray()); }
        return store;
    }
    static Path trust(String name, java.security.cert.Certificate certificate) throws Exception {
        var store = KeyStore.getInstance("PKCS12"); store.load(null, null); store.setCertificateEntry("trusted", certificate);
        Path path = directory.resolve(name + ".p12");
        try (var output = Files.newOutputStream(path)) { store.store(output, PASSWORD.toCharArray()); }
        return path;
    }
    @BeforeAll static void certificates() throws Exception {
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "7",
                "-dname", "CN=Synthetic test CA", "-ext", "BC=ca:true,pathlen:0", "-ext", "KU=keyCertSign,cRLSign",
                "-storetype", "PKCS12", "-keystore", directory.resolve("ca.p12").toString());
        keytool("-exportcert", "-alias", "ca", "-keystore", directory.resolve("ca.p12").toString(), "-rfc", "-file", directory.resolve("ca.pem").toString());
        serverKeys = identity("server", "serverAuth"); clientKeys = identity("client", "clientAuth"); otherKeys = identity("other", "clientAuth");
        trust = trust("service-ca", load(directory.resolve("ca.p12")).getCertificate("ca"));
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "7",
                "-dname", "CN=Other synthetic CA", "-ext", "BC=ca:true", "-storetype", "PKCS12", "-keystore", directory.resolve("other-ca.p12").toString());
        wrongTrust = trust("wrong-service-ca", load(directory.resolve("other-ca.p12")).getCertificate("ca"));
        var serverIdentity = load(serverKeys);
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keyManagers.init(serverIdentity, PASSWORD.toCharArray());
        var clients = KeyStore.getInstance("PKCS12"); clients.load(null, null); clients.setCertificateEntry("allowed-leaf", load(clientKeys).getCertificate("identity"));
        var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trustManagers.init(clients);
        var verifier = (X509TrustManager) trustManagers.getTrustManagers()[0];
        // A leaf identity is not a CA issuer hint. Do not advertise its subject as
        // an issuing CA; validation still delegates to the exact leaf trust store.
        var pinned = new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws java.security.cert.CertificateException { verifier.checkClientTrusted(chain, authType); }
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws java.security.cert.CertificateException { verifier.checkServerTrusted(chain, authType); }
        };
        serverTLS = SSLContext.getInstance("TLS"); serverTLS.init(keyManagers.getKeyManagers(), new TrustManager[]{pinned}, new SecureRandom());
    }
    @BeforeEach void start() throws Exception {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTLS) {
            @Override public void configure(HttpsParameters parameters) {
                var ssl = serverTLS.getDefaultSSLParameters(); ssl.setNeedClientAuth(true); parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/", exchange -> {
            visits.incrementAndGet(); requestHeaders = exchange.getRequestHeaders(); requestMethod = exchange.getRequestMethod(); requestPath = exchange.getRequestURI().getPath();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            if (redirect != null) exchange.getResponseHeaders().set("Location", redirect);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                if (bodyGate != null) {
                    bodyStarted.countDown();
                    try { bodyGate.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                output.write(body);
            }
            catch (java.io.IOException closed) { /* Oversize/cancellation tests can close the response. */ }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    URI origin(String host) { return URI.create("https://" + host + ":" + server.getAddress().getPort()); }
    InternalAgentHealthClient client() { return new InternalAgentHealthClient(origin("localhost"), clientKeys, PASSWORD.toCharArray(), trust, PASSWORD.toCharArray()); }
    static void failure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(InternalAgentHealthClient.TransportException.class)
                .hasMessage("INTERNAL_AGENT_HEALTH_FAILED").hasNoCause();
    }
    @Test void mutualTLSReachesOnlyFixedHealthPathWithoutCredentials() throws Exception {
        char[] password = PASSWORD.toCharArray();
        try (var client = new InternalAgentHealthClient(origin("localhost"), clientKeys, password, trust, password)) {
            client.verifyHealth();
            assertThat(password).isEqualTo(PASSWORD.toCharArray());
            assertThat(client.toString()).doesNotContain(PASSWORD, "localhost");
        }
        assertThat(visits).hasValue(1); assertThat(requestMethod).isEqualTo("GET"); assertThat(requestPath).isEqualTo("/internal/health");
        assertThat(requestHeaders.getFirst("Authorization")).isNull(); assertThat(requestHeaders.getFirst("Cookie")).isNull();
    }
    @Test void sameCAUnlistedClientCannotEnterHTTP() {
        try (var client = new InternalAgentHealthClient(origin("localhost"), otherKeys, PASSWORD.toCharArray(), trust, PASSWORD.toCharArray())) { failure(client::verifyHealth); }
        assertThat(visits).hasValue(0);
    }
    @Test void untrustedServerCannotEnterHTTP() {
        try (var client = new InternalAgentHealthClient(origin("localhost"), clientKeys, PASSWORD.toCharArray(), wrongTrust, PASSWORD.toCharArray())) { failure(client::verifyHealth); }
        assertThat(visits).hasValue(0);
    }
    @Test void mismatchedHostnameCannotEnterHTTP() {
        try (var client = new InternalAgentHealthClient(origin("127.0.0.1"), clientKeys, PASSWORD.toCharArray(), trust, PASSWORD.toCharArray())) { failure(client::verifyHealth); }
        assertThat(visits).hasValue(0);
    }
    @Test void redirectDoesNotReachSecondTarget() throws Exception {
        AtomicInteger secondVisits = new AtomicInteger();
        var second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        second.createContext("/", exchange -> { secondVisits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); }); second.start();
        try {
            status = 307; redirect = "http://127.0.0.1:" + second.getAddress().getPort() + "/";
            try (var client = client()) { failure(client::verifyHealth); }
            assertThat(visits).hasValue(1); assertThat(secondVisits).hasValue(0);
        } finally { second.stop(0); }
    }
    @ParameterizedTest @ValueSource(strings = {"http://localhost", "https://user:password@localhost", "https://localhost/path", "https://localhost?key=secret", "https://localhost#fragment", "https://localhost:0", "https://localhost:65536", "https:opaque"})
    void illegalOriginIsRejectedBeforeIO(String target) { failure(() -> new InternalAgentHealthClient(URI.create(target), clientKeys, PASSWORD.toCharArray(), trust, PASSWORD.toCharArray())); }
    @Test void invalidIdentityTrustOrPasswordIsSanitized() throws Exception {
        failure(() -> new InternalAgentHealthClient(origin("localhost"), clientKeys, "SYNTHETIC_SECRET".toCharArray(), trust, PASSWORD.toCharArray()));
        failure(() -> new InternalAgentHealthClient(origin("localhost"), directory.resolve("absent"), PASSWORD.toCharArray(), trust, PASSWORD.toCharArray()));
        failure(() -> new InternalAgentHealthClient(origin("localhost"), serverKeys, PASSWORD.toCharArray(), trust, PASSWORD.toCharArray()));
        failure(() -> new InternalAgentHealthClient(origin("localhost"), directory.resolve("ca.p12"), PASSWORD.toCharArray(), trust, PASSWORD.toCharArray()));
        var leafTrust = trust("leaf-not-ca", load(clientKeys).getCertificate("identity"));
        failure(() -> new InternalAgentHealthClient(origin("localhost"), clientKeys, PASSWORD.toCharArray(), leafTrust, PASSWORD.toCharArray()));
    }
    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "{\"status\":\"ok\",\"analysisAvailable\":true}", "{\"status\":\"ok\",\"analysisAvailable\":\"false\"}", "{\"status\":\"ok\",\"analysisAvailable\":false,\"key\":\"SYNTHETIC_SECRET\"}", "{\"status\":\"ok\",\"status\":\"ok\",\"analysisAvailable\":false}", "{\"status\":\"ok\",\"analysisAvailable\":false} {}", "SYNTHETIC_SECRET"})
    void invalidHealthBodyNeverBecomesSuccess(String body) { response = body; try (var client = client()) { failure(client::verifyHealth); } }
    @Test void oversizedHealthBodyAndWrongContentTypeAreRejected() {
        response = "SYNTHETIC_SECRET".repeat(200);
        try (var client = client()) { failure(client::verifyHealth); }
        response = "{\"status\":\"ok\",\"analysisAvailable\":false}"; contentType = "text/html";
        try (var client = client()) { failure(client::verifyHealth); }
    }
    @Test void ambientProxyDoesNotReceiveInternalConnection() {
        ProxySelector previous = ProxySelector.getDefault(); AtomicInteger selected = new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            public List<Proxy> select(URI uri) { selected.incrementAndGet(); return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1))); }
            public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {}
        });
        try (var client = client()) { client.verifyHealth(); assertThat(selected).hasValue(0); }
        finally { ProxySelector.setDefault(previous); }
        assertThat(visits).hasValue(1);
    }
    @Test void interruptedIncompleteBodyCancelsRequestAndPreservesInterrupt() throws Exception {
        bodyGate = new CountDownLatch(1); AtomicReference<Throwable> observed = new AtomicReference<>(); AtomicInteger interrupted = new AtomicInteger();
        try (var client = client()) {
            Thread worker = Thread.startVirtualThread(() -> {
                try { client.verifyHealth(); }
                catch (Throwable failed) { observed.set(failed); if (Thread.currentThread().isInterrupted()) interrupted.incrementAndGet(); }
            });
            try {
                assertThat(bodyStarted.await(3, TimeUnit.SECONDS)).isTrue(); worker.interrupt(); worker.join(3000);
                assertThat(worker.isAlive()).isFalse(); assertThat(observed.get()).isInstanceOf(InternalAgentHealthClient.TransportException.class).hasMessage("INTERNAL_AGENT_HEALTH_FAILED").hasNoCause();
                assertThat(interrupted).hasValue(1);
            } finally { bodyGate.countDown(); worker.interrupt(); worker.join(3000); }
        }
        assertThat(visits).hasValue(1);
    }
    @Test void closedClientCannotMakeNewRequests() { var client = client(); client.close(); failure(client::verifyHealth); assertThat(visits).hasValue(0); }
}
