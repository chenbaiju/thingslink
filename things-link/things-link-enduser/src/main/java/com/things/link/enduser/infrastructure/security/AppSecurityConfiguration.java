package com.things.link.enduser.infrastructure.security;

import com.things.link.support.web.DashboardDataNoStoreFilter;

import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.ApiError;
import com.things.link.support.idempotency.IdempotencyFilter;
import com.things.link.support.trace.TraceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * App 独立安全链（ADR 0036）。
 *
 * <h2>为什么是第二根链而不是扩展现有链</h2>
 * App 令牌与控制台令牌必须互斥：同一根链上让 decoder 同时接受两把密钥，互斥就退化成
 * 「issuer 字符串不同」，而 issuer 只是令牌里一条可被伪造方填写的声明，不是安全边界。
 * 两根链各持一把密钥、各配一个 decoder，密码学隔离才成立。
 *
 * <h2>排序是关键</h2>
 * 本链 {@code @Order(HIGHEST_PRECEDENCE)} + {@code securityMatcher("/api/v1/app/**")}，
 * 先于控制台无 matcher 的链匹配；其余路径落到控制台链兜底。顺序反了，App 路径会被
 * 控制台链先吃、App 令牌被 console decoder 拒成 401。互斥测试（App 令牌打
 * {@code /api/v1/app/**} 200、控制台令牌打进来 60009）就是用来钉住这个顺序的。
 */
@Configuration
public class AppSecurityConfiguration {

    /**
     * 无需 Bearer 身份即可自验请求体凭据的 App 会话路径。
     *
     * <p>每一条都要能说清为什么公开：
     * <ul>
     *   <li>{@code /login} —— 登录不可能要求先登录。凭据是 projectKey + 用户名 + 口令，
     *       在服务层自校验，并按 identity + IP 双维度限流。</li>
     *   <li>{@code /refresh} —— 凭据是请求体里的刷新令牌，不是 Bearer 令牌（后者此时
     *       多半已过期）。在 {@code AppSessionService#rotate} 自校验，不是无认证接口。</li>
     *   <li>{@code /logout} —— 与 refresh 同理，凭据是刷新令牌；且 logout 必须免登录，
     *       否则「访问令牌已过期」的用户永远登不出去。</li>
     * </ul>
     *
     * <p><b>会话只放行这三条精确路径，不放行 {@code /api/v1/app/**}</b>：S12-2a2b 新增的
     * 应用定位另由方法感知的规范键匹配器公开。整个前缀放行会让数据面端点默认公开，漏配一个
     * 就是未授权访问。
     */
    private static final String[] APP_PUBLIC_SESSION_PATHS = {
            "/api/v1/app/auth/login",
            "/api/v1/app/auth/refresh",
            "/api/v1/app/auth/logout"
    };

    /** 统一错误响应 JSON 序列化器（与控制台链共用一个 bean）。 */
    private final ObjectMapper objectMapper;

    /** App 令牌校验器。必须是 {@code appJwtDecoder}，用错成控制台 decoder 会拒绝所有 App 令牌。 */
    private final JwtDecoder appJwtDecoder;

    /** ADR0064项目快照由project公开端口提供，不能跨域查项目表。 */
    private final ProjectLifecycleAccessService projectLifecycle;

    /** App认证与项目范围之后执行的公共幂等完成墓碑。 */
    private final IdempotencyFilter idempotencyFilter;

    /** 安全授权与App范围过滤器共享同一个规范匿名resolve判定。 */
    private final WebAppApplicationResolveRequestMatcher applicationResolvePublicMatcher =
            new WebAppApplicationResolveRequestMatcher();

    public AppSecurityConfiguration(ObjectMapper objectMapper,
                                    @Qualifier("appJwtDecoder") JwtDecoder appJwtDecoder,
                                    ProjectLifecycleAccessService projectLifecycle,
                                    IdempotencyFilter idempotencyFilter) {
        this.objectMapper = objectMapper;
        this.appJwtDecoder = appJwtDecoder;
        this.projectLifecycle = projectLifecycle;
        this.idempotencyFilter = idempotencyFilter;
    }

    /**
     * App 安全过滤链。
     *
     * @param http 配置入口
     * @return App 过滤链
     * @throws Exception 配置失败
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 只处理 App 路径，其余交给控制台链
                .securityMatcher(request -> request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/app/")
                        && !AppBrowserSecurityConfiguration.namespace(request))
                // App 客户端是移动端/原生，不涉及浏览器表单 CSRF；刷新令牌在请求体而非
                // Cookie，天然不受 CSRF 影响。控制台链关闭 CSRF 的理由在此同样成立
                .csrf(csrf -> csrf.disable())
                // 无状态：不创建也不使用 HttpSession
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(APP_PUBLIC_SESSION_PATHS).permitAll()
                        .requestMatchers(applicationResolvePublicMatcher).permitAll()
                        // 兜底：未显式放行的一律要求认证。App 数据面端点（S11-2b）默认受保护
                        .anyRequest().authenticated())
                // 显式指定 App decoder，不交给框架按类型自动装配（那样会与控制台 decoder 二义）
                // 并显式指定令牌解析失败（坏签名/过期/非法）时的 entryPoint —— 不设的话
                // BearerTokenAuthenticationFilter 用自己的默认 entryPoint（空 body 401），
                // 与控制台的统一错误结构不一致
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(appJwtDecoder))
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, EndUserErrorCode.END_USER_ACCESS_INVALID)))
                // 认证完成后把 App 令牌的租户/项目写入 RlsScopeContext，驱动 RLS
                // no-store先于Bearer解析，覆盖非法令牌、安全拒绝、MVC业务错误与成功响应。
                .addFilterBefore(new DashboardDataNoStoreFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new WebAppRuntimeNoStoreFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new AppScopeFilter(projectLifecycle, objectMapper, Set.of(APP_PUBLIC_SESSION_PATHS),
                        applicationResolvePublicMatcher), AuthorizationFilter.class)
                // S12-0c显式放在App身份与项目生命周期之后；公开认证路径没有可信RLS范围，
                // 精确POST查询和领域自管幂等路径由公共策略放行到当前Controller授权。
                .addFilterAfter(idempotencyFilter, AppScopeFilter.class)
                // 统一错误结构（架构文档 11.1）。这里的 entryPoint 覆盖「无令牌」，
                // accessDenied 覆盖「有令牌但未授权」——App 侧暂无细粒度授权，二者统一 60009
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, EndUserErrorCode.END_USER_ACCESS_INVALID))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(response, EndUserErrorCode.END_USER_ACCESS_INVALID)))
                .build();
    }

    /**
     * 以统一结构写出错误响应。
     *
     * <p>这里在 {@code GlobalExceptionHandler} 的作用范围之外（异常处理器只覆盖
     * Controller 层），所以必须自己组装 {@link ApiError}。
     */
    private void writeError(HttpServletResponse response, EndUserErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = new ApiError(
                errorCode.code(), errorCode.defaultMessage(), TraceContext.current(), List.of());
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

}
