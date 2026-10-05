package com.things.link.assistant.infrastructure.policy;

import com.things.link.assistant.application.OutboundPropertyPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OutboundPolicyConfigurationTests {
    final UUID project=UUID.randomUUID(), model=UUID.randomUUID();
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(OutboundPolicyConfiguration.class);
    String[] configured() {
        return new String[]{"things-link.assistant.outbound.bindings[0].project-id="+project,
                "things-link.assistant.outbound.bindings[0].model-version-id="+model,
                "things-link.assistant.outbound.bindings[0].property-key=temperature",
                "things-link.assistant.outbound.bindings[0].semantic=TEMPERATURE",
                "things-link.assistant.outbound.bindings[0].unit=CELSIUS"};
    }
    @Test void emptyConfigurationStartsWithNoAllowedProperty() {
        context.run(c->{assertThat(c).hasNotFailed();assertThat(c.getBean(OutboundPropertyPolicy.class).find(project,model,"temperature")).isEmpty();});
    }
    @Test void springBindsAnExactProjectModelAndProperty() {
        context.withPropertyValues(configured()).run(c->{
            assertThat(c).hasNotFailed();var p=c.getBean(OutboundPropertyPolicy.class);
            assertThat(p.find(project,model,"temperature")).isPresent();
            assertThat(p.find(UUID.randomUUID(),model,"temperature")).isEmpty();
        });
    }
    @Test void missingFieldDoesNotSilentlyCreateAnUnrestrictedMapping() {
        context.withPropertyValues(configured()[0],configured()[2],configured()[3],configured()[4]).run(c->assertThat(c).hasFailed());
    }
    @Test void unknownOrIncompatibleUnitFailsStartup() {
        for(String unit:new String[]{"private-free-text","WATT"}) {
            var values=configured();values[4]="things-link.assistant.outbound.bindings[0].unit="+unit;
            context.withPropertyValues(values).run(c->assertThat(c).hasFailed());
        }
    }
    @Test void unknownConfigurationMemberFailsStartup() {
        context.withPropertyValues(configured()).withPropertyValues("things-link.assistant.outbound.bindings[0].description=private-free-text")
                .run(c->assertThat(c).hasFailed());
    }
}
