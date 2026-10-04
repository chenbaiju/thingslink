package com.things.link.support.notification.delivery;

import com.things.link.support.notification.mail.SmtpMailSender;
import com.things.link.testing.tls.TestTlsMaterial;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本机真实 TLS/SMTP 接收资格。仅替代外部接收服务器，不替换 JavaMail、MIME 或生产发送适配器。
 * 不证明外部邮箱送达、Kafka/通知调度器重试、SMTP 接收后的最终投递或跨故障恰好一次。
 */
class ExternalEmailNotificationSmtpWireTests {
    private static final String USER = "wire-sender@example.test";
    private static final String PASSWORD = "local-smtp-fixture-only";

    @Test
    void receivesFrozenChineseMimeOverAuthenticatedTls() throws Exception {
        try (var smtp = new SmtpReceiver(false)) {
            var request = request();
            String providerId = sender(smtp, true, PASSWORD).send(request);

            assertThat(providerId).isEqualTo("email:" + request.deliveryId());
            assertThat(smtp.accepted).hasSize(1);
            assertReceived(smtp.accepted.getFirst(), request);
            assertThat(smtp.authenticated.get()).isEqualTo(1);
            assertThat(smtp.protocols).allMatch(protocol -> protocol.equals("TLSv1.3") || protocol.equals("TLSv1.2"));
        }
    }

    @Test
    void smtp451RejectsBeforeAcceptanceAndSameFrozenRequestCanBeRetried() throws Exception {
        try (var smtp = new SmtpReceiver(true)) {
            var request = request();
            var sender = sender(smtp, true, PASSWORD);
            assertRetryable(() -> sender.send(request));
            assertThat(smtp.accepted).isEmpty();
            assertThat(smtp.attempts).hasSize(1);

            // 手动重试适配器，不能据此认领业务重试调度器已执行。
            assertThat(sender.send(request)).isEqualTo("email:" + request.deliveryId());
            assertThat(smtp.attempts).hasSize(2);
            assertThat(smtp.accepted).hasSize(1);
            for (Received attempt : smtp.attempts) assertReceived(attempt, request);
        }
    }

    @Test
    void smtp535AuthenticationRejectionDoesNotTransmitMessageData() throws Exception {
        try (var smtp = new SmtpReceiver(false)) {
            assertRetryable(() -> sender(smtp, true, "wrong-fixture-password").send(request()));
            assertThat(smtp.authenticationFailures.get()).isEqualTo(1);
            assertThat(smtp.authenticated.get()).isZero();
            assertThat(smtp.attempts).isEmpty();
            assertThat(smtp.accepted).isEmpty();
        }
    }

