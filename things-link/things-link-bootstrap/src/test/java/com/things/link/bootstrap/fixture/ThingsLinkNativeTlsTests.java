package com.things.link.bootstrap.fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThingsLinkNativeTlsTests {
    @TempDir Path temporary;

    @Test void rejectsUnboundedOrNonLocalTargets() {
        for (String host : List.of("0.0.0.0", "8.8.8.8", "localhost", "192.168.1.256", "192.168.01.1", "172.32.0.1", "::1", "192.168.1.1,IP:8.8.8.8"))
            assertThatThrownBy(() -> ThingsLinkNativeTls.validateHost(host)).isInstanceOf(IllegalArgumentException.class);
        for (String host : List.of("127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.2.76"))
            assertThatCode(() -> ThingsLinkNativeTls.validateHost(host)).doesNotThrowAnyException();
    }

    @Test void certificateOnlyCoversExplicitAddressAndKeepsTrustedChain() throws Exception {
        var material = ThingsLinkNativeTls.create(temporary, "192.168.2.76");
        var factory = CertificateFactory.getInstance("X.509");
        try (var leafInput = Files.newInputStream(material.serverCertificate());
             var rootInput = Files.newInputStream(material.rootCertificate())) {
            var leaf = (X509Certificate) factory.generateCertificate(leafInput);
            var root = (X509Certificate) factory.generateCertificate(rootInput);
            leaf.verify(root.getPublicKey());
            leaf.checkValidity();
            assertThat(leaf.getSubjectAlternativeNames()).containsExactly(List.of(7, "192.168.2.76"));
            assertThat(leaf.getBasicConstraints()).isEqualTo(-1);
            assertThat(root.getBasicConstraints()).isGreaterThanOrEqualTo(0);
            assertThat(leaf.getExtendedKeyUsage()).containsExactly("1.3.6.1.5.5.7.3.1");
        }
    }
}
