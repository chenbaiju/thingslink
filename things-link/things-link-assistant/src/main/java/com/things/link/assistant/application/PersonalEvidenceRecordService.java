package com.things.link.assistant.application;
import com.things.link.assistant.domain.PersonalEvidenceRecord;
import com.things.link.assistant.domain.PersonalEvidenceRecordRepository;
import com.things.link.project.application.ProjectManagementWriteGuard;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 平台持有个人历史事实；不依赖模型配置，不把历史记录作为再次出站许可。 */
@Service
public class PersonalEvidenceRecordService {
    private final DeviceEvidenceService evidence;
    private final ProjectService projects;
    private final ProjectManagementWriteGuard guard;
    private final TransactionLocalRlsScope rls;
    private final PersonalEvidenceRecordRepository records;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    public PersonalEvidenceRecordService(DeviceEvidenceService evidence, ProjectService projects, ProjectManagementWriteGuard guard,
            TransactionLocalRlsScope rls, PersonalEvidenceRecordRepository records, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.evidence=evidence;this.projects=projects;this.guard=guard;this.rls=rls;this.records=records;this.mapper=mapper;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /**
     * 手动重新取证并保存本人历史事实；写入时重新确权，采集时间区间不等于当前原子状态。
     * @param project 当前选定项目
     * @param device 项目内设备
     * @param model 期望精确模型版本
     * @param keys 一至十个不同顶层属性键
     * @return 服务端生成的历史记录元数据
     */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public PersonalEvidenceRecordView create(UUID project, UUID device, UUID model, List<String> keys) {
        precheck(project);
        var snapshot=PersonalEvidenceSnapshot.from(evidence.read(project,device,model,keys));
        String content=mapper.writeValueAsString(snapshot);
        if (content.getBytes(StandardCharsets.UTF_8).length>65536) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return transaction.execute(status -> {
            UUID tenant=authorize(project,true), creator=TenantContext.require().accountId();
            records.deleteExpired(tenant,project,creator);
            if (records.count(tenant,project,creator)>=100) throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
            return PersonalEvidenceRecordView.from(records.insert(Uuid7.generate(),tenant,project,creator,device,model,sha256(content),content));
        });
    }
    /** @param project 当前项目 @return 最多100条本人有效元数据，不包含历史事实正文 */
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public List<PersonalEvidenceRecordView> list(UUID project) {
        UUID tenant=authorize(project,false);
        var result=records.list(tenant,project,TenantContext.require().accountId()).stream().map(PersonalEvidenceRecordView::from).toList();
        projects.requireRoleInProject(project);
        return result;
    }
    /** @param project 当前项目 @param id 本人记录标识 @return 已校验完整性的历史事实，来源可能已换版或删除 */
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public PersonalEvidenceRecordView.Detail read(UUID project, UUID id) {
        UUID tenant=authorize(project,false);
        PersonalEvidenceRecord record=records.find(tenant,project,TenantContext.require().accountId(),id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!sha256(record.content()).equals(record.contentSha256())) throw new IllegalStateException("历史事实完整性校验失败");
        var snapshot=mapper.readValue(record.content(),PersonalEvidenceSnapshot.class);
        if (snapshot.schemaVersion()!=1 || !record.deviceId().equals(snapshot.deviceId()) || !record.modelVersionId().equals(snapshot.modelVersionId()))
            throw new IllegalStateException("历史事实身份校验失败");
        projects.requireRoleInProject(project);
        return new PersonalEvidenceRecordView.Detail(PersonalEvidenceRecordView.from(record),snapshot);
    }
    /**
     * 以一个当前有效元数据查询统一复核集合来源，避免早先逐条读取被当成最终可见性。
     * @param project 当前受权项目
     * @param expected 最多五条不同的本人不可变来源元数据
     * @throws BusinessException 任一来源删除、到期、不是本人或元数据不一致时整体拒绝
     */
    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public void verifyReportSources(UUID project,List<PersonalEvidenceRecordView> expected) {
        UUID tenant=authorize(project,false);
        if (expected==null || expected.isEmpty() || expected.size()>5 || expected.stream().anyMatch(Objects::isNull)
                || expected.stream().map(PersonalEvidenceRecordView::id).distinct().count()!=expected.size())
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        var visible=records.list(tenant,project,TenantContext.require().accountId()).stream().map(PersonalEvidenceRecordView::from).toList();
        if (!visible.containsAll(expected)) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        projects.requireRoleInProject(project);
    }
    /** @param project 当前可写项目 @param id 本人记录标识；不可见统一拒绝 */
    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void delete(UUID project, UUID id) {
        UUID tenant=authorize(project,true), creator=TenantContext.require().accountId();
        if (!records.delete(tenant,project,creator,id)) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        records.deleteExpired(tenant,project,creator);
    }
    private void precheck(UUID project) {
        if (project==null || !Objects.equals(project,TenantContext.require().projectId())) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
    private UUID authorize(UUID project, boolean write) {
        precheck(project);
        if (write) guard.requireMember(project,TenantContext.require().accountId()); else projects.requireRoleInProject(project);
        UUID tenant=projects.requireProjectTenant(project);rls.establish(tenant,project);return tenant;
    }
    /** @param content 原始不可变UTF-8正文 @return 完整正文摘要，不是模型Key或设备凭据摘要 */
    public static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ignored) { throw new IllegalStateException("摘要算法不可用"); }
    }
}
