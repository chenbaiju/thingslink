package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR0064：低层钉住方法/路径分类和系统故障语义，真实认证及SQL资格另由HTTP集成验证。 */
class AppScopeFilterTests {

    /** 本例可信JWT项目归属，与客户端URL/body无关。 */
    private final UUID tenantId = UUID.randomUUID();
    /** 本例单项目JWT隔离轴。 */
    private final UUID projectId = UUID.randomUUID();
    /** 只替换跨域快照；不据此声称真实事务/数据库已通过。 */
    private final ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
    /** 与安全链相同的三条精确公开路径。 */
    private final AppScopeFilter filter = new AppScopeFilter(lifecycle, new ObjectMapper(), Set.of(
            "/api/v1/app/auth/login", "/api/v1/app/auth/refresh", "/api/v1/app/auth/logout"),
            new WebAppApplicationResolveRequestMatcher());

    /** ARCHIVED保留GET/HEAD，其他受保护方法在业务链前拒绝，不能误用401促使客户端刷新。 */
    @ParameterizedTest
    @CsvSource({"GET,200,true", "HEAD,200,true", "POST,403,false", "DELETE,403,false"})
    void classifiesReadOnlyProjectByHttpMethod(String method, int status, boolean forwarded) throws Exception {
        authenticate(true);
        when(lifecycle.snapshot(tenantId, projectId)).thenReturn(new ProjectAccessPolicy(true, false));
        var result = request(method, "/api/v1/app/devices");
        assertThat(result.response().getStatus()).isEqualTo(status);
        assertThat(result.forwarded()).isEqualTo(forwarded);
        assertThat(RlsScopeContext.current()).contains(new RlsScope(tenantId, projectId));
        if (!forwarded) assertThat(result.response().getContentAsString()).contains("60022");
    }

    /** ADR0099精确POST读取可用于归档项目，近似路径及写方法仍受写禁止约束。 */
    @ParameterizedTest
    @CsvSource({
            "POST,/api/v1/app/devices/snapshots/query,true",
            "POST,/api/v1/app/devices/current-values/query,true",
            "POST,/api/v1/app/alarms/query,true",
            "POST,/api/v1/app/alarms/query/extra,false",
            "POST,/api/v1/app/alarms/id/ack,false",
            "POST,/api/v1/app/devices/snapshots/query/,false",
            "PUT,/api/v1/app/devices/current-values/query,false"
    })
    void archivedProjectAllowsOnlyExactDataQueryPosts(String method, String path, boolean allowed) throws Exception {
        authenticate(true);
        when(lifecycle.snapshot(tenantId, projectId)).thenReturn(new ProjectAccessPolicy(true, false));
        var result = request(method, path);
        assertThat(result.forwarded()).isEqualTo(allowed);
        assertThat(result.response().getStatus()).isEqualTo(allowed ? 200 : 403);
    }

