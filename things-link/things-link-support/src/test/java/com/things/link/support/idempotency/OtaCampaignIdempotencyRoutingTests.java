package com.things.link.support.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** OTA只有创建具备持久领域恢复；取消保留公共完成墓碑，不能扩大豁免。 */
class OtaCampaignIdempotencyRoutingTests {
    /** 精确创建路由与HTTP方法共同构成豁免身份。 */
    private static final String PATH = "/api/v1/projects/project/ota/campaigns";

    /** 公共存储不能截获创建重放，真实授权与领域映射每次都必须执行。 */
    @Test
    void creationAlwaysReachesDomainWithoutCachingResponses() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotencyFilter filter = new IdempotencyFilter(store, new ObjectMapper());
        for (int status : new int[] {201, 403, 409}) {
            var request = new MockHttpServletRequest("POST", PATH);
            request.addHeader("Idempotency-Key", "test-key");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (forwarded, outgoing) -> response.setStatus(status));
            assertThat(response.getStatus()).isEqualTo(status);
        }
        verifyNoInteractions(store);
    }

    /** 相邻取消、排程、其他方法不可因共享路径前缀绕过公共保护。 */
    @Test
    void preservesGuardForAdjacentWrites() {
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", PATH)).isTrue();
        assertThat(IdempotencyFilter.usesDomainIdempotency("PUT", PATH)).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", PATH + "/id/cancellation")).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", PATH + "/id/scheduling")).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", PATH + "-other")).isFalse();
    }
}
