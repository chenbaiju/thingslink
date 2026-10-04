package com.things.link.testing.tls;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** JDK-only, local test material. Also executable with Java source-file mode before Maven starts. */
public final class TestTlsMaterial {
    public static final String CERT = "device-access-test-cert.pem";
    public static final String KEY = "device-access-test-key.pem";
    public static final Duration MIN_REMAINING_VALIDITY = Duration.ofHours(24);
    private static final String DIRECTORY = "things-link/things-link-ingestion/src/test/resources/tls";

    private TestTlsMaterial() { }

    /** Only a checkout root is accepted; callers cannot specify a production certificate destination. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: TestTlsMaterial.java <checkout-root>");
        ensure(Path.of(args[0]));
    }

    public static Path checkoutRoot(Path start) throws IOException {
        for (Path path = start.toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("verify/sh/verify-backend-full.sh"))
                    && Files.isRegularFile(path.resolve("things-link/things-link-ingestion/pom.xml"))) return path;
        }
        throw new IOException("Cannot locate ThingsLink checkout for test TLS material from " + start);
    }

    /** Serialize preparation across threads and JVMs. A retained lock file avoids inode replacement races. */
    public static synchronized Path ensure(Path root) throws Exception {
        if (Runtime.version().feature() < 21) throw new IOException("Test TLS preparation requires JDK 21 or newer");
        root = root.toAbsolutePath().normalize();
        if (!checkoutRoot(root).equals(root)) throw new IOException("Expected checkout root: " + root);
        Path directory = root.resolve(DIRECTORY);
        rejectSymlinks(root, directory);
        Files.createDirectories(directory);
        rejectSymlinks(root, directory.resolve(".prepare.lock"));
        try (var channel = FileChannel.open(directory.resolve(".prepare.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (lock == null && System.nanoTime() < deadline) {
                lock = channel.tryLock();
                if (lock == null) Thread.sleep(100);
            }
            if (lock == null) throw new IOException("Timed out waiting for test TLS preparation lock");
            try {
                rejectSymlinks(root, directory.resolve(CERT));
                rejectSymlinks(root, directory.resolve(KEY));
                try {
                    validate(directory, Instant.now());
                    System.out.println("[test-tls] Valid existing loopback certificate and matching PKCS#8 key");
                    return directory;
                } catch (IOException | GeneralSecurityException | IllegalArgumentException invalid) {
                    System.out.println("[test-tls] Missing or invalid local fixture; regenerating: "
                            + invalid.getClass().getSimpleName());
                }
                Path staging = Files.createTempDirectory(directory, ".prepare-");
                try {
                    generate(staging);
                    validate(staging, Instant.now());
                    // Each file is published atomically; the shared preparation lock gates the pair.
                    // No non-atomic fallback: unsupported filesystems must fail before tests start.
                    Files.move(staging.resolve(KEY), directory.resolve(KEY), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(staging.resolve(CERT), directory.resolve(CERT), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    validate(directory, Instant.now());
                    System.out.println("[test-tls] Ready: localhost / 127.0.0.1 (test only)");
                } finally {
                    try (var paths = Files.walk(staging)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                    }
                }
                return directory;
            } finally { lock.release(); }
        }
    }

    private static void rejectSymlinks(Path root, Path destination) throws IOException {
        for (Path part = destination; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Test TLS path must not be a symlink: " + part);
        }
    }

    /** Fast, offline check: validity, exact loopback SANs, P-256, self-signature and proof of key possession. */
    public static void validate(Path directory, Instant now) throws IOException, GeneralSecurityException {
        X509Certificate certificate;
        try (var input = Files.newInputStream(directory.resolve(CERT))) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        certificate.checkValidity(Date.from(now));
        certificate.checkValidity(Date.from(now.plus(MIN_REMAINING_VALIDITY)));
        certificate.verify(certificate.getPublicKey());
        var sans = certificate.getSubjectAlternativeNames();
        if (sans == null || !new HashSet<>(sans).equals(Set.of(List.of(2, "localhost"), List.of(7, "127.0.0.1"))))
            throw new GeneralSecurityException("Expected exactly DNS:localhost,IP:127.0.0.1");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        if (!(certificate.getPublicKey() instanceof ECPublicKey ec)
                || !ec.getParams().getCurve().equals(expected.getCurve())
                || !ec.getParams().getGenerator().equals(expected.getGenerator())
                || !ec.getParams().getOrder().equals(expected.getOrder())
                || ec.getParams().getCofactor() != expected.getCofactor()
                || !certificate.getSigAlgName().equalsIgnoreCase("SHA256withECDSA"))
            throw new GeneralSecurityException("Expected EC P-256 / SHA256withECDSA");
        // Existing unrestricted test certificates remain usable; explicit restrictions must allow TLS.
        boolean[] usage = certificate.getKeyUsage();
        var extended = certificate.getExtendedKeyUsage();
        if ((usage != null && !usage[0]) || (extended != null &&
                !extended.containsAll(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"))))
            throw new GeneralSecurityException("Certificate must permit TLS server/client authentication");
        String pem = Files.readString(directory.resolve(KEY), StandardCharsets.US_ASCII);
        if (!pem.startsWith("-----BEGIN PRIVATE KEY-----") || !pem.stripTrailing().endsWith("-----END PRIVATE KEY-----"))
            throw new GeneralSecurityException("Expected unencrypted PKCS#8 test key");
        byte[] encoded = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key); signature.update(challenge); byte[] proof = signature.sign();
        signature.initVerify(certificate.getPublicKey()); signature.update(challenge);
        if (!signature.verify(proof)) throw new GeneralSecurityException("Test certificate/private key mismatch");
    }

    private static void generate(Path directory) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "keytool.exe" : "keytool");
        String password = UUID.randomUUID().toString();
        Path store = directory.resolve("fixture.p12");
        var builder = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "EC",
                "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-dname", "CN=things-link-device-access-test",
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1", "-ext", "KU=digitalSignature", "-ext", "EKU=serverAuth,clientAuth",
                "-startdate", "-1d", "-validity", "3650", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass:env", "TC_TEST_TLS_STORE_PASSWORD", "-noprompt");
        builder.environment().put("TC_TEST_TLS_STORE_PASSWORD", password);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile());
        Process process = builder.start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("Test TLS keytool timed out");
            if (process.exitValue() != 0) throw new IOException("Test TLS keytool failed: "
                    + Files.readString(directory.resolve("keytool.log")));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
        var keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keyStore.load(input, password.toCharArray()); }
        writePem(directory.resolve(CERT), "CERTIFICATE", keyStore.getCertificate("fixture").getEncoded());
        writePem(directory.resolve(KEY), "PRIVATE KEY", keyStore.getKey("fixture", password.toCharArray()).getEncoded());
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(directory.resolve(KEY), PosixFilePermissions.fromString("rw-------"));
    }

    private static void writePem(Path path, String type, byte[] bytes) throws IOException {
        Files.writeString(path, "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n", StandardCharsets.US_ASCII);
    }

    private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }
}
