package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.domain.DashboardCatalogEntry;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.dashboard.domain.DashboardPublicationState;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardShareCreationResult;
import com.things.link.dashboard.domain.DashboardShareRepository;
import com.things.link.dashboard.domain.DashboardShareSummary;
import com.things.link.dashboard.domain.DashboardShareToken;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.device.application.DeviceModelBindingFacts;
import com.things.link.device.application.DeviceModelBindingFactsPort;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 分享管理保持锁序、一次性秘密、有限scope与完整资格；不以mock正例冒称真实宿主已放行。 */
class DashboardShareManagementServiceTests {
    /** 有界合同JSON构造器。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 项目真实归属。 */ private final UUID tenant = UUID.randomUUID();
    /** 当前项目。 */ private final UUID project = UUID.randomUUID();
    /** 管理目标看板。 */ private final UUID dashboard = UUID.randomUUID();
    /** 独立Console操作者。 */ private final UUID actor = UUID.randomUUID();
    /** 允许分享的精确旧版本，与当前指针故意不同。 */ private final UUID versionId = UUID.randomUUID();
    /** 候选设备。 */ private final UUID device = UUID.randomUUID();
    /** 精确模型。 */ private final UUID model = UUID.randomUUID();
    /** DB权威时间故意远离本机时钟。 */ private final Instant now = Instant.parse("2031-01-01T00:00:00.123456Z");
    /** Dashboard锁与版本端口。 */ private final DashboardRepository dashboards = mock(DashboardRepository.class);
    /** 原事务分享持久端口。 */ private final DashboardShareRepository shares = mock(DashboardShareRepository.class);
    /** 管理角色端口。 */ private final ProjectService projects = mock(ProjectService.class);
    /** ACTIVE许可。 */ private final ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
    /** 真实RLS轴。 */ private final TransactionLocalRlsScope rls = mock(TransactionLocalRlsScope.class);
    /** 历史Schema完整重建。 */ private final DashboardPublicationCandidateFactory candidates = mock(DashboardPublicationCandidateFactory.class);
    /** 资格核心作为独立协作者，其全矩阵由既有资格测试覆盖。 */ private final DashboardPublicationQualificationService qualification = mock(DashboardPublicationQualificationService.class);
    /** 当前宿主注册事实。 */ private final DashboardHostQualificationPort hosts = mock(DashboardHostQualificationPort.class);
    /** 当前设备精确模型。 */ private final DeviceModelBindingFactsPort devices = mock(DeviceModelBindingFactsPort.class);
    /** 原事务审计。 */ private final AuditLogService audit = mock(AuditLogService.class);
    /** 锁内历史版本。 */ private final DashboardVersion version = mock(DashboardVersion.class);
    /** 同一完整候选。 */ private final DashboardPublicationCandidate candidate = mock(DashboardPublicationCandidate.class);
    /** 同一次宿主快照。 */ private final DashboardHostQualificationDescriptor host = new DashboardHostQualificationDescriptor(
            "tc.webapp-host/v1", "1.0.0", Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"), Map.of(), Map.of());
    /** 被测应用入口。 */ private DashboardShareManagementService service;

    /** 默认建立纯静态旧版本正例，只用于验证管理事务编排。 */
    @BeforeEach
    void setup() {
        TenantContext.set(new TenantScope(tenant, project, actor));
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.OWNER);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
        when(lifecycle.snapshot(tenant, project)).thenReturn(new ProjectAccessPolicy(true, true, 4));
        when(dashboards.lockPublicationState(project, dashboard)).thenReturn(Optional.of(
                new DashboardPublicationState(dashboard, tenant, project, 1, 3, UUID.randomUUID(), 2, null)));
        when(dashboards.findVersion(project, dashboard, versionId)).thenReturn(Optional.of(version));
        when(version.id()).thenReturn(versionId);
        when(version.tenantId()).thenReturn(tenant);
        when(version.projectId()).thenReturn(project);
        when(version.dashboardId()).thenReturn(dashboard);
        when(candidates.prepareHistoricalVersion(version)).thenReturn(candidate);
        when(candidate.projectId()).thenReturn(project);
        when(candidate.normalizedSchema()).thenReturn(JSON.readTree("{\"variables\":[]}"));
        when(candidate.modelReferences()).thenReturn(List.of());
        when(hosts.current()).thenReturn(Optional.of(host));
        when(shares.databaseNow()).thenReturn(now);
        service = new DashboardShareManagementService(dashboards, shares, projects, lifecycle, rls,
                candidates, qualification, hosts, devices, audit);
    }

