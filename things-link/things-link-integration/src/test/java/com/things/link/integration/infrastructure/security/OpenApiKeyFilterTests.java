package com.things.link.integration.infrastructure.security;
import com.things.link.integration.application.*;
import com.things.link.shared.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 只验证同步过滤器的finally边界；真实身份/SQL另由HTTP和PG组合测试证明。 */
class OpenApiKeyFilterTests {
    @Test void transactionCreationFailureUsesRedactedSystemError()throws Exception{
        var auth=mock(ApiKeyAuthenticationService.class);
        when(auth.authenticate("test-only", "127.0.0.1")).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("sensitive connection details"));
        var filter=new OpenApiKeyFilter(auth,List.of(),JsonMapper.builder().build(),"tc_refresh",(owner,project,issuer,key,write)->new com.things.link.project.application.ProjectRestQuotaAdmission.Decision(true,0));
        var request=new MockHttpServletRequest("GET","/api/open/v1/test");request.addHeader("X-Api-Key","test-only");request.setRemoteAddr("127.0.0.1");
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->{throw new AssertionError("must not dispatch");});
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("90000").doesNotContain("sensitive","test-only");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(TenantContext.current()).isEmpty();assertThat(RlsScopeContext.current()).isEmpty();
    }
    @Test void downstreamFailureRestoresOriginalSecurityAndRlsContext()throws Exception{
        var auth=mock(ApiKeyAuthenticationService.class);
        var principal=new ApiKeyPrincipal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,UUID.randomUUID(),Set.of("device:read"),Instant.now().plusSeconds(30),true);
        when(auth.authenticate("test-only", "127.0.0.1")).thenReturn(principal);
        var filter=new OpenApiKeyFilter(auth,List.of(new OpenApiRoute("GET",Pattern.compile("^/api/open/v1/test$"),"device:read",false)),JsonMapper.builder().build(),"tc_refresh",(owner,project,issuer,key,write)->new com.things.link.project.application.ProjectRestQuotaAdmission.Decision(true,0));
        var request=new MockHttpServletRequest("GET","/api/open/v1/test");request.addHeader("X-Api-Key","test-only");request.setRemoteAddr("127.0.0.1");
        var originalSecurity=SecurityContextHolder.createEmptyContext();SecurityContextHolder.setContext(originalSecurity);
        var originalRls=new RlsScope(UUID.randomUUID(),UUID.randomUUID());RlsScopeContext.set(originalRls);
        try {
            assertThatThrownBy(()->filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
                assertThat(TenantContext.current()).isEmpty();
                assertThat(RlsScopeContext.current().orElseThrow().projectId()).isEqualTo(principal.projectId());
                assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(principal);
                throw new jakarta.servlet.ServletException("test downstream failure");
            })).isInstanceOf(jakarta.servlet.ServletException.class);
            assertThat(SecurityContextHolder.getContext()).isSameAs(originalSecurity);
            assertThat(RlsScopeContext.current()).contains(originalRls);assertThat(TenantContext.current()).isEmpty();
        }finally{SecurityContextHolder.clearContext();RlsScopeContext.clear();}
    }
}
