package com.things.link.dashboard.infrastructure.qualification;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** CLI封闭输入与秘密最小化；数据库/提交真实行为由HostActivationTests验证。 */
class HostDeploymentCliTests {
    @Test void malformedArgumentsNeverOpenConnection() {
        String operation = UUID.randomUUID().toString();
        for (String[] args : new String[][]{{}, {"--help"}, {"activate", "latest", "0", operation},
                {"activate", "1.1.0", "-1", operation}, {"activate", "1.1.0", "00", operation},
                {"activate", "1.1.0", "9223372036854775807", operation}, {"activate", "1.1.0", "0", "1-1-1-1-1"},
                {"status", operation.toUpperCase()}, {"preflight", "1.1.0", "secret"}}) {
            var opened = new AtomicBoolean(); var output = new ByteArrayOutputStream();
            assertThat(HostDeploymentCli.run(args, environment(), new PrintStream(output), env -> {
                opened.set(true); throw new SQLException("must not open");
            })).isEqualTo(2);
            assertThat(opened).isFalse(); assertThat(output.toString()).doesNotContain("secret", operation);
        }
    }
    @Test void missingCredentialsAndUrlCredentialsAreRejectedWithoutEcho() {
        for (Map<String, String> env : java.util.List.of(Map.<String, String>of(),
                withUrl("jdbc:postgresql://localhost/db?%75ser=secret"),
                withUrl("jdbc:postgresql://secret:secret@localhost/db"),
                withUrl("jdbc:postgresql://localhost/db?password=secret"))) {
            var output = new ByteArrayOutputStream(); var opened = new AtomicBoolean();
            assertThat(HostDeploymentCli.run(new String[]{"preflight", "1.1.0"}, env, new PrintStream(output), input -> {
                opened.set(true); throw new SQLException("secret");
            })).isEqualTo(2);
            assertThat(opened).isFalse(); assertThat(output.toString()).doesNotContain("secret", "localhost");
        }
    }
    @Test void connectionFailureNeverLeaksExceptionOrConfiguration() {
        var output = new ByteArrayOutputStream();
        assertThat(HostDeploymentCli.run(new String[]{"status", UUID.randomUUID().toString()}, environment(),
                new PrintStream(output), env -> { throw new SQLException("password=secret at localhost"); })).isEqualTo(4);
        assertThat(output.toString()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("secret", "localhost");
    }
    private static Map<String, String> environment() { return withUrl("jdbc:postgresql://localhost/db"); }
    private static Map<String, String> withUrl(String url) {
        return Map.of("TC_HOST_OPERATOR_JDBC_URL", url, "TC_HOST_OPERATOR_USER", "operator", "TC_HOST_OPERATOR_PASSWORD", "secret",
                "TC_HOST_REGISTRY_DIRECTORY", "/nonexistent-managed-registry");
    }
}
