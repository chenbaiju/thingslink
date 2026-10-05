package com.things.link.support.idempotency;

import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** S12数据POST查询每次重新授权读取，公共幂等不能保存或阻挡其结果。 */
class IdempotencyReadQueryTests {
    /** 本例只观察是否触碰存储，不模拟任何业务授权通过。 */
    private final IdempotencyStore store = mock(IdempotencyStore.class);

    /** 同一key连续查询都进入业务，近似方法/路径必须仍进入公共幂等存储。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/app/devices/snapshots/query", "/api/v1/app/devices/current-values/query",
            "/api/v1/projects/id/assistant/fact-reports/collection",
            "/api/v1/app/alarms/query", "/api/v1/projects/id/devices/snapshots/query",
            "/api/v1/projects/id/devices/current-value-snapshots/query", "/api/v1/projects/id/alarms/query"
    })
    void exactQueryPostsAreFreshButNearWritesAreGuarded(String path) throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(store, new ObjectMapper());
        RlsScopeContext.set(new RlsScope(UUID.randomUUID(), UUID.randomUUID()));
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            filter.doFilter(request("POST", path), new MockHttpServletResponse(), (req, res) -> calls.incrementAndGet());
        }
        assertThat(calls).hasValue(2);
        verifyNoInteractions(store);
        filter.doFilter(request("PUT", path), new MockHttpServletResponse(), (req, res) -> calls.incrementAndGet());
        verify(store).tryAcquire(any());
        assertThat(calls).hasValue(2);
    }

    /** 可信主体与scope由外层安全链建立，此处仅固定过滤器前置输入。 */
    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setUserPrincipal(() -> "filter-test-subject");
        request.addHeader("Idempotency-Key", "same-query-key");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    /** 单测结束清除自身创建的范围，避免下个测试继承租户事实。 */
    @AfterEach void clearScope() { RlsScopeContext.clear(); }
}
