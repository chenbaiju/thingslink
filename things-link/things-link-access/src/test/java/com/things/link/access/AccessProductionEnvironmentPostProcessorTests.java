package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.things.link.runtime.ProductionEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class AccessProductionEnvironmentPostProcessorTests {

    private final ProductionEnvironmentPostProcessor guard = new ProductionEnvironmentPostProcessor();
    private final SpringApplication access = new SpringApplication(ThingsLinkAccessApplication.class);

    @Test
    void accessRoleNeedsOnlyItsOwnProductionSecrets() {
        assertThatCode(() -> guard.postProcessEnvironment(valid(), access)).doesNotThrowAnyException();
    }

    @Test
    void fullRuntimeCannotClaimAccessRoleToBypassOwnerAndJwtChecks() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(valid(), new SpringApplication(
                ProductionEnvironmentPostProcessor.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("things-link.deployment.role");
    }

    @Test
    void rejectsWeakAccessSecretsAndMigrationOrUnsafeHttp() {
        for (String key : new String[]{"spring.datasource.password",
                "things-link.security.broker-callback.secret"}) {
            assertThatThrownBy(() -> guard.postProcessEnvironment(valid().withProperty(key, "short"), access))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(key);
        }
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                valid().withProperty("spring.flyway.enabled", "true"), access))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.flyway.enabled");
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                valid().withProperty("things-link.access.http.allow-insecure-loopback", "true"), access))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-insecure-loopback");
        assertThatThrownBy(() -> guard.postProcessEnvironment(
                valid().withProperty("spring.datasource.username", "thingslink"), access))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.datasource.username");
    }

    private static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("things-link.deployment.role", "device-access")
                .withProperty("spring.datasource.username", "thingslink_app")
                .withProperty("spring.datasource.password", "access-test-secret-123456")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("things-link.security.broker-callback.secret",
                        "access-broker-test-secret-long-enough-123456")
                .withProperty("server.forward-headers-strategy", "native");
    }
}
