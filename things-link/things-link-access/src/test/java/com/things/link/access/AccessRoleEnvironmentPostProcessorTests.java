package com.things.link.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.things.link.runtime.ThingsLinkDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class AccessRoleEnvironmentPostProcessorTests {

    private final AccessRoleEnvironmentPostProcessor guard = new AccessRoleEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication(ThingsLinkAccessApplication.class);

    @Test
    void acceptsOnlyDistinctAccessPortWithoutMigration() {
        assertThatCode(() -> guard.postProcessEnvironment(valid(), application)).doesNotThrowAnyException();
    }

    @Test
    void loadsPlatformDefaultsOnlyWhenExplicitlySelected() {
        var environment = valid();
        new ThingsLinkDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, application);
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.datasource.url")).contains("jdbc:postgresql:");
        assertThat(environment.getProperty("things-link.runtime.embedded", "false")).isEqualTo("false");
    }

    @Test
    void rejectsWrongRoleAndFullRuntimeAssembly() {
        var wrongRole = valid().withProperty("things-link.deployment.role", "platform-api");
        assertThatThrownBy(() -> guard.postProcessEnvironment(wrongRole, application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("deployment.role");
        var fullRuntime = valid().withProperty("things-link.runtime.embedded", "true");
        assertThatThrownBy(() -> guard.postProcessEnvironment(fullRuntime, application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("runtime.embedded");
    }

    @Test
    void rejectsMigrationAndPortDriftBeforeStartup() {
        var migration = valid().withProperty("spring.flyway.enabled", "true");
        assertThatThrownBy(() -> guard.postProcessEnvironment(migration, application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.flyway.enabled");
        var conflictingPort = valid().withProperty("things-link.access.http.port", "8080");
        assertThatThrownBy(() -> guard.postProcessEnvironment(conflictingPort, application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("access.http.port");
        var invalidPort = valid().withProperty("server.port", "not-a-port");
        assertThatThrownBy(() -> guard.postProcessEnvironment(invalidPort, application))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("server.port");
    }

    private static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("things-link.deployment.role", "device-access")
                .withProperty("things-link.runtime.embedded", "false")
                .withProperty("things-link.runtime.defaults", "true")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("server.port", "8081")
                .withProperty("things-link.access.http.port", "8081");
    }
}
