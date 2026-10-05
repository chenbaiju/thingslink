package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisCallRepository;
import com.things.link.assistant.domain.AnalysisSlotRepository;
import com.things.link.assistant.domain.ModelCredentialRepository;
import com.things.link.device.application.ConsoleDeviceEvidenceService;
import com.things.link.project.application.ProjectManagementWriteGuard;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import static com.things.link.assistant.domain.AnalysisCall.Status.*;

/** 每步独立提交最小元数据；设备自有事务顺序完成，避免持锁事务嵌套耗尽连接池。
 * 复核和提交不是跨域原子快照；网络必须在事务之外，只有dispatch成功提交才许可一次发送。
 */
@Service
public class AnalysisCallService {
    private final ProjectService projects;
    private final ProjectManagementWriteGuard guard;
    private final TransactionLocalRlsScope rls;
    private final ConsoleDeviceEvidenceService devices;
    private final AnalysisCallRepository calls;
    private final ModelCredentialRepository credentials;
    private final AnalysisSlotRepository slots;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public AnalysisCallService(ProjectService projects, ProjectManagementWriteGuard guard,
            TransactionLocalRlsScope rls, ConsoleDeviceEvidenceService devices,
            AnalysisCallRepository calls, ModelCredentialRepository credentials, AnalysisSlotRepository slots, Clock clock, PlatformTransactionManager manager) {
        this.projects = projects; this.guard = guard; this.rls = rls; this.devices = devices;
        this.calls = calls; this.credentials = credentials; this.slots = slots; this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /**
     * 校验设备及付费权限后预留调用元数据；同一幂等键只允许同一请求摘要。
     * @param project 当前身份所选项目
     * @param key 带签发时间的规范 UUIDv7 幂等键，不是项目模型密钥
     * @param request 固定模板、设备、模型版本及有界属性键组成的请求
     * @return 个人可见的预留记录及是否首次创建，不授予直接发送权限
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisCallView.Reservation prepare(UUID project, String key, AnalysisRequest request) {
        precheck(project);
        fingerprint(request); expiry(key, clock.instant());
        devices.read(project, request.deviceId(), request.expectedModelVersionId(), request.propertyKeys());
        return transaction.execute(s -> prepareStored(project, key, request));
    }
    private AnalysisCallView.Reservation prepareStored(UUID project, String key, AnalysisRequest request) {
        UUID tenant = authorize(project), creator = TenantContext.require().accountId();
        Instant now = clock.instant(), expires = expiry(key, now);
        String keyHash = hash(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII)), requestHash = fingerprint(request);
        var existing = calls.findByKey(tenant, project, creator, keyHash);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) throw conflict();
            return new AnalysisCallView.Reservation(AnalysisCallView.of(expire(existing.get(), now)), false);
        }
        if (!expires.isAfter(now.plusSeconds(60))) throw invalid();
        long revision = configuration(tenant, project);
        slots.expire(tenant, project);
        calls.deleteExpired(tenant, project, 100);
        var call = new AnalysisCall(Uuid7.generate(), tenant, project, creator, request.deviceId(),
                request.expectedModelVersionId(), keyHash, requestHash, revision, RESERVED,
                now, now.plusSeconds(60), expires, null, null);
        if (!calls.insert(call)) throw conflict();
        return new AnalysisCallView.Reservation(AnalysisCallView.of(call), true);
    }
    /**
     * 重验设备、身份及配置后获取共享槽，将预留记录推进为已派发。
     * @param project 当前身份所选项目
     * @param id 当前调用者拥有的调用记录标识
     * @param originalRequest 与预留摘要完全一致的原始请求
     * @return 含共享槽令牌的内部许可；提交后仍须单次执行认领
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisDispatchPermit dispatch(UUID project, UUID id, AnalysisRequest originalRequest) {
        revalidate(inspect(project, id));
        return transaction.execute(s -> dispatchStored(project, id, originalRequest));
    }
    private AnalysisDispatchPermit dispatchStored(UUID project, UUID id, AnalysisRequest originalRequest) {
        UUID tenant = authorize(project);
        var call = owned(tenant, project, id);
        Instant now = clock.instant();
        if (!call.requestHash().equals(fingerprint(originalRequest))) throw conflict();
        if (call.status() != RESERVED || !now.isBefore(call.deadline()) || !now.isBefore(call.expiresAt())
                || configuration(tenant, project) != call.configurationRevision()) throw conflict();
        UUID token = UUID.randomUUID();
        var admission = slots.acquire(call, token);
        if (admission == AnalysisSlotRepository.Admission.BUSY) throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        if (admission != AnalysisSlotRepository.Admission.ADMITTED) throw conflict();
        // 槽位/项目锁等待不延长运行窗口，提交前再次采时。
        now = clock.instant();
        if (!now.isBefore(call.deadline())) throw conflict();
        if (!calls.transition(call, DISPATCHED, now)) throw conflict();
        return new AnalysisDispatchPermit(AnalysisCallView.of(owned(tenant, project, id)), token);
    }
    /**
     * 可信内部编排回执，未来 HTTP 不得将此方法暴露给浏览器或接受模型自报状态。
     * @param project 当前身份所选项目
     * @param id 当前调用者拥有的调用记录标识
     * @param outcome 可信执行器确定的终态；成功须已认领执行槽
     * @return 持久终态视图，过期的不确定执行收敛为未知
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisCallView complete(UUID project, UUID id, AnalysisCall.Status outcome) {
        revalidate(inspect(project, id));
        return transaction.execute(s -> completeStored(project, id, outcome));
    }
    private AnalysisCallView completeStored(UUID project, UUID id, AnalysisCall.Status outcome) {
        UUID tenant = authorize(project);
        var call = owned(tenant, project, id);
        if (outcome == null || !outcome.terminal()) throw invalid();
        Instant now = clock.instant();
        call = expire(call, now);
        if (call.status().terminal()) {
            if (call.status() != outcome && call.status() != UNKNOWN) throw conflict();
            return AnalysisCallView.of(call);
        }
        if (outcome == SUCCEEDED && (call.status() == RESERVED || !slots.wasClaimed(call))) throw conflict();
        if (!calls.transition(call, outcome, now)) throw conflict();
        return AnalysisCallView.of(owned(tenant, project, id));
    }
    /**
     * 重验设备及权限后读取个人调用元数据，过期记录收敛为未知。
     * @param project 当前身份所选项目
     * @param id 当前调用者拥有的调用标识
     * @return 脱敏状态，不包含设备证据、凭据或生成正文
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisCallView read(UUID project, UUID id) {
        revalidate(inspect(project, id));
        return transaction.execute(s -> AnalysisCallView.of(expire(owned(authorize(project), project, id), clock.instant())));
    }
    /**
     * 响应丢失时按原幂等键定位本人调用；不创建记录或执行，不把未找到解释为未消费。
     * @param project 当前身份所选且仍有付费分析权限的项目
     * @param key 原规范UUIDv7幂等键，不是调用标识或项目密钥
     * @return 重新确权的最小元数据，沿既有规则将过期未完成调用收敛为未知
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisCallView readByKey(UUID project, String key) {
        precheck(project);
        expiry(key, clock.instant());
        String keyHash = hash(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var original = transaction.execute(s -> {
            UUID tenant = authorize(project);
            expiry(key, clock.instant());
            return calls.findByKey(tenant, project, TenantContext.require().accountId(), keyHash)
                    .orElseThrow(AnalysisCallService::missing);
        });
        revalidate(original);
        return transaction.execute(s -> {
            UUID tenant = authorize(project);
            Instant now = clock.instant();
            expiry(key, now);
            return AnalysisCallView.of(expire(owned(tenant, project, original.id()), now));
        });
    }
    /**
     * 清理当前项目过期共享槽及最多一百条已过保留期的调用元数据。
     * @param project 当前身份所选且具备付费调用权限的项目
     * @return 实际删除的调用元数据条数
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int cleanupExpired(UUID project) {
        return transaction.execute(s -> {
            UUID tenant = authorize(project); slots.expire(tenant, project);
            return calls.deleteExpired(tenant, project, 100);
        });
    }
    /**
     * 一次认领提交后才可进入后续密钥及 HTTP 执行器；失败不回退，不能复用许可重试。
     * @param project 当前身份所选项目
     * @param permit 原派发许可，必须匹配仍有效的记录及共享槽令牌
     * @return 已提交认领对应的原调用与密文；只允许后续事务外同步执行，不是业务准入
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisExecutionContext claimForExecution(UUID project, AnalysisDispatchPermit permit) {
        if (permit == null) throw invalid();
        revalidate(inspect(project, permit.call().id()));
        return transaction.execute(s -> {
            UUID tenant = authorize(project);
            var call = owned(tenant, project, permit.call().id());
            Instant now = clock.instant();
            var credential = credentials.find(tenant, project)
                    .filter(c -> tenant.equals(c.tenantId()) && project.equals(c.projectId())
                            && c.enabled() && c.ciphertext() != null && c.revision() == call.configurationRevision())
                    .orElseThrow(AnalysisCallService::conflict);
            if (call.status() != DISPATCHED || !AnalysisCallView.of(call).equals(permit.call())
                    || !now.isBefore(call.deadline()) || !now.isBefore(call.expiresAt())
                    || !slots.claim(call, permit.token())) throw conflict();
            // 原项目管理锁覆盖配置查询与认领；认领等待耗时不能延长期限。
            now = clock.instant();
            if (!now.isBefore(call.deadline()) || !now.isBefore(call.expiresAt())) throw conflict();
            return new AnalysisExecutionContext(call, credential);
        });
    }
    /**
     * 必须由已停止本地传输的可信执行器调用；取消按钮或未知状态不证明可提前释放。
     * @param project 当前身份所选项目
     * @param permit 原派发许可，须与持久共享槽匹配
     * @return 匹配的共享槽成功释放时为真
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean releasePermit(UUID project, AnalysisDispatchPermit permit) {
        if (permit == null) throw invalid();
        return transaction.execute(s -> {
            UUID tenant = authorize(project);
            return slots.release(owned(tenant, project, permit.call().id()), permit.token());
        });
    }
    private AnalysisCall inspect(UUID project, UUID id) {
        return transaction.execute(s -> owned(authorize(project), project, id));
    }

    /**
     * 在独立短事务中核验当前项目和付费角色；关闭通道不必读取密文或创建调用。
     * @param project 当前身份所选项目
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void authorizeForAnalysis(UUID project) {
        transaction.executeWithoutResult(s -> authorize(project));
    }

    /**
     * 已受审状态只检查本项目配置元数据，不解密、不输出版本或密文。
     * @param project 当前身份项目，仅付费角色可读，返回前再次确权
     * @return 当前项目具备已启用且存在密文的配置，不代表余额或供应商可用
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean analysisConfigurationAvailable(UUID project) {
        return transaction.execute(s -> {
            UUID tenant = authorize(project);
            boolean configured = credentials.find(tenant, project)
                    .map(value -> value.enabled() && value.ciphertext() != null).orElse(false);
            authorize(project);
            return configured;
        });
    }

    /**
     * 即使内部通道关闭也拒绝非法或过期请求身份，不读证据或创建调用。
     * @param key 规范UUIDv7幂等键，不是模型密钥
     * @param request 封闭的设备模板请求
     */
    public void validateRequestIdentity(String key, AnalysisRequest request) {
        fingerprint(request);
        expiry(key, clock.instant());
    }

    /**
     * 临发前重验设备、身份、认领及当前配置，不解密，也不返还或延长执行机会。
     * @param project 当前所选项目
     * @param execution 原始已提交执行上下文，不接受请求反序列化对象
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void verifyExecution(UUID project, AnalysisExecutionContext execution) {
        if (execution == null) throw invalid();
        revalidate(inspect(project, execution.call().id()));
        transaction.executeWithoutResult(s -> verifyExecutionStored(project, execution));
    }

    /**
     * 返回未准入确认前再确权，在同一项目锁下登记未知；不把技术确认提升为业务成功。
     * @param project 当前所选项目
     * @param execution 本次原始执行上下文
     * @return 个人可见未知元数据，不含用量推测或模型正文
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisCallView completeUnqualified(UUID project, AnalysisExecutionContext execution) {
        if (execution == null) throw invalid();
        revalidate(inspect(project, execution.call().id()));
        return transaction.execute(s -> {
            verifyExecutionStored(project, execution);
            return completeStored(project, execution.call().id(), UNKNOWN);
        });
    }

    /**
     * 最终确权后消费原调用的一次性结果凭证，仅提交成功元数据后才交付瞬时正文。
     * @param project 当前身份所选项目，必须仍有付费角色
     * @param execution 原始已认领上下文，配置及设备模型必须仍匹配
     * @param permit 受信应用签发的单次凭证，当前生产尚无签发者
     * @return 本次瞬时结果；重复、过期、撤权或事务失败均不返回正文，也不释放执行槽
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReleasedAnalysisResult releaseResult(UUID project, AnalysisExecutionContext execution, AnalysisResultPermit permit) {
        if (execution == null || permit == null) throw invalid();
        revalidate(inspect(project, execution.call().id()));
        AnalysisCallView completed = transaction.execute(s -> {
            verifyExecutionStored(project, execution);
            permit.consume(execution, clock.instant());
            var result = completeStored(project, execution.call().id(), SUCCEEDED);
            if (result.status() != SUCCEEDED) throw conflict();
            permit.verifyReturn(execution, clock.instant());
            return result;
        });
        return new ReleasedAnalysisResult(completed, permit.content(), permit.usage(), permit.model(), permit.promptVersion());
    }

    /** 当前状态须与原认领一致；配置变化、过期或丢失持久认领均拒绝继续。 */
    private void verifyExecutionStored(UUID project, AnalysisExecutionContext execution) {
        UUID tenant = authorize(project);
        var call = owned(tenant, project, execution.call().id());
        Instant now = clock.instant();
        if (!call.equals(execution.call()) || call.status() != DISPATCHED
                || !now.isBefore(call.deadline()) || !now.isBefore(call.expiresAt())
                || configuration(tenant, project) != call.configurationRevision() || !slots.wasClaimed(call))
            throw conflict();
    }
    private void precheck(UUID project) {
        if (!Objects.equals(project, TenantContext.require().projectId())) throw missing();
        paid(projects.requireRoleInProject(project));
    }
    private UUID authorize(UUID project) {
        precheck(project);
        paid(guard.requireMember(project, TenantContext.require().accountId()));
        UUID tenant = projects.requireProjectTenant(project);
        rls.establish(tenant, project);
        return tenant;
    }
    private static void paid(ProjectRole role) {
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN && role != ProjectRole.OPERATOR)
            throw new BusinessException(com.things.link.assistant.domain.AssistantErrorCode.MODEL_ANALYSIS_FORBIDDEN);
    }
    private AnalysisCall owned(UUID tenant, UUID project, UUID id) {
        return calls.find(tenant, project, TenantContext.require().accountId(), id).orElseThrow(AnalysisCallService::missing);
    }
    private void revalidate(AnalysisCall c) { devices.revalidate(c.projectId(), c.deviceId(), c.modelVersionId()); }
    private long configuration(UUID tenant, UUID project) {
        return credentials.find(tenant, project).filter(c -> c.enabled() && c.ciphertext() != null)
                .orElseThrow(AnalysisCallService::conflict).revision();
    }
    private AnalysisCall expire(AnalysisCall c, Instant now) {
        if (!c.status().terminal() && (!now.isBefore(c.deadline()) || !now.isBefore(c.expiresAt()))) {
            if (!calls.transition(c, UNKNOWN, now)) throw conflict();
            return owned(c.tenantId(), c.projectId(), c.id());
        }
        return c;
    }
    /**
     * 从规范 UUIDv7 幂等键计算二十四小时失效时间，拒绝过期或超出时钟容差的键。
     * @param raw 规范小写 UUIDv7 文本
     * @param now 服务端当前时间
     * @return 基于键内签发时间的失效时间，不根据重放时刻续期
     */
    private static Instant expiry(String raw, Instant now) {
        UUID key;
        try { key = UUID.fromString(raw); } catch (RuntimeException ignored) { throw invalid(); }
        if (!key.toString().equals(raw) || key.version() != 7 || key.variant() != 2) throw invalid();
        Instant issued = Instant.ofEpochMilli(key.getMostSignificantBits() >>> 16);
        Instant expires = issued.plusSeconds(86400);
        if (!expires.isAfter(now) || issued.isAfter(now.plusSeconds(300))) throw invalid();
        return expires;
    }
    /**
     * 对固定请求身份、模板及有序属性键生成规范摘要，防止同键更换请求。
     * @param r 已通过结构校验的内部分析请求
     * @return 原始请求规范编码的 SHA-256 摘要
     */
    private static String fingerprint(AnalysisRequest r) {
        if (r == null) throw invalid();
        try {
            var buffer = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(buffer)) {
                out.writeUTF("thingslink-agent-analysis-v1");
                out.writeLong(r.deviceId().getMostSignificantBits()); out.writeLong(r.deviceId().getLeastSignificantBits());
                out.writeLong(r.expectedModelVersionId().getMostSignificantBits()); out.writeLong(r.expectedModelVersionId().getLeastSignificantBits());
                out.writeUTF(r.template().name()); out.writeInt(r.propertyKeys().size());
                for (String property : r.propertyKeys()) out.writeUTF(property);
            }
            return hash(buffer.toByteArray());
        } catch (java.io.IOException impossible) { throw new IllegalStateException("analysis fingerprint unavailable"); }
    }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("analysis digest unavailable"); }
    }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    private static BusinessException conflict() { return new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT); }
    private static BusinessException missing() { return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND); }
}
