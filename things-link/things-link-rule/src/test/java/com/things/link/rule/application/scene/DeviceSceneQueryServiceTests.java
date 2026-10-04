package com.things.link.rule.application.scene;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectService;
import com.things.link.rule.domain.*;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.*;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** 管理与读取资格独立，游标不授予权限或跨用途能力。 */
class DeviceSceneQueryServiceTests {
    final ProjectService projects=mock(ProjectService.class);
    final TransactionLocalRlsScope scope=mock(TransactionLocalRlsScope.class);
    final DeviceIngestionService devices=mock(DeviceIngestionService.class);
    final DeviceSceneRepository repository=mock(DeviceSceneRepository.class);
    final SignedQueryCursorCodec codec=new SignedQueryCursorCodec("device-scene-tests-secret-at-least-32");
    final DeviceSceneQueryService service=new DeviceSceneQueryService(projects,scope,devices,repository,codec);
    final UUID tenant=UUID.randomUUID(),project=UUID.randomUUID(),device=UUID.randomUUID(),actor=UUID.randomUUID();
    final Instant time=Instant.parse("2026-09-30T12:00:00Z");
    @BeforeEach void context(){TenantContext.set(new TenantScope(tenant,project,actor));when(projects.requireProjectTenant(project)).thenReturn(tenant);}
    @AfterEach void clear(){TenantContext.clear();}
    @Test void managersReadDefinitionsWhileAllMembersReadHistory(){
        when(repository.definitions(project,device,null,null,21)).thenReturn(List.of());
        when(repository.history(project,device,null,null,21)).thenReturn(List.of());
        for(ProjectRole role:ProjectRole.values()){
            when(projects.requireRoleInProject(project)).thenReturn(role);
            assertThat(service.history(project,device,null,20).items()).isEmpty();
            if(role==ProjectRole.OWNER||role==ProjectRole.ADMIN)assertThat(service.definitions(project,device,null,20).items()).isEmpty();
            else assertThatThrownBy(()->service.definitions(project,device,null,20)).isInstanceOf(BusinessException.class);
        }
        verify(repository,times(2)).definitions(any(),any(),any(),any(),anyInt());
    }
    @Test void signedDefinitionPositionCannotCrossActorDeviceLimitOrHistory(){
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.OWNER);
        var item=new DeviceSceneItem(UUID.randomUUID(),"a","PAUSED",UUID.randomUUID(),1,"PROJECT_CANDIDATE",false,true,time);
        when(repository.definitions(project,device,null,null,2)).thenReturn(List.of(item,item));
        String cursor=service.definitions(project,device,null,1).nextCursor();
        assertThat(codec.decode(cursor,"CONSOLE_DEVICE_SCENE_CANDIDATES",tenant+"|"+actor+"|"+project+"|"+device+"|1").orElseThrow().sortId()).isEqualTo(item.id());
        assertThatThrownBy(()->service.history(project,device,cursor,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.definitions(project,UUID.randomUUID(),cursor,1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.definitions(project,device,cursor,2)).isInstanceOf(BusinessException.class);
        TenantContext.set(new TenantScope(tenant,project,UUID.randomUUID()));
        assertThatThrownBy(()->service.definitions(project,device,cursor,1)).isInstanceOf(BusinessException.class);
    }
    @Test void rechecksSelectedProjectMembershipAndDeviceBeforeAnyRead(){
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.OWNER);
        assertThatThrownBy(()->service.history(UUID.randomUUID(),device,null,20)).isInstanceOf(BusinessException.class);
        for(int limit:List.of(0,51,-1))assertThatThrownBy(()->service.history(project,device,null,limit)).isInstanceOf(BusinessException.class);
        doThrow(new IllegalStateException("device deleted")).when(devices).requireDeviceOwner(project,device);
        assertThatThrownBy(()->service.history(project,device,null,20)).hasMessage("device deleted");
        verifyNoInteractions(repository);
    }
    @Test void usesOwnerRlsWithoutChangingCollaboratorIdentity(){
        UUID owner=UUID.randomUUID();when(projects.requireProjectTenant(project)).thenReturn(owner);
        when(projects.requireRoleInProject(project)).thenReturn(ProjectRole.VIEWER);
        when(repository.history(project,device,null,null,21)).thenReturn(List.of());
        service.history(project,device,null,20);verify(scope).establish(owner,project);
        assertThat(TenantContext.current().orElseThrow().tenantId()).isEqualTo(tenant);
    }
}
