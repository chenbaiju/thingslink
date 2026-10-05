package com.things.link.assistant.application;
import com.things.link.assistant.domain.ModelCredential;
import com.things.link.assistant.domain.ModelCredentialCipher;
import com.things.link.assistant.domain.ModelCredentialRepository;
import com.things.link.assistant.domain.ModelCredentialProtectionException;
import com.things.link.assistant.domain.AssistantErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectManagementWriteGuard;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.audit.AuditLogEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 项目管理员配置，所有写操作与审计同一事务；没有外部 HTTP 调用。 */
@Service
public class ModelConfigurationService {
    private final ProjectService projects;
    private final ProjectManagementWriteGuard guard;
    private final TransactionLocalRlsScope rls;
    private final ModelCredentialRepository repository;
    private final ModelCredentialCipher cipher;
    private final AuditLogService audit;
    private final Clock clock;
    public ModelConfigurationService(ProjectService projects,ProjectManagementWriteGuard guard,TransactionLocalRlsScope rls,
            ModelCredentialRepository repository,ModelCredentialCipher cipher,AuditLogService audit,Clock clock) {
        this.projects=projects;this.guard=guard;this.rls=rls;this.repository=repository;this.cipher=cipher;this.audit=audit;this.clock=clock;
    }
    /**
     * 管理员读取脱敏配置状态，未配置时返回禁用及零版本。
     * @param project 当前身份所选项目
     * @return 是否配置、启用状态、版本及更新时间，不返回密文或明文
     */
    @Transactional(readOnly=true)
    public ModelConfigurationView read(UUID project) {
        UUID tenant=authorize(project,false);
        return repository.find(tenant,project).map(ModelConfigurationService::view)
            .orElse(new ModelConfigurationView(false,false,"0",null));
    }
    /**
     * 在同一事务内替换密文并启用；任一步失败均回滚密文、版本及审计。
     * @param project 当前身份所选项目，调用者须具备管理权限
     * @param input 带期望版本及本次项目密钥的输入，不得记录日志
     * @return 保存并启用后的脱敏状态
     */
    @Transactional
    public ModelConfigurationView replaceAndEnable(UUID project,ModelCredentialInput input) {
        // 沿既有密文版本守卫先替换再启用；外层事务使凭据、两次版本推进和审计一起提交或回滚。
        var replaced=replace(project,input);
        return enable(project,replaced.revision(),true);
    }
    /**
     * 按期望版本替换项目密钥；加密后的配置默认停用。
     * @param project 当前身份所选项目，调用者须具备管理权限
     * @param input 包含期望配置版本及可打印字符组成的项目密钥
     * @return 新密文对应的脱敏状态，不返回密钥
     */
    @Transactional
    public ModelConfigurationView replace(UUID project,ModelCredentialInput input) {
        String expected=input.expectedRevision(),secret=input.apiKey();
        if (secret == null || !secret.matches("[!-~]{1,4096}")) throw invalid();
        ModelCredential old=current(project,expected);
        long next=old.revision()+1;
        ModelCredential updated;
        try {
            updated=cipher.encrypt(new ModelCredential(old.id(),old.tenantId(),project,next,next,false,null,null,null,
                TenantContext.require().accountId(),clock.instant()),secret);
        } catch (ModelCredentialProtectionException ignored) { throw new BusinessException(AssistantErrorCode.CREDENTIAL_PROTECTION_UNAVAILABLE); }
        return save(updated,old.revision(),"replace");
    }
    /**
     * 按期望版本启用或停用现有配置；启用前验证密文可解密，不调用供应商。
     * @param project 当前身份所选项目，调用者须具备管理权限
     * @param expected 当前配置的十进制版本文本，冲突时拒绝更新
     * @param enabled 为真时启用，为假时停用
     * @return 更新后的脱敏状态，凭据版本及密文保持不变
     */
    @Transactional
    public ModelConfigurationView enable(UUID project,String expected,boolean enabled) {
        ModelCredential old=current(project,expected);
        if (enabled) {
            if (old.ciphertext()==null) throw conflict();
            try { cipher.verify(old); }
            catch (ModelCredentialProtectionException ignored) { throw new BusinessException(AssistantErrorCode.CREDENTIAL_PROTECTION_UNAVAILABLE); }
        }
        return save(new ModelCredential(old.id(),old.tenantId(),project,old.revision()+1,old.credentialRevision(),enabled,
            old.ciphertext(),old.nonce(),old.keyId(),TenantContext.require().accountId(),clock.instant()),old.revision(),enabled?"enable":"disable");
    }
    /**
     * 按期望版本移除项目密文并停用，保留配置记录及版本推进。
     * @param project 当前身份所选项目，调用者须具备管理权限
     * @param expected 当前配置版本，防止覆盖并发修改
     * @return 不含凭据且已停用的脱敏状态
     */
    @Transactional
    public ModelConfigurationView remove(UUID project,String expected) {
        ModelCredential old=current(project,expected);
        return save(new ModelCredential(old.id(),old.tenantId(),project,old.revision()+1,0,false,null,null,null,
            TenantContext.require().accountId(),clock.instant()),old.revision(),"remove");
    }
    private UUID authorize(UUID project,boolean write) {
        if (!Objects.equals(project,TenantContext.require().projectId())) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        if (write) guard.requireMemberManager(project,TenantContext.require().accountId());
        else projects.requireProjectAdministrator(project);
        UUID tenant=projects.requireProjectTenant(project); rls.establish(tenant,project); return tenant;
    }
    private ModelCredential current(UUID project,String revision) {
        UUID tenant=authorize(project,true);
        if (revision==null || !revision.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        long expected;
        try { expected=Long.parseLong(revision); } catch (NumberFormatException ignored) { throw invalid(); }
        var old=repository.find(tenant,project).orElse(new ModelCredential(Uuid7.generate(),tenant,project,0,0,false,null,null,null,null,null));
        if (expected!=old.revision() || expected==Long.MAX_VALUE) throw conflict();
        return old;
    }
    /**
     * 原子保存期望版本并记录审计，审计只包含用途、版本及启用状态。
     * @param c 待持久化的密文或移除状态，不得直接输出
     * @param expected 更新前配置版本
     * @param action 固定内部操作分类，用于审计事件名称
     * @return 保存成功后的脱敏状态
     */
    private ModelConfigurationView save(ModelCredential c,long expected,String action) {
        if (!repository.save(c,expected)) throw conflict();
        audit.record(new AuditLogEntry(c.tenantId(),c.projectId(),c.updatedBy(),"assistant_model_configuration",c.id(),
            "assistant.model_configuration."+action,Map.of("purpose",ModelCredential.PURPOSE,"revision",Long.toString(c.revision()),"enabled",c.enabled())));
        return view(c);
    }
    private static ModelConfigurationView view(ModelCredential c) { return new ModelConfigurationView(c.ciphertext()!=null,c.enabled(),Long.toString(c.revision()),c.updatedAt()); }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    private static BusinessException conflict() { return new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT); }
}
