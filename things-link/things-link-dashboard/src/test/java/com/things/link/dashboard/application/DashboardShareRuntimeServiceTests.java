package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.DashboardPublicationCandidate;
import com.things.link.dashboard.application.publication.DashboardPublicationCandidateFactory;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardShareRuntimeIdentity;
import com.things.link.dashboard.domain.DashboardShareRuntimeRepository;
import com.things.link.dashboard.domain.DashboardShareRuntimeState;
import com.things.link.dashboard.domain.DashboardShareToken;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 匿名能力逐请求复验、最小context、精确旧Schema、不可枚举失权与503首因的纯测试。 */
class DashboardShareRuntimeServiceTests {
    /** 不可逆测试摘要，从不表示真实secret。 */ private static final String HASH = "a".repeat(64);
    /** 固定数据库时刻。 */ private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    /** 可信租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 可信项目。 */ private final UUID project = UUID.randomUUID();
    /** 分享身份。 */ private final UUID share = UUID.randomUUID();
    /** 固定看板。 */ private final UUID dashboard = UUID.randomUUID();
    /** 冻结旧版本，不能替换成当前指针。 */ private final UUID version = UUID.randomUUID();
    /** 凭据存储和状态读取。 */ private final DashboardShareRuntimeRepository repository = mock(DashboardShareRuntimeRepository.class);
    /** 同事务可信RLS恢复。 */ private final TransactionLocalRlsScope scope = mock(TransactionLocalRlsScope.class);
    /** 不借Console账号的项目策略。 */ private final ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
    /** 仅schema入口使用的完整版本仓储。 */ private final DashboardRepository dashboards = mock(DashboardRepository.class);
    /** 历史完整语义与PG摘要验证。 */ private final DashboardPublicationCandidateFactory candidates = mock(DashboardPublicationCandidateFactory.class);
    /** 被测匿名运行服务。 */ private final DashboardShareRuntimeService service = new DashboardShareRuntimeService(
            repository, scope, lifecycle, dashboards, candidates);
    /** 固定完整token事实。 */ private DashboardShareToken token;

