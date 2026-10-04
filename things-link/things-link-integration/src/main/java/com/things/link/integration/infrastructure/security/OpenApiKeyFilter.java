package com.things.link.integration.infrastructure.security;

import com.things.link.integration.application.*;
import com.things.link.integration.domain.IntegrationErrorCode;
import com.things.link.shared.error.*;
import com.things.link.shared.tenant.*;
import com.things.link.support.trace.TraceContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

/** ADR0171独立Key身份，不建立Console上下文；来源只用容器处理后的远端地址。 */
final class OpenApiKeyFilter extends OncePerRequestFilter {
    private final ApiKeyAuthenticationService authentication;
    private final com.things.link.project.application.ProjectRestQuotaAdmission quotas;
    private final List<OpenApiRoute> routes;
    private final ObjectMapper json;
    private final Set<String> sessionCookies;
    OpenApiKeyFilter(ApiKeyAuthenticationService authentication,List<OpenApiRoute> routes,ObjectMapper json,String consoleCookie,com.things.link.project.application.ProjectRestQuotaAdmission quotas){
        this.quotas=quotas;
        this.authentication=authentication;this.routes=List.copyOf(routes);this.json=json;
        this.sessionCookies=new HashSet<>(List.of(consoleCookie,"tc_app_refresh","JSESSIONID"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        ApiKeyPrincipal principal;
        try {
            List<String> values=Collections.list(request.getHeaders("X-Api-Key"));
            if(values.size()!=1||request.getHeader("Authorization")!=null||hasSessionCookie(request)
                    ||TenantContext.current().isPresent())throw new BusinessException(IntegrationErrorCode.ACCESS_INVALID);
            principal=authentication.authenticate(values.getFirst(),request.getRemoteAddr());
            String path=request.getRequestURI().substring(request.getContextPath().length());
            List<OpenApiRoute> matches=routes.stream().filter(route->route.matches(request.getMethod(),path)).toList();
            if(matches.size()!=1)throw new BusinessException(IntegrationErrorCode.ACCESS_FORBIDDEN);
            principal.require(matches.getFirst().scope(),matches.getFirst().write());
            var quota=quotas.admit(principal.tenantId(),principal.projectId(),principal.issuerAccountId(),principal.keyId(),matches.getFirst().write());
            if(!quota.allowed()){
                response.setHeader("Retry-After",Long.toString(quota.retryAfterSeconds()));
                writeError(response,CommonErrorCode.TOO_MANY_REQUESTS);return;
            }
        } catch(BusinessException denied){writeError(response,denied.errorCode());return;}
        catch(RuntimeException unavailable){
            logger.error("公开认证失败，拒绝请求；failureType="+unavailable.getClass().getName()+" traceId="+TraceContext.current());
            writeError(response,CommonErrorCode.INTERNAL_ERROR);return;
        }
        var previousSecurity=SecurityContextHolder.getContext();
        var previousRls=RlsScopeContext.current();
        try {
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
            SecurityContextHolder.setContext(context);
            RlsScopeContext.set(new RlsScope(principal.tenantId(),principal.projectId()));
            chain.doFilter(request,response);
        } finally {
            SecurityContextHolder.setContext(previousSecurity);
            RlsScopeContext.clear();previousRls.ifPresent(RlsScopeContext::set);
        }
    }
    private boolean hasSessionCookie(HttpServletRequest request){
        // Raw header also rejects malformed name-only cookies instead of depending on container normalization.
        for(String header:Collections.list(request.getHeaders("Cookie")))
            for(String part:header.split(";")){
                String name=part.split("=",2)[0].trim();
                if(sessionCookies.contains(name))return true;
            }
        return false;
    }
    private void writeError(HttpServletResponse response,ErrorCode code)throws IOException{
        response.setStatus(code.httpStatus());response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(),new ApiError(code.code(),code.defaultMessage(),TraceContext.current(),List.of()));
    }
}
