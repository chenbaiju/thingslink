package com.things.link.support.idempotency;

import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 上传一次消费不预读正文；创建领域恢复与取消公共墓碑明确分开。 */
class ArtifactUploadIdempotencyTests {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String BASE = "/api/v1/projects/" + ID + "/ota/firmwares/" + ID + "/uploads";
    @Test void exactCreationDelegatesButCancelAndNeighboursDoNot() {
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", BASE)).isTrue();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", BASE + "/" + ID + "/cancel")).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("PUT", BASE)).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", BASE + "/")).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", BASE.replace(ID, "1-1-1-1-1"))).isFalse();
    }
    @Test void authenticatedContentWithKeyNeverReadsOrCreatesCommonRecord() throws Exception {
        var scope = new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var store = mock(IdempotencyStore.class);
        var request = new MockHttpServletRequest("PUT", BASE + "/" + ID + "/content");
        request.setUserPrincipal(() -> scope.accountId().toString());
        request.addHeader("Idempotency-Key", "content-key");
        request.setContent(new byte[] { 1, 2, 3 });
        TenantContext.set(scope);
        try {
            new IdempotencyFilter(store, JsonMapper.builder().build()).doFilter(request,
                    new MockHttpServletResponse(), (forwarded, response) -> {
                        assertThat(forwarded).isSameAs(request);
                        assertThat(forwarded.getInputStream().read()).isEqualTo(1);
                    });
            verifyNoInteractions(store);
        } finally { TenantContext.clear(); }
    }
}
