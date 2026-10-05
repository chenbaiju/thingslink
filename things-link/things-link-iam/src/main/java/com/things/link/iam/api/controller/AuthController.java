package com.things.link.iam.api.controller;

import com.things.link.iam.api.dto.request.ForgotPasswordRequest;
import com.things.link.iam.api.dto.request.LoginRequest;
import com.things.link.iam.api.dto.request.RegisterRequest;
import com.things.link.iam.api.dto.request.ResendVerificationRequest;
import com.things.link.iam.api.dto.request.ResetPasswordRequest;
import com.things.link.iam.api.dto.request.SwitchProjectRequest;
import com.things.link.iam.api.dto.request.VerifyEmailRequest;
import com.things.link.iam.api.dto.response.CurrentUserResponse;
import com.things.link.iam.api.dto.response.LoginResponse;
import com.things.link.iam.api.dto.response.VerifyEmailResponse;
import com.things.link.iam.api.support.JwtIdentity;
import com.things.link.iam.api.support.RefreshTokenCookie;
import com.things.link.iam.application.AccessToken;
import com.things.link.iam.application.AuthenticationService;
import com.things.link.iam.application.ClientContext;
import com.things.link.iam.application.CurrentUser;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.iam.application.CurrentUserService;
import com.things.link.iam.application.EmailVerificationService;
import com.things.link.iam.application.IssuedSession;
import com.things.link.iam.application.LoginCommand;
import com.things.link.iam.application.MenuService;
import com.things.link.iam.application.SelfHostedReviewAccess;
import com.things.link.iam.application.PasswordResetService;
import com.things.link.iam.application.RefreshTokenService;
import com.things.link.iam.application.RegisterCommand;
import com.things.link.iam.application.RegistrationService;
import com.things.link.iam.infrastructure.security.JwtTokenIssuer;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.CommercialOperatorAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.ObjectProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * 认证接口。
 *
 * <p>{@code /login} 不需要认证（否则无法登录），{@code /me} 需要。
 * {@code SecurityConfiguration} 里<b>逐个路径</b>放行而非放行整个
 * {@code /api/v1/auth/**} 前缀 —— 否则本类里每新增一个接口都默认变成公开的，
 * 而漏掉的那个不会有任何症状。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "登录与令牌管理")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final CurrentUserService currentUserService;
    private final MenuService menuService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final RegistrationService registrationService;
    private final EmailVerificationService emailVerificationService;
    private final AuthRateLimiter rateLimiter;
    private final PasswordResetService passwordResetService;

    /**
     * 校验「当前账号是不是目标项目的成员」。
     *
     * <p>成员关系归 project 模块管，iam 只能通过它的 application 入口去问
     * （架构文档 10.4 规则 2）。iam → project 是允许的单向依赖。
     */
    private final ProjectService projectService;
    private final CommercialOperatorAccessService commercialAccess;
    private final ObjectProvider<SelfHostedReviewAccess> reviewAccess;

    public AuthController(AuthenticationService authenticationService,
                          CurrentUserService currentUserService,
                          MenuService menuService,
                          RefreshTokenService refreshTokenService,
                          RefreshTokenCookie refreshTokenCookie,
                          RegistrationService registrationService,
                          EmailVerificationService emailVerificationService,
                          AuthRateLimiter rateLimiter,
                          PasswordResetService passwordResetService,
                          ProjectService projectService,
                          CommercialOperatorAccessService commercialAccess,
                          ObjectProvider<SelfHostedReviewAccess> reviewAccess) {
        this.authenticationService = authenticationService;
        this.currentUserService = currentUserService;
        this.menuService = menuService;
        this.refreshTokenService = refreshTokenService;
        this.refreshTokenCookie = refreshTokenCookie;
        this.registrationService = registrationService;
        this.emailVerificationService = emailVerificationService;
        this.rateLimiter = rateLimiter;
        this.passwordResetService = passwordResetService;
        this.projectService = projectService;
        this.commercialAccess = commercialAccess;
        this.reviewAccess = reviewAccess;
    }

    /**
     * 登录并获取令牌对。
     *
     * <p>访问令牌走响应体，刷新令牌走 HttpOnly Cookie —— 两者投递方式不同是
     * 刻意的安全设计，见 {@link RefreshTokenCookie} 与 ADR 0010。
     *
     * <p>两道防护已就位（切片 4b）：按邮箱与来源 IP 限流（10029），连续失败 5 次
     * 临时锁定 15 分钟（20003）。两者拦的不是同一类攻击 —— 锁定防单账号爆破，
     * 限流防撞库（一个口令喷向大量账号，每个都只试一两次）。
     *
     * <p>限流计数存在 Redis，多实例下共用同一份额度。Redis 不可用时<b>放行</b>
     * 而不是拒绝，理由见 {@code AuthRateLimiter} 类注释。
     *
     * @param request     登录请求
     * @param httpRequest 用于取 User-Agent 与来源 IP，仅作记录
     * @return 访问令牌
     */
    @PostMapping("/login")
    @Operation(summary = "登录",
            description = "校验邮箱与口令，签发短时效访问令牌（响应体）"
                    + "与刷新令牌（HttpOnly Cookie）。"
                    + "无论邮箱不存在还是口令错误都返回同一个错误码 20001，"
                    + "区分开会形成账号枚举漏洞。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "401", description = "邮箱或口令错误",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "账号被停用或锁定",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "尝试过于频繁，触发限流",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        IssuedSession session = authenticationService.login(
                new LoginCommand(request.email(), request.password()),
                clientContextOf(httpRequest));
        return respondWithSession(session);
    }

    /**
     * 注册：创建租户与初始账号，并发送验证邮件。
     *
     * <p>按 ADR 0008，注册的语义是<b>开一个租户</b>，不是「加一个用户」——
     * 注册者是自己租户的 OWNER。别人要进到你的范围里，走的是项目邀请，
     * 而不是再注册一次。
     *
     * <p>注册成功不返回令牌对：邮箱尚未验证时不能进入控制台。用户需要先点邮件里的
     * 验证链接，再回登录页用刚才的口令登录。
     *
     * @param request     注册请求
     * @param httpRequest 用于取来源 IP 做限流
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/register")
    @Operation(summary = "注册",
            description = "创建租户、初始账号与租户归属关系（ADR 0008），并异步发出验证邮件。"
                    + "邮箱验证完成前不签发会话，也不能登录控制台。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "注册已受理，请前往邮箱验证"),
            @ApiResponse(responseCode = "400", description = "参数不合法或口令强度不足",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "邮箱已被注册",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "注册过于频繁，触发限流",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request,
                                         HttpServletRequest httpRequest) {
        registrationService.register(
                new RegisterCommand(request.email(), request.password(), request.displayName()),
                clientContextOf(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * 验证邮箱。
     *
     * <p>令牌走请求体而不是 URL 查询串：查询串会进服务端访问日志、反向代理日志与
     * 浏览器历史，而这个令牌能改变账号状态。控制台页面从地址栏取到它之后再 POST 过来。
     *
     * <p>令牌不存在、用途不符、已过期、已被使用<b>一律返回 20021</b>。区分开会告诉
     * 一个拿着随机令牌试探的人「这个令牌真实存在过」，而对真实用户四种情况的处置
     * 完全一样：重新获取链接。
     *
     * @param request 验证请求
     * @return 已验证的邮箱
     */
    @PostMapping("/email/verify")
    @Operation(summary = "验证邮箱",
            description = "消费邮件链接里的一次性令牌，把账号邮箱标记为已验证（ADR 0013）。"
                    + "令牌无效、用途不符、已过期或已被使用都返回同一个错误码 20021。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证成功"),
            @ApiResponse(responseCode = "400", description = "验证链接无效或已过期",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<VerifyEmailResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(
                new VerifyEmailResponse(emailVerificationService.verifyRegistration(request.token())));
    }

    /**
     * 重发验证邮件。
     *
     * <p><b>无论邮箱是否注册、是否已验证，一律返回 204。</b>这个接口不需要登录，
     * 区分开就是一个比注册接口（20010）更便宜的账号枚举通道 —— 那个至少还要提交
     * 一个合法口令并真的建出账号来。
     *
     * <p>限流比其他入口严得多：每次调用都会向外部 SMTP 发一封真实的信，
     * 被刷一轮的后果是发信账号被服务商封禁，那时所有人的验证信都发不出去。
     *
     * @param request     重发请求
     * @param httpRequest 用于取来源 IP 做限流
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/email/resend")
    @Operation(summary = "重发验证邮件",
            description = "向指定邮箱重新发送验证链接。"
                    + "无论该邮箱是否注册、是否已验证都返回 204——"
                    + "区分开会让这个免登录接口变成账号枚举通道。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已受理（不代表该邮箱存在）"),
            @ApiResponse(responseCode = "400", description = "邮箱格式不正确",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "重发过于频繁，触发限流",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request,
                                                    HttpServletRequest httpRequest) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!rateLimiter.tryAcquireVerificationResend(email, httpRequest.getRemoteAddr())) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        emailVerificationService.resendRegistrationVerification(email, httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    /**
     * 找回密码：向已验证的注册邮箱发送重置链接。
     *
     * <p><b>无论邮箱是否注册、是否已验证、账号是否被停用，一律返回 204。</b>
     * 这个接口不需要登录，区分开不仅会泄露「这个邮箱注册过」，还会连带泄露账号状态。
     *
     * <p>只对<b>已验证</b>的邮箱签发重置令牌。未经证实的邮箱能走重置流程，等于任何人
     * 都可以用别人的邮箱注册、再把账号找回到自己手里 —— 这也正是邮箱验证必须排在
     * 本功能之前的全部原因（ADR 0013）。
     *
     * @param request     找回密码请求
     * @param httpRequest 用于取来源 IP 做限流
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/password/forgot")
    @Operation(summary = "找回密码",
            description = "向已验证的注册邮箱发送一次性重置链接（30 分钟有效）。"
                    + "无论邮箱是否注册、是否已验证都返回 204——"
                    + "区分开会让这个免登录接口同时泄露账号是否存在与账号状态。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已受理（不代表该邮箱存在）"),
            @ApiResponse(responseCode = "400", description = "邮箱格式不正确",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "请求过于频繁，触发限流",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                                HttpServletRequest httpRequest) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!rateLimiter.tryAcquirePasswordReset(email, httpRequest.getRemoteAddr())) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        passwordResetService.requestReset(email, httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    /**
     * 用重置令牌设置新口令。
     *
     * <p>成功后<b>该账号的全部会话都会被撤销</b>，包括调用者自己的。重置密码的典型
     * 场景就是「账号可能已经被别人拿到了」，只换口令而不踢掉现有会话，攻击者手里的
     * 刷新令牌照样能一直续期 —— 这次重置等于没做。
     *
     * <p>因此本接口<b>不返回令牌对</b>，用户必须用新口令重新登录一次。
     *
     * @param request 重置请求
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/password/reset")
    @Operation(summary = "重置密码",
            description = "消费邮件链接里的一次性重置令牌并设置新口令。"
                    + "成功后会撤销该账号的全部会话（包括调用者自己的），"
                    + "并作废其余尚未使用的重置链接，因此不返回令牌对，需要重新登录。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "重置成功，请用新口令登录"),
            @ApiResponse(responseCode = "400", description = "链接无效或已过期（20021）、口令强度不足（20011）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * 用刷新令牌换一对新令牌。
     *
     * <p>刷新令牌从 Cookie 读取，<b>不接受请求体或查询参数里的令牌</b>。允许其他来源
     * 会让 HttpOnly 失去意义：脚本读不到 Cookie，但如果接口也认请求体，
     * 攻击者只需诱导用户提交一次表单即可。
     *
     * <p>每次刷新都会轮换：旧令牌立即作废，新令牌写回 Cookie。旧令牌再次出现会被
     * 判定为复用并作废整族，见 {@link RefreshTokenService}。
     *
     * @param httpRequest 当前请求
     * @return 新的访问令牌
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌",
            description = "用 Cookie 中的刷新令牌换取新的访问令牌，并轮换刷新令牌。"
                    + "服务端会重新校验账号状态与成员关系——这是账号停用真正生效的地方。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "刷新成功"),
            @ApiResponse(responseCode = "401", description = "刷新令牌无效、过期、已撤销或被复用",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<LoginResponse> refresh(HttpServletRequest httpRequest) {
        // 没有 Cookie 时传 null，由 application 层统一报 20020。
        // 在这里自己抛的话，api 就得引用 domain 的错误码枚举，跨过了 application 层
        return respondWithSession(refreshTokenService.rotate(
                refreshTokenCookie.read(httpRequest).orElse(null),
                clientContextOf(httpRequest)));
    }

    /**
     * 切换当前项目，重新签发带新 {@code pid} 的令牌对。
     *
     * <p>项目选择是<b>会话状态</b>：它写在刷新令牌上，因此能跨访问令牌刷新存活。
     * 实现上就是一次轮换 —— <b>旧访问令牌里的 pid 随之失效</b>，否则用户切走之后，
     * 旧令牌在剩余有效期内仍能操作原项目的数据。
     *
     * <p><b>非成员一律按「项目不存在」处理</b>（404），不返回「无权限」——
     * 后者等于告诉调用方「这个项目存在，只是你进不去」，可以用来枚举平台上的项目。
     *
     * @param request     目标项目
     * @param httpRequest 当前请求，用于读刷新令牌 Cookie
     * @return 新的访问令牌
     */
    @PostMapping("/switch-project")
    @Operation(summary = "切换当前项目",
            description = "校验成员关系后重新签发令牌，新的访问令牌里带上目标项目的 pid。"
                    + "该选择写在刷新令牌上，跨刷新存活。"
                    + "传 null 表示退出项目上下文。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "切换成功"),
            @ApiResponse(responseCode = "401", description = "未认证或刷新令牌失效",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在或当前账号不是其成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<LoginResponse> switchProject(
            @RequestBody SwitchProjectRequest request, HttpServletRequest httpRequest) {

        UUID projectId = request.projectId() == null || request.projectId().isBlank()
                ? null
                : UUID.fromString(request.projectId());

        if (projectId != null && projectService.roleInProject(projectId).isEmpty()) {
            // 非成员与项目不存在返回同一个码：区分开就成了项目枚举通道
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }

        return respondWithSession(refreshTokenService.switchProject(
                refreshTokenCookie.read(httpRequest).orElse(null),
                projectId,
                clientContextOf(httpRequest)));
    }

    /**
     * 退出登录。
     *
     * <p>作废整族刷新令牌并清除 Cookie。<b>无论令牌是否有效都返回 204</b> ——
     * 退出必须是幂等且总能成功的；让「退出」失败没有任何安全收益，只会把用户困在
     * 一个他想离开的会话里。
     *
     * <p><b>已签发的访问令牌不受影响</b>，最长还能用到过期（15 分钟）。这是无状态
     * 令牌的固有代价：真正的即时失效需要每次请求都查库，那等于放弃无状态。
     * 需要即时踢人的场景（账号被盗）要靠缩短访问令牌有效期，或引入撤销名单。
     *
     * @param httpRequest 当前请求
     * @return HTTP 204 响应，表示本次操作的处理结果
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录",
            description = "作废整族刷新令牌并清除 Cookie。幂等，令牌无效时同样返回 204。"
                    + "注意已签发的访问令牌最长仍可用到其过期时刻。")
    @ApiResponse(responseCode = "204", description = "已退出")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        refreshTokenCookie.read(httpRequest).ifPresent(refreshTokenService::revoke);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString())
                .build();
    }

    /**
     * 把令牌对写成响应：访问令牌进 body，刷新令牌进 Set-Cookie。
     *
     * @param session 令牌对
     * @return 响应
     */
    private ResponseEntity<LoginResponse> respondWithSession(IssuedSession session) {
        AccessToken accessToken = session.accessToken();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshTokenCookie.issue(session.refreshToken(), session.refreshExpiresAt())
                                .toString())
                .body(new LoginResponse(accessToken.value(), accessToken.expiresAt()));
    }

    /**
     * 从请求中提取客户端信息。
     *
     * @param request 当前请求
     * @return 客户端信息
     */
    private static ClientContext clientContextOf(HttpServletRequest request) {
        // G3-SEC-1：容器只解析显式可信代理的转发头；业务层不自行读取XFF。
        return new ClientContext(request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
    }

    /**
     * 取当前登录用户。
     *
     * <p>前端拿到令牌后需要知道「我是谁」才能渲染显示名与按项目角色的菜单。这些信息
     * 刻意<b>不放进令牌</b>：JWT 只是 Base64 编码，放进去等于对任何持有令牌的人
     * 公开（见 {@link JwtTokenIssuer} 的说明）。
     *
     * <p>本接口还承担一个副作用：<b>重新校验账号与成员关系是否仍然有效</b>。
     * 令牌签发后无法撤销，账号在有效期内被停用、或用户被移出租户，只有回库才能
     * 发现。前端在应用启动时调用它，等于每次刷新页面都做一次复核。
     *
     * @param jwt 已通过校验的令牌，由 Spring Security 注入
     * @return 当前用户信息
     */
    @GetMapping("/me")
    @Operation(summary = "当前登录用户",
            description = "返回当前令牌对应的账号、租户、当前项目与项目角色，"
                    + "并重新校验账号状态与成员关系是否仍然有效。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "令牌无效、账号已删除或已被移出租户",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<CurrentUserResponse> currentUser(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = currentUserService.resolve(
                JwtIdentity.accountId(jwt), JwtIdentity.tenantId(jwt));

        // 项目角色每次回库查而不是从令牌读 —— 角色变更必须立即生效
        UUID currentProjectId = JwtIdentity.projectId(jwt);
        ProjectRole projectRole = currentProjectId == null
                ? null
                : projectService.roleInProject(currentProjectId).orElse(null);

        SelfHostedReviewAccess reviewer = reviewAccess.getIfAvailable();
        boolean canReview = reviewer != null && reviewer.isReviewer(user.accountId());
        return ResponseEntity.ok(new CurrentUserResponse(
                user.accountId().toString(),
                user.email(),
                user.displayName(),
                user.tenantId().toString(),
                // 项目角色，不是租户角色（ADR 0012）。未选项目时为 null
                projectRole == null ? null : projectRole.name(),
                menuService.permissionCodesFor(projectRole, commercialAccess.isOperator(user.accountId()), canReview),
                // 直接从令牌读，不回库：当前项目是**会话状态**，令牌就是它的载体。
                // 回库查「这个人最近选了哪个项目」会得到一个跨设备共享的值，
                // 那与「每个会话各自选各自的项目」是两回事
                jwt.getClaimAsString(JwtTokenIssuer.CLAIM_PROJECT_ID)));
    }

}
