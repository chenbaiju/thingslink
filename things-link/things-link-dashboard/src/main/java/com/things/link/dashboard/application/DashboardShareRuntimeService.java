package com.things.link.dashboard.application;

import com.things.link.dashboard.application.publication.DashboardPublicationCandidate;
import com.things.link.dashboard.application.publication.DashboardPublicationCandidateFactory;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardShareRuntimeRepository;
import com.things.link.dashboard.domain.DashboardShareRuntimeState;
import com.things.link.dashboard.domain.DashboardShareToken;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ADR0101匿名capability每请求独立只读复验，不能借用签发者Console或App身份。
 * 首次最小定位后在真实事务建立可信RLS；filter身份不是正文读取的授权快照。
 */
@Service
public class DashboardShareRuntimeService {
    /** 唯一受限定位和后续普通项目RLS端口。 */
    private final DashboardShareRuntimeRepository repository;
    /** 同一事务物理连接恢复可信tenant/project，不设置Servlet ThreadLocal。 */
    private final TransactionLocalRlsScope scope;
    /** 逐请求读取ACTIVE/ARCHIVED和项目代次，不依赖原签发者成员关系。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 同域精确不可变版本持久入口。 */
    private final DashboardRepository dashboards;
    /** 历史Schema完整内部语义、派生清单与PG摘要验证，不消费当前D-145 Host。 */
    private final DashboardPublicationCandidateFactory candidateFactory;

    /** 装配匿名运行验证所需的本域持久和公开项目生命周期端口。 */
    public DashboardShareRuntimeService(DashboardShareRuntimeRepository repository, TransactionLocalRlsScope scope,
            ProjectLifecycleAccessService lifecycle, DashboardRepository dashboards,
            DashboardPublicationCandidateFactory candidateFactory) {
        this.repository = repository;
        this.scope = scope;
        this.lifecycle = lifecycle;
        this.dashboards = dashboards;
        this.candidateFactory = candidateFactory;
    }

    /**
     * 最小匿名认证，不加载Schema正文；未知或失效capability统一60053。
     * @param shareId URL能力选择器 @param secretHash 规范secret不可逆摘要
     * @return 冻结内部身份，后续context/schema必须继续复验
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DashboardSharePrincipal authenticate(UUID shareId, String secretHash) {
        if (shareId == null || secretHash == null || !secretHash.matches("[0-9a-f]{64}")) throw unavailable();
        return guarded(() -> {
            var identity = repository.locate(shareId, secretHash).orElseThrow(DashboardShareRuntimeService::unavailable);
            DashboardShareRuntimeState state = current(identity.tenantId(), identity.projectId(), shareId, secretHash);
            DashboardShareToken token = state.token();
            return new DashboardSharePrincipal(token.id(), token.tenantId(), token.projectId(), token.dashboardId(),
                    token.dashboardVersionId(), token.projectGeneration(), token.expiresAt(), token.refererPolicy(), token.secretHash());
        });
    }

    /**
     * 恢复一个分享的最小候选范围与DB历史锚点，不重复读取500KiB完整Schema。
     * @param principal 最小认证形成的不可变身份 @return 当前真实事实的上下文
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DashboardShareContext context(DashboardSharePrincipal principal) {
        return guarded(() -> {
            DashboardShareRuntimeState state = current(principal);
            List<DashboardShareVariableScope> variables = repository.findScopes(principal.tenantId(), principal.projectId(), principal.shareId());
            validateScopes(variables);
            return new DashboardShareContext(principal.shareId(), principal.dashboardId(), principal.dashboardVersionId(),
                    state.dashboardVersionNumber(), state.token().expiresAt(), state.databaseNow(), state.token().hostCompatibility(),
                    variables.stream().map(variable -> new DashboardShareVariableScopeView(variable.variableKey(), variable.deviceIds())).toList());
        });
    }

    /**
     * 返回分享冻结的精确旧版本完整Schema，当前指针仅证明可运行而不替换版本。
     * @param principal 最小认证身份 @return 完整语义与PG摘要已复验的Schema包
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DashboardShareSchema schema(DashboardSharePrincipal principal) {
        return guarded(() -> readSchema(principal, current(principal)));
    }

    /** 数据编排已开启只读事务时加入同一连接；返回后领域端口仍处于相同RLS范围。 */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public DashboardShareReadContext read(DashboardSharePrincipal principal) {
        return guarded(() -> {
            DashboardShareRuntimeState state = current(principal);
            List<DashboardShareVariableScope> variables = repository.findScopes(
                    principal.tenantId(), principal.projectId(), principal.shareId());
            validateScopes(variables);
            return new DashboardShareReadContext(principal, readSchema(principal, state), variables, state.databaseNow());
        });
    }

