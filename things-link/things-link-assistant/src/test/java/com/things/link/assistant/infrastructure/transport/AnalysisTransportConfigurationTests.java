package com.things.link.assistant.infrastructure.transport;

import com.things.link.assistant.application.AnalysisTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class AnalysisTransportConfigurationTests {
    @Test void defaultSpringPortCannotSendAndClearsAccidentalCredential() {
        new ApplicationContextRunner().withUserConfiguration(AnalysisTransportConfiguration.class).run(context -> {
            var port=context.getBean(AnalysisTransport.class);
            assertThat(port.ready()).isFalse();
            byte[] key="synthetic-disabled".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            assertThatThrownBy(()->port.execute(null,null,key)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("ANALYSIS_TRANSPORT_DISABLED").hasNoCause();
            assertThat(key).isEqualTo(new byte[key.length]);
        });
    }
}
