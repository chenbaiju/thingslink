package com.things.link.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 数据运行合同§4：由各安全链在Bearer认证前显式装配，拒绝响应同样不可缓存。 */
public final class DashboardDataNoStoreFilter extends OncePerRequestFilter {
    /** 成功和失败沿同一头部规则；不产生ETag或304，不拦截或吞掉业务异常。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        chain.doFilter(request, response);
    }

    /** 去掉部署contextPath后精确匹配，不能通过扩大安全链匹配来实现禁止缓存。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !DashboardDataRequestPaths.isDataRead(request.getMethod(),
                request.getRequestURI().substring(request.getContextPath().length()));
    }
}
