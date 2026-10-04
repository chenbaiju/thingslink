package com.things.link.rule.application.automation;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.rule.domain.*;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

/** 设备定义沿管理权限，执行沿读取权限；每页重新确权，不扩大已选项目。 */
@Service
public class DeviceAutomationQueryService {
    private final ProjectService projects;
    private final TransactionLocalRlsScope scope;
    private final DeviceIngestionService devices;
    private final DeviceAutomationRepository repository;
    private final SignedQueryCursorCodec cursors;
    public DeviceAutomationQueryService(ProjectService projects,TransactionLocalRlsScope scope,DeviceIngestionService devices,
            DeviceAutomationRepository repository,SignedQueryCursorCodec cursors) {
        this.projects=projects;this.scope=scope;this.devices=devices;this.repository=repository;this.cursors=cursors;
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=3)
    public CursorPage<DeviceAutomationItem> definitions(UUID project,UUID device,String cursor,int limit) {
        String binding=authorize(project,device,limit,true);
        var anchor=cursors.decode(cursor,"CONSOLE_DEVICE_AUTOMATIONS",binding);
        var rows=repository.definitions(project,device,anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null),limit+1);
        if(rows.size()<=limit)return CursorPage.last(rows);
        var last=rows.get(limit-1);
        return CursorPage.of(rows.subList(0,limit),cursors.encode("CONSOLE_DEVICE_AUTOMATIONS",binding,last.createdAt(),last.id()));
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,timeout=3)
    public CursorPage<DeviceAutomationExecutionItem> history(UUID project,UUID device,String cursor,int limit) {
        String binding=authorize(project,device,limit,false);
        var anchor=cursors.decode(cursor,"CONSOLE_DEVICE_AUTOMATION_EXECUTIONS",binding);
        var rows=repository.history(project,device,anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null),limit+1);
        if(rows.size()<=limit)return CursorPage.last(rows);
        var last=rows.get(limit-1);
        return CursorPage.of(rows.subList(0,limit),cursors.encode("CONSOLE_DEVICE_AUTOMATION_EXECUTIONS",binding,last.createdAt(),last.id()));
    }
    private String authorize(UUID project,UUID device,int limit,boolean manage) {
        var identity=TenantContext.current().orElseThrow();
        if(!java.util.Objects.equals(project,identity.projectId()))throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        var role=projects.requireRoleInProject(project);
        if(manage&&role!=ProjectRole.OWNER&&role!=ProjectRole.ADMIN)throw new BusinessException(RuleErrorCode.AUTOMATION_MANAGE_FORBIDDEN);
        if(device==null||limit<1||limit>50)throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        scope.establish(projects.requireProjectTenant(project),project);
        devices.requireDeviceOwner(project,device);
        return identity.tenantId()+"|"+identity.accountId()+"|"+project+"|"+device+"|"+limit;
    }
}
