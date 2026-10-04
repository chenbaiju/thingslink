package com.things.link.bootstrap.integration;

import com.things.link.integration.application.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="things-link.integration.api-key.enabled=true")
@Import(OpenApiSecurityTests.ProbeConfiguration.class)
class OpenApiSecurityTests extends ApiKeyHttpFixture {
    static final String PROBE="/api/open/v1/test-only-probe";
    @Autowired ApiKeyManagementService keys;
    @Autowired com.things.link.enduser.application.AppTokenIssuer appTokens;
    @Test void validAppSignatureCannotEnterPublicKeyChain()throws Exception{
        String app=appTokens.issue(new com.things.link.enduser.application.AppAuthenticatedPrincipal(tenant,project,account)).value();
        assertThat(request("GET",PROBE,null,app,Map.of()).statusCode()).isEqualTo(401);
        assertThat(request("GET",PROBE,null,null,Map.of("X-Api-Key",app)).statusCode()).isEqualTo(401);
        assertThat(request("GET",PROBE,null,app,Map.of("X-Api-Key",key())).statusCode()).isEqualTo(401);
    }
    String key(List<String> scopes,List<String> cidrs){
        return keys.issue(tenant,project,account,Uuid7.generate(),new ApiKeyManagementService.Spec("probe",scopes,cidrs,Instant.now().plusSeconds(3600))).secret();
    }
    String key(){return key(List.of("device:read"),List.of("127.0.0.1/32"));}
    @Test void realKeyCreatesOnlyIndependentIdentityAndUnknownRoutesStayClosed()throws Exception{
        String secret=key();var response=request("GET",PROBE,null,null,Map.of("X-Api-Key",secret));
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains(project.toString(),"api-key:","\"console\":false").doesNotContain(secret);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var unknown=request("GET","/api/open/v1/projects",null,null,Map.of("X-Api-Key",secret));
        assertThat(unknown.statusCode()).isEqualTo(403);assertThat(json.readTree(unknown.body()).path("code").asInt()).isEqualTo(80004);
        assertThat(request("GET",base(),null,null,Map.of("X-Api-Key",secret)).statusCode()).isEqualTo(401);
        assertThat(request("GET",PROBE,null,null,Map.of()).statusCode()).isEqualTo(401);
    }
    @Test void consoleBearerCookiesDuplicateAndQueryCredentialsCannotReplaceKey()throws Exception{
        String secret=key();
        for(var headers:List.of(Map.of("X-Api-Key",secret,"Cookie","tc_refresh=anything"),
                Map.of("X-Api-Key",secret,"Cookie","tc_app_refresh=anything"),
                Map.of("X-Api-Key",secret,"Cookie","JSESSIONID=anything"),
                Map.of("X-Api-Key",secret,"Authorization","Bearer "+token()),
                Map.of("X-Api-Key",secret,"Authorization","Basic dGVzdDp0ZXN0"),
                Map.of("X-Api-Key",token()))) {
            var denied=request("GET",PROBE,null,null,headers);assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(denied.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
        assertThat(request("GET",PROBE,null,token(),Map.of()).statusCode()).isEqualTo(401);
        assertThat(request("GET",PROBE+"?apiKey="+secret,null,null,Map.of()).statusCode()).isEqualTo(401);
        var duplicate=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+PROBE))
                .header("X-Api-Key",secret).header("X-Api-Key",secret).GET().build();
        assertThat(client.send(duplicate,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
    @Test void scopeAndRevocationAreCheckedEveryRequest()throws Exception{
        String alarmOnly=key(List.of("alarm:read"),List.of("127.0.0.1/32"));
        assertThat(request("GET",PROBE,null,null,Map.of("X-Api-Key",alarmOnly)).statusCode()).isEqualTo(403);
        String secret=key();UUID id=com.things.link.integration.domain.ApiKeyCredential.parse(secret).orElseThrow().keyId();
        assertThat(request("GET",PROBE,null,null,Map.of("X-Api-Key",secret)).statusCode()).isEqualTo(200);
        keys.revoke(tenant,project,account,Uuid7.generate(),id);
        assertThat(request("GET",PROBE,null,null,Map.of("X-Api-Key",secret)).statusCode()).isEqualTo(401);
        assertThat(request("GET",base(),null,token(),Map.of()).statusCode()).isEqualTo(200);
    }
    @Test void untrustedForwardedHeadersCannotChooseAllowedAddress()throws Exception{
        String remote=key(List.of("device:read"),List.of("198.51.100.10/32"));
        assertThat(request("GET",PROBE,null,null,Map.of("X-Api-Key",remote,"X-Forwarded-For","198.51.100.10")).statusCode()).isEqualTo(401);
        assertThat(request("GET",PROBE,null,null,Map.of("X-Api-Key",key(),"X-Forwarded-For","198.51.100.10")).statusCode()).isEqualTo(200);
    }
    @Test void databaseUnavailableResponseIsRedactedAndNotAnAuthenticationSuccess()throws Exception{
        String secret=key();owner.execute("REVOKE EXECUTE ON FUNCTION integ_authenticate_api_key(uuid,text,inet) FROM thingslink_app");
        try{
            var response=request("GET",PROBE,null,null,Map.of("X-Api-Key",secret));
            assertThat(response.statusCode()).isEqualTo(500);assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(90000);
            assertThat(response.body()).doesNotContain(secret,"SELECT","integ_authenticate");
        }finally{owner.execute("GRANT EXECUTE ON FUNCTION integ_authenticate_api_key(uuid,text,inet) TO thingslink_app");}
    }
    /** 仅测试bean允许探针；生产无此端点及允许集，不进入正式OpenAPI。 */
    @TestConfiguration(proxyBeanMethods=false)
    static class ProbeConfiguration {
        @Bean OpenApiRoute probeRoute(){return new OpenApiRoute("GET",Pattern.compile("^"+PROBE+"$"),"device:read",false);}
        @Bean Probe probe(){return new Probe();}
    }
    @org.springframework.boot.test.context.TestComponent
    @RestController static class Probe {
        @GetMapping(PROBE) Map<String,Object> read(java.security.Principal raw,jakarta.servlet.http.HttpServletRequest request){
            var authentication=(org.springframework.security.core.Authentication)raw;
            var identity=(ApiKeyPrincipal)authentication.getPrincipal();
            assertThat(RlsScopeContext.current().orElseThrow().projectId()).isEqualTo(identity.projectId());
            return Map.of("principal",identity.getName(),"project",identity.projectId(),"console",TenantContext.current().isPresent(),"ip",request.getRemoteAddr());
        }
    }
}
