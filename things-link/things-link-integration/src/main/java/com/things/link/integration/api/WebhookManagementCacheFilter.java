package com.things.link.integration.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
/** ADR0211：认证/解析错误同样禁止管理响应缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class WebhookManagementCacheFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().substring(request.getContextPath().length()).matches("^/api/v1/projects/[^/]+/webhooks(?:/.*)?$");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");chain.doFilter(request,response);
    }
}
