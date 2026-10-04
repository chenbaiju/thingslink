package com.things.link.device.application;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.support.outbox.TransactionalOutboxReader;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 配置交付短事务准入测试，固定原Outbox核验与项目锁顺序。 */
@ExtendWith(MockitoExtension.class)
class DeviceConfigDeliveryAdmissionServiceTests {

    /** 事务局部RLS集中入口。 */ @Mock private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 等价原Outbox查询。 */ @Mock private TransactionalOutboxReader outboxReader;
    /** 项目生命周期锁。 */ @Mock private ProjectLifecycleAccessService lifecycle;
    /** 被测服务。 */ private DeviceConfigDeliveryAdmissionService service;

    /** 每例创建无状态服务，避免mock交互跨例污染。 */
    @BeforeEach
    void setUp() {
        service = new DeviceConfigDeliveryAdmissionService(
                transactionLocalRlsScope, outboxReader, lifecycle, new ObjectMapper());
    }

    /** 等价历史信封通过后才申请项目许可，成功返回只代表短事务可提交。 */
    @Test
    void admitsEquivalentOutboxBeforeProjectLock() {
        DeviceConfigPush push = push();
        when(outboxReader.existsEquivalent(eq(push.tenantId()), eq(push.projectId()), eq("DEVICE_CONFIG"),
                eq(push.gatewayId()), eq(DeviceConfigPush.EVENT_TYPE), eq("tc.device.config"),
                eq(push.gatewayId().toString()), anyString())).thenReturn(true);
        when(lifecycle.lockActiveForWrite(push.tenantId(), push.projectId())).thenReturn(true);

        assertThat(service.admit(push)).isTrue();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(transactionLocalRlsScope, outboxReader, lifecycle);
        order.verify(transactionLocalRlsScope).establish(push.tenantId(), push.projectId());
        order.verify(outboxReader).existsEquivalent(eq(push.tenantId()), eq(push.projectId()),
                eq("DEVICE_CONFIG"), eq(push.gatewayId()), eq(DeviceConfigPush.EVENT_TYPE),
                eq("tc.device.config"), eq(push.gatewayId().toString()), payload.capture());
        order.verify(lifecycle).lockActiveForWrite(push.tenantId(), push.projectId());
        assertThat(new ObjectMapper().readValue(payload.getValue(), DeviceConfigPush.class)).isEqualTo(push);
    }

    /** 找不到完整等价原事件属于永久身份缺失，不能继续取得项目锁。 */
    @Test
    void rejectsMissingEquivalentOutboxBeforeProjectLock() {
        DeviceConfigPush push = push();
        when(outboxReader.existsEquivalent(eq(push.tenantId()), eq(push.projectId()), eq("DEVICE_CONFIG"),
                eq(push.gatewayId()), eq(DeviceConfigPush.EVENT_TYPE), eq("tc.device.config"),
                eq(push.gatewayId().toString()), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.admit(push))
                .isInstanceOf(InvalidDeviceConfigPushException.class)
                .hasMessageContaining("持久交付身份");
        verifyNoInteractions(lifecycle);
    }

    /** 等价信封在项目冻结后走独立错误类型，供Kafka保留PROJECT_FROZEN死信。 */
    @Test
    void rejectsFrozenProjectWithDedicatedFailure() {
        DeviceConfigPush push = push();
        when(outboxReader.existsEquivalent(eq(push.tenantId()), eq(push.projectId()), eq("DEVICE_CONFIG"),
                eq(push.gatewayId()), eq(DeviceConfigPush.EVENT_TYPE), eq("tc.device.config"),
                eq(push.gatewayId().toString()), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.admit(push))
                .isInstanceOf(ProjectFrozenConfigDeliveryException.class)
                .hasMessageContaining("PROJECT_FROZEN");
    }

    /** SQL错误保持原类型，Kafka才能沿基础设施恢复路径重试。 */
    @Test
    void propagatesDatabaseFailureWithoutPermanentClassification() {
        DeviceConfigPush push = push();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(outboxReader.existsEquivalent(eq(push.tenantId()), eq(push.projectId()), eq("DEVICE_CONFIG"),
                eq(push.gatewayId()), eq(DeviceConfigPush.EVENT_TYPE), eq("tc.device.config"),
                eq(push.gatewayId().toString()), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> service.admit(push)).isSameAs(failure);
        verify(lifecycle, never()).lockActiveForWrite(push.tenantId(), push.projectId());
    }

    /** 空信封必须在设置RLS和查询前确定拒绝，不能依赖数据库偶然失败。 */
    @Test
    void rejectsNullBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.admit(null)).isInstanceOf(InvalidDeviceConfigPushException.class);
        verifyNoInteractions(transactionLocalRlsScope, outboxReader, lifecycle);
    }

    /** 创建具有完整点位数组的冻结共享信封。 */
    private static DeviceConfigPush push() {
        return new DeviceConfigPush(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "project_1", "gateway_1", DeviceConfigPush.CONFIG_TYPE, 3,
                List.of(new DeviceConfigPush.Point("sub_1", "temperature", 1,
                        "READ_HOLDING_REGISTERS", 10, "INT16", "BIG_ENDIAN",
                        BigDecimal.ONE, BigDecimal.ZERO, 1000)));
    }
}
