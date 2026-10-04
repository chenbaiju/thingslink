package com.things.link.task.application;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.DeviceSearchService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.task.domain.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 稀疏匹配必须继续扫描，游标不能越过最后返回项；超时/权限异常不能转为空页。 */
class DeviceTaskQueryServiceTests {
    final ProjectService projects=mock(ProjectService.class);
    final TransactionLocalRlsScope scope=mock(TransactionLocalRlsScope.class);
    final DeviceIngestionService devices=mock(DeviceIngestionService.class);
    final DeviceSearchService groups=mock(DeviceSearchService.class);
    final DeviceTaskRepository repository=mock(DeviceTaskRepository.class);
    final SignedQueryCursorCodec codec=new SignedQueryCursorCodec("device-task-query-test-secret-at-least32");
    final DeviceTaskQueryService service=new DeviceTaskQueryService(projects,scope,devices,groups,repository,codec);
    final UUID tenant=UUID.randomUUID(), project=UUID.randomUUID(), device=UUID.randomUUID(), actor=UUID.randomUUID(), group=UUID.randomUUID();
    final Instant time=Instant.parse("2026-09-30T12:00:00Z");
    @BeforeEach void context() {
        TenantContext.set(new TenantScope(tenant,project,actor));
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.VIEWER);
        when(projects.requireProjectTenant(project)).thenReturn(tenant);
    }
    @AfterEach void clear() { TenantContext.clear(); }
    DeviceTaskJobItem job(int id, boolean all) {
        return new DeviceTaskJobItem(new UUID(0,id),"任务"+id,"PAUSED",2,all?"ALL_DEVICES":"DEVICE_GROUP",all?null:group,"restart",time);
    }
    @Test void sparseCandidatesContinueBeyondOneHundredWithoutReturningUnrelatedRows() {
        var excluded=java.util.stream.IntStream.rangeClosed(101,200).mapToObj(i->job(301-i,false)).toList();
        when(repository.candidates(project,null,null,100)).thenReturn(excluded);
        var last=excluded.getLast();
        when(repository.candidates(project,time,last.id(),100)).thenReturn(List.of(job(4,true),job(3,true),job(2,true)));
        when(groups.matchingTaskTargetGroups(project,device,Set.of(group))).thenReturn(Set.of());
        var first=service.jobs(project,device,null,2);
        assertThat(first.items()).extracting(DeviceTaskJobItem::id).containsExactly(new UUID(0,4),new UUID(0,3));
        when(repository.candidates(project,time,new UUID(0,3),100)).thenReturn(List.of(job(2,true)));
        assertThat(service.jobs(project,device,first.nextCursor(),2).items()).extracting(DeviceTaskJobItem::id).containsExactly(new UUID(0,2));
        verify(groups,times(1)).matchingTaskTargetGroups(project,device,Set.of(group));
    }
    @Test void matchingGroupsAreDeterminedOnServerAndRecheckedEachPage() {
        when(repository.candidates(project,null,null,100)).thenReturn(List.of(job(3,false),job(2,false),job(1,false)));
        when(groups.matchingTaskTargetGroups(project,device,Set.of(group))).thenReturn(Set.of(group));
        var cursor=service.jobs(project,device,null,1).nextCursor();
        when(repository.candidates(project,time,new UUID(0,3),100)).thenReturn(List.of(job(2,false),job(1,false)));
        when(groups.matchingTaskTargetGroups(project,device,Set.of(group))).thenReturn(Set.of());
        assertThat(service.jobs(project,device,cursor,1).items()).isEmpty();
        verify(groups,times(2)).matchingTaskTargetGroups(project,device,Set.of(group));
    }
    @Test void historyNeverConsultsCurrentGroupAndCursorsCannotCrossPurposeOrIdentity() {
        var a=new DeviceTaskExecutionItem(new UUID(0,2),UUID.randomUUID(),"MANUAL","RUNNING","ACCEPTED","restart",UUID.randomUUID(),time,time,null);
        var b=new DeviceTaskExecutionItem(new UUID(0,1),a.jobId(),"MANUAL","FAILED","SKIPPED","restart",null,time,null,null);
        when(repository.history(project,device,null,null,2)).thenReturn(List.of(a,b));
        var cursor=service.history(project,device,null,1).nextCursor();
        assertThatThrownBy(()->service.jobs(project,device,cursor,1)).isInstanceOf(BusinessException.class);
        TenantContext.set(new TenantScope(tenant,project,UUID.randomUUID()));
        assertThatThrownBy(()->service.history(project,device,cursor,1)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(groups);
    }
    @Test void failuresNeverBecomeEmptyAssociationAndInvalidLimitsDoNotRead() {
        for (int limit:List.of(0,51,-1)) assertThatThrownBy(()->service.jobs(project,device,null,limit)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
        when(repository.candidates(project,null,null,100)).thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
        assertThatThrownBy(()->service.jobs(project,device,null,20)).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        doThrow(new IllegalStateException("device deleted")).when(devices).requireDeviceOwner(project,device);
        assertThatThrownBy(()->service.history(project,device,null,20)).hasMessage("device deleted");
    }
}
