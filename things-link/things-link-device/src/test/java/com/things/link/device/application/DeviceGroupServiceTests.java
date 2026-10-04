package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceGroupRepository;
import com.things.link.device.domain.DeviceGroupRule;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 设备组服务须先授权、全量校验静态成员，再执行唯一一次替换仓储调用。 */
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class DeviceGroupServiceTests {
    /** 组持久化替身。 */ @Mock private DeviceGroupRepository groups; /** 设备确权替身。 */ @Mock private DeviceRepository devices; /** 项目授权替身。 */ @Mock private ProjectService projects;
    /** 被测服务。 */ private DeviceGroupService service; /** 测试项目。 */ private UUID projectId; /** 静态组。 */ private DeviceGroup group;
    /** 建立有租户范围的 OWNER。 */ @BeforeEach void setUp(){projectId=UUID.randomUUID();group=new DeviceGroup(UUID.randomUUID(),UUID.randomUUID(),projectId,"g",null,DeviceGroup.Type.STATIC,null,Instant.now());service=new DeviceGroupService(groups,projects,devices);TenantContext.set(new TenantScope(group.tenantId(),projectId,UUID.randomUUID()));lenient().when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);}
    /** 清除线程上下文。 */ @AfterEach void clear(){TenantContext.clear();}
    /** VIEWER 不得替换成员，也不能误触仓储。 */ @Test void viewerCannotReplaceMembers(){when(projects.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);assertThatThrownBy(()->service.replaceMembers(projectId,group.id(),List.of())).isInstanceOf(BusinessException.class);verifyNoInteractions(groups,devices);}
    /** 跨项目或不存在设备在删除旧成员之前失败，保证事务替换的原子前置条件。 */ @Test void invalidMemberDoesNotReplaceExistingMembers(){UUID foreign=UUID.randomUUID();when(groups.findGroup(projectId,group.id())).thenReturn(Optional.of(group));when(devices.findById(projectId,foreign)).thenReturn(Optional.empty());assertThatThrownBy(()->service.replaceMembers(projectId,group.id(),List.of(foreign))).isInstanceOf(BusinessException.class);verify(groups,never()).replaceMembers(any(),any(),any());}
    /** 重复成员先去重，仓储只得到完整校验后的替换集合。 */ @Test void replaceIsWholeAndDeduplicated(){UUID d=UUID.randomUUID();when(groups.findGroup(projectId,group.id())).thenReturn(Optional.of(group));when(devices.findById(projectId,d)).thenReturn(Optional.of(mock(Device.class)));service.replaceMembers(projectId,group.id(),List.of(d,d));verify(groups).replaceMembers(projectId,group.id(),List.of(d));}
    /** 动态组条件含多个维度时由领域白名单冻结为不可变集合和映射。 */ @Test void dynamicRuleFreezesWhitelist(){var rule=new DeviceGroupRule(Set.of(UUID.randomUUID()),Set.of(Device.Status.ONLINE),Map.of("site","sh"),DeviceGroupRule.TagMatch.ALL);assertThat(rule.tags()).containsEntry("site","sh");assertThat(rule.statuses()).containsExactly(Device.Status.ONLINE);}
}
