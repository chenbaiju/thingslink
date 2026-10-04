package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.support.AppBrowserRequestParser;
import com.things.link.enduser.application.AppBrowserProperties;
import com.things.link.enduser.application.AppBrowserRefreshCookieCodec;
import com.things.link.enduser.application.AppBrowserSessionService;
import com.things.link.enduser.application.AppSessionService;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Cookie属性与副作用矩阵纯测试，真实PG原子替换另由Bootstrap覆盖。 */
class AppBrowserAuthControllerTests {
    /** 当前非凭据代次。 */
    private static final String EPOCH = "be_" + "A".repeat(22);
    /** 合法但与当前不同的旧代次。 */
    private static final String OLD_EPOCH = "be_" + "B".repeat(21) + "A";
    /** 有事务的浏览器签发端口替身。 */
    private final AppBrowserSessionService browser = mock(AppBrowserSessionService.class);
    /** 旧会话撤销替身。 */
    private final AppSessionService sessions = mock(AppSessionService.class);
    /** 最低层控制绑定结果，不把替身作为签验实证。 */
    private final AppBrowserRefreshCookieCodec codec = mock(AppBrowserRefreshCookieCodec.class);
    /** HTTPS属性必须在本层直接证明，loopback真实HTTP不是Secure资格。 */
    private final AppBrowserAuthController controller = new AppBrowserAuthController(new AppBrowserRequestParser(), browser, sessions, codec,
            new AppBrowserProperties(true, "https://share.test", false, null, null, null, null));

    /** HTTPS成功Cookie精确独立路径、Strict、HttpOnly、Secure且响应只有四公开字段。 */
    @Test void refreshIssuesSecureHostOnlyCookie() throws Exception {
        verified(EPOCH, Instant.now().plusSeconds(60));
        when(browser.refresh(anyString(), anyString())).thenReturn(new AppBrowserSessionService.Issued("access", Instant.now().plusSeconds(15),
                UUID.randomUUID(), UUID.randomUUID(), "wrapped.cookie.value", Instant.now().plusSeconds(60)));
        var response = new MockHttpServletResponse();
        var result = controller.refresh(request(), response);
        assertThat(response.getHeader("Set-Cookie")).contains("tc_app_refresh=wrapped.cookie.value", "Path=/api/v1/app/browser-auth", "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain=");
        assertThat(result.getBody().accessToken()).isEqualTo("access");
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
    /** 不匹配先于过期；任何会话方法和清Cookie都不得执行。 */
    @Test void mismatchedExpiredCookieHasNoSideEffects() {
        verified(OLD_EPOCH, Instant.now().minusSeconds(60));
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.refresh(request(), response)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(60029));
        assertThatThrownBy(() -> controller.logout(request(), response)).isInstanceOf(BusinessException.class);
        assertThat(response.getHeader("Set-Cookie")).isNull();
        verifyNoInteractions(browser, sessions);
    }
    /** 内部故障不清Cookie，不把失败的撤销或刷新报告成成功。 */
    @Test void dependencyFailureDoesNotDeleteCookie() {
        verified(EPOCH, Instant.now().plusSeconds(60));
        when(browser.refresh(anyString(), anyString())).thenThrow(new IllegalStateException("test dependency"));
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.refresh(request(), response)).isInstanceOf(IllegalStateException.class);
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }
    /** 匹配代次过期才允许删除，并保持签发相同安全属性。 */
    @Test void matchedExpiryDeletesWithSameSecureAttributes() {
        verified(EPOCH, Instant.now().minusSeconds(60));
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.refresh(request(), response)).isInstanceOf(BusinessException.class);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0", "Secure", "HttpOnly", "SameSite=Strict", "Path=/api/v1/app/browser-auth");
        verifyNoInteractions(browser, sessions);
    }
    /** 重复同名Cookie拒绝而不选择第一个，也不调用解码器猜归属。 */
    @Test void duplicateCookieNeverSelectsAnIdentity() {
        var request = request(); request.addHeader("Cookie", "tc_app_refresh=second");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.refresh(request, response)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(codec, browser, sessions);
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }
    /** 裸同名项不能被忽略后选择后面的有效Cookie，单独裸名也必须拒绝。 */
    @Test void bareCookieNameNeverFallsThroughToValidValue() {
        for (String header : new String[] {"tc_app_refresh", "tc_app_refresh; tc_app_refresh=wrapped", "tc_app_refresh=wrapped; tc_app_refresh"}) {
            var request = request();
            request.removeHeader("Cookie");
            request.addHeader("Cookie", header);
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> controller.refresh(request, response)).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode().code()).isEqualTo(60007));
            assertThat(response.getHeader("Set-Cookie")).isNull();
        }
        verifyNoInteractions(codec, browser, sessions);
    }

    /** 已验证值仅为控制器行为测试。 */
    private void verified(String epoch, Instant until) {
        when(codec.decode(any())).thenReturn(new AppBrowserRefreshCookieCodec.VerifiedCookie(epoch, "refresh", until));
    }
    /** 每次返回新流，避免已消费请求假阳性。 */
    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.addHeader("Cookie", "tc_app_refresh=wrapped");
        request.setContent(("{\"browserEpoch\":\"" + EPOCH + "\"}").getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
