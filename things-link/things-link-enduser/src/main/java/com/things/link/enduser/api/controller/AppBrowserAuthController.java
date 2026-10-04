package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.request.AppBrowserEpochRequest;
import com.things.link.enduser.api.dto.request.AppBrowserLoginRequest;
import com.things.link.enduser.api.dto.response.AppBrowserSessionResponse;
import com.things.link.enduser.api.support.AppBrowserRequestParser;
import com.things.link.enduser.application.AppBrowserCookieBinding;
import com.things.link.enduser.application.AppBrowserProperties;
import com.things.link.enduser.application.AppBrowserRefreshCookieCodec;
import com.things.link.enduser.application.AppBrowserSessionService;
import com.things.link.enduser.application.AppSessionService;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.token.OpaqueToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

/** ADR0107独立浏览器适配，先比较期望代次再允许刷新/撤族或Cookie删除。 */
@RestController
@RequestMapping("/api/v1/app/browser-auth")
@ApiResponse(responseCode = "400", description = "请求参数不合法")
@ApiResponse(responseCode = "401", description = "登录或Cookie会话无效")
@ApiResponse(responseCode = "403", description = "来源不允许")
@ApiResponse(responseCode = "409", description = "Cookie不属于请求期望代次；无副作用")
@ApiResponse(responseCode = "413", description = "JSON超过8KiB")
@ApiResponse(responseCode = "503", description = "浏览器认证入口未启用")
public class AppBrowserAuthController {
    /** 名称/Path与Console彻底独立，删除时必须复用全部属性。 */
    private static final String COOKIE_NAME = "tc_app_refresh";
    /** 仅三个浏览器身份端点共享Cookie，不发送到业务API。 */
    private static final String COOKIE_PATH = "/api/v1/app/browser-auth";
    /** 防止宽松对象绑定与无界输入分配。 */
    private final AppBrowserRequestParser parser;
    /** 同原事务完成原子替换与封装编码。 */
    private final AppBrowserSessionService browser;
    /** logout保留原用户锁与幂等撤族语义。 */
    private final AppSessionService sessions;
    /** 启动已验证的独立编码器，不信任请求传入身份。 */
    private final AppBrowserRefreshCookieCodec codec;
    /** Cookie属性来自可信部署配置。 */
    private final AppBrowserProperties properties;

    /** 明确区分封装验证与真实PG会话副作用。 */
    public AppBrowserAuthController(AppBrowserRequestParser parser, AppBrowserSessionService browser,
            AppSessionService sessions, AppBrowserRefreshCookieCodec codec, AppBrowserProperties properties) {
        this.parser = parser;
        this.browser = browser;
        this.sessions = sessions;
        this.codec = codec;
        this.properties = properties;
    }

