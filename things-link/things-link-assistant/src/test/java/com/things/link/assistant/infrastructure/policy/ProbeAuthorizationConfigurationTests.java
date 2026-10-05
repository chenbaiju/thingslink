package com.things.link.assistant.infrastructure.policy;

import com.things.link.assistant.application.ProbeAuthorizationProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ProbeAuthorizationConfigurationTests {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(ProbeAuthorizationConfiguration.class);
    @Test void defaultHasNoAuthorization() {
        runner.run(c->assertThat(c.getBean(ProbeAuthorizationProvider.class).find("grant")).isEmpty());
    }
    @Test void explicitPartialConfigurationFailsClosed() {
        runner.withPropertyValues("things-link.assistant.probe.authorization-id=grant").run(c->{
            assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasRootCauseMessage("INVALID_PROBE_AUTHORIZATION");
        });
    }
    @Test void bindingIsFrozenAndNeverAcceptsClientGeneratedAuthorization() {
        UUID project=UUID.randomUUID();
        runner.withPropertyValues("things-link.assistant.probe.authorization-id=grant","things-link.assistant.probe.environment-id=test",
            "things-link.assistant.probe.project-id="+project,"things-link.assistant.probe.configuration-revision=2",
            "things-link.assistant.probe.manifest-sha256="+"a".repeat(64)).run(c->{
                var provider=c.getBean(ProbeAuthorizationProvider.class);var binding=provider.find("grant").orElseThrow();
                assertThat(binding.projectId()).isEqualTo(project);assertThat(binding.configurationRevision()).isEqualTo(2);
                c.getBean(ProbeAuthorizationConfiguration.Properties.class).setEnvironmentId("mutated");
                assertThat(provider.find("grant").orElseThrow().environmentId()).isEqualTo("test");
                assertThat(provider.find("new-grant")).isEmpty();assertThat(binding.toString()).isEqualTo("ProbeAuthorization[REDACTED]");
            });
    }
}