    /** 默认认证和项目可读，测试逐项替换对应失效事实。 */
    @BeforeEach
    void setup() {
        token = token(null);
        when(repository.locate(share, HASH)).thenReturn(Optional.of(new DashboardShareRuntimeIdentity(tenant, project)));
        when(repository.findState(tenant, project, share, HASH)).thenReturn(Optional.of(state(token, true, NOW)));
        when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, true, 3));
    }

    /** 第一次先精确定位后建立RLS，握手不加载完整Schema或候选范围。 */
    @Test
    void authenticateUsesOnlyMinimalLocatorAndFreshState() {
        DashboardSharePrincipal principal = service.authenticate(share, HASH);
        assertThat(principal.shareId()).isEqualTo(share);
        assertThat(principal.projectGeneration()).isEqualTo(3);
        var order = inOrder(repository, scope, lifecycle);
        order.verify(repository).locate(share, HASH);
        order.verify(scope).establish(tenant, project);
        order.verify(repository).findState(tenant, project, share, HASH);
        order.verify(lifecycle).snapshot(tenant, project);
        verifyNoInteractions(dashboards, candidates);
    }

    /** context只取有界scope和DB锚点，归档可读且不受当前HostDescriptor影响。 */
    @Test
    void contextUsesDatabaseAnchorWithoutLoadingSchema() {
        UUID device = UUID.randomUUID();
        when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, false, 3));
        when(repository.findScopes(tenant, project, share)).thenReturn(List.of(
                new DashboardShareVariableScope("devices", UUID.randomUUID(), List.of(device))));
        DashboardShareContext result = service.context(principal());
        assertThat(result.historyAnchorAt()).isEqualTo(NOW);
        assertThat(result.dashboardVersionId()).isEqualTo(version);
        assertThat(result.variableScopes()).containsExactly(new DashboardShareVariableScopeView("devices", List.of(device)));
        verifyNoInteractions(dashboards, candidates);
    }

    /** 认证完成后撤销必须在正文服务的新观察被拒绝，不能沿用filter快照。 */
    @Test
    void revokedAfterAuthenticationCannotReadContext() {
        DashboardSharePrincipal identity = service.authenticate(share, HASH);
        when(repository.findState(tenant, project, share, HASH)).thenReturn(Optional.of(state(token(NOW), true, NOW)));
        assertThatThrownBy(() -> service.context(identity)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_NOT_FOUND));
        verifyNoInteractions(dashboards, candidates);
    }

    /** 过期、撤回和错代次采用同一不可枚举60053。 */
    @Test
    void expiryWithdrawalAndGenerationMismatchHaveSameError() {
        for (DashboardShareRuntimeState invalid : List.of(state(token, true, token.expiresAt()),
                state(token, false, NOW))) {
            when(repository.findState(tenant, project, share, HASH)).thenReturn(Optional.of(invalid));
            assertThatThrownBy(() -> service.context(principal())).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_NOT_FOUND));
        }
        when(repository.findState(tenant, project, share, HASH)).thenReturn(Optional.of(state(token, true, NOW)));
        when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, true, 4));
        assertThatThrownBy(() -> service.context(principal())).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_NOT_FOUND));
    }

    /** 空token与错误selector同样拒绝；不存在不能以空context成功冒充静态看板。 */
    @Test
    void unknownIdentityAndWrongVersionNeverBecomeEmptySuccess() {
        when(repository.locate(share, HASH)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticate(share, HASH)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_NOT_FOUND));
        var changed = new DashboardSharePrincipal(share, tenant, project, dashboard, UUID.randomUUID(), 3,
                token.expiresAt(), "NONE", HASH);
        assertThatThrownBy(() -> service.schema(changed)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_NOT_FOUND));
        verifyNoInteractions(dashboards, candidates);
    }

    /** 数据库不可用保留原cause，但响应消息不泄露连接、SQL或token摘要。 */
    @Test
    void infrastructureFailureIs503WithOriginalCause() {
        var cause = new DataAccessResourceFailureException("private-database-diagnostic");
        when(repository.findState(tenant, project, share, HASH)).thenThrow(cause);
        assertThatThrownBy(() -> service.context(principal())).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(failure.getMessage()).doesNotContain("private-database-diagnostic", HASH);
        });
    }

    /** 多变量各自合法但候选并集21时属于持久合同损坏，不能截断为20台成功。 */
    @Test
    void persistedScopeOverflowIsNotTruncated() {
        when(repository.findScopes(tenant, project, share)).thenReturn(List.of(
                new DashboardShareVariableScope("first", UUID.randomUUID(),
                        IntStream.range(0, 20).mapToObj(index -> UUID.randomUUID()).toList()),
                new DashboardShareVariableScope("second", UUID.randomUUID(), List.of(UUID.randomUUID()))));
        assertThatThrownBy(() -> service.context(principal())).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE));
    }

    /** 只读精确旧版本并重跑历史完整语义与摘要，不从看板当前版本替换。 */
    @Test
    void schemaUsesExactHistoricalVersionAndCandidateValidation() {
        DashboardVersion historical = mock(DashboardVersion.class);
        when(historical.id()).thenReturn(version);
        when(historical.tenantId()).thenReturn(tenant);
        when(historical.projectId()).thenReturn(project);
        when(historical.dashboardId()).thenReturn(dashboard);
        when(historical.versionNumber()).thenReturn(2L);
        when(historical.sourceDraftRevision()).thenReturn(4L);
        when(historical.schemaVersion()).thenReturn("tc.dashboard/v1");
        when(dashboards.findVersion(project, dashboard, version)).thenReturn(Optional.of(historical));
        DashboardPublicationCandidate candidate = mock(DashboardPublicationCandidate.class);
        when(candidate.dashboardId()).thenReturn(dashboard);
        when(candidate.tenantId()).thenReturn(tenant);
        when(candidate.projectId()).thenReturn(project);
        when(candidate.sourceDraftRevision()).thenReturn(4L);
        when(candidate.schemaDigestAlgorithm()).thenReturn("PG_JSONB_TEXT_V1_SHA256");
        when(candidate.schemaDigest()).thenReturn("b".repeat(64));
        when(candidate.requiredComponents()).thenReturn(List.of());
        when(candidate.requiredResources()).thenReturn(List.of());
        when(candidate.normalizedSchema()).thenReturn(JsonMapper.builder().build().readTree("{\"schemaVersion\":\"tc.dashboard/v1\"}"));
        when(candidates.prepareHistoricalVersion(historical)).thenReturn(candidate);
        DashboardShareSchema result = service.schema(principal());
        assertThat(result.dashboardVersionId()).isEqualTo(version);
        assertThat(result.schemaDigest()).isEqualTo("b".repeat(64));
        verify(candidates).prepareHistoricalVersion(historical);
    }

    /** 内部hash不会经principal诊断或JSON序列化被带到客户端。 */
    @Test
    void principalDoesNotExposeHashThroughDiagnosticsOrSerialization() {
        assertThat(principal().toString()).doesNotContain(HASH);
        assertThat(JsonMapper.builder().build().writeValueAsString(principal())).doesNotContain(HASH, "secretHash");
    }

    /** 生成固定时刻与身份的数据库token投影。 */
    private DashboardShareToken token(Instant revokedAt) {
        return new DashboardShareToken(share, tenant, project, dashboard, version, 3, HASH,
                JsonMapper.builder().build().readTree("{}"), "NONE", NOW.minusSeconds(60), NOW.plusSeconds(3600),
                UUID.randomUUID(), revokedAt, revokedAt == null ? null : UUID.randomUUID());
    }

    /** 原filter身份，正文入口仍须数据库逐次验证。 */
    private DashboardSharePrincipal principal() {
        return new DashboardSharePrincipal(share, tenant, project, dashboard, version, 3, token.expiresAt(), "NONE", HASH);
    }

    /** 显式当前DB时刻，便于断言到期边界而不依赖测试机器时钟。 */
    private DashboardShareRuntimeState state(DashboardShareToken token, boolean runnable, Instant now) {
        return new DashboardShareRuntimeState(token, runnable, 2, now);
    }
}
