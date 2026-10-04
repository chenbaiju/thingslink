package com.things.link.rule.application.automation;

import com.things.link.rule.domain.*;
import com.things.link.rule.application.*;
import com.things.link.project.application.*;
import com.things.link.device.application.DeviceAutomationAccess;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** 属性自动化定义与不可变版本；原项目事务串行管理/受理，HTTP只通过显式投影读取。 */
@Service
public class AutomationManagementService {
    private final AutomationRepository store;
    private final AutomationEventReceiptRepository journal;
    private final ProjectService projects;
    private final ProjectLifecycleAccessService lifecycle;
    private final BackgroundProjectActorAccess actors;
    private final DeviceAutomationAccess devices;
    private final AutomationQuotaService quota;
    private final RuleNodeCatalog nodes;
    private final AuditLogService audit;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${things-link.automation.time.enabled:false}")
    private boolean timeEnabled;
    private final AutomationTimePolicy timePolicy;
    private final AutomationScheduleRepository schedules;
    public AutomationManagementService(AutomationRepository store,AutomationEventReceiptRepository journal,ProjectService projects,
            ProjectLifecycleAccessService lifecycle,BackgroundProjectActorAccess actors,DeviceAutomationAccess devices,
            AutomationQuotaService quota,RuleNodeCatalog nodes,AuditLogService audit,ObjectMapper json,AutomationTimePolicy timePolicy,AutomationScheduleRepository schedules){
        this.store=store;this.journal=journal;this.projects=projects;this.lifecycle=lifecycle;this.actors=actors;
        this.devices=devices;this.quota=quota;this.nodes=nodes;this.audit=audit;this.json=json;this.timePolicy=timePolicy;this.schedules=schedules;
    }
    @Transactional public AutomationDefinition create(UUID project,Edit edit){
        Identity actor=writer(project);edit=normalized(project,edit);validate(edit);
        if(store.countDefinitions(project)>=100)throw error(RuleErrorCode.AUTOMATION_CAPACITY_EXCEEDED);
        validateDevice(actor.tenant(),project,device(edit.triggerConfig()));
        var now=journal.databaseNow();UUID id=Uuid7.generate();
        var definition=new AutomationDefinition(id,actor.tenant(),project,edit.name().strip(),description(edit.description()),
                AutomationDefinition.Status.DRAFT,null,1,actor.account(),now,now,null);
        var version=version(definition,actor,edit,1);
        try{store.create(definition,version);}catch(DuplicateKeyException failure){throw error(RuleErrorCode.AUTOMATION_NAME_CONFLICT);}
        audit(actor,project,id,"automation.created",Map.of("versionId",version.id()));return definition;
    }
    @Transactional public AutomationDefinition revise(UUID project,UUID id,long expected,Edit edit){
        Identity actor=writer(project);edit=normalized(project,edit);validate(edit);var current=locked(project,id,expected);
        validateDevice(actor.tenant(),project,device(edit.triggerConfig()));
        var replacement=new AutomationDefinition(id,actor.tenant(),project,edit.name().strip(),description(edit.description()),
                current.status(),current.activeVersionId(),current.version(),current.createdBy(),current.createdAt(),journal.databaseNow(),null);
        var version=version(current,actor,edit,store.nextVersion(project,id));
        try{if(!store.revise(replacement,expected,version))throw error(RuleErrorCode.AUTOMATION_STATE_CONFLICT);}
        catch(DuplicateKeyException failure){throw error(RuleErrorCode.AUTOMATION_NAME_CONFLICT);}
        audit(actor,project,id,"automation.version.created",Map.of("versionId",version.id()));return find(project,id);
    }
    @Transactional public AutomationDefinition activate(UUID project,UUID id,UUID versionId,long expected){
        Identity actor=writer(project);locked(project,id,expected);var version=store.version(project,id,versionId).orElseThrow(()->error(RuleErrorCode.AUTOMATION_NOT_FOUND));
        if(!actors.lockManager(project,version.createdBy()))throw error(RuleErrorCode.AUTOMATION_MANAGE_FORBIDDEN);
        if(!timeEnabled&&!"PROPERTY_REPORTED".equals(version.triggerType()))throw error(RuleErrorCode.AUTOMATION_NOT_ENABLED);
        timePolicy.normalize(version.triggerType(),version.triggerConfig(),projects.requireSchedulingContext(project).timezone());
        nodes.historical(version.conditions(),true);nodes.historical(version.actions(),false);
        UUID device=device(version.triggerConfig());validateDevice(actor.tenant(),project,device);
        if("PROPERTY_REPORTED".equals(version.triggerType())&&store.countActiveSubscriptions(project,device,id)>=10)throw error(RuleErrorCode.AUTOMATION_CAPACITY_EXCEEDED);
        if(!quota.enabled(actor.tenant(),project))throw error(RuleErrorCode.AUTOMATION_NOT_ENABLED);
        if(!store.activate(project,id,versionId,expected))throw error(RuleErrorCode.AUTOMATION_STATE_CONFLICT);
        if("PROPERTY_REPORTED".equals(version.triggerType()))schedules.clear(project,id);
        else {
            var state=schedules.state(project,versionId).orElse(null);
            var next=timePolicy.activate(version.triggerType(),version.triggerConfig(),journal.databaseNow(),state==null?null:state.floor(),state!=null&&state.consumed());
            schedules.activate(version,next);
        }
        audit(actor,project,id,"automation.version.activated",Map.of("versionId",versionId));return find(project,id);
    }
    @Transactional public AutomationDefinition pause(UUID project,UUID id,long expected){
        Identity actor=writer(project);locked(project,id,expected);
        if(!store.pause(project,id,expected))throw error(RuleErrorCode.AUTOMATION_STATE_CONFLICT);
        schedules.clear(project,id);
        audit(actor,project,id,"automation.paused",Map.of("version",expected));return find(project,id);
    }
    @Transactional public void delete(UUID project,UUID id,long expected){
        Identity actor=writer(project);locked(project,id,expected);
        if(!store.delete(project,id,expected))throw error(RuleErrorCode.AUTOMATION_STATE_CONFLICT);
        schedules.clear(project,id);
        audit(actor,project,id,"automation.deleted",Map.of("version",expected));
    }
    @Transactional(readOnly=true) public AutomationDefinition get(UUID project,UUID id){reader(project);return find(project,id);}
    @Transactional(readOnly=true) public AutomationVersion getVersion(UUID project,UUID id,UUID version){reader(project);find(project,id);return store.version(project,id,version).orElseThrow(()->error(RuleErrorCode.AUTOMATION_NOT_FOUND));}
    /** 有界目录采用创建时间/ID降序；scope不承担授权。 */
    @Transactional(readOnly=true) public RuleManagementPage<AutomationView> list(UUID project,String name,String status,String cursor,int limit){
        reader(project);String query=name==null?"":name.strip(),state=status==null?"":status;
        if(query.length()>128||!Set.of("","DRAFT","ACTIVE","PAUSED").contains(state)||limit<1||limit>100)throw error(RuleErrorCode.AUTOMATION_INVALID);
        String scope=RuleManagementCursor.scope(project,"automation",query,state);
        var position=position(cursor,scope,false);var rows=store.page(project,query,state,position.time(),position.id(),limit+1);
        var selected=rows.subList(0,Math.min(limit,rows.size()));String next=null;
        if(rows.size()>limit){var last=selected.getLast();next=RuleManagementCursor.encode(scope,List.of(last.createdAt().toString(),last.id().toString()));}
        return new RuleManagementPage<>(selected.stream().map(this::view).toList(),next);
    }
    /** 版本分页必须绑定实体，软删后管理入口不再暴露旧配置。 */
    @Transactional(readOnly=true) public RuleManagementPage<AutomationVersionView> history(UUID project,UUID id,String cursor,int limit){
        reader(project);find(project,id);if(limit<1||limit>100)throw error(RuleErrorCode.AUTOMATION_INVALID);
        String scope=RuleManagementCursor.scope(project,"automation-version:"+id,"","");
        var position=position(cursor,scope,true);var rows=store.history(project,id,position.version(),limit+1);
        var selected=rows.subList(0,Math.min(limit,rows.size()));
        return new RuleManagementPage<>(selected.stream().map(AutomationManagementService::view).toList(),
                rows.size()>limit?RuleManagementCursor.encode(scope,selected.getLast().versionNumber()):null);
    }
    /** 在受保护的管理事务中创建安全HTTP投影。 */
    @Transactional(readOnly=true) public AutomationView overview(UUID project,UUID id){reader(project);return view(find(project,id));}
    @Transactional(readOnly=true) public AutomationVersionView versionView(UUID project,UUID id,UUID version){return view(getVersion(project,id,version));}
    private AutomationView view(AutomationDefinition d){
        var active=d.activeVersionId()==null?null:store.version(d.projectId(),d.id(),d.activeVersionId()).orElseThrow();
        String trigger=active==null?null:active.triggerType();
        String zone=active!=null&&"CRON".equals(trigger)?active.triggerConfig().path("timezone").asString():"UTC";
        return new AutomationView(d.id(),d.name(),d.description(),d.status().name(),d.activeVersionId(),trigger,schedules.nextFire(d.projectId(),d.id()),zone,d.version(),d.createdAt(),d.updatedAt());
    }
    private static AutomationVersionView view(AutomationVersion v){return new AutomationVersionView(v.id(),v.versionNumber(),v.triggerType(),v.triggerConfig(),v.conditions(),v.actions(),v.createdBy(),v.createdAt());}
    private static RuleManagementCursor.Position position(String cursor,String scope,boolean history){
        try{return RuleManagementCursor.decode(cursor,scope,history,false);}
        catch(BusinessException invalid){throw error(RuleErrorCode.AUTOMATION_INVALID);}
    }
    private Identity reader(UUID project){
        var scope=TenantContext.current().orElseThrow(()->new IllegalStateException("没有账号上下文"));
        if(!Objects.equals(project,scope.projectId()))throw error(RuleErrorCode.AUTOMATION_NOT_FOUND);
        ProjectRole role=projects.requireRoleInProject(project);
        if(role!=ProjectRole.OWNER&&role!=ProjectRole.ADMIN)throw error(RuleErrorCode.AUTOMATION_MANAGE_FORBIDDEN);
        return new Identity(projects.requireProjectTenant(project),scope.accountId());
    }
    private Identity writer(UUID project){
        var identity=reader(project);lifecycle.requireActiveForWrite(identity.tenant(),project);journal.lockProject(project);
        if(!actors.lockManager(project,identity.account()))throw error(RuleErrorCode.AUTOMATION_MANAGE_FORBIDDEN);
        return identity;
    }
    private AutomationDefinition find(UUID project,UUID id){return store.find(project,id,false).orElseThrow(()->error(RuleErrorCode.AUTOMATION_NOT_FOUND));}
    private AutomationDefinition locked(UUID project,UUID id,long expected){
        var found=store.find(project,id,true).orElseThrow(()->error(RuleErrorCode.AUTOMATION_NOT_FOUND));
        if(expected<1||found.version()!=expected)throw error(RuleErrorCode.AUTOMATION_STATE_CONFLICT);return found;
    }
    private Edit normalized(UUID project,Edit edit){
        if(edit==null)throw error(RuleErrorCode.AUTOMATION_INVALID);
        if(!timeEnabled&&Set.of("ONE_SHOT","CRON").contains(edit.triggerType()==null?"":edit.triggerType()))throw error(RuleErrorCode.AUTOMATION_NOT_ENABLED);
        return new Edit(edit.name(),edit.description(),edit.triggerType(),timePolicy.normalize(edit.triggerType(),edit.triggerConfig(),projects.requireSchedulingContext(project).timezone()),edit.conditions(),edit.actions());
    }
    private void validate(Edit edit){
        if(edit==null||edit.name()==null||edit.name().isBlank()||edit.name().strip().length()>128
                ||(edit.description()!=null&&edit.description().strip().length()>512)||edit.conditions()==null
                ||edit.actions()==null||edit.actions().isEmpty())throw error(RuleErrorCode.AUTOMATION_INVALID);
        if(!Set.of("PROPERTY_REPORTED","ONE_SHOT","CRON").contains(edit.triggerType()))throw error(RuleErrorCode.AUTOMATION_TRIGGER_INVALID);
        device(edit.triggerConfig());
        if(edit.conditions().size()>32||edit.actions().size()>32||json.writeValueAsString(List.of(edit.conditions(),edit.actions())).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)
            throw error(RuleErrorCode.AUTOMATION_INVALID);
        for(var c:edit.conditions()){if(c==null)throw error(RuleErrorCode.AUTOMATION_INVALID);nodes.validate(c.nodeType(),c.config(),true);}
        for(var a:edit.actions()){if(a==null)throw error(RuleErrorCode.AUTOMATION_INVALID);nodes.validate(a.nodeType(),a.config(),false);}
    }
    private static UUID device(JsonNode config){
        try{if(config==null||!config.isObject()||!config.path("deviceId").isString())throw new IllegalArgumentException();
            return UUID.fromString(config.get("deviceId").asString());}
        catch(IllegalArgumentException failure){throw error(RuleErrorCode.AUTOMATION_TRIGGER_INVALID);}
    }
    private void validateDevice(UUID tenant,UUID project,UUID device){if(devices.lock(tenant,project,device)!=DeviceAutomationAccess.State.AVAILABLE)throw error(RuleErrorCode.AUTOMATION_TRIGGER_INVALID);}
    private AutomationVersion version(AutomationDefinition d,Identity actor,Edit edit,long number){return new AutomationVersion(Uuid7.generate(),d.tenantId(),d.projectId(),d.id(),number,edit.triggerType(),edit.triggerConfig(),json.valueToTree(edit.conditions()),json.valueToTree(edit.actions()),actor.account(),journal.databaseNow());}
    private void audit(Identity actor,UUID project,UUID id,String action,Map<String,?> detail){audit.record(new AuditLogEntry(actor.tenant(),project,actor.account(),"automation",id,action,detail));}
    private static BusinessException error(RuleErrorCode code){return new BusinessException(code);}
    private static String description(String value){return value==null||value.isBlank()?null:value.strip();}
    public record Edit(String name,String description,String triggerType,JsonNode triggerConfig,List<ConditionSpec> conditions,List<ActionSpec> actions){
        public Edit {
            triggerConfig=triggerConfig==null?null:triggerConfig.deepCopy();
            conditions=conditions==null?null:List.copyOf(conditions);
            actions=actions==null?null:List.copyOf(actions);
        }
        @Override public JsonNode triggerConfig(){return triggerConfig==null?null:triggerConfig.deepCopy();}
    }
    private record Identity(UUID tenant,UUID account){}
}
