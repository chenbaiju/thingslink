package com.things.link.enduser.infrastructure.persistence;

import com.things.link.alarm.application.AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget;
import com.things.link.enduser.application.PushTokenCipher;
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

/** ADR 0064 决策 4：发送前许可先于App查询与解密，确定拒绝为空而数据库故障保持异常。 */
class JdbcAlarmPushDeliveryAuthorizationAdapterTests {

    /** 原授权SQL端口，用于证明拒绝时没有读取安装及关系。 */
    private JdbcTemplate jdbcTemplate;
    /** 未获项目许可时不得触碰敏感token解密端口。 */
    private PushTokenCipher cipher;
    /** 原事务项目许可端口，MANDATORY和真实解密另由PG验收。 */
    private ProjectLifecycleAccessService lifecycleAccessService;
    /** 不创建自身事务的被测适配器。 */
    private JdbcAlarmPushDeliveryAuthorizationAdapter adapter;
    /** 已确权投递事实的租户。 */
    private UUID tenantId;
    /** 已确权投递事实的项目。 */
    private UUID projectId;
    /** 真实告警实例解析的设备身份。 */
    private UUID deviceId;
    /** 原投递目标用户。 */
    private UUID appUserId;
    /** 原投递目标安装事实ID。 */
    private UUID pushTokenId;
    /** 模拟原SQL完整成功结果，不以无桩查询返回空造成假绿色。 */
    private AuthorizedPushTarget target;

    /** 默认原授权SQL完整成功，测试只隔离新增项目许可编排。 */
    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        cipher = mock(PushTokenCipher.class);
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        adapter = new JdbcAlarmPushDeliveryAuthorizationAdapter(jdbcTemplate, cipher, lifecycleAccessService);
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        pushTokenId = UUID.randomUUID();
        target = new AuthorizedPushTarget("MOCK", "mock:success");
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<AuthorizedPushTarget>>any(),
                eq(projectId), eq(deviceId), eq(tenantId), eq(appUserId), eq(pushTokenId)))
                .thenReturn(List.of(target));
    }

    /** 生命周期拒绝返回空授权，不能先读App事实或解密再丢弃结果。 */
    @Test
    void deniedProjectReturnsEmptyWithoutReadingOrDecryptingAppFacts() {
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(false);

        assertThat(adapter.authorize(tenantId, projectId, deviceId, appUserId, pushTokenId)).isEmpty();

        verifyNoInteractions(jdbcTemplate, cipher);
    }

    /** 获得许可后沿原五轴查询返回同一目标，不能替换厂商或已解析的安装凭据。 */
    @Test
    void writableProjectAcquiresPermitBeforeOriginalAuthorizationQuery() {
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenReturn(true);

        assertThat(adapter.authorize(tenantId, projectId, deviceId, appUserId, pushTokenId).orElseThrow())
                .isSameAs(target);

        InOrder order = inOrder(lifecycleAccessService, jdbcTemplate);
        order.verify(lifecycleAccessService).lockActiveForWrite(tenantId, projectId);
        order.verify(jdbcTemplate).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<AuthorizedPushTarget>>any(),
                eq(projectId), eq(deviceId), eq(tenantId), eq(appUserId), eq(pushTokenId));
    }

    /** 项目锁SQL超时必须原样交给既有投递重试逻辑，不能伪装成空授权终态。 */
    @Test
    void projectDatabaseFailurePropagatesWithoutReadingOrDecryptingAppFacts() {
        QueryTimeoutException failure = new QueryTimeoutException("PUSH发送前项目许可等待超时");
        when(lifecycleAccessService.lockActiveForWrite(tenantId, projectId)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.authorize(tenantId, projectId, deviceId, appUserId, pushTokenId))
                .isSameAs(failure);

        verifyNoInteractions(jdbcTemplate, cipher);
    }
}
