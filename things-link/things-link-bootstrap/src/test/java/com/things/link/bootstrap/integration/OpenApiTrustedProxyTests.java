package com.things.link.bootstrap.integration;
import com.things.link.integration.application.ApiKeyManagementService;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** 真Tomcat可信代理链接入Key IP白名单；生产启动配置规则另由TrustedProxyHttpTests验证。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "things-link.integration.api-key.enabled=true","server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1","server.tomcat.remoteip.remote-ip-header=X-Forwarded-For",
        "server.tomcat.remoteip.trusted-proxies="})
@Import(OpenApiSecurityTests.ProbeConfiguration.class)
class OpenApiTrustedProxyTests extends ApiKeyHttpFixture {
    @Autowired ApiKeyManagementService keys;
    String key(String ip){return keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("proxy",List.of("device:read"),List.of(ip+"/32"),Instant.now().plusSeconds(3600))).secret();}
    @Test void trustedProxyUsesRightmostUntrustedHopNotSpoofedLeftmost()throws Exception{
        String allowed=key("198.51.100.10");
        var valid=request("GET",OpenApiSecurityTests.PROBE,null,null,Map.of("X-Api-Key",allowed,"X-Forwarded-For","198.51.100.10"));
        assertThat(valid.statusCode()).isEqualTo(200);assertThat(valid.body()).contains("198.51.100.10");
        var spoofed=request("GET",OpenApiSecurityTests.PROBE,null,null,Map.of("X-Api-Key",key("192.0.2.66"),"X-Forwarded-For","192.0.2.66, 198.51.100.10"));
        assertThat(spoofed.statusCode()).isEqualTo(401);
        assertThat(request("GET",OpenApiSecurityTests.PROBE,null,null,Map.of("X-Api-Key",allowed)).statusCode()).isEqualTo(401);
    }
}
