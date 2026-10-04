package com.things.link.iam.infrastructure.security;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.regex.Pattern;
/** 邀请收件邮箱与码在认证、校验失败时也不缓存；该分类不改变认证或项目配额。 */
public final class ProjectInvitationNoStoreFilter extends OncePerRequestFilter {
    private static final Pattern PATHS = Pattern.compile(
            "^/api/v1/(?:auth/project-invitation/(?:preview|register)|project-invitations(?:/[^/]+/accept)?"
            + "|projects/[^/]+/invitations(?:/[^/]+(?:/resend)?)?)$");
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATHS.matcher(request.getRequestURI().substring(request.getContextPath().length())).matches();
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
