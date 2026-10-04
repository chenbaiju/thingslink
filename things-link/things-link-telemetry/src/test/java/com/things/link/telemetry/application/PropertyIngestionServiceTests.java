package com.things.link.telemetry.application;

import com.things.link.alarm.application.AlarmEvaluationService;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.PropertyAggregateBackfillRepository;
import com.things.link.telemetry.domain.PropertyPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S12-2a1b 属性摄入事务局部 RLS 编排测试；真实连接边界由 support 集成测试证明。 */
class PropertyIngestionServiceTests {
    /** 摄入热路径业务 SQL 替身，用于证明范围建立前不会访问 inbox。 */
    private JdbcTemplate jdbc;
    /** 集中事务局部范围组件替身。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 设备领域校验端口替身。 */
    private DeviceIngestionService deviceIngestionService;
    /** 被测属性摄入服务。 */
    private PropertyIngestionService service;

    /** 装配最小编排替身，避免复制 support 组件已经覆盖的 JDBC 实现细节。 */
    @BeforeEach
    void prepare() {
        jdbc = mock(JdbcTemplate.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        deviceIngestionService = mock(DeviceIngestionService.class);
        service = new PropertyIngestionService(
                mock(PropertyPointRepository.class), mock(ProjectService.class), jdbc, transactionLocalRlsScope,
                mock(ObjectMapper.class), deviceIngestionService, mock(MessageLogService.class),
                mock(DataPlaneMetrics.class), mock(AlarmEvaluationService.class), mock(TimeSeriesWriteMetrics.class),
                mock(ProjectDailyQuotaDecisionService.class), mock(ProjectLifecycleAccessService.class),
                mock(PropertyAggregateBackfillRepository.class),
                mock(com.things.link.support.outbox.TransactionalOutboxRepository.class),
                mock(com.things.link.project.application.PlanCapacityService.class));
    }

    /** 完整可信二元组必须先交集中组件，再执行任何设备或摄入业务 SQL。 */
    @Test
    void establishesTrustedMessageScopeBeforeDeviceLookup() {
        StandardUplinkMessage message = message();
        IllegalStateException stopped = new IllegalStateException("停止于设备校验");
        when(deviceIngestionService.validateReportedProperties(
                message.tenantId(), message.projectId(), message.deviceId(), message.modelVersion(), message.receivedAt(),
                message.payload())).thenThrow(stopped);

        assertThatThrownBy(() -> service.ingest(message)).isSameAs(stopped);

        var order = inOrder(transactionLocalRlsScope, deviceIngestionService);
        order.verify(transactionLocalRlsScope).establish(message.tenantId(), message.projectId());
        order.verify(deviceIngestionService).validateReportedProperties(
                message.tenantId(), message.projectId(), message.deviceId(), message.modelVersion(), message.receivedAt(),
                message.payload());
        verifyNoInteractions(jdbc);
    }

    /** 构造满足封闭标准信封合同的最小属性消息，身份字段代表接入层已确权结果。 */
    private static StandardUplinkMessage message() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-09-06T12:00:00Z");
        return new StandardUplinkMessage(
                Uuid7.generate(), tenantId, projectId, UUID.randomUUID(), null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", receivedAt, receivedAt,
                "trace-s12-2a1b", 8, Map.of("temperature", 21.5));
    }
}