    /** 已删/缺失/错配快照统一无访问资格，读取和写入都不进入业务链。 */
    @ParameterizedTest
    @CsvSource({"GET", "POST"})
    void refusesInvalidProjectBeforeBusinessChain(String method) throws Exception {
        authenticate(true);
        when(lifecycle.snapshot(tenantId, projectId)).thenReturn(new ProjectAccessPolicy(false, false));
        var result = request(method, "/api/v1/app/devices");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("60009");
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 删除前JWT代次与恢复后项目不符时，在建立RLS范围前按原60009拒绝。 */
    @Test
    void refusesStaleProjectGenerationBeforeEstablishingScope() throws Exception {
        authenticate(true, 3L);
        when(lifecycle.snapshot(tenantId, projectId))
                .thenReturn(new ProjectAccessPolicy(true, true, 4L));

        var result = request("GET", "/api/v1/app/devices");

        assertThat(result.forwarded()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("60009");
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 三个公开会话路径自己验证body凭据，OPTIONS继续原安全链，不能新增项目查询。 */
    @ParameterizedTest
    @CsvSource({"POST,/api/v1/app/auth/login", "POST,/api/v1/app/auth/refresh",
            "POST,/api/v1/app/auth/logout", "OPTIONS,/api/v1/app/devices"})
    void leavesPublicSessionsAndOptionsToExistingSecurity(String method, String path) throws Exception {
        var result = request(method, path);
        assertThat(result.forwarded()).isTrue();
        verifyNoInteractions(lifecycle);
    }

    /** 规范应用resolve由安全链与本过滤器共同公开，不能要求匿名请求先携带App JWT。 */
    @Test
    void leavesCanonicalApplicationResolveToPublicController() throws Exception {
        var result = request("GET", "/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve");

        assertThat(result.forwarded()).isTrue();
        verifyNoInteractions(lifecycle);
    }

    /** resolve近似方法和路径不能绕过App身份与项目生命周期门禁。 */
    @ParameterizedTest
    @CsvSource({
            "POST,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve",
            "HEAD,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve",
            "GET,/api/v1/app/applications/app_0123456789ABCDEF0123456789ABCDEF/resolve",
            "GET,/api/v1/app/applications/app_0123456789abcdef0123456789abcdef/resolve/extra"
    })
    void rejectsNearApplicationResolveWithoutIdentity(String method, String path) throws Exception {
        var result = request(method, path);

        assertThat(result.forwarded()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("60009");
        verifyNoInteractions(lifecycle);
    }

    /** 精确路径不能变成前缀放行；签名有效但缺pid也不能绕过豁免RLS的业务表。 */
    @Test
    void rejectsMissingIdentityAndNearPublicPathWithoutQueryingProject() throws Exception {
        authenticate(false);
        var result = request("POST", "/api/v1/app/auth/logout/extra");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("60009");
        verifyNoInteractions(lifecycle);
    }

    /** subject也是必须完整的App身份，签名正确不能替代用户UUID格式检查。 */
    @Test
    void refusesMissingSubjectBeforeLifecycleQuery() throws Exception {
        Jwt jwt = Jwt.withTokenValue("unit-token").header("alg", "HS256")
                .claim(AppTokenIssuer.CLAIM_TENANT_ID, tenantId.toString())
                .claim(AppTokenIssuer.CLAIM_PROJECT_ID, projectId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var result = request("GET", "/api/v1/app/devices");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("60009");
        verifyNoInteractions(lifecycle);
    }

    /** 数据库故障沿90000/500合同退出，不改成业务失效或继续业务链，也不泄露异常正文。 */
    @Test
    void returnsSystemErrorForLifecycleQueryFailure() throws Exception {
        authenticate(true);
        when(lifecycle.snapshot(tenantId, projectId)).thenThrow(new DataAccessResourceFailureException("private-sql-detail"));
        var result = request("GET", "/api/v1/app/devices");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(500);
        assertThat(result.response().getContentAsString()).contains("90000").doesNotContain("private-sql-detail", "60009");
    }

    /** 仅认证结果替身；密码学校验属于完整安全链测试，不能由本例替代。 */
    private void authenticate(boolean withProject) {
        authenticate(withProject, null);
    }

    /** 构造带可选项目代次的已验签身份；null模拟滚动升级前缺少pgv的旧JWT。 */
    private void authenticate(boolean withProject, Long projectGeneration) {
        Jwt.Builder jwt = Jwt.withTokenValue("unit-token").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .claim(AppTokenIssuer.CLAIM_TENANT_ID, tenantId.toString());
        if (withProject) jwt.claim(AppTokenIssuer.CLAIM_PROJECT_ID, projectId.toString());
        if (projectGeneration != null) {
            jwt.claim(AppTokenIssuer.CLAIM_PROJECT_GENERATION, projectGeneration);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
    }

    /** 只记录是否到达业务链；真实controller副作用另以数据库HTTP反例验证。 */
    private Result request(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> forwarded.set(true));
        return new Result(response, forwarded.get());
    }

    /** 单测没有最外层TenantContextFilter，显式清理自身上下文；集成测试验证生产清理。 */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RlsScopeContext.clear();
    }

    /** @param response 实际过滤器输出 @param forwarded 是否进入后续业务链 */
    private record Result(MockHttpServletResponse response, boolean forwarded) { }
}
