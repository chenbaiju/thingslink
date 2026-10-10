package com.things.link.enduser.infrastructure.security;

import com.things.link.support.web.DashboardDataRequestPaths;

import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ErrorCode;
import com.things.link.support.trace.TraceContext;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

/**
 * 把已认证 App 令牌里的租户与项目写入 {@link RlsScopeContext}。
 *
 * <p>与控制台 {@code TenantScopeFilter} 是同一职责的第二类身份过滤器，但关键区别在于：
 *
 * <ul>
 *   <li>主体是 {@code app_user_id}（应用 JWT 主体），<b>不是</b> {@code accountId}。
 *       App 身份绝不能冒充控制台账号去写审计日志（ADR 0036），因此本过滤器只写
 *       {@link RlsScopeContext}（数据源驱动 RLS 的输入），<b>不碰</b> {@code TenantContext}
 *       —— 后者携带 accountId，是控制台账号语义。</li>
 *   <li>App 令牌是单项目令牌，{@code pid} 必带。解析失败（缺失/格式错）就返回空范围，
 *       按ADR0064拒绝受保护业务请求为60009，不能继续到不受RLS保护的表。</li>
 * </ul>
 *
 * <p>清理仍然由 support 的 {@code TenantContextFilter} 在最外层 finally 里保证 ——
 * 它同时清 {@code TenantContext} 与 {@code RlsScopeContext}，本过滤器不重复清理。
 *
 * <h2>挂在 App 安全链内部</h2>
 * 依赖认证结果，必须在认证之后运行，由
 * {@code AppSecurityConfiguration} 用 {@code addFilterAfter(..., AuthorizationFilter.class)}
 * 显式挂载，顺序不依赖 {@code @Order} 约定。
 */
public class AppScopeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AppScopeFilter.class);

    /** 生命周期查询只决定能否继续原授权，不代替设备关系或项目角色检查。 */
    private final ProjectLifecycleAccessService projectLifecycle;
    /** 过滤器位于MVC异常处理器之外，沿同一ApiError合同返回确定拒绝和系统故障。 */
    private final ObjectMapper objectMapper;
    /** 与安全链共享精确公开路径，新增业务端点默认接受生命周期门禁。 */
    private final Set<String> publicPaths;

    /** 与安全链共享规范应用resolve判定，避免两层匿名资格漂移。 */
    private final RequestMatcher applicationResolvePublicMatcher;

    /**
     * @param projectLifecycle 项目公开快照
     * @param objectMapper 统一JSON
     * @param publicPaths 三个公开会话路径
     * @param applicationResolvePublicMatcher 规范应用resolve公开请求匹配器
     */
    public AppScopeFilter(ProjectLifecycleAccessService projectLifecycle, ObjectMapper objectMapper,
                          Set<String> publicPaths, RequestMatcher applicationResolvePublicMatcher) {
        this.projectLifecycle = projectLifecycle;
        this.objectMapper = objectMapper;
        this.publicPaths = Set.copyOf(publicPaths);
        this.applicationResolvePublicMatcher = applicationResolvePublicMatcher;
    }

    /** 认证后先确定隔离范围，再按ADR0064检查项目；公开会话入口及OPTIONS保持原安全处理。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (publicPaths.contains(path) || applicationResolvePublicMatcher.matches(request)
                || "OPTIONS".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AppProjectIdentity> identity = resolveIdentity();
        if (identity.isEmpty()) {
            writeError(response, EndUserErrorCode.END_USER_ACCESS_INVALID);
            return;
        }
        // ADR0236窄安全撤销例外：只给新资源精确UUID路径建立可信范围，业务写入仍走原门禁。
        if ("DELETE".equals(request.getMethod()) && path.matches("/api/v1/app/push-installations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            RlsScopeContext.set(identity.orElseThrow().scope());
            filterChain.doFilter(request, response);
            return;
        }
        ProjectAccessPolicy policy;
        try {
            AppProjectIdentity authenticated = identity.orElseThrow();
            policy = projectLifecycle.snapshot(
                    authenticated.scope().tenantId(), authenticated.scope().projectId());
        } catch (RuntimeException failure) {
            // 不把数据库不可用或映射错误伪装成令牌失效；与GlobalExceptionHandler保持90000/500合同。
            log.error("App项目生命周期查询失败", failure);
            writeError(response, CommonErrorCode.INTERNAL_ERROR);
            return;
        }
        AppProjectIdentity authenticated = identity.orElseThrow();
        // ADR0073：代次在建立RLS范围前比较，删除前JWT不能借恢复后的项目重新取得任何数据范围。
        if (!policy.matchesGeneration(authenticated.projectGeneration()) || !policy.readAllowed()) {
            writeError(response, EndUserErrorCode.END_USER_ACCESS_INVALID);
            return;
        }
        RlsScopeContext.set(authenticated.scope());
        boolean readRequest = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || DashboardDataRequestPaths.isAppPostRead(request.getMethod(), path);
        if (!readRequest && !policy.writeAllowed()) {
            writeError(response, EndUserErrorCode.PROJECT_READ_ONLY);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 确定拒绝与系统错误均不输出SQL/令牌细节，traceId沿原请求链提供。 */
    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(new ApiError(
                errorCode.code(), errorCode.defaultMessage(), TraceContext.current(), List.of())));
    }

    /**
     * 从安全上下文里取出 App 令牌声明的租户/项目范围。
     *
     * @return 已认证且声明齐全时返回隔离范围与代次，否则为空（行级隔离在缺失条件时拒绝访问）
     */
    private Optional<AppProjectIdentity> resolveIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        try {
            // ADR0036主体是app_user UUID；签名校验不保证声明完整，不能留给controller解析成500。
            UUID.fromString(jwt.getSubject());
            UUID tenantId = UUID.fromString(jwt.getClaimAsString(AppTokenIssuer.CLAIM_TENANT_ID));
            UUID projectId = UUID.fromString(jwt.getClaimAsString(AppTokenIssuer.CLAIM_PROJECT_ID));
            long projectGeneration = projectGeneration(jwt);
            if (projectGeneration < 0) {
                return Optional.empty();
            }
            return Optional.of(new AppProjectIdentity(
                    new RlsScope(tenantId, projectId), projectGeneration));
        } catch (IllegalArgumentException | NullPointerException e) {
            // 令牌签名有效但声明缺失或格式不对。这不该发生（令牌是本服务签的），
            // 出现即说明签发与消费两侧不一致；受保护请求在查询项目前统一60009，不借空范围继续。
            log.error("App 令牌声明无法解析为隔离范围，本次请求将没有租户/项目上下文", e);
            return Optional.empty();
        }
    }

    /** ADR0073滚动升级兼容：旧JWT缺少pgv时按0解释，数值格式错误由身份解析统一拒绝。 */
    private static long projectGeneration(Jwt jwt) {
        Object claim = jwt.getClaim(AppTokenIssuer.CLAIM_PROJECT_GENERATION);
        if (claim == null) {
            return 0L;
        }
        if (!(claim instanceof Number number)) {
            throw new IllegalArgumentException("项目生命周期代次声明必须为数值");
        }
        long generation = number.longValue();
        if (number.doubleValue() != generation) {
            throw new IllegalArgumentException("项目生命周期代次声明必须为整数");
        }
        return generation;
    }

    /** 已验签App JWT中尚未获准写入线程上下文的项目身份。 */
    private record AppProjectIdentity(RlsScope scope, long projectGeneration) {
    }

}