    @Test
    void untrustedCertificateFailsBeforeSmtpAuthenticationOrData() throws Exception {
        try (var smtp = new SmtpReceiver(false)) {
            assertRetryable(() -> sender(smtp, false, PASSWORD).send(request()));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(smtp.tlsFailures.get()).isEqualTo(1));
            assertThat(smtp.authenticated.get()).isZero();
            assertThat(smtp.attempts).isEmpty();
        }
    }

    private static ExternalNotificationRequest request() {
        return new ExternalNotificationRequest(UUID.randomUUID(), UUID.randomUUID(), "receiver@example.test",
                "设备告警：温度越界", "事故与投递保持同一冻结正文\r\n.以点开始的行\r\n温度 31℃", Map.of());
    }

    private static void assertRetryable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ExternalNotificationException.class).satisfies(error -> {
            var failure = (ExternalNotificationException) error;
            assertThat(failure.reason()).isEqualTo(ExternalNotificationException.Reason.SMTP_FAILURE);
            assertThat(failure.retryable()).isTrue();
            assertThat(failure.getMessage()).isEqualTo("SMTP_FAILURE");
        });
    }

    private static void assertReceived(Received received, ExternalNotificationRequest request) throws Exception {
        assertThat(received.from()).isEqualTo("<" + USER + ">");
        assertThat(received.to()).isEqualTo("<" + request.target() + ">");
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(received.mime().getBytes(StandardCharsets.US_ASCII)));
        assertThat(mime.getSubject()).isEqualTo(request.subject());
        assertThat(((InternetAddress) mime.getFrom()[0]).getPersonal()).isEqualTo("本机告警验证");
        assertThat(((InternetAddress) mime.getAllRecipients()[0]).getAddress()).isEqualTo(request.target());
        assertThat(mime.getContent().toString().stripTrailing()).isEqualTo(request.body());
        assertThat(mime.getContentType().toLowerCase(java.util.Locale.ROOT)).contains("charset=utf-8");
    }

    private static ExternalEmailNotificationSender sender(SmtpReceiver smtp, boolean trust, String password)
            throws Exception {
        var javaMail = new JavaMailSenderImpl();
        javaMail.setHost("127.0.0.1");
        javaMail.setPort(smtp.listener.getLocalPort());
        javaMail.setUsername(USER);
        javaMail.setPassword(password);
        var settings = javaMail.getJavaMailProperties();
        settings.setProperty("mail.smtp.auth", "true");
        settings.setProperty("mail.smtp.auth.mechanisms", "LOGIN");
        settings.setProperty("mail.smtp.ssl.enable", "true");
        settings.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        settings.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
        settings.setProperty("mail.smtp.connectiontimeout", "3000");
        settings.setProperty("mail.smtp.timeout", "3000");
        settings.setProperty("mail.smtp.writetimeout", "3000");
        settings.setProperty("mail.smtp.ssl.socketFactory.fallback", "false");
        if (trust) settings.put("mail.smtp.ssl.socketFactory", smtp.trustedClient.getSocketFactory());
        return new ExternalEmailNotificationSender(new SmtpMailSender(javaMail, USER, "本机告警验证"));
    }

    private record Received(String from, String to, String mime) { }

    /** 有界、仅回环的 SMTP 测试接收器：支持 EHLO/AUTH/MAIL/RCPT/DATA/RSET/QUIT。 */
    private static final class SmtpReceiver implements AutoCloseable {
        private final SSLServerSocket listener;
        private final SSLContext trustedClient;
        private final Thread worker;
        private final boolean rejectFirst;
        private final List<Received> attempts = new CopyOnWriteArrayList<>();
        private final List<Received> accepted = new CopyOnWriteArrayList<>();
        private final List<String> protocols = new CopyOnWriteArrayList<>();
        private final AtomicInteger authenticated = new AtomicInteger();
        private final AtomicInteger authenticationFailures = new AtomicInteger();
        private final AtomicInteger tlsFailures = new AtomicInteger();
        private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        private volatile boolean closing;
        private volatile SSLSocket active;

        private SmtpReceiver(boolean rejectFirst) throws Exception {
            this.rejectFirst = rejectFirst;
            Path materials = TestTlsMaterial.ensure(TestTlsMaterial.checkoutRoot(Path.of("")));
            Certificate certificate;
            try (var input = Files.newInputStream(materials.resolve(TestTlsMaterial.CERT))) {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            String pem = Files.readString(materials.resolve(TestTlsMaterial.KEY), StandardCharsets.US_ASCII);
            byte[] key = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
            KeyStore identity = KeyStore.getInstance("PKCS12");
            identity.load(null);
            char[] storePassword = "local-test-identity".toCharArray();
            identity.setKeyEntry("server", KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(key)),
                    storePassword, new Certificate[]{certificate});
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(identity, storePassword);
            SSLContext server = SSLContext.getInstance("TLS");
            server.init(keys.getKeyManagers(), null, null);
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null);
            trust.setCertificateEntry("loopback", certificate);
            var trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trusts.init(trust);
            trustedClient = SSLContext.getInstance("TLS");
            trustedClient.init(null, trusts.getTrustManagers(), null);
            listener = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(
                    0, 8, InetAddress.getByName("127.0.0.1"));
            listener.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            worker = Thread.ofVirtual().name("local-smtp-wire").start(this::serve);
        }

        private void serve() {
            while (!closing) {
                try (SSLSocket connection = (SSLSocket) listener.accept()) {
                    active = connection;
                    connection.setSoTimeout(5000);
                    try {
                        connection.startHandshake();
                    } catch (SSLException rejected) {
                        tlsFailures.incrementAndGet();
                        continue;
                    }
                    protocols.add(connection.getSession().getProtocol());
                    receive(connection);
                } catch (SocketException failure) {
                    if (!closing) serverFailure.compareAndSet(null, failure);
                } catch (Throwable failure) {
                    if (!closing) serverFailure.compareAndSet(null, failure);
                } finally {
                    active = null;
                }
            }
        }

        private void receive(SSLSocket connection) throws Exception {
            var input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            var output = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.US_ASCII));
            reply(output, "220 localhost controlled-smtp");
            boolean authorized = false;
            String from = null, to = null;
            int commands = 0;
            String line;
            while ((line = line(input)) != null) {
                if (++commands > 40) throw new IOException("SMTP fixture command budget exceeded");
                if (line.startsWith("EHLO ")) {
                    reply(output, "250-localhost\r\n250 AUTH LOGIN");
                } else if (line.equals("AUTH LOGIN")) {
                    reply(output, "334 VXNlcm5hbWU6");
                    String user = line(input);
                    reply(output, "334 UGFzc3dvcmQ6");
                    String password = line(input);
                    authorized = encoded(USER).equals(user) && encoded(PASSWORD).equals(password);
                    if (authorized) {
                        authenticated.incrementAndGet();
                        reply(output, "235 2.7.0 Authenticated");
                    } else {
                        authenticationFailures.incrementAndGet();
                        reply(output, "535 5.7.8 Authentication rejected");
                    }
                } else if (line.startsWith("MAIL FROM:") && authorized) {
                    from = line.substring("MAIL FROM:".length());
                    reply(output, "250 2.1.0 Sender accepted");
                } else if (line.startsWith("RCPT TO:") && authorized && from != null) {
                    to = line.substring("RCPT TO:".length());
                    reply(output, "250 2.1.5 Recipient accepted");
                } else if (line.equals("DATA") && authorized && from != null && to != null) {
                    reply(output, "354 End with dot");
                    var data = new StringBuilder();
                    while (true) {
                        String part = line(input);
                        if (part == null) throw new IOException("SMTP DATA closed before terminator");
                        if (part.equals(".")) break;
                        if (part.startsWith("..")) part = part.substring(1);
                        data.append(part).append("\r\n");
                        if (data.length() > 65536) throw new IOException("SMTP fixture message budget exceeded");
                    }
                    var received = new Received(from, to, data.toString());
                    attempts.add(received);
                    if (rejectFirst && attempts.size() == 1) reply(output, "451 4.3.0 Controlled temporary rejection");
                    else {
                        accepted.add(received);
                        reply(output, "250 2.0.0 Accepted by controlled receiver");
                    }
                    from = null;
                    to = null;
                } else if (line.equals("RSET")) {
                    from = null;
                    to = null;
                    reply(output, "250 Reset");
                } else if (line.equals("QUIT")) {
                    reply(output, "221 Bye");
                    return;
                } else throw new IOException("Unexpected SMTP fixture command/state");
            }
        }

        private static String encoded(String value) {
            return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
        }

        private static String line(BufferedReader reader) throws IOException {
            var result = new StringBuilder();
            for (int value; (value = reader.read()) >= 0;) {
                if (value == '\n') return result.toString();
                if (value != '\r') result.append((char) value);
                if (result.length() > 8192) throw new IOException("SMTP fixture line budget exceeded");
            }
            if (!result.isEmpty()) throw new IOException("SMTP fixture line truncated");
            return null;
        }

        private static void reply(BufferedWriter writer, String response) throws IOException {
            writer.write(response);
            writer.write("\r\n");
            writer.flush();
        }

        @Override
        public void close() throws Exception {
            closing = true;
            listener.close();
            SSLSocket connection = active;
            if (connection != null) connection.close();
            worker.join(Duration.ofSeconds(5));
            assertThat(worker.isAlive()).as("SMTP receiver terminated").isFalse();
            assertThat(serverFailure.get()).as("SMTP receiver protocol failure").isNull();
        }
    }
}
