package com.things.link.iam.infrastructure.security;

import com.things.link.iam.application.RestQuotaPolicy;
import com.things.link.iam.application.RestQuotaPolicyResolver;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** REST 配额过滤器必须保持豁免、套餐语义、故障降级和统一错误响应。 */
class QuotaRestRateLimitFilterTests {

    /** 测试使用的 JSON 编解码器，与生产过滤器的响应结构保持一致。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 归档预检仅认领选中项目的精确管理写路径，不能阻断项目恢复或把GET当写。 */
    @Test void ruleWriteClassificationDoesNotCaptureRestoreOrUnknownRoutes() {
        UUID project = UUID.randomUUID(), rule = UUID.randomUUID(), version = UUID.randomUUID();
        String base = "/api/v1/projects/" + project;
        for (String path : List.of("/message-rules", "/scenes", "/message-rules/" + rule + "/pause",
                "/message-rules/" + rule + "/versions/" + version + "/debug", "/scenes/" + rule + "/executions")) {
            assertThat(QuotaRestRateLimitFilter.isRuleManagementWrite(new MockHttpServletRequest("POST", base + path), project)).isTrue();
            assertThat(QuotaRestRateLimitFilter.isRuleManagementWrite(new MockHttpServletRequest("GET", base + path), project)).isFalse();
        }
        for (String path : List.of(base + "/restore", base + "/scenes/unknown/executions", base + "/message-rules/" + rule + "/unknown"))
            assertThat(QuotaRestRateLimitFilter.isRuleManagementWrite(new MockHttpServletRequest("POST", path), project)).isFalse();
        assertThat(QuotaRestRateLimitFilter.isRuleManagementWrite(new MockHttpServletRequest("POST", base + "/message-rules"), UUID.randomUUID())).isFalse();
    }

    /** 产品凭据早期归档检查只覆盖精确选中项目及POST，不授予读路径或恢复路径写资格。 */
    @Test void productCredentialClassificationIsExactAndContextAware() {
        UUID project = UUID.randomUUID(), type = UUID.randomUUID();
        String path = "/api/v1/projects/" + project + "/device-types/" + type + "/product-credential";
        assertThat(QuotaRestRateLimitFilter.isProductCredentialWrite(new MockHttpServletRequest("POST", path), project)).isTrue();
        for (String method : List.of("GET", "PUT", "DELETE"))
            assertThat(QuotaRestRateLimitFilter.isProductCredentialWrite(new MockHttpServletRequest(method, path), project)).isFalse();
        for (String suffix : List.of("/extra", "s", "/", "?ignored=true"))
            assertThat(QuotaRestRateLimitFilter.isProductCredentialWrite(new MockHttpServletRequest("POST", path + suffix), project)).isFalse();
        assertThat(QuotaRestRateLimitFilter.isProductCredentialWrite(new MockHttpServletRequest("POST", path), UUID.randomUUID())).isFalse();
        assertThat(QuotaRestRateLimitFilter.isProductCredentialWrite(new MockHttpServletRequest("POST", path.replace(type.toString(), "unknown")), project)).isFalse();
        MockHttpServletRequest contextual = new MockHttpServletRequest("POST", "/console" + path);
        contextual.setContextPath("/console");
        assertThat(QuotaRestRateLimitFilter.isProductCredentialWrite(contextual, project)).isTrue();
    }

