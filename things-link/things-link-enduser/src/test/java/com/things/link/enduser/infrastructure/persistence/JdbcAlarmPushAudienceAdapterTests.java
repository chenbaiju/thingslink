package com.things.link.enduser.infrastructure.persistence;

import com.things.link.alarm.application.AlarmPushAudiencePort.PushAudience;
import com.things.link.project.application.ProjectLifecycleAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR 0064 决策 4：受众适配器先取得原事务许可，确定拒绝为空且数据库故障不伪装为空。 */
class JdbcAlarmPushAudienceAdapterTests {

    /** App授权查询端口，用于证明冻结拒绝没有执行任何下游SQL。 */
    private JdbcTemplate jdbcTemplate;
    /** 事务许可端口，真实MANDATORY及锁行为由PG集成验收承担。 */
    private ProjectLifecycleAccessService lifecycleAccessService;
    /** 被测适配器不添加自己的事务边界。 */
    private JdbcAlarmPushAudienceAdapter adapter;
    /** 已确权告警事实携带的租户轴。 */
    private UUID tenantId;
    /** 已确权告警事实携带的项目轴。 */
    private UUID projectId;
    /** 原告警实例的设备轴，仍需原App关系查询核验。 */
    private UUID deviceId;
    /** 两个安装构成完整成功返回，防止许可接线误截断受众。 */
    private List<PushAudience> audiences;

    /** 默认支持原受众查询成功，最低层RED不能由无关JDBC桩缺失触发。 */
    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        adapter = new JdbcAlarmPushAudienceAdapter(jdbcTemplate, lifecycleAccessService);
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        audiences = List.of(new PushAudience(UUID.randomUUID(), UUID.randomUUID()),
                new PushAudience(UUID.randomUUID(), UUID.randomUUID()));
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PushAudience>>any(),
                org.mockito.ArgumentMatchers.any(String[].class), eq(tenantId), eq(projectId), eq(deviceId))).thenReturn(audiences);
    }

    /** 确定无写许可时返回空受众，不能先读取用户/关系/安装再丢弃结果。 */
    @Test
    void deniedProjectReturnsEmptyWithoutReadingAppFacts() {
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);

        assertThat(adapter.listActiveInstallations(tenantId, projectId, deviceId)).isEmpty();

        verifyNoInteractions(jdbcTemplate);
    }

    /** 获得许可后保留原三轴查询结果，且许可调用必须位于授权SQL之前。 */
    @Test
    void writableProjectAcquiresPermitBeforeOriginalAudienceQuery() {
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(true);

        assertThat(adapter.listActiveInstallations(tenantId, projectId, deviceId)).isSameAs(audiences);

        InOrder order = inOrder(lifecycleAccessService, jdbcTemplate);
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(jdbcTemplate).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PushAudience>>any(),
                org.mockito.ArgumentMatchers.any(String[].class), eq(tenantId), eq(projectId), eq(deviceId));
    }

    /** 项目SQL锁超时必须原样向调用事务传播，不得当作空受众或继续查询App事实。 */
    @Test
    void projectDatabaseFailurePropagatesWithoutReadingAppFacts() {
        QueryTimeoutException failure = new QueryTimeoutException("PUSH受众项目许可等待超时");
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.listActiveInstallations(tenantId, projectId, deviceId))
                .isSameAs(failure);

        verifyNoInteractions(jdbcTemplate);
    }
}
