package com.things.link.bootstrap.integration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="things-link.integration.webhook.enabled=false")
class WebhookDisabledHttpTests extends ApiKeyHttpFixture {
    @Test void disabledManagementFailsClosedWithNoStore()throws Exception {
        String base="/api/v1/projects/"+project+"/webhooks";
        for(String suffix:new String[]{"","/deliveries","/events?eventType=device.online"}) {
            var response=request("GET",base+suffix,null,token(),Map.of());
            assertThat(response.statusCode()).isEqualTo(503);assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(80002);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
    }
}
