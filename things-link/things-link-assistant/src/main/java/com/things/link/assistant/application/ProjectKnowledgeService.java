package com.things.link.assistant.application;

import com.things.link.assistant.domain.KnowledgeDocument;
import com.things.link.assistant.domain.KnowledgeDocumentRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** 受控项目知识由Java维护；普通只读事务取证后以新事务复核引用和当前成员。 */
@Service
public class ProjectKnowledgeService {
    private final ProjectService projects;
    private final ProjectManagementWriteGuard guard;
    private final TransactionLocalRlsScope rls;
    private final KnowledgeDocumentRepository documents;
    private final Clock clock;
    private final TransactionTemplate read, write;
    /** @param projects 当前成员及真实项目租户 @param guard 管理员及项目生命周期锁 @param rls 普通双轴范围 @param documents 自有不可变版本 @param clock 取证时钟 @param manager 短事务管理器 */
    public ProjectKnowledgeService(ProjectService projects, ProjectManagementWriteGuard guard, TransactionLocalRlsScope rls,
            KnowledgeDocumentRepository documents, Clock clock, PlatformTransactionManager manager) {
        this.projects=projects; this.guard=guard; this.rls=rls; this.documents=documents; this.clock=clock;
        read=transaction(manager,true); write=transaction(manager,false);
    }
    /** @param manager 平台事务管理器 @param readonly 是否只读 @return 不复用外层快照的普通短事务 */
    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readonly) {
        var tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setReadOnly(readonly); return tx;
    }
    /** @param project 当前项目 @param source 受控来源 @param expected 当前版本预期，首次为空 @param content 人工批准正文 @param approved 是否明确批准全部当前项目成员读取 @return 服务端新版本元数据 */
    public KnowledgeSourceView publish(UUID project, String source, UUID expected, String content, boolean approved) {
        source(source); precheck(project); if (!approved) throw invalid();
        String normalized=LocalKnowledgeRetriever.normalize(content);
        return write.execute(status -> {
            UUID tenant=authorize(project,true); var current=documents.latest(tenant,project,source);
            if (!Objects.equals(expected,current.map(KnowledgeDocument::id).orElse(null))) throw conflict();
            int version=current.map(d -> d.versionNumber()+1).orElse(1);
            if (version>20 || current.isEmpty() && documents.current(tenant,project,false).size()>=100)
                throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
            return KnowledgeSourceView.from(documents.insert(Uuid7.generate(),tenant,project,TenantContext.require().accountId(),
                    source,version,LocalKnowledgeRetriever.hash(normalized),normalized));
        });
    }
    /** @param project 当前项目 @return 最多100个当前批准来源，不含正文 */
    public List<KnowledgeSourceView> list(UUID project) {
        var result=reading(project,tenant -> documents.current(tenant,project,false));
        if (result.size()>100) throw new IllegalStateException("知识来源超过有界合同");
        revalidate(project,result); return result.stream().map(KnowledgeSourceView::from).toList();
    }
    /** @param project 当前项目 @param source 来源标识 @return 当前批准的规范正文，旧版本不提供 */
    public KnowledgeSourceView.Detail detail(UUID project, String source) {
        source(source);
        var result=reading(project,tenant -> documents.latest(tenant,project,source).orElseThrow(ProjectKnowledgeService::missing));
        String content=LocalKnowledgeRetriever.verify(result); revalidate(project,List.of(result));
        return new KnowledgeSourceView.Detail(KnowledgeSourceView.from(result),content);
    }
    /** @param project 当前项目 @param source 已批准来源 @param expected 删除时的精确当前版本 @return 无返回值，物理删除全部最多20个版本 */
    public void delete(UUID project, String source, UUID expected) {
        source(source); precheck(project); if (expected==null) throw invalid();
        write.executeWithoutResult(status -> {
            UUID tenant=authorize(project,true);
            var current=documents.latest(tenant,project,source).orElseThrow(ProjectKnowledgeService::missing);
            if (!expected.equals(current.id())) throw conflict();
            int count=documents.deleteSource(tenant,project,source);
            if (count<1 || count>20) throw new IllegalStateException("知识来源删除越过有界合同");
        });
    }
    /** @param project 当前项目 @param requested 1至5个不同字面关键词 @return 本地有界引用，不提供外部模型资格或诊断 */
    public LocalKnowledgeRetriever.Result search(UUID project, List<String> requested) {
        var keys=LocalKnowledgeRetriever.keywords(requested);
        var sources=reading(project,tenant -> documents.current(tenant,project,true));
        var hits=LocalKnowledgeRetriever.retrieve(sources,keys);
        var result=new LocalKnowledgeRetriever.Result(project,clock.instant(),"LOCAL_LITERAL",
                sources.isEmpty()?"NO_SOURCES":hits.isEmpty()?"NO_MATCH":"MATCHED",false,hits);
        revalidate(project,sources.stream().filter(source -> hits.stream().anyMatch(hit -> hit.source().id().equals(source.id()))).toList());
        return result;
    }
    /** @param project 当前项目 @param query 普通受权查询 @param <T> 结果类型 @return 新事务采集的结果 */
    private <T> T reading(UUID project, Function<UUID,T> query) {
        precheck(project); return read.execute(status -> query.apply(authorize(project,false)));
    }
    /** @param project 当前项目 @param expected 已采集当前版本 @return 无返回值，撤权或换版后拒绝返回正文 */
    private void revalidate(UUID project, List<KnowledgeDocument> expected) {
        reading(project,tenant -> {
            var current=documents.current(tenant,project,false);
            if (current.size()>100) throw new IllegalStateException("当前知识来源超过有界合同");
            for (var document:expected)
                if (current.stream().noneMatch(item -> item.sourceKey().equals(document.sourceKey()) && item.id().equals(document.id()))) throw conflict();
            return true;
        });
    }
    /** @param project 路径项目 @param writing 是否要求当前管理员写锁 @return 项目真实租户，不能取JWT租户代替 */
    private UUID authorize(UUID project, boolean writing) {
        precheck(project);
        if (writing) guard.requireMemberManager(project,TenantContext.require().accountId());
        else projects.requireRoleInProject(project);
        UUID tenant=projects.requireProjectTenant(project); rls.establish(tenant,project); return tenant;
    }
    /** @param project 路径项目 @return 无返回值，必须匹配当前可信身份的所选项目 */
    private static void precheck(UUID project) {
        if (project==null || !project.equals(TenantContext.require().projectId())) throw missing();
    }
    /** @param source 受控ASCII来源 @return 无返回值，不接受路径、URL或自由文本名称 */
    private static void source(String source) { if (source==null || !source.matches("[a-z][a-z0-9_-]{0,63}")) throw invalid(); }
    /** @return 不回显输入的固定参数错误 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** @return 版本已变化的固定错误，调用者需手动刷新 */
    private static BusinessException conflict() { return new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT); }
    /** @return 隐藏项目或来源存在性的错误 */
    private static BusinessException missing() { return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND); }
}
