package com.things.link.bootstrap.integration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Map;
import com.things.link.shared.id.Uuid7;
import static org.assertj.core.api.Assertions.assertThat;
/** 未显式启用时真实管理HTTP也不放行；默认值是技术门禁。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyDisabledHttpTests extends ApiKeyHttpFixture {
    @Test void publicChainIsDisabledWithoutExplicitEnablement()throws Exception{
        String key=com.things.link.integration.domain.ApiKeyCredential.generate(Uuid7.generate()).reveal();
        var response=request("GET","/api/open/v1/devices",null,null,Map.of("X-Api-Key",key));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(80002);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
    @Test void managementIsDisabledByDefault()throws Exception{
        var response=request("POST",base(),requestBody(Uuid7.generate()),token(),Map.of());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(80002);
        assertThat(owner.queryForObject("SELECT count(*) FROM integ_api_key WHERE project_id=?",Integer.class,project)).isZero();
    }
}