    /** 新epoch不需等于旧Cookie；坏单Cookie无撤旧能力，但仍须通过完整新密码认证。 */
    @PostMapping(value = "/login", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "loginAppBrowser", summary = "浏览器登录并替换旧会话",
            description = "必须同源JSON请求且不附Bearer；浏览器自动管理tc_app_refresh HttpOnly Cookie，不接收refresh正文，不适用公共幂等缓存。", requestBody = @RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = AppBrowserLoginRequest.class))))
    @ApiResponse(responseCode = "200", description = "仅访问令牌及实际身份；刷新凭据在HttpOnly Cookie",
            content = @Content(schema = @Schema(implementation = AppBrowserSessionResponse.class)))
    public ResponseEntity<AppBrowserSessionResponse> login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AppBrowserLoginRequest input = parser.login(request);
        String oldCookie = cookie(request);
        byte[] oldHash = null;
        if (oldCookie != null) {
            try { oldHash = OpaqueToken.hash(codec.decode(oldCookie).refreshToken()); }
            catch (IllegalArgumentException invalidCookie) { /* 坏Cookie不能定位旧族，也不能阻止明确新密码登录恢复。 */ }
        }
        return success(browser.login(input.projectKey(), input.username(), input.password(), request.getRemoteAddr(), input.browserEpoch(), oldHash), response);
    }

    /** 当前匹配Cookie才调用rotate，旧Cookie不触发独立提交的复用撤族。 */
    @PostMapping(value = "/refresh", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "refreshAppBrowser", summary = "按浏览器代次轮换会话",
            description = "必须同源JSON及专用HttpOnly Cookie；期望代次不匹配拒绝且不清Cookie，响应不含refresh，不自动重试未知结果。", requestBody = @RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = AppBrowserEpochRequest.class))))
    @ApiResponse(responseCode = "200", description = "轮换保持请求代次",
            content = @Content(schema = @Schema(implementation = AppBrowserSessionResponse.class)))
    public ResponseEntity<AppBrowserSessionResponse> refresh(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String epoch = parser.epoch(request);
        var result = binding(epoch, request);
        if (result.status() == AppBrowserCookieBinding.Status.EXPIRED) {
            clear(response);
            throw invalidRefresh();
        }
        requireValid(result);
        try { return success(browser.refresh(epoch, result.cookie().refreshToken()), response); }
        catch (BusinessException failure) {
            if (failure.errorCode() == EndUserErrorCode.END_USER_REFRESH_INVALID) clear(response);
            throw failure;
        }
    }

    /** 请求必须携带退出前capturedEpoch；缺Cookie幂等，DB失败不清Cookie或伪报已退出。 */
    @PostMapping(value = "/logout", consumes = "application/json")
    @Operation(operationId = "logoutAppBrowser", summary = "退出匹配浏览器代次的会话",
            description = "必须同源JSON，提交退出前捕获的代次；浏览器自动携带专用Cookie，缺Cookie幂等204，数据库失败不清Cookie。", requestBody = @RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = AppBrowserEpochRequest.class))))
    @ApiResponse(responseCode = "204", description = "匹配会话已撤销或Cookie不存在")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var result = binding(parser.epoch(request), request);
        if (result.status() == AppBrowserCookieBinding.Status.MISSING) return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        if (result.status() != AppBrowserCookieBinding.Status.EXPIRED) requireValid(result);
        sessions.revoke(result.cookie().refreshToken());
        clear(response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 同名Cookie必须唯一，不取第一个或把重复值当作可恢复的坏单Cookie。 */
    private static String cookie(HttpServletRequest request) {
        String found = null;
        boolean present = false;
        for (String header : Collections.list(request.getHeaders(HttpHeaders.COOKIE))) {
            for (String part : header.split(";", -1)) {
                String item = part.trim();
                int separator = item.indexOf('=');
                if (separator < 0) {
                    // 裸同名项也是歧义凭据，不能跳过后再选择另一条有效Cookie。
                    if (COOKIE_NAME.equals(item)) throw invalidRefresh();
                    continue;
                }
                if (!COOKIE_NAME.equals(item.substring(0, separator).trim())) continue;
                if (present) throw invalidRefresh();
                present = true;
                found = item.substring(separator + 1);
            }
        }
        return found;
    }
    /** 纯绑定先比较epoch再判断到期，控制器不能自行交换次序。 */
    private AppBrowserCookieBinding.Result binding(String epoch, HttpServletRequest request) {
        return new AppBrowserCookieBinding(codec).verify(epoch, cookie(request), Instant.now());
    }
    /** 不匹配错误与无效Cookie不同，但两者都不写Cookie或猜测身份。 */
    private static void requireValid(AppBrowserCookieBinding.Result result) {
        switch (result.status()) {
            case VALID -> { }
            case EPOCH_MISMATCH -> throw new BusinessException(EndUserErrorCode.APP_BROWSER_EPOCH_MISMATCH);
            case INVALID_EPOCH -> throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
            default -> throw invalidRefresh();
        }
    }
    /** 事务服务已成功提交，显式投影四字段而非序列化内部含Cookie载体。 */
    private ResponseEntity<AppBrowserSessionResponse> success(AppBrowserSessionService.Issued issued, HttpServletResponse response) {
        long seconds = Math.max(0, Duration.between(Instant.now(), issued.refreshExpiresAt()).getSeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookieBuilder(issued.cookie()).maxAge(Duration.ofSeconds(seconds)).build().toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AppBrowserSessionResponse(
                issued.accessToken(), issued.accessExpiresAt(), issued.appUserId(), issued.projectId()));
    }
    /** 正式HTTPS必须Secure；唯一例外是启动已验证的显式loopback开发配置。 */
    private ResponseCookie.ResponseCookieBuilder cookieBuilder(String value) {
        return ResponseCookie.from(COOKIE_NAME, value).path(COOKIE_PATH).httpOnly(true)
                .secure(properties.origin().startsWith("https://")).sameSite("Strict");
    }
    /** 删除与签发保持同名同Path及同安全属性，不删除ConsoleCookie。 */
    private void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookieBuilder("").maxAge(Duration.ZERO).build().toString());
    }
    /** 保留既有App失效码，不向客户端暴露不存在/已撤销/角色失效分类。 */
    private static BusinessException invalidRefresh() { return new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID); }
}