    /** 同一观察内加载完整精确版本，供Schema包和匿名数据复用，不再开启嵌套新事务。 */
    private DashboardShareSchema readSchema(DashboardSharePrincipal principal, DashboardShareRuntimeState state) {
        DashboardVersion version = dashboards.findVersion(principal.projectId(), principal.dashboardId(), principal.dashboardVersionId())
                .orElseThrow(DashboardShareRuntimeService::unavailable);
        if (!principal.tenantId().equals(version.tenantId()) || !principal.projectId().equals(version.projectId())
                || !principal.dashboardId().equals(version.dashboardId()) || !principal.dashboardVersionId().equals(version.id())
                || state.dashboardVersionNumber() != version.versionNumber()) {
            throw new IllegalStateException("分享精确版本持久身份漂移");
        }
        DashboardPublicationCandidate candidate = candidateFactory.prepareHistoricalVersion(version);
        if (!principal.dashboardId().equals(candidate.dashboardId()) || !principal.projectId().equals(candidate.projectId())
                || !principal.tenantId().equals(candidate.tenantId()) || version.sourceDraftRevision() != candidate.sourceDraftRevision()) {
            throw new IllegalStateException("分享Schema候选身份漂移");
        }
        return new DashboardShareSchema(principal.dashboardId(), principal.dashboardVersionId(), version.versionNumber(),
                version.schemaVersion(), candidate.schemaDigestAlgorithm(), candidate.schemaDigest(),
                candidate.requiredComponents(), candidate.requiredResources(), candidate.normalizedSchema());
    }

    /** 每次入口按冻结完整身份重新观察，错代次或selector不能复用既有filter快照。 */
    private DashboardShareRuntimeState current(DashboardSharePrincipal principal) {
        if (principal == null) throw unavailable();
        DashboardShareRuntimeState state = current(principal.tenantId(), principal.projectId(), principal.shareId(), principal.secretHash());
        DashboardShareToken token = state.token();
        if (!principal.dashboardId().equals(token.dashboardId()) || !principal.dashboardVersionId().equals(token.dashboardVersionId())
                || principal.projectGeneration() != token.projectGeneration() || !principal.expiresAt().equals(token.expiresAt())
                || !principal.refererPolicy().equals(token.refererPolicy())) throw unavailable();
        return state;
    }

    /** 在真实事务连接恢复RLS后复验token、DB时刻、资源与项目代次，任何确定失权均同一404。 */
    private DashboardShareRuntimeState current(UUID tenantId, UUID projectId, UUID shareId, String secretHash) {
        scope.establish(tenantId, projectId);
        DashboardShareRuntimeState state = repository.findState(tenantId, projectId, shareId, secretHash)
                .orElseThrow(DashboardShareRuntimeService::unavailable);
        DashboardShareToken token = state.token();
        if (!tenantId.equals(token.tenantId()) || !projectId.equals(token.projectId()) || !shareId.equals(token.id())
                || !secretHash.equals(token.secretHash())) throw new IllegalStateException("分享定位与持久身份漂移");
        if (token.revokedAt() != null || !state.databaseNow().isBefore(token.expiresAt())
                || !state.dashboardRunnable() || state.dashboardVersionNumber() <= 0) throw unavailable();
        var policy = lifecycle.snapshot(tenantId, projectId);
        if (!policy.readAllowed() || !policy.matchesGeneration(token.projectGeneration())) throw unavailable();
        return state;
    }

    /** 仓储scope损坏不能被默认为静态页或截断为部分授权，保留503而非匿名404。 */
    private static void validateScopes(List<DashboardShareVariableScope> variables) {
        if (variables == null || variables.size() > 20) throw new IllegalStateException("分享变量范围损坏");
        Set<String> keys = new HashSet<>();
        Set<UUID> devices = new HashSet<>();
        for (DashboardShareVariableScope variable : variables) {
            if (variable == null || variable.variableKey() == null || !keys.add(variable.variableKey())
                    || variable.modelVersionId() == null || variable.deviceIds().isEmpty() || variable.deviceIds().size() > 20
                    || variable.deviceIds().stream().anyMatch(Objects::isNull)
                    || Set.copyOf(variable.deviceIds()).size() != variable.deviceIds().size()) {
                throw new IllegalStateException("分享变量候选损坏");
            }
            devices.addAll(variable.deviceIds());
        }
        if (devices.size() > 20) throw new IllegalStateException("分享候选设备并集损坏");
    }

    /** 业务不可用保持60053，其他依赖或持久损坏503并保留首因，消息不暴露异常原文。 */
    private static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (BusinessException failure) {
            if (failure.errorCode() == DashboardErrorCode.SHARE_NOT_FOUND) throw failure;
            throw dependencyFailure(failure);
        } catch (RuntimeException failure) {
            throw dependencyFailure(failure);
        }
    }

    /** 匿名确定失权不枚举内部原因。 */
    private static BusinessException unavailable() { return new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND); }

    /** HTTP固定503消息保留可诊断cause，但响应与脱敏事件不输出cause原文。 */
    private static BusinessException dependencyFailure(RuntimeException cause) {
        BusinessException result = new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
        result.initCause(cause);
        return result;
    }
}
