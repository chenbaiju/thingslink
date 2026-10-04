package com.things.link.support.idempotency;

import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 即使上游残留可信身份，浏览器身份三POST也必须直接重新校验Cookie代次。 */
class AppBrowserIdempotencyExclusionTests {
    /** 已有身份和幂等键也不能使认证请求进入公共存储，不能靠无身份的早退假绿。 */
    @Test void exactPostsBypassStoreEvenWithAuthenticatedScope() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        var filter = new IdempotencyFilter(store, new ObjectMapper());
        RlsScopeContext.set(new RlsScope(UUID.randomUUID(), UUID.randomUUID()));
        try {
            for (String endpoint : new String[] {"login", "refresh", "logout"}) {
                var request = request("POST", "/api/v1/app/browser-auth/" + endpoint);
                filter.doFilter(request, new MockHttpServletResponse(), (forwarded, response) -> assertThat(forwarded).isSameAs(request));
            }
            verifyNoInteractions(store);
        } finally { RlsScopeContext.clear(); }
    }
    /** 错方法/尾缀和普通业务写仍触达存储保护，不把整个前缀变成幂等例外。 */
    @Test void nearbyWritesStillReachStore() {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.find(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("store reached"));
        var filter = new IdempotencyFilter(store, new ObjectMapper());
        RlsScopeContext.set(new RlsScope(UUID.randomUUID(), UUID.randomUUID()));
        try {
            for (var request : new MockHttpServletRequest[] {request("PUT", "/api/v1/app/browser-auth/login"),
                    request("POST", "/api/v1/app/browser-auth/login/extra"), request("POST", "/api/v1/projects/project/devices")}) {
                assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (incoming, response) -> { }))
                        .isInstanceOf(IllegalStateException.class).hasMessage("store reached");
            }
        } finally { RlsScopeContext.clear(); }
    }
    /** 主体和范围刻意齐全，强制证明路由排除在存储之前。 */
    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Idempotency-Key", "browser-test");
        request.setUserPrincipal(() -> "00000000-0000-0000-0000-000000000001");
        return request;
    }
}
