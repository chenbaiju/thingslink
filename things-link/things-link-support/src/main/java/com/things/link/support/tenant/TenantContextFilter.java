package com.things.link.support.tenant;

import com.things.link.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 保证租户上下文在请求结束时一定被清除。
 *
 * <h2>本过滤器只负责清理，填充在别处</h2>
 * 填充由 iam 的 {@code TenantScopeFilter} 完成（S1 切片 5a 落地，5c 补上项目维度）——
 * 它需要知道令牌里各个声明的名字，那是 iam 的知识，而依赖方向是 iam → support，
 * support 不能反过来引用。
 *
 * <p><b>两者是一对，清理必须留在最外层</b>：本过滤器注册得更靠前，因此填充方抛异常时
 * finally 仍然覆盖得到。把清理挪到填充那边去，就会出现「填充失败 → 上下文没清 →
 * 线程复用时被下一个请求继承」的窗口。这类 bug 偶发、只在高并发下出现、
 * 功能测试完全发现不了，后果是跨租户数据泄露。
 *
 * <p>顺序仅次于 {@code TraceIdFilter}：要在所有可能设置租户上下文的组件之外，
 * finally 才能覆盖到它们。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

}
