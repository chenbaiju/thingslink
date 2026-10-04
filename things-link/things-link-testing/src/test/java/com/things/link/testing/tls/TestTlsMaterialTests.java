package com.things.link.testing.tls;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.*;
import java.security.KeyStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class TestTlsMaterialTests {
    @TempDir static Path baseline;
    @TempDir Path temporary;
    static Path valid;
    Path root;
    Path directory;

    @BeforeAll static void seed() throws Exception {
        valid = TestTlsMaterial.ensure(checkout(baseline.resolve("seed")));
    }

    @BeforeEach void setup() throws Exception {
        root = checkout(temporary.resolve("checkout with spaces"));
        directory = root.resolve("things-link/things-link-ingestion/src/test/resources/tls");
        Files.createDirectories(directory);
        copy(valid, directory);
    }

    @Test void validMaterialIsReusedWithoutChangingEitherFile() throws Exception {
        byte[] cert = Files.readAllBytes(directory.resolve(TestTlsMaterial.CERT));
        byte[] key = Files.readAllBytes(directory.resolve(TestTlsMaterial.KEY));
        TestTlsMaterial.ensure(root); TestTlsMaterial.ensure(root);
        assertThat(Files.readAllBytes(directory.resolve(TestTlsMaterial.CERT))).isEqualTo(cert);
        assertThat(Files.readAllBytes(directory.resolve(TestTlsMaterial.KEY))).isEqualTo(key);
    }

    @Test void missingPairAndMissingKeyAreRebuiltAndTemporarySecretsRemoved() throws Exception {
        Files.delete(directory.resolve(TestTlsMaterial.CERT)); Files.delete(directory.resolve(TestTlsMaterial.KEY));
        TestTlsMaterial.ensure(root);
        TestTlsMaterial.validate(directory, Instant.now());
        Files.delete(directory.resolve(TestTlsMaterial.KEY));
        TestTlsMaterial.ensure(root);
        TestTlsMaterial.validate(directory, Instant.now());
        try (var paths = Files.list(directory)) {
            assertThat(paths.map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(TestTlsMaterial.CERT, TestTlsMaterial.KEY, ".prepare.lock");
        }
    }

    @ParameterizedTest
    @CsvSource({"NONE,-1d,3650", "DNS:wrong.example,-1d,3650", "DNS:localhost,-1d,3650", "IP:127.0.0.1,-1d,3650",
            "'DNS:localhost,IP:127.0.0.1',-10d,1", "'DNS:localhost,IP:127.0.0.1',-2d,3", "'DNS:localhost,IP:127.0.0.1',+2d,3650"})
    void wrongSansExpiredAndFutureCertificatesAreRejectedThenRepaired(String san, String start, String days) throws Exception {
        variant(directory, san, start, days);
        assertThatThrownBy(() -> TestTlsMaterial.validate(directory, Instant.now())).isInstanceOf(Exception.class);
        TestTlsMaterial.ensure(root);
        TestTlsMaterial.validate(directory, Instant.now());
    }

    @Test void mismatchedKeyAndMalformedPemAreRepaired() throws Exception {
        Path other = TestTlsMaterial.ensure(checkout(temporary.resolve("other")));
        Files.copy(other.resolve(TestTlsMaterial.KEY), directory.resolve(TestTlsMaterial.KEY), StandardCopyOption.REPLACE_EXISTING);
        assertThatThrownBy(() -> TestTlsMaterial.validate(directory, Instant.now())).hasMessageContaining("mismatch");
        TestTlsMaterial.ensure(root);
        TestTlsMaterial.validate(directory, Instant.now());
        Files.writeString(directory.resolve(TestTlsMaterial.KEY), "not a private key");
        TestTlsMaterial.ensure(root);
        TestTlsMaterial.validate(directory, Instant.now());
    }

    @Test void publicationFailureStopsPreparationAndNextRunRepairsThePair() throws Exception {
        Files.delete(directory.resolve(TestTlsMaterial.CERT));
        Files.createDirectory(directory.resolve(TestTlsMaterial.CERT));
        Path blocker = directory.resolve(TestTlsMaterial.CERT).resolve("blocker");
        Files.writeString(blocker, "owned test blocker");
        assertThatThrownBy(() -> TestTlsMaterial.ensure(root)).isInstanceOf(java.io.IOException.class);
        try (var paths = Files.list(directory)) {
            assertThat(paths.map(p -> p.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith(".prepare-"));
        }
        Files.delete(blocker); Files.delete(directory.resolve(TestTlsMaterial.CERT));
        TestTlsMaterial.ensure(root);
        TestTlsMaterial.validate(directory, Instant.now());
    }

    @Test void destinationOutsideCheckoutIsRejected() {
        assertThatThrownBy(() -> TestTlsMaterial.ensure(temporary)).hasMessageContaining("Cannot locate");
    }

    @Test void symlinkCannotRedirectWritesOutsideFixtureDirectory() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"), "Windows symlink privilege is optional");
        Path untouched = temporary.resolve("production-key"); Files.writeString(untouched, "untouched");
        Files.delete(directory.resolve(TestTlsMaterial.KEY));
        Files.createSymbolicLink(directory.resolve(TestTlsMaterial.KEY), untouched);
        assertThatThrownBy(() -> TestTlsMaterial.ensure(root)).hasMessageContaining("symlink");
        assertThat(Files.readString(untouched)).isEqualTo("untouched");
    }

    @Test void concurrentProcessesSeeingInvalidMaterialReadTheSameCompleteGeneration() throws Exception {
        Files.writeString(directory.resolve(TestTlsMaterial.CERT), "invalid certificate");
        Process first = child(ConcurrentReaderProbe.class.getName(), root, "first");
        Process second = child(ConcurrentReaderProbe.class.getName(), root, "second");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while ((!Files.exists(root.resolve("first.ready")) || !Files.exists(root.resolve("second.ready")))
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(root.resolve("first.ready")).exists();
            assertThat(root.resolve("second.ready")).exists();
            Files.writeString(root.resolve("start"), "both observed invalid material");
            successful(first); successful(second);
            assertThat(Files.readString(root.resolve("first.certificate")))
                    .isEqualTo(Files.readString(root.resolve("second.certificate")))
                    .isEqualTo(Files.readString(directory.resolve(TestTlsMaterial.CERT)));
        } finally { first.destroyForcibly(); second.destroyForcibly(); }
        TestTlsMaterial.validate(directory, Instant.now());
    }

    @Test void remainingValidityHasAnExplicit24HourBoundary() throws Exception {
        java.security.cert.X509Certificate certificate;
        try (var input = Files.newInputStream(directory.resolve(TestTlsMaterial.CERT))) {
            certificate = (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        assertThat(TestTlsMaterial.MIN_REMAINING_VALIDITY).isEqualTo(java.time.Duration.ofHours(24));
        Instant boundary = certificate.getNotAfter().toInstant().minus(TestTlsMaterial.MIN_REMAINING_VALIDITY);
        TestTlsMaterial.validate(directory, boundary.minusSeconds(1));
        TestTlsMaterial.validate(directory, boundary);
        assertThatThrownBy(() -> TestTlsMaterial.validate(directory, boundary.plusSeconds(1)))
                .isInstanceOf(java.security.cert.CertificateExpiredException.class);
    }

    public static final class ConcurrentReaderProbe {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            Path directory = root.resolve("things-link/things-link-ingestion/src/test/resources/tls");
            boolean invalid = false;
            try { TestTlsMaterial.validate(directory, Instant.now()); }
            catch (java.security.GeneralSecurityException | java.io.IOException expected) { invalid = true; }
            if (!invalid) throw new AssertionError("Expected invalid precondition before releasing both preparers");
            Files.writeString(root.resolve(args[1]+".ready"), "ready");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(root.resolve("start")) && System.nanoTime() < deadline) Thread.sleep(20);
            if (!Files.exists(root.resolve("start"))) throw new AssertionError("Start barrier timed out");
            Path prepared = TestTlsMaterial.ensure(root);
            String certificate = Files.readString(prepared.resolve(TestTlsMaterial.CERT));
            for (int i = 0; i < 20; i++) {
                TestTlsMaterial.validate(prepared, Instant.now());
                if (!Files.readString(prepared.resolve(TestTlsMaterial.CERT)).equals(certificate))
                    throw new AssertionError("Material changed after preparation");
            }
            Files.writeString(root.resolve(args[1]+".certificate"), certificate);
        }
    }

    @Test void serviceLoadedLauncherPreparesBeforeDiscoveryWithoutMavenOrShellWrapper() throws Exception {
        Files.delete(directory.resolve(TestTlsMaterial.CERT)); Files.delete(directory.resolve(TestTlsMaterial.KEY));
        Process process = child(LauncherProbe.class.getName(), root);
        try { successful(process); } finally { process.destroyForcibly(); }
        TestTlsMaterial.validate(directory, Instant.now());
    }

    public static final class LauncherProbe {
        public static void main(String[] args) throws Exception {
            try (var ignored = org.junit.platform.launcher.core.LauncherFactory.openSession()) {
                TestTlsMaterial.validate(Path.of(args[0]).resolve("things-link/things-link-ingestion/src/test/resources/tls"), Instant.now());
            }
        }
    }

    private Process child(String main, Path working, String... arguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"), main, working.toString()));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command)
                .directory(working.toFile()).redirectErrorStream(true).redirectOutput(temporary.resolve(UUID.randomUUID()+".log").toFile()).start();
    }

    private static void successful(Process child) throws Exception {
        assertThat(child.waitFor(45, TimeUnit.SECONDS)).as("child preparation deadline").isTrue();
        assertThat(child.exitValue()).isZero();
    }

    private static Path checkout(Path root) throws Exception {
        Files.createDirectories(root.resolve("things-link/things-link-ingestion"));
        Files.createDirectories(root.resolve("verify/sh"));
        Files.writeString(root.resolve("verify/sh/verify-backend-full.sh"), "test fixture");
        Files.writeString(root.resolve("things-link/things-link-ingestion/pom.xml"), "test fixture");
        return root;
    }

    private static void copy(Path from, Path to) throws Exception {
        Files.copy(from.resolve(TestTlsMaterial.CERT), to.resolve(TestTlsMaterial.CERT));
        Files.copy(from.resolve(TestTlsMaterial.KEY), to.resolve(TestTlsMaterial.KEY));
    }

    /** Real keytool generates independently invalid certificates; no mock validity checks. */
    private static void variant(Path destination, String san, String start, String days) throws Exception {
        Path store = destination.resolve("variant.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        var command = new ArrayList<>(List.of(keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=test", "-startdate", start,
                "-validity", days, "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "test-only-password"));
        if (!san.equals("NONE")) command.addAll(List.of("-ext", "SAN="+san));
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try { successful(process); } finally { process.destroyForcibly(); }
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "test-only-password".toCharArray()); }
        pem(destination.resolve(TestTlsMaterial.CERT), "CERTIFICATE", keys.getCertificate("test").getEncoded());
        pem(destination.resolve(TestTlsMaterial.KEY), "PRIVATE KEY", keys.getKey("test", "test-only-password".toCharArray()).getEncoded());
        Files.delete(store);
    }

    private static void pem(Path path, String kind, byte[] encoded) throws Exception {
        Files.writeString(path, "-----BEGIN "+kind+"-----\n"+Base64.getMimeEncoder().encodeToString(encoded)+"\n-----END "+kind+"-----\n");
    }
}
