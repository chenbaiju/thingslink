package com.things.link.dashboard.application.publication;

import com.things.link.dashboard.application.DashboardShareContextBudget;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardPublicationState;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardShareCreationResult;
import com.things.link.dashboard.domain.DashboardShareRepository;
import com.things.link.dashboard.domain.DashboardShareSummary;
import com.things.link.dashboard.domain.DashboardShareToken;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.dashboard.application.schema.DashboardSchemaParseException;
import com.things.link.dashboard.application.schema.DashboardSchemaValidationException;
import com.things.link.device.application.DeviceModelBindingFacts;
import com.things.link.device.application.DeviceModelBindingFactsPort;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ADR0101：原事务签发一次性分享、分页管理及可审计撤销，资格始终覆盖完整不可变版本。 */
@Service
public class DashboardShareManagementService {
    /** 仅序列化已经验证和排序的固定字段，不保存原始请求或secret。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** CSPRNG生成32随机字节，不能使用UUID充当capability秘密。 */
    private final SecureRandom random = new SecureRandom();
    /** 看板锁和精确历史版本。 */ private final DashboardRepository dashboards;
    /** DB时间、范围事实和域级幂等映射。 */ private final DashboardShareRepository shares;
    /** 管理角色与真实项目归属。 */ private final ProjectService projects;
    /** ACTIVE锁及真实项目代次。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 同事务唯一RLS二轴。 */ private final TransactionLocalRlsScope rls;
    /** 从持久历史内容重建完整候选。 */ private final DashboardPublicationCandidateFactory candidates;
    /** 所有页面和外部资格的既有核心。 */ private final DashboardPublicationQualificationService qualification;
    /** 当前受管制品权威描述符。 */ private final DashboardHostQualificationPort hosts;
    /** 候选全集当前精确模型事实。 */ private final DeviceModelBindingFactsPort devices;
    /** 业务与审计同事务成功或回滚。 */ private final AuditLogService audit;

    /** 只接受权威公开端口，不提供调用方可直接提交资格结果的捷径。 */
    public DashboardShareManagementService(DashboardRepository dashboards, DashboardShareRepository shares,
            ProjectService projects, ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls,
            DashboardPublicationCandidateFactory candidates, DashboardPublicationQualificationService qualification,
            DashboardHostQualificationPort hosts, DeviceModelBindingFactsPort devices, AuditLogService audit) {
        this.dashboards = dashboards; this.shares = shares; this.projects = projects; this.lifecycle = lifecycle;
        this.rls = rls; this.candidates = candidates; this.qualification = qualification; this.hosts = hosts;
        this.devices = devices; this.audit = audit;
    }