    /** 仅信任包导入新增归档预检，方法、合法域、选中项目及上下文路径必须同时匹配。 */
    @Test void otaTrustImportClassificationIsExactAndContextAware() {
        UUID project = UUID.randomUUID();
        String base = "/api/v1/projects/" + project + "/ota/trust-domains/";
        String path = base + "release.domain_1-a/bundles";
        for (String domain : List.of("a", "release.domain_1-a", "a".repeat(64)))
            assertThat(QuotaRestRateLimitFilter.isOtaTrustImportWrite(
                    new MockHttpServletRequest("POST", base + domain + "/bundles"), project)).isTrue();
        for (String method : List.of("GET", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS"))
            assertThat(QuotaRestRateLimitFilter.isOtaTrustImportWrite(new MockHttpServletRequest(method, path), project)).isFalse();
        for (String suffix : List.of("/extra", "s", "/", "?ignored=true"))
            assertThat(QuotaRestRateLimitFilter.isOtaTrustImportWrite(new MockHttpServletRequest("POST", path + suffix), project)).isFalse();
        for (String domain : List.of("", ".domain", "-domain", "a".repeat(65), "bad/domain", "bad%2Fdomain", "bad;domain"))
            assertThat(QuotaRestRateLimitFilter.isOtaTrustImportWrite(
                    new MockHttpServletRequest("POST", base + domain + "/bundles"), project)).isFalse();
        assertThat(QuotaRestRateLimitFilter.isOtaTrustImportWrite(new MockHttpServletRequest("POST", path), UUID.randomUUID())).isFalse();
        for (String other : List.of(base + "release.domain_1-a/keys", base + "release.domain_1-a",
                "/api/v1/projects/" + project + "/ota/device-types/" + UUID.randomUUID() + "/baseline",
                "/api/v1/projects/" + project + "/restore"))
            assertThat(QuotaRestRateLimitFilter.isOtaTrustImportWrite(new MockHttpServletRequest("POST", other), project)).isFalse();
        MockHttpServletRequest contextual = new MockHttpServletRequest("POST", "/console" + path);
        contextual.setContextPath("/console");
        assertThat(QuotaRestRateLimitFilter.isOtaTrustImportWrite(contextual, project)).isTrue();
    }

    /** 类型基线登记只认选中项目和精确POST，不捕获管理GET、版本历史或其他类型管理写。 */
    @Test void otaTypeBaselineRegistrationClassificationIsExactAndContextAware() {
        UUID project = UUID.randomUUID(), type = UUID.randomUUID();
        String base = "/api/v1/projects/" + project + "/ota/device-types/";
        String path = base + type + "/baseline/registrations";
        assertThat(QuotaRestRateLimitFilter.isOtaTypeBaselineRegistrationWrite(new MockHttpServletRequest("POST", path), project)).isTrue();
        assertThat(QuotaRestRateLimitFilter.isOtaTypeBaselineRegistrationWrite(
                new MockHttpServletRequest("POST", base + type.toString().toUpperCase(java.util.Locale.ROOT) + "/baseline/registrations"), project)).isTrue();
        for (String method : List.of("GET", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS"))
            assertThat(QuotaRestRateLimitFilter.isOtaTypeBaselineRegistrationWrite(new MockHttpServletRequest(method, path), project)).isFalse();
        for (String suffix : List.of("/extra", "s", "/", "?ignored=true"))
            assertThat(QuotaRestRateLimitFilter.isOtaTypeBaselineRegistrationWrite(new MockHttpServletRequest("POST", path + suffix), project)).isFalse();
        for (String identity : List.of("", "unknown", type.toString().replace("-", ""), type + "/extra", "%2F" + type))
            assertThat(QuotaRestRateLimitFilter.isOtaTypeBaselineRegistrationWrite(
                    new MockHttpServletRequest("POST", base + identity + "/baseline/registrations"), project)).isFalse();
        assertThat(QuotaRestRateLimitFilter.isOtaTypeBaselineRegistrationWrite(new MockHttpServletRequest("POST", path), UUID.randomUUID())).isFalse();
        for (String other : List.of(base + type + "/baseline", base + type + "/baseline/versions",
                "/api/v1/projects/" + project + "/restore", "/api/v1/projects/" + project + "/device-types/" + type + "/product-credential"))
            assertThat(QuotaRestRateLimitFilter.isOtaTypeBaselineRegistrationWrite(new MockHttpServletRequest("POST", other), project)).isFalse();
        MockHttpServletRequest contextual = new MockHttpServletRequest("POST", "/console" + path);
        contextual.setContextPath("/console");
        assertThat(QuotaRestRateLimitFilter.isOtaTypeBaselineRegistrationWrite(contextual, project)).isTrue();
    }

    /** 两个受控OTA写入口通过生命周期预检后仍受日硬限、短窗和实际计量约束，不新增配额豁免。 */
    @ParameterizedTest
    @CsvSource({"trust,normal", "trust,daily", "trust,window", "baseline,normal", "baseline,daily", "baseline,window", "contact,normal", "contact,daily", "contact,window"})
    void activeControlledOtaWritesStillUseOriginalQuotaAdmission(String route, String outcome) throws Exception {
        var lifecycle = mock(com.things.link.project.application.ProjectLifecycleAccessService.class);
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 1L, 1L, 10L, 10L), lifecycle);
        TenantScope scope = new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(fixture.redis().execute(any(), anyList(), any(Object[].class))).thenReturn("window".equals(outcome) ? 0L : 1L);
        if ("daily".equals(outcome)) when(fixture.daily().decideTrustedProject(any(), any(), any()))
                .thenReturn(com.things.link.project.application.QuotaStatus.HARD_LIMIT);
        String suffix = "contact".equals(route) ? "/end-users/" + UUID.randomUUID() + "/notification-contact" : "baseline".equals(route) ? "/ota/device-types/" + UUID.randomUUID() + "/baseline/registrations"
                : "/ota/trust-domains/release.domain/bundles";
        MockHttpServletResponse response = invoke(fixture.filter(), "contact".equals(route) ? "PUT" : "POST", "/api/v1/projects/" + scope.projectId() + suffix, scope);
        verify(lifecycle).requireWritableAccountSnapshot(scope.accountId(), scope.projectId());
        verify(fixture.resolver()).resolve(scope.projectId());
        verify(fixture.daily()).decideTrustedProject(any(), any(), any());
        if ("normal".equals(outcome)) {
            assertThat(response.getStatus()).isEqualTo(200);
            verify(fixture.recorder()).record(any(), any(), any(), any(), any());
        } else {
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asInt()).isEqualTo(10029);
            verify(fixture.recorder(), org.mockito.Mockito.never()).record(any(), any(), any(), any(), any());
        }
    }

    @Test void notificationContactClassificationIsNarrow() {
        UUID project = UUID.randomUUID();
        String path = "/api/v1/projects/" + project + "/end-users/" + UUID.randomUUID() + "/notification-contact";
        assertThat(QuotaRestRateLimitFilter.isNotificationContactWrite(new MockHttpServletRequest("PUT", path), project)).isTrue();
        for (String method : List.of("GET", "POST", "DELETE", "HEAD"))
            assertThat(QuotaRestRateLimitFilter.isNotificationContactWrite(new MockHttpServletRequest(method, path), project)).isFalse();
        for (String suffix : List.of("/", "/extra", "s"))
            assertThat(QuotaRestRateLimitFilter.isNotificationContactWrite(new MockHttpServletRequest("PUT", path + suffix), project)).isFalse();
        assertThat(QuotaRestRateLimitFilter.isNotificationContactWrite(new MockHttpServletRequest("PUT", path), UUID.randomUUID())).isFalse();
        var contextual = new MockHttpServletRequest("PUT", "/console" + path);
        contextual.setContextPath("/console");
        assertThat(QuotaRestRateLimitFilter.isNotificationContactWrite(contextual, project)).isTrue();
    }

    /** 每个测试清除 ThreadLocal，避免后续用例继承错误的项目范围。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** 0 是明确禁用而非未配置，写请求必须直接返回统一 429，且不触碰 Redis。 */
    @Test
    void rejectsExplicitlyDisabledWriteWithRetryAfter() throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), null, 0L, null, 0L));

        MockHttpServletResponse response = invoke(fixture.filter(), "POST", "/api/v1/projects/devices");

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asInt()).isEqualTo(10029);
        verify(fixture.redis(), org.mockito.Mockito.never()).execute(any(), anyList(), any(Object[].class));
    }

    /** null 是显式不限；读请求不能被错误转换为 0 而拒绝。 */
    @Test
    void permitsUnlimitedReadWithoutRedisCounter() throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), null, 0L, null, 0L));

        MockHttpServletResponse response = invoke(fixture.filter(), "GET", "/api/v1/projects/devices");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(fixture.redis(), org.mockito.Mockito.never()).execute(any(), anyList(), any(Object[].class));
    }

    /** 三个新Console查询不占日写池，但读短窗口仍必须执行，不能变成限流豁免。 */
    @ParameterizedTest
    @ValueSource(strings = {"devices/snapshots/query", "devices/current-value-snapshots/query", "alarms/query"})
    void dashboardPostQueriesConsumeReadBucketsInsteadOfDailyWritePool(String suffix) throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 10L, 0L, 100L, 0L));
        List<String> keys = new ArrayList<>();
        when(fixture.redis().execute(any(), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            keys.add((String) ((List<?>) invocation.getArgument(1)).getFirst());
            return 1L;
        });
        when(fixture.recorder().record(any(), any(), any(), any(), any())).thenReturn(false);
        assertThat(invoke(fixture.filter(), "POST", "/api/v1/projects/project/" + suffix).getStatus()).isEqualTo(200);
        assertThat(keys).hasSize(3).allSatisfy(key -> assertThat(key).contains("read"));
        verify(fixture.daily(), org.mockito.Mockito.never()).decideTrustedProject(any(), any(), any());
        assertThat(invoke(fixture.filter(), "POST", "/api/v1/projects/project/" + suffix + "/extra").getStatus())
                .isEqualTo(429);
    }

    /** 查询POST若达到读取短窗口仍429，不能因为不计日写池而无限制执行。 */
    @Test
    void dashboardPostQueryStillRejectsExhaustedReadBucket() throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 0L, null, 0L, null));
        assertThat(invoke(fixture.filter(), "POST", "/api/v1/projects/project/alarms/query").getStatus())
                .isEqualTo(429);
    }

    /** Redis 是普通业务短窗的辅助设施，故障只增加固定类别指标并降级放行。 */
    @Test
    void failsOpenWhenRedisCounterIsUnavailable() throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 1L, 1L, null, null));
        when(fixture.redis().execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        MockHttpServletResponse response = invoke(fixture.filter(), "GET", "/api/v1/projects/devices");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.FAIL_OPEN)
                .tag("kind", "read").counter().count()).isEqualTo(1D);
    }

    /** PostgreSQL 日共享池达到硬限后只拒绝写请求，读接口仍可用于排障和导出已有事实。 */
    @Test
    void dailyHardLimitRejectsWriteButKeepsRead() throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), null, null, null, null));
        when(fixture.daily().decideTrustedProject(any(), any(), any())).thenReturn(
                com.things.link.project.application.QuotaStatus.HARD_LIMIT);

        assertThat(invoke(fixture.filter(), "POST", "/api/v1/projects/devices").getStatus()).isEqualTo(429);
        assertThat(invoke(fixture.filter(), "GET", "/api/v1/projects/devices").getStatus()).isEqualTo(200);
    }

    /** Redis 脚本空返回同样没有形成可审计裁决，允许业务继续时必须留下 fail-open 指标。 */
    @Test
    void recordsFailOpenWhenRedisScriptReturnsNoDecision() throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 1L, 1L, null, null));
        when(fixture.redis().execute(any(), anyList(), any(Object[].class))).thenReturn(null);

        MockHttpServletResponse response = invoke(fixture.filter(), "GET", "/api/v1/projects/devices");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.FAIL_OPEN)
                .tag("kind", "read").counter().count()).isEqualTo(1D);
    }

    /** EMQX、认证等端点不能被控制台套餐桶拦住，否则会破坏各自独立的安全和重试语义。 */
    @Test
    void exemptsBrokerAndAuthenticationPaths() throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 0L, 0L, 0L, 0L));

        assertThat(invoke(fixture.filter(), "POST", "/api/v1/emqx/events/message-published").getStatus())
                .isEqualTo(200);
        assertThat(invoke(fixture.filter(), "POST", "/api/v1/auth/login").getStatus()).isEqualTo(200);
        verify(fixture.resolver(), org.mockito.Mockito.never()).resolve(any());
    }

    /** 跨租户协作者必须扣受邀项目所属租户，而不是 JWT 自身租户。 */
    @Test
    void usesProjectOwnerTenantInsteadOfJwtTenantForSharedBucket() throws Exception {
        UUID ownerTenantId = UUID.randomUUID();
        Fixture fixture = fixture(new RestQuotaPolicy(ownerTenantId, 10L, 10L, 100L, 100L));
        List<String> bucketKeys = new ArrayList<>();
        when(fixture.redis().execute(any(), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            List<?> keys = invocation.getArgument(1);
            bucketKeys.add((String) keys.getFirst());
            return 1L;
        });
        UUID collaboratorTenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        invoke(fixture.filter(), "GET", "/api/v1/projects/devices",
                new TenantScope(collaboratorTenantId, projectId, accountId));

        assertThat(bucketKeys).hasSize(3);
        assertThat(bucketKeys.getLast()).contains(ownerTenantId.toString())
                .doesNotContain(collaboratorTenantId.toString());
    }

    /** 无法确认项目 owner 时只保留账号安全桶，不能用 JWT 租户替代共享池。 */
    @Test
    void failsOpenProjectAndTenantScopesWhenOwnerCannotBeResolved() throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 10L, 10L, 100L, 100L));
        when(fixture.resolver().resolve(any())).thenThrow(new IllegalStateException("project store unavailable"));
        List<String> bucketKeys = new ArrayList<>();
        when(fixture.redis().execute(any(), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            List<?> keys = invocation.getArgument(1);
            bucketKeys.add((String) keys.getFirst());
            return 1L;
        });
        UUID jwtTenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        MockHttpServletResponse response = invoke(fixture.filter(), "GET", "/api/v1/projects/devices",
                new TenantScope(jwtTenantId, projectId, accountId));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bucketKeys).hasSize(1);
        assertThat(bucketKeys.getFirst()).contains(accountId.toString())
                .doesNotContain(jwtTenantId.toString())
                .doesNotContain(projectId.toString());
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.SAFE_DEFAULT)
                .tag("kind", "read").counter().count()).isEqualTo(1D);
    }

    /** 归档项目记账返回false与设施异常同属未记账，GET/HEAD保持可读并记录实际降级放行。 */
    @ParameterizedTest
    @CsvSource({"GET,false", "HEAD,false", "GET,true", "HEAD,true"})
    void preservesReadWhenUsageFactCannotBeRecorded(String method, boolean throwsException) throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), null, null, null, null));
        if (throwsException) {
            when(fixture.recorder().record(any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("usage fact unavailable"));
        } else {
            when(fixture.recorder().record(any(), any(), any(), any(), any())).thenReturn(false);
        }

        assertThat(invoke(fixture.filter(), method, "/api/v1/projects/devices").getStatus()).isEqualTo(200);
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.FAIL_OPEN)
                .tag("kind", "read").counter().count()).isEqualTo(1D);
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.REJECTED)
                .tags("kind", "read", "scope", "daily_shared").counter().count()).isZero();
        verify(fixture.daily(), org.mockito.Mockito.never()).decideTrustedProject(any(), any(), any());
    }

    /** 写请求无法保存权威事实时仍429，不得把拒绝误计为fail-open或交给MVC产生业务副作用。 */
    @ParameterizedTest
    @CsvSource({"POST,false", "PUT,false", "PATCH,false", "DELETE,false",
            "POST,true", "PUT,true", "PATCH,true", "DELETE,true"})
    void rejectsWriteWhenUsageFactCannotBeRecorded(String method, boolean throwsException) throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), null, null, null, null));
        if (throwsException) {
            when(fixture.recorder().record(any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("usage fact unavailable"));
        } else {
            when(fixture.recorder().record(any(), any(), any(), any(), any())).thenReturn(false);
        }

        MockHttpServletResponse response = invoke(fixture.filter(), method, "/api/v1/projects/devices");
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asInt()).isEqualTo(10029);
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.REJECTED)
                .tags("kind", "write", "scope", "daily_shared").counter().count()).isEqualTo(1D);
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.FAIL_OPEN)
                .tag("kind", "write").counter().count()).isZero();
    }

    /** 记账成功的读写请求保持原放行行为，不能为修复false而额外记录降级或重复计量。 */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"})
    void keepsSuccessfulUsageRecordingUnchanged(String method) throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), null, null, null, null));

        assertThat(invoke(fixture.filter(), method, "/api/v1/projects/devices").getStatus()).isEqualTo(200);
        verify(fixture.recorder()).record(any(), any(), any(), any(), any());
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.FAIL_OPEN)
                .tag("kind", "read").counter().count()).isZero();
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.FAIL_OPEN)
                .tag("kind", "write").counter().count()).isZero();
    }

    /** Redis明确返回额度不足时仍立即429，读记账降级不能绕过真实短窗拒绝裁决。 */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void preservesExplicitShortWindowRejectionBeforeUsageRecording(String method) throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 1L, 1L, null, null));
        when(fixture.redis().execute(any(), anyList(), any(Object[].class))).thenReturn(0L);
        when(fixture.recorder().record(any(), any(), any(), any(), any())).thenReturn(false);

        assertThat(invoke(fixture.filter(), method, "/api/v1/projects/devices").getStatus()).isEqualTo(429);
        verify(fixture.recorder(), org.mockito.Mockito.never()).record(any(), any(), any(), any(), any());
        assertThat(fixture.meters().get(RestQuotaRateLimitMetrics.FAIL_OPEN)
                .tag("kind", "read").counter().count()).isZero();
    }

    /** OPTIONS预检在shouldNotFilter已明确豁免，不能为记账false修复改变框架原有安全链分工。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesOptionsPreflightExemption(boolean throwsException) throws Exception {
        Fixture fixture = fixture(new RestQuotaPolicy(UUID.randomUUID(), 0L, 0L, 0L, 0L));
        if (throwsException) {
            when(fixture.recorder().record(any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("usage fact unavailable"));
        } else {
            when(fixture.recorder().record(any(), any(), any(), any(), any())).thenReturn(false);
        }

        assertThat(invoke(fixture.filter(), "OPTIONS", "/api/v1/projects/devices").getStatus()).isEqualTo(200);
        verify(fixture.recorder(), org.mockito.Mockito.never()).record(any(), any(), any(), any(), any());
        verify(fixture.resolver(), org.mockito.Mockito.never()).resolve(any());
        verify(fixture.redis(), org.mockito.Mockito.never()).execute(any(), anyList(), any(Object[].class));
    }

    /**
     * 建立以 project 解析策略的过滤器夹具；这里刻意令 JWT 租户与套餐所属租户不同，防止实现退化为 JWT tid。
     *
     * @param policy 项目所属租户的有效套餐
     * @return 测试夹具
     */
    private Fixture fixture(RestQuotaPolicy policy) {
        return fixture(policy, null);
    }

    /** 生命周期端口仅用于精确写入口，其他夹具继续验证相同默认计量行为。 */
    private Fixture fixture(RestQuotaPolicy policy,
            com.things.link.project.application.ProjectLifecycleAccessService lifecycle) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RestQuotaPolicyResolver resolver = mock(RestQuotaPolicyResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of(policy));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RestQuotaRateLimitMetrics metrics = new RestQuotaRateLimitMetrics(meters);
        var daily = mock(com.things.link.project.application.ProjectDailyQuotaDecisionService.class);
        var recorder = mock(com.things.link.project.application.ProjectUsageFactRecorder.class);
        when(daily.decideTrustedProject(any(), any(), any())).thenReturn(
                com.things.link.project.application.QuotaStatus.NORMAL);
        when(recorder.record(any(), any(), any(), any(), any())).thenReturn(true);
        QuotaRestRateLimitFilter filter = new QuotaRestRateLimitFilter(
                resolver, redis, metrics, objectMapper, daily, recorder, true, lifecycle);
        return new Fixture(filter, redis, resolver, meters, daily, recorder);
    }

    /**
     * @param filter 被测过滤器
     * @param method HTTP 方法
     * @param path 请求路径
     * @return 过滤器写出的 MockMvc 等价响应
     * @throws Exception 过滤器或 JSON 写出失败
     */
    private MockHttpServletResponse invoke(QuotaRestRateLimitFilter filter, String method, String path)
            throws Exception {
        return invoke(filter, method, path, new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
    }

    /**
     * @param filter 被测过滤器
     * @param method HTTP 方法
     * @param path 请求路径
     * @param scope 测试显式指定的租户范围
     * @return 过滤器写出的 MockMvc 等价响应
     * @throws Exception 过滤器或 JSON 写出失败
     */
    private MockHttpServletResponse invoke(QuotaRestRateLimitFilter filter, String method, String path,
                                           TenantScope scope) throws Exception {
        TenantContext.set(scope);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));
        assertThat(invoked.get()).isEqualTo(response.getStatus() == 200);
        return response;
    }

    /** 测试依赖集合，避免每个用例遗漏指标或策略替身。 */
    private record Fixture(QuotaRestRateLimitFilter filter, StringRedisTemplate redis,
                           RestQuotaPolicyResolver resolver, SimpleMeterRegistry meters,
                           com.things.link.project.application.ProjectDailyQuotaDecisionService daily,
                           com.things.link.project.application.ProjectUsageFactRecorder recorder) {
    }
}
