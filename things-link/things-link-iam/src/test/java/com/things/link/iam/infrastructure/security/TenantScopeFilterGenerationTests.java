package com.things.link.iam.infrastructure.security;

import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR0073控制台JWT代次必须在建立TenantContext和进入业务链之前权威复核。 */
class TenantScopeFilterGenerationTests {

    /** JWT固定可信三元组；测试仅改变项目代次声明及权威快照。 */
    private final UUID accountId = UUID.randomUUID();
    /** 当前会话租户必须保留到匹配成功的范围。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 当前选中项目用于代次权威查询。 */
    private final UUID projectId = UUID.randomUUID();
    /** 只替换项目代次端口，不用业务Controller结果冒充过滤器边界。 */
    private final ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
    /** 与生产安全配置相同的过滤器。 */
    private final TenantScopeFilter filter = new TenantScopeFilter(lifecycle, new ObjectMapper());

    /** 缺失pgv按零代兼容；显式当前代次也均在快照匹配后建立完整范围。 */
    @Test
    void missingZeroAndExplicitCurrentGenerationEstablishScope() throws Exception {
        when(lifecycle.tokenSnapshot(accountId, projectId))
                .thenReturn(new ProjectAccessPolicy(true, true, 0L),
                        new ProjectAccessPolicy(true, true, 7L));

        authenticate(null);
        Result legacy = request();
        assertThat(legacy.forwarded()).isTrue();
        assertThat(TenantContext.current()).contains(new TenantScope(tenantId, projectId, accountId));
        TenantContext.clear();

        authenticate(7L);
        Result current = request();
        assertThat(current.forwarded()).isTrue();
        assertThat(TenantContext.current()).contains(new TenantScope(tenantId, projectId, accountId));
        verify(lifecycle, org.mockito.Mockito.times(2)).tokenSnapshot(accountId, projectId);
    }

    /** 权威代次不匹配按既有20020终止，不能先建立范围或进入业务。 */
    @Test
    void staleGenerationRejectsBeforeScopeAndBusiness() throws Exception {
        authenticate(3L);
        when(lifecycle.tokenSnapshot(accountId, projectId))
                .thenReturn(new ProjectAccessPolicy(true, true, 4L));

        Result result = request();

        assertInvalid(result);
        verify(lifecycle).tokenSnapshot(accountId, projectId);
    }

    /** 负数、小数和字符串pgv在项目查询前即为20020，不能把强制转换结果当代次。 */
    @Test
    void malformedGenerationRejectsBeforeProjectQuery() throws Exception {
        for (Object invalid : java.util.List.of(-1L, 1.5D, "1")) {
            authenticate(invalid);
            assertInvalid(request());
            SecurityContextHolder.clearContext();
        }
        verifyNoInteractions(lifecycle);
    }

    /** 无pid的账号级JWT正常建立空项目范围，且绝不读取项目快照。 */
    @Test
    void tokenWithoutProjectDoesNotQueryLifecycle() throws Exception {
        authenticateWithoutProject();

        Result result = request();

        assertThat(result.forwarded()).isTrue();
        assertThat(TenantContext.current()).contains(new TenantScope(tenantId, null, accountId));
        verifyNoInteractions(lifecycle);
    }

    /** 构造带项目及可选任意pgv形状的已验签JWT。 */
    private void authenticate(Object generation) {
        Jwt.Builder builder = baseJwt().claim(JwtTokenIssuer.CLAIM_PROJECT_ID, projectId.toString());
        if (generation != null) builder.claim(JwtTokenIssuer.CLAIM_PROJECT_GENERATION, generation);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
    }

    /** 构造不含项目声明的账号级JWT。 */
    private void authenticateWithoutProject() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(baseJwt().build()));
    }

    /** 生产解析所需subject与tid均使用真实UUID格式。 */
    private Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("console-unit-token").header("alg", "HS256")
                .subject(accountId.toString())
                .claim(JwtTokenIssuer.CLAIM_TENANT_ID, tenantId.toString());
    }

    /** 执行一次过滤器并记录是否进入后续业务链。 */
    private Result request() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/devices");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> forwarded.set(true));
        return new Result(response, forwarded.get());
    }

    /** 既有IAM无效令牌合同为HTTP 401/code 20020，且失败不能留下线程范围。 */
    private void assertInvalid(Result result) throws Exception {
        assertThat(result.forwarded()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(new ObjectMapper().readTree(result.response().getContentAsString()).get("code").asInt())
                .isEqualTo(20020);
        assertThat(TenantContext.current()).isEmpty();
    }

    /** 每例清除直接过滤器测试建立的SecurityContext与TenantContext。 */
    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /** @param response 实际过滤器响应 @param forwarded 是否到达业务链 */
    private record Result(MockHttpServletResponse response, boolean forwarded) {
    }
}