    /** 项目ACTIVE许可→Dashboard锁→原事务幂等仲裁；重放永不恢复secret。 */
    @Transactional
    public DashboardShareCreated create(UUID projectId, UUID dashboardId, String idempotencyKey,
                                        DashboardShareCreateRequest request) {
        validateRequest(request);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) throw invalid();
        UUID tenant = requireWrite(projectId);
        UUID actor = actor();
        DashboardPublicationState state = lock(projectId, dashboardId, tenant);
        String keyDigest = digest("dashboard-share-key/v1\n" + idempotencyKey);
        String requestDigest = requestDigest(request);
        var previous = shares.findCreationResult(tenant, projectId, dashboardId, actor, keyDigest);
        if (previous.isPresent()) {
            if (!previous.get().requestDigest().equals(requestDigest)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
            }
            throw new BusinessException(DashboardErrorCode.SHARE_SECRET_NOT_REPLAYABLE,
                    DashboardErrorCode.SHARE_SECRET_NOT_REPLAYABLE.defaultMessage(),
                    List.of(previous.get().shareId().toString()));
        }
        if (state.currentVersionId() == null
                || state.publicationRevision() != Long.parseLong(request.expectedDashboardPublicationRevision())) {
            throw new BusinessException(DashboardErrorCode.SHARE_CONFLICT);
        }
        DashboardVersion version = dashboards.findVersion(projectId, dashboardId, request.dashboardVersionId())
                .orElseThrow(() -> new BusinessException(DashboardErrorCode.SHARE_CONFLICT));
        if (!version.tenantId().equals(tenant) || !version.projectId().equals(projectId)
                || !version.dashboardId().equals(dashboardId)) throw new IllegalStateException("分享精确版本归属异常");
        DashboardPublicationCandidate candidate;
        try {
            candidate = candidates.prepareHistoricalVersion(version);
        } catch (DashboardSchemaParseException | DashboardSchemaValidationException invalidSchema) {
            throw invalid();
        }
        List<DashboardShareVariableScope> scopes = scopes(candidate, request.variables());
        // 完整变量候选可能因重复设备映射超16KiB，签发前拒绝，不能留下永远不可恢复的capability。
        DashboardShareContextBudget.requireFits(scopes);
        DashboardHostQualificationDescriptor host = hosts.current().orElseThrow(DashboardShareManagementService::dependency);
        int[] minimum = semver(request.hostCompatibility().get("minInclusive"));
        int[] maximum = semver(request.hostCompatibility().get("maxExclusive"));
        int[] actual;
        try { actual = semver(JSON.valueToTree(host.hostVersion())); }
        catch (BusinessException brokenHost) { throw dependency(); }
        if (compare(actual, minimum) < 0 || compare(actual, maximum) >= 0) throw dependency();
        try {
            qualification.qualify(candidate, host);
        } catch (DashboardPublicationQualificationException failure) {
            throw switch (failure.reason()) {
                case HOST_COMPONENT_UNAVAILABLE, DATA_ADAPTER_UNAVAILABLE -> dependency();
                default -> invalid();
            };
        }
        Instant now = shares.databaseNow();
        if (shares.countActive(projectId, dashboardId, now) >= 20) {
            throw new BusinessException(DashboardErrorCode.SHARE_LIMIT_EXCEEDED);
        }
        var lifecycleSnapshot = lifecycle.snapshot(tenant, projectId);
        if (!lifecycleSnapshot.writeAllowed() || lifecycleSnapshot.lifecycleGeneration() < 0) {
            throw new IllegalStateException("ACTIVE项目锁内生命周期快照不一致");
        }
        long generation = lifecycleSnapshot.lifecycleGeneration();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = "sh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID shareId = Uuid7.generate();
        Instant expires = now.plusSeconds(request.expiresInSeconds());
        DashboardShareToken token = new DashboardShareToken(shareId, tenant, projectId, dashboardId,
                version.id(), generation, digest(secret), request.hostCompatibility(), request.refererPolicy(),
                now, expires, actor, null, null);
        shares.create(token, scopes, new DashboardShareCreationResult(tenant, projectId, dashboardId, actor,
                keyDigest, requestDigest, shareId, now));
        audit.record(new AuditLogEntry(tenant, projectId, actor, "dashboard_share", shareId,
                "dashboard.share.create", Map.of("dashboardId", dashboardId.toString(),
                "dashboardVersionId", version.id().toString(), "expiresAt", expires.toString())));
        return new DashboardShareCreated(shareId, secret, expires);
    }

    /** ARCHIVED列表仍可读，但不能从manage角色降为普通成员；只返回安全摘要。 */
    @Transactional(readOnly = true)
    public CursorPage<DashboardShareSummary> page(UUID projectId, UUID dashboardId, String cursor, int limit) {
        if (limit < 1 || limit > 50) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        requireRole(projectId);
        UUID tenant = projects.requireProjectTenant(projectId);
        rls.establish(tenant, projectId);
        if (dashboards.find(projectId, dashboardId).isEmpty()) throw notFound();
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 512) throw new IllegalArgumentException();
                String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                String[] fields = value.split("\\|", -1);
                if (fields.length != 4 || !fields[0].equals(projectId.toString())
                        || !fields[1].equals(dashboardId.toString())) throw new IllegalArgumentException();
                beforeAt = Instant.parse(fields[2]); beforeId = UUID.fromString(fields[3]);
                if (!encodeCursor(projectId, dashboardId, beforeAt, beforeId).equals(cursor)) throw new IllegalArgumentException();
            } catch (RuntimeException malformed) { throw new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
        }
        List<DashboardShareSummary> rows = shares.page(projectId, dashboardId, beforeAt, beforeId, limit + 1);
        if (rows.size() <= limit) return CursorPage.last(rows);
        List<DashboardShareSummary> page = List.copyOf(rows.subList(0, limit));
        DashboardShareSummary tail = page.getLast();
        return CursorPage.of(page, encodeCursor(projectId, dashboardId, tail.createdAt(), tail.shareId()));
    }

    /** 项目和看板锁内重复撤销零变化，不重复写审计，绝不删除历史。 */
    @Transactional
    public void revoke(UUID projectId, UUID dashboardId, UUID shareId) {
        UUID tenant = requireWrite(projectId);
        lock(projectId, dashboardId, tenant);
        DashboardShareToken token = shares.find(projectId, dashboardId, shareId)
                .orElseThrow(() -> new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND));
        if (token.revokedAt() != null) return;
        shares.revoke(projectId, dashboardId, shareId, actor()).ifPresent(revoked ->
                audit.record(new AuditLogEntry(tenant, projectId, actor(), "dashboard_share", shareId,
                        "dashboard.share.revoke", Map.of("dashboardId", dashboardId.toString()))));
    }

    /** 保持已有锁序：前后角色复验和项目ACTIVE许可均先于Dashboard行锁。 */
    private UUID requireWrite(UUID project) {
        requireRole(project);
        UUID tenant = projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant, project);
        requireRole(project);
        rls.establish(tenant, project);
        return tenant;
    }

    /** 非成员由project隐藏，普通成员由dashboard领域60035拒绝。 */
    private void requireRole(UUID project) {
        ProjectRole role = projects.requireRoleInProject(project);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);
        }
    }

    /** 锁内实体必须属于已确权项目真实租户。 */
    private DashboardPublicationState lock(UUID project, UUID dashboard, UUID tenant) {
        DashboardPublicationState state = dashboards.lockPublicationState(project, dashboard)
                .filter(value -> value.deletedAt() == null).orElseThrow(DashboardShareManagementService::notFound);
        if (!tenant.equals(state.tenantId())) throw new IllegalStateException("看板锁内租户归属异常");
        return state;
    }

    /** 校每一个设备变量及其全部defaults；候选并集20，与运行选中数不是同一个约束。 */
    private List<DashboardShareVariableScope> scopes(DashboardPublicationCandidate candidate,
                                                    List<DashboardShareVariableRequest> input) {
        Map<String, DashboardShareVariableRequest> requested = new HashMap<>();
        for (DashboardShareVariableRequest value : input) requested.put(value.variableKey(), value);
        Map<String, UUID> models = new HashMap<>();
        candidate.modelReferences().forEach(model -> models.put(model.modelKey(), model.thingModelVersionId()));
        Map<UUID, DeviceModelBindingFacts> observed = new HashMap<>();
        List<DashboardShareVariableScope> result = new ArrayList<>();
        for (JsonNode variable : candidate.normalizedSchema().path("variables")) {
            String type = variable.path("type").asString();
            if (!"DEVICE_SINGLE".equals(type) && !"DEVICE_MULTI".equals(type)) continue;
            String key = variable.path("key").asString();
            DashboardShareVariableRequest scope = requested.remove(key);
            if (scope == null) throw invalid();
            UUID model = models.get(variable.path("modelKey").asString());
            if (model == null) throw invalid();
            Set<UUID> ids = Set.copyOf(scope.deviceIds());
            if (variable.has("defaultDeviceId") && !ids.contains(UUID.fromString(variable.get("defaultDeviceId").asString()))) throw invalid();
            for (JsonNode id : variable.path("defaultDeviceIds")) {
                if (!ids.contains(UUID.fromString(id.asString()))) throw invalid();
            }
            for (UUID id : ids) {
                DeviceModelBindingFacts fact = observed.computeIfAbsent(id,
                        value -> devices.find(candidate.projectId(), value).orElseThrow(DashboardShareManagementService::invalid));
                if (!candidate.projectId().equals(fact.projectId()) || !id.equals(fact.deviceId())
                        || !model.equals(fact.thingModelVersionId())) throw invalid();
            }
            result.add(new DashboardShareVariableScope(key, model, ids.stream().sorted().toList()));
        }
        if (!requested.isEmpty()) throw invalid();
        return List.copyOf(result);
    }

    /** 领域入口重复验证DTO不能独占的数量、TTL、精确revision和HostRange约束。 */
    private static void validateRequest(DashboardShareCreateRequest request) {
        if (request == null || request.dashboardVersionId() == null || request.expiresInSeconds() < 300
                || request.expiresInSeconds() > 86400 || request.refererPolicy() == null
                || !Set.of("NONE", "HOST_ORIGIN").contains(request.refererPolicy())
                || request.variables() == null || request.variables().size() > 20) throw invalid();
        try {
            String revision = request.expectedDashboardPublicationRevision();
            if (revision == null || !revision.matches("[1-9][0-9]{0,18}") || Long.parseLong(revision) <= 0) throw invalid();
        } catch (NumberFormatException failure) { throw invalid(); }
        JsonNode host = request.hostCompatibility();
        if (host == null || !host.isObject() || !host.propertyNames().equals(Set.of("minInclusive", "maxExclusive"))
                || compare(semver(host.get("minInclusive")), semver(host.get("maxExclusive"))) >= 0) throw invalid();
        Set<String> keys = new HashSet<>();
        Set<UUID> union = new HashSet<>();
        for (DashboardShareVariableRequest variable : request.variables()) {
            if (variable == null || variable.variableKey() == null || !variable.variableKey().matches("[a-z][a-z0-9_]{0,63}")
                    || !keys.add(variable.variableKey()) || variable.deviceIds() == null
                    || variable.deviceIds().isEmpty() || variable.deviceIds().size() > 20) throw invalid();
            Set<UUID> ids = new HashSet<>();
            for (UUID id : variable.deviceIds()) if (id == null || !ids.add(id)) throw invalid();
            union.addAll(ids);
        }
        if (union.size() > 20) throw invalid();
    }

    /** HostRange采用既有有限三段SemVer，不接受前导零、扩展或超过65535的段。 */
    private static int[] semver(JsonNode value) {
        if (value == null || !value.isString() || !value.asString().matches("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})")) throw invalid();
        int[] result = java.util.Arrays.stream(value.asString().split("\\.")).mapToInt(Integer::parseInt).toArray();
        for (int component : result) if (component > 65535) throw invalid();
        return result;
    }

    /** 数值比较不能用SemVer字典序。 */
    private static int compare(int[] left, int[] right) {
        for (int index = 0; index < 3; index++) {
            int result = Integer.compare(left[index], right[index]);
            if (result != 0) return result;
        }
        return 0;
    }

    /** 请求键身份由关系唯一键绑定；body摘要保留语义，变量和候选作为集合排序。 */
    private static String requestDigest(DashboardShareCreateRequest request) {
        List<Object> variables = request.variables().stream().sorted(Comparator.comparing(DashboardShareVariableRequest::variableKey))
                .map(variable -> (Object) List.of(variable.variableKey(), variable.deviceIds().stream().sorted().map(UUID::toString).toList())).toList();
        return digest(JSON.writeValueAsString(List.of(request.dashboardVersionId().toString(),
                request.expectedDashboardPublicationRevision(), request.expiresInSeconds(), request.refererPolicy(),
                request.hostCompatibility().get("minInclusive").asString(), request.hostCompatibility().get("maxExclusive").asString(), variables)));
    }

    /** 不透明keyset绑定管理目标；它不是匿名capability游标，也不授予额外数据权限。 */
    private static String encodeCursor(UUID project, UUID dashboard, Instant at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (project + "|" + dashboard + "|" + at + "|" + id).getBytes(StandardCharsets.US_ASCII));
    }

    /** 只生成不可逆SHA-256，凭据/摘要均不写入审计或日志。 */
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("运行环境缺少SHA-256", impossible); }
    }

    /** Console可信主体只用于管理审计，匿名运行不复用此身份。 */
    private static UUID actor() { return TenantContext.require().accountId(); }
    /** 安全输入分类。 */ private static BusinessException invalid() { return new BusinessException(DashboardErrorCode.SHARE_INVALID); }
    /** D-145尚未资格的生产宿主或适配器保持fail-closed。 */ private static BusinessException dependency() { return new BusinessException(DashboardErrorCode.DASHBOARD_PUBLICATION_DEPENDENCY_UNAVAILABLE); }
    /** 不可见Dashboard沿既有管理错误。 */ private static BusinessException notFound() { return new BusinessException(DashboardErrorCode.DASHBOARD_NOT_FOUND); }
}
