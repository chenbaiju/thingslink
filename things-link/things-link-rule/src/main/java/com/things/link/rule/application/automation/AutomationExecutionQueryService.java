package com.things.link.rule.application.automation;
import com.things.link.project.application.ProjectService;
import com.things.link.rule.application.RuleManagementCursor;
import com.things.link.rule.application.RuleManagementPage;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
/** 当前项目成员可读运行事实；默认时间窗在第一页冻结并随游标携带。 */
@Service
public class AutomationExecutionQueryService {
    private final AutomationExecutionReadPort store;
    private final ProjectService projects;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public AutomationExecutionQueryService(AutomationExecutionReadPort store,ProjectService projects){this.store=store;this.projects=projects;}
    @Transactional(readOnly=true) public RuleManagementPage<AutomationExecutionView> list(UUID project,UUID automation,String status,Instant from,Instant to,String cursor,int limit){
        read(project);if(limit<1||limit>100||(status!=null&&!Set.of("QUEUED","RUNNING","RETRY_WAIT","DISPATCHED","SKIPPED","FAILED","REJECTED").contains(status)))throw invalid();
        String scope=JSON.writeValueAsString(Arrays.asList(project.toString(),automation,status,from==null?null:from.toString(),to==null?null:to.toString()));
        Instant end=to,start=from;String position=null;
        if(cursor!=null){try{
            if(cursor.length()>4096||cursor.isBlank())throw invalid();
            var node=JSON.readTree(Base64.getUrlDecoder().decode(cursor));
            if(!node.isObject()||node.size()!=4||!node.path("scope").isString()||!scope.equals(node.get("scope").asString())||!node.path("from").isString()||!node.path("to").isString()||!node.path("position").isString())throw invalid();
            start=Instant.parse(node.get("from").asString());end=Instant.parse(node.get("to").asString());position=node.get("position").asString();
            if((from!=null&&!from.equals(start))||(to!=null&&!to.equals(end)))throw invalid();
        }catch(RuntimeException malformed){throw invalid();}}
        if(end==null)end=store.now();if(start==null)start=end.minus(Duration.ofHours(24));
        if(!start.isBefore(end)||Duration.between(start,end).compareTo(Duration.ofDays(30))>0)throw invalid();
        RuleManagementCursor.Position key;
        try{key=RuleManagementCursor.decode(position,scope,false,false);}catch(RuntimeException malformed){throw invalid();}
        var rows=store.page(project,automation,status,start,end,key.time(),key.id(),limit+1);
        var items=rows.subList(0,Math.min(limit,rows.size()));String next=null;
        if(rows.size()>limit){var last=items.getLast();var node=JSON.createObjectNode().put("scope",scope).put("from",start.toString()).put("to",end.toString()).put("position",RuleManagementCursor.encode(scope,List.of(last.createdAt().toString(),last.id().toString())));
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        return new RuleManagementPage<>(items,next);
    }
    @Transactional(readOnly=true) public AutomationExecutionDetailView detail(UUID project,UUID id){read(project);return store.detail(project,id).orElseThrow(()->new BusinessException(RuleErrorCode.AUTOMATION_EXECUTION_NOT_FOUND));}
    private void read(UUID project){if(!Objects.equals(project,TenantContext.requireProjectId()))throw new BusinessException(RuleErrorCode.AUTOMATION_EXECUTION_NOT_FOUND);projects.requireRoleInProject(project);}
    private static BusinessException invalid(){return new BusinessException(RuleErrorCode.AUTOMATION_INVALID);}
}