    /** 不允许测试线程身份残留到下一测试。 */
    @AfterEach
    void cleanup() { TenantContext.clear(); }

    /** 首次成功仅返回32随机字节secret，数据库/恢复映射/审计不保存明文。 */
    @Test
    void createsOldPublishedVersionWithDatabaseTimeAndIrreversibleSecret() throws Exception {
        DashboardShareCreated created = service.create(project, dashboard, "key", request(List.of(), 300));
        assertThat(created.secret()).matches("sh_[A-Za-z0-9_-]{43}");
        byte[] raw = Base64.getUrlDecoder().decode(created.secret().substring(3));
        assertThat(raw).hasSize(32);
        assertThat(created.expiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(created.toString()).doesNotContain(created.secret());
        ArgumentCaptor<DashboardShareToken> token = ArgumentCaptor.forClass(DashboardShareToken.class);
        ArgumentCaptor<DashboardShareCreationResult> result = ArgumentCaptor.forClass(DashboardShareCreationResult.class);
        verify(shares).create(token.capture(), eq(List.of()), result.capture());
        assertThat(token.getValue().secretHash()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(created.secret().getBytes(StandardCharsets.UTF_8))));
        assertThat(token.getValue().dashboardVersionId()).isEqualTo(versionId);
        assertThat(token.getValue().projectGeneration()).isEqualTo(4);
        assertThat(token.getValue().createdAt()).isEqualTo(now);
        assertThat(result.getValue().toString()).doesNotContain(created.secret());
        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().toString()).doesNotContain(created.secret(), token.getValue().secretHash());
        verify(qualification).qualify(candidate, host);
        var order = inOrder(lifecycle, dashboards, shares, audit);
        order.verify(lifecycle).requireActiveForWrite(tenant, project);
        order.verify(dashboards).lockPublicationState(project, dashboard);
        order.verify(shares).findCreationResult(eq(tenant), eq(project), eq(dashboard), eq(actor), anyString());
        order.verify(shares).databaseNow();
        order.verify(shares).countActive(project, dashboard, now);
        order.verify(shares).create(any(), any(), any());
        order.verify(audit).record(any());
    }

    /** 相同语义请求只返回安全shareId冲突；不同内容复用键为10009且不再签发。 */
    @Test
    void replayNeverRecreatesOrRecoversSecret() {
        service.create(project, dashboard, "key", request(List.of(), 3600));
        ArgumentCaptor<DashboardShareCreationResult> result = ArgumentCaptor.forClass(DashboardShareCreationResult.class);
        verify(shares).create(any(), any(), result.capture());
        when(shares.findCreationResult(eq(tenant), eq(project), eq(dashboard), eq(actor), anyString()))
                .thenReturn(Optional.of(result.getValue()));
        assertThatThrownBy(() -> service.create(project, dashboard, "key", request(List.of(), 3600)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode().code()).isEqualTo(60052);
                    assertThat(error.details()).containsExactly(result.getValue().shareId().toString());
                });
        assertCode(() -> service.create(project, dashboard, "key", request(List.of(), 300)), 10009);
        verify(shares).create(any(), any(), any());
    }

    /** 无当前发布或发布代次失配均不能以旧版本本身存在来绕过管理观察。 */
    @Test
    void rejectsWithdrawnAndStalePublication() {
        for (DashboardPublicationState state : List.of(
                new DashboardPublicationState(dashboard, tenant, project, 1, 3, null, 2, null),
                new DashboardPublicationState(dashboard, tenant, project, 1, 4, UUID.randomUUID(), 2, null))) {
            when(dashboards.lockPublicationState(project, dashboard)).thenReturn(Optional.of(state));
            assertCode(() -> service.create(project, dashboard, "key", request(List.of(), 3600)), 60050);
        }
        verifyNoInteractions(candidates, qualification);
        verify(shares, never()).create(any(), any(), any());
    }

    /** 缺失受管Host事实保留D-145的60041，不能用静态Schema正例声称完整资格。 */
    @Test
    void missingHostAndDataQualificationStayUnavailable() {
        when(hosts.current()).thenReturn(Optional.empty());
        assertCode(() -> service.create(project, dashboard, "key", request(List.of(), 3600)), 60041);
        when(hosts.current()).thenReturn(Optional.of(host));
        when(qualification.qualify(candidate, host)).thenThrow(new DashboardPublicationQualificationException(
                DashboardPublicationQualificationException.Reason.DATA_ADAPTER_UNAVAILABLE));
        assertCode(() -> service.create(project, dashboard, "key", request(List.of(), 3600)), 60041);
        verify(shares, never()).create(any(), any(), any());
    }

    /** 活动上限按锁后DB时间检查，已满不生成第二十一个事实。 */
    @Test
    void rejectsTwentiethActiveShareBoundary() {
        when(shares.countActive(project, dashboard, now)).thenReturn(20L);
        assertCode(() -> service.create(project, dashboard, "key", request(List.of(), 86400)), 60051);
        verify(shares, never()).create(any(), any(), any());
        verifyNoInteractions(audit);
    }

    /** TTL和每变量候选合法但全局21台的请求都在持久操作前整体拒绝。 */
    @Test
    void rejectsTtlAndGlobalUnionOverflow() {
        for (int ttl : List.of(299, 86401)) {
            assertCode(() -> service.create(project, dashboard, "key", request(List.of(), ttl)), 60049);
        }
        List<UUID> twenty = IntStream.range(0, 20).mapToObj(index -> UUID.randomUUID()).toList();
        assertCode(() -> service.create(project, dashboard, "key", request(List.of(
                new DashboardShareVariableRequest("first", twenty),
                new DashboardShareVariableRequest("second", List.of(UUID.randomUUID()))), 3600)), 60049);
        verifyNoInteractions(shares);
    }

    /** 全Schema第二个变量默认值也必须落scope内，不能只检查当前页绑定。 */
    @Test
    void checksAllVariableDefaultsAndExactCurrentModels() {
        configureDeviceVariables();
        List<DashboardShareVariableRequest> scope = List.of(new DashboardShareVariableRequest("first", List.of(device)),
                new DashboardShareVariableRequest("second", List.of(device)));
        assertCode(() -> service.create(project, dashboard, "key", request(scope, 3600)), 60049);
        when(candidate.normalizedSchema()).thenReturn(JSON.readTree("""
                {"variables":[{"key":"first","type":"DEVICE_SINGLE","modelKey":"model"}]}
                """));
        when(devices.find(project, device)).thenReturn(Optional.of(new DeviceModelBindingFacts(device, project, UUID.randomUUID())));
        assertCode(() -> service.create(project, dashboard, "key", request(List.of(scope.getFirst()), 3600)), 60049);
        verify(shares, never()).create(any(), any(), any());
    }

    /** SINGLE可授权多个候选，候选数不是选中数；每台仍必须匹配精确模型。 */
    @Test
    void singleDeviceVariableCanFreezeMultipleCandidates() {
        UUID second = UUID.randomUUID();
        when(candidate.modelReferences()).thenReturn(List.of(new DashboardModelReference(0, "model", model)));
        when(candidate.normalizedSchema()).thenReturn(JSON.readTree("""
                {"variables":[{"key":"first","type":"DEVICE_SINGLE","modelKey":"model"}]}
                """));
        when(devices.find(project, device)).thenReturn(Optional.of(new DeviceModelBindingFacts(device, project, model)));
        when(devices.find(project, second)).thenReturn(Optional.of(new DeviceModelBindingFacts(second, project, model)));
        service.create(project, dashboard, "key", request(List.of(
                new DashboardShareVariableRequest("first", List.of(device, second))), 3600));
        verify(shares).create(any(), eq(List.of(new com.things.link.dashboard.domain.DashboardShareVariableScope(
                "first", model, List.of(device, second).stream().sorted().toList()))), any());
    }

    /** 原数量约束合法却超过context字节上限时，不能签发不可恢复token或写审计。 */
    @Test
    void contextBudgetRejectsBeforeTokenAndAuditPersistence() {
        List<UUID> ids = IntStream.range(0, 20).mapToObj(index -> UUID.randomUUID()).toList();
        List<DashboardShareVariableRequest> requested = IntStream.range(0, 20).mapToObj(index ->
                new DashboardShareVariableRequest("v" + String.format(java.util.Locale.ROOT, "%02d", index)
                        + "a".repeat(61), ids)).toList();
        var schema = JSON.createObjectNode();
        var variables = schema.putArray("variables");
        requested.forEach(variable -> variables.addObject().put("key", variable.variableKey())
                .put("type", "DEVICE_SINGLE").put("modelKey", "model"));
        when(candidate.normalizedSchema()).thenReturn(schema);
        when(candidate.modelReferences()).thenReturn(List.of(new DashboardModelReference(0, "model", model)));
        ids.forEach(id -> when(devices.find(project, id)).thenReturn(Optional.of(new DeviceModelBindingFacts(id, project, model))));
        assertCode(() -> service.create(project, dashboard, "oversized-context", request(requested, 3600)), 60049);
        verify(shares, never()).create(any(), any(), any());
        verifyNoInteractions(audit, hosts, qualification);
    }

    /** 所有管理操作都要求OWNER/ADMIN，列表不降低为普通项目读取权限。 */
    @Test
    void rejectsMemberForCreateListAndRevoke() {
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.VIEWER);
        assertCode(() -> service.create(project, dashboard, "key", request(List.of(), 3600)), 60035);
        assertCode(() -> service.page(project, dashboard, null, 20), 60035);
        assertCode(() -> service.revoke(project, dashboard, UUID.randomUUID()), 60035);
        verifyNoInteractions(shares, lifecycle);
    }

    /** 列表不取得ACTIVE写许可，允许已确权归档管理；撤销仍不能绕过写许可。 */
    @Test
    void archivedListUsesReadScopeAndRepeatedRevokeDoesNotAudit() {
        when(dashboards.find(project, dashboard)).thenReturn(Optional.of(mock(DashboardCatalogEntry.class)));
        when(shares.page(project, dashboard, null, null, 21)).thenReturn(List.of());
        assertThat(service.page(project, dashboard, null, 20).items()).isEmpty();
        verify(lifecycle, never()).requireActiveForWrite(any(), any());
        UUID share = UUID.randomUUID();
        when(shares.find(project, dashboard, share)).thenReturn(Optional.of(new DashboardShareToken(
                share, tenant, project, dashboard, versionId, 4, "a".repeat(64), hostRange(), "NONE",
                now, now.plusSeconds(3600), actor, now.plusSeconds(1), actor)));
        service.revoke(project, dashboard, share);
        verify(lifecycle).requireActiveForWrite(tenant, project);
        verify(shares, never()).revoke(any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    /** 审计异常保持原错误传播，由服务@Transactional使持久事实回滚。 */
    @Test
    void auditFailureIsNotSwallowed() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any());
        assertThatThrownBy(() -> service.create(project, dashboard, "key", request(List.of(), 3600)))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
    }

    /** 两变量共享模型，但第二默认值故意位于候选外。 */
    private void configureDeviceVariables() {
        when(candidate.modelReferences()).thenReturn(List.of(new DashboardModelReference(0, "model", model)));
        when(candidate.normalizedSchema()).thenReturn(JSON.readTree("""
                {"variables":[{"key":"first","type":"DEVICE_SINGLE","modelKey":"model"},
                {"key":"second","type":"DEVICE_MULTI","modelKey":"model","maxItems":2,"defaultDeviceIds":["%s"]}]}
                """.formatted(UUID.randomUUID())));
        when(devices.find(project, device)).thenReturn(Optional.of(new DeviceModelBindingFacts(device, project, model)));
    }

    /** 有限请求构造，默认使用精确历史版本及当前发布代次3。 */
    private DashboardShareCreateRequest request(List<DashboardShareVariableRequest> variables, int ttl) {
        return new DashboardShareCreateRequest(versionId, "3", ttl, "HOST_ORIGIN", hostRange(), variables);
    }

    /** 实际宿主1.0.0命中的半开范围。 */
    private static JsonNode hostRange() { return JSON.readTree("{\"minInclusive\":\"1.0.0\",\"maxExclusive\":\"1.0.1\"}"); }

    /** 错误必须为明确业务码，不能把任意异常当作反例通过。 */
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode().code()).isEqualTo(code));
    }
}
