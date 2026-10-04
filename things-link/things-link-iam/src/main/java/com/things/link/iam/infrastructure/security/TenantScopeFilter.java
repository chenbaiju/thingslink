package com.things.link.iam.infrastructure.security;

import com.things.link.iam.domain.IamErrorCode;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ErrorCode;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 把已认证令牌里的租户与账号写入 {@link TenantContext}。
 *
 * <p>这是架构文档第 7 节那条链路上<b>一直缺失的一环</b>：
 *
 * <pre>
 * HTTP 请求 → 认证 → 【本过滤器】→ TenantContext
 *          → TenantAwareDataSource 写 app.tenant_id → RLS 策略生效
 * </pre>
 *
 * 在此之前，{@code TenantContext} 只在测试里被手工 {@code set()} 过，
 * 「真实请求能让 RLS 生效」这件事从未被验证。S0-5C 把它登记为遗留项，
 * 到这里才补上。
 *
 * <h2>为什么放在 iam 而不是 support</h2>
 * 填充需要知道令牌里租户 ID 用的是哪个声明名，那是 iam 的知识
 * （{@link JwtTokenIssuer#CLAIM_TENANT_ID}）。而模块依赖方向是 iam → support，
 * support 不能反过来引用 iam。
 *
 * <p>清理仍然由 support 的 {@code TenantContextFilter} 负责，它注册得更靠前，
 * 因此在 finally 里能覆盖本过滤器设置的值。<b>两者是一对，不要把清理挪过来</b> ——
 * 清理必须在最外层，本过滤器抛异常时它仍要生效。
 *
 * <h2>为什么挂在 Security 过滤链内部而不是普通 Bean</h2>
 * 它依赖认证结果，必须在认证之后运行。注册成普通 {@code @Component} 的话，
 * 顺序由 {@code @Order} 与 Security 链的相对位置决定，而后者是个容易记错的常量 ——
 * 排错的症状是「租户上下文有时有、有时没有」。用
 * {@code http.addFilterAfter(..., AuthorizationFilter.class)} 显式挂在链尾，
 * 顺序就不再依赖任何约定。
 */
public class TenantScopeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantScopeFilter.class);
    /** 项目成员、状态与当前代次的权威认证快照。 */
    private final ProjectLifecycleAccessService projectLifecycleAccessService;
    /** 过滤器位于Controller异常处理器之外，必须自行写统一错误JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * @param projectLifecycleAccessService 项目令牌代次核验端口
     * @param objectMapper 统一错误响应序列化器
     */
    public TenantScopeFilter(ProjectLifecycleAccessService projectLifecycleAccessService, ObjectMapper objectMapper) {
        this.projectLifecycleAccessService = projectLifecycleAccessService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            resolveScope().ifPresentOrElse(
                    TenantContext::set,
                    // 未认证的请求（登录、刷新、健康检查）没有租户上下文，这是正常的。
                    // 此时 TenantAwareDataSource 会写入空串，app_current_tenant() 得到 NULL，
                    // 于是所有受 RLS 保护的表一行也读不到 —— fail-closed，正是期望行为
                    () -> { });
        } catch (InvalidProjectTokenException exception) {
            // 已验签却无法建立可信项目范围时必须在业务前终止，不能仅清空scope后放行账号级接口。
            log.warn("控制台项目令牌代次或身份已失效");
            writeError(response, IamErrorCode.INVALID_TOKEN);
            return;
        } catch (RuntimeException failure) {
            // 数据库不可用不是凭据错误；保留系统错误分类，避免客户端错误地清除仍有效会话。
            log.error("控制台项目生命周期查询失败", failure);
            writeError(response, CommonErrorCode.INTERNAL_ERROR);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从安全上下文里取出租户范围。
     *
     * @return 已认证且声明齐全时返回租户范围，否则为空
     */
    private java.util.Optional<TenantScope> resolveScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return java.util.Optional.empty();
        }

        try {
            UUID accountId = UUID.fromString(jwt.getSubject());
            UUID tenantId = UUID.fromString(jwt.getClaimAsString(JwtTokenIssuer.CLAIM_TENANT_ID));

            // pid 可以缺席：用户还没选项目时就是这个状态。
            // 此时 app.project_id 写空串、app_current_project() 得到 NULL，
            // 受项目 RLS 保护的表一行也读不到 —— fail-closed，正是期望行为（ADR 0012）
            String rawProjectId = jwt.getClaimAsString(JwtTokenIssuer.CLAIM_PROJECT_ID);
            UUID projectId = rawProjectId == null ? null : UUID.fromString(rawProjectId);

            if (projectId != null) {
                long expectedGeneration = projectGeneration(jwt);
                ProjectAccessPolicy policy = projectLifecycleAccessService.tokenSnapshot(accountId, projectId);
                if (!policy.readAllowed() || !policy.matchesGeneration(expectedGeneration)) {
                    throw new InvalidProjectTokenException();
                }
            }

            return java.util.Optional.of(new TenantScope(tenantId, projectId, accountId));
        } catch (IllegalArgumentException | NullPointerException e) {
            // 令牌签名有效但声明缺失或格式不对。这不该发生（令牌是本服务签的），
            // 出现即说明签发与消费两侧不一致 —— 记 ERROR 并让请求在无租户上下文下
            // 继续，RLS 会把它拦成「查不到任何数据」，而不是悄悄放行
            log.error("令牌声明无法解析为租户范围，本次请求按无效令牌拒绝", e);
            throw new InvalidProjectTokenException();
        }
    }

    /** 缺失pgv只兼容历史零代；非数值或负数声明均不得进入项目业务。 */
    private static long projectGeneration(Jwt jwt) {
        Object claim = jwt.getClaim(JwtTokenIssuer.CLAIM_PROJECT_GENERATION);
        if (claim == null) return 0L;
        if (!(claim instanceof Number number)) {
            throw new IllegalArgumentException("项目生命周期代次声明不合法");
        }
        long generation = number.longValue();
        if (generation < 0 || number.doubleValue() != generation) {
            throw new IllegalArgumentException("项目生命周期代次声明必须为非负整数");
        }
        return generation;
    }

    /** 在安全过滤器内沿用全局20020/401响应结构。 */
    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = new ApiError(errorCode.code(),
                errorCode.defaultMessage(), TraceContext.current(), List.of());
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    /** 区分未认证公开请求与已验签但项目身份失效的请求。 */
    private static final class InvalidProjectTokenException extends RuntimeException {
    }

}
