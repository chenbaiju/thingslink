package com.things.link.dashboard.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** ADR0101：在Security防火墙及端点判定之前为整个匿名命名空间关闭缓存，含拒绝结果。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public final class DashboardShareNoStoreFilter extends OncePerRequestFilter {
    /** 只承担输出安全策略，不让namespace匹配变成认证放行规则。 */
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/v1/shares") && !path.startsWith("/api/v1/shares/");
    }
    /** 没有ETag/304或凭据Cookie；共享响应必须每次回库验有效性。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
