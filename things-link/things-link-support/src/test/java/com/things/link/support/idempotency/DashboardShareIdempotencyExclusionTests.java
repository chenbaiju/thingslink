package com.things.link.support.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** ADR0101：分享签发必须先到真实安全链，再由领域仲裁；公共层连claim/read都不能执行。 */
class DashboardShareIdempotencyExclusionTests {
    /** 精确创建路径不扩大到撤销或任意匿名写。 */
    private static final String PATH = "/api/v1/projects/project-id/dashboards/dashboard-id/shares";

    /** 两次同key都原样到下游，首次secret响应绝不交给通用幂等存储。 */
    @Test
    void bypassesEveryStoreOperationBeforeAuthenticationAndDomainArbitration() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotencyFilter filter = new IdempotencyFilter(store, new ObjectMapper());
        for (int status : new int[] {201, 409, 401}) {
            var request = new MockHttpServletRequest("POST", PATH);
            request.addHeader("Idempotency-Key", "same-key");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (forwarded, outgoing) -> {
                assertThat(forwarded).isSameAs(request);
                response.setStatus(status);
                response.getWriter().write("{\"secret\":\"sh_test_only\"}");
            });
            assertThat(response.getStatus()).isEqualTo(status);
        }
        verifyNoInteractions(store);
    }

    /** 必须精确方法和路径，其他写依然按既有公共幂等策略处理。 */
    @Test
    void doesNotBroadenDomainBypassToNearbyWrites() {
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", PATH)).isTrue();
        assertThat(IdempotencyFilter.usesDomainIdempotency("PUT", PATH)).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", PATH + "/id/revoke")).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", PATH + "/extra")).isFalse();
    }
}
