package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ModbusPointMapping;
import com.things.link.device.domain.ModbusPoll;
import com.things.link.device.domain.ModbusPollRepository;
import com.things.link.device.infrastructure.metrics.ModbusPollMetrics;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Modbus 平台轮询引擎：响应关联、值解码、推进与超时释放。 */
@ExtendWith(MockitoExtension.class)
class ModbusPollServiceTests {

    @Mock private ModbusPollRepository repository;
    @Mock private TransactionalOutboxRepository outboxRepository;

    /** 到期、超时和通过D-121三轴核验的响应都用它建立可信二轴范围。 */
    @Mock private TransactionLocalRlsScope transactionLocalRlsScope;

    /** 驱动逐poll REQUIRES_NEW事务，不把单元测试伪装成真实数据库事务证据。 */
    @Mock private PlatformTransactionManager transactionManager;

    /** 响应和耗尽分支不新增请求，不依赖当前在线事实。 */
    @Mock private DeviceRepository deviceRepository;
    /** 保持与生产构造器一致的协议事实入口。 */
    @Mock private DeviceTypeRepository typeRepository;

    /** 仅声明低层分支所需许可；真实事务与SHARE排序由PG集成验证。 */
    @Mock private ProjectLifecycleAccessService projectLifecycle;

    private ModbusPollService service;

    /** 每例独立指标注册表，错误归属不能冒充轮询成功或设备读取失败。 */
    private SimpleMeterRegistry meterRegistry;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID gatewayId = UUID.randomUUID();
    private final UUID subId = UUID.randomUUID();
    private final UUID pollId = UUID.randomUUID();

    /** 保留真实指标对象，以便观测授权拒绝不产生成功或失败计数。 */
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new ModbusPollService(repository, outboxRepository, new ObjectMapper(),
                new ModbusPollMetrics(meterRegistry), transactionLocalRlsScope, transactionManager,
                deviceRepository, typeRepository, projectLifecycle);
    }

    /** INT16 仅在相同请求关联完成成功后返回解码值；D-120 禁止旧快照无条件推进。 */
    @Test
    void decodesInt16ResponseAndCompletesPoll() {
        UUID requestId = Uuid7.generate();
        when(repository.findByRequestId(requestId)).thenReturn(Optional.of(poll(ModbusPointMapping.DataType.INT16,
                ModbusPointMapping.ByteOrder.BIG_ENDIAN, new BigDecimal("0.1"), BigDecimal.ZERO, requestId)));
        when(projectLifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(true);
        when(repository.completeRequest(eq(pollId), eq(requestId), any())).thenReturn(true);

        Optional<ModbusPollService.ResolvedModbusValue> resolved = service.handleResponse(
                new ModbusResponse(requestId, tenantId, projectId, gatewayId, ModbusResponse.Status.SUCCESS,
                        List.of(265), null, Instant.now(), "trace"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().subDeviceId()).isEqualTo(subId);
        assertThat(resolved.get().propertyKey()).isEqualTo("temperature");
        assertThat(resolved.get().value()).isEqualTo(26.5);
        InOrder order = inOrder(repository, transactionLocalRlsScope, projectLifecycle);
        order.verify(repository).findByRequestId(requestId);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(projectLifecycle).lockActiveForWrite(tenantId, projectId);
        order.verify(repository).completeRequest(eq(pollId), eq(requestId), any());
    }

    /** 未知 requestId（重复或已完成的响应）幂等吸收。 */
    @Test
    void noOpForUnknownRequestId() {
        UUID requestId = Uuid7.generate();
        when(repository.findByRequestId(requestId)).thenReturn(Optional.empty());

        assertThat(service.handleResponse(new ModbusResponse(requestId, tenantId, projectId, gatewayId,
                ModbusResponse.Status.SUCCESS, List.of(1), null, Instant.now(), "trace"))).isEmpty();
        verifyNoInteractions(transactionLocalRlsScope);
        verifyNoInteractions(projectLifecycle);
        verify(repository, never()).completeRequest(any(), any(), any());
    }

    /** ERROR 也必须按响应 requestId 条件完成；不能释放已经换成另一个请求的轮询行。 */
    @Test
    void releasesOnErrorResponse() {
        UUID requestId = Uuid7.generate();
        when(repository.findByRequestId(requestId)).thenReturn(Optional.of(poll(ModbusPointMapping.DataType.INT16,
                ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, requestId)));
        when(projectLifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(true);
        when(repository.completeRequest(eq(pollId), eq(requestId), any())).thenReturn(true);

        assertThat(service.handleResponse(new ModbusResponse(requestId, tenantId, projectId, gatewayId,
                ModbusResponse.Status.ERROR, List.of(), "slave_timeout", Instant.now(), "trace"))).isEmpty();
        InOrder order = inOrder(repository, transactionLocalRlsScope, projectLifecycle);
        order.verify(repository).findByRequestId(requestId);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(projectLifecycle).lockActiveForWrite(tenantId, projectId);
        order.verify(repository).completeRequest(eq(pollId), eq(requestId), any());
    }

    /**
     * D-121 三个归属字段各自必需；每例只改变一个已确权字段，避免其他字段同时不符掩盖漏检。
     * SUCCESS 故意没有寄存器值，错误归属必须在解码及状态推进前返回空。
     */
    @ParameterizedTest(name = "{0}：仅 {1} 不匹配即拒绝")
    @CsvSource({
            "SUCCESS, TENANT", "ERROR, TENANT",
            "SUCCESS, PROJECT", "ERROR, PROJECT",
            "SUCCESS, GATEWAY", "ERROR, GATEWAY"
    })
    void rejectsEachMismatchedOwnershipFieldBeforeCompletion(ModbusResponse.Status status, OwnershipField field) {
        UUID requestId = Uuid7.generate();
        UUID otherId = Uuid7.generate();
        when(repository.findByRequestId(requestId)).thenReturn(Optional.of(poll(ModbusPointMapping.DataType.INT16,
                ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, requestId)));
        double successBefore = meterRegistry.get(ModbusPollMetrics.SUCCESS).counter().count();
        double failedBefore = meterRegistry.get(ModbusPollMetrics.FAILED).counter().count();
        ModbusResponse response = new ModbusResponse(requestId,
                field == OwnershipField.TENANT ? otherId : tenantId,
                field == OwnershipField.PROJECT ? otherId : projectId,
                field == OwnershipField.GATEWAY ? otherId : gatewayId,
                status, List.of(), status == ModbusResponse.Status.ERROR ? "slave_timeout" : null, Instant.now(), "trace");

        assertThat(service.handleResponse(response)).isEmpty();

        verifyNoInteractions(transactionLocalRlsScope);
        verifyNoInteractions(projectLifecycle);
        verify(repository, never()).completeRequest(any(), any(), any());
        verify(repository, never()).complete(any(), any());
        verify(repository, never()).release(any(), any());
        assertThat(meterRegistry.get(ModbusPollMetrics.SUCCESS).counter().count()).isEqualTo(successBefore);
        assertThat(meterRegistry.get(ModbusPollMetrics.FAILED).counter().count()).isEqualTo(failedBefore);
    }

    /** 范围基础设施故障必须保留首因，并在项目许可与CAS之前终止整笔接纳事务。 */
    @Test
    void propagatesTrustedScopeFailureBeforeProjectPermit() {
        UUID requestId = Uuid7.generate();
        when(repository.findByRequestId(requestId)).thenReturn(Optional.of(poll(ModbusPointMapping.DataType.INT16,
                ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, requestId)));
        var failure = new DataAccessResourceFailureException("建立可信范围失败");
        doThrow(failure).when(transactionLocalRlsScope).establish(tenantId, projectId);

        assertThatThrownBy(() -> service.handleResponse(new ModbusResponse(requestId, tenantId, projectId, gatewayId,
                ModbusResponse.Status.SUCCESS, List.of(1), null, Instant.now(), "trace"))).isSameAs(failure);

        verifyNoInteractions(projectLifecycle);
        verify(repository, never()).completeRequest(any(), any(), any());
    }

    /** 无许可的SUCCESS/ERROR都在CAS与解码前拒绝，不能产生成功或设备失败指标。 */
    @ParameterizedTest
    @CsvSource({"SUCCESS", "ERROR"})
    void rejectsResponseWhenProjectWritePermitIsDenied(ModbusResponse.Status status) {
        UUID requestId = Uuid7.generate();
        when(repository.findByRequestId(requestId)).thenReturn(Optional.of(poll(ModbusPointMapping.DataType.INT16,
                ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, requestId)));
        when(projectLifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(false);
        assertThat(service.handleResponse(new ModbusResponse(requestId, tenantId, projectId, gatewayId,
                status, List.of(), status == ModbusResponse.Status.ERROR ? "slave_timeout" : null,
                Instant.now(), "trace"))).isEmpty();
        InOrder order = inOrder(repository, transactionLocalRlsScope, projectLifecycle);
        order.verify(repository).findByRequestId(requestId);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(projectLifecycle).lockActiveForWrite(tenantId, projectId);
        verify(repository, never()).completeRequest(any(), any(), any());
        verifyNoInteractions(outboxRepository);
        assertThat(meterRegistry.get(ModbusPollMetrics.SUCCESS).counter().count()).isZero();
        assertThat(meterRegistry.get(ModbusPollMetrics.FAILED).counter().count()).isZero();
    }

    /** 数据库许可故障必须保持首因，让真实接纳外层事务回滚，不能当作普通失效响应吸收。 */
    @Test
    void propagatesProjectPermitFailureBeforeResponseCompletion() {
        UUID requestId = Uuid7.generate();
        when(repository.findByRequestId(requestId)).thenReturn(Optional.of(poll(ModbusPointMapping.DataType.INT16,
                ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, requestId)));
        var failure = new DataAccessResourceFailureException("项目许可数据库失败");
        when(projectLifecycle.lockActiveForWrite(tenantId, projectId)).thenThrow(failure);
        assertThatThrownBy(() -> service.handleResponse(new ModbusResponse(requestId, tenantId, projectId, gatewayId,
                ModbusResponse.Status.SUCCESS, List.of(), null, Instant.now(), "trace"))).isSameAs(failure);
        InOrder order = inOrder(repository, transactionLocalRlsScope, projectLifecycle);
        order.verify(repository).findByRequestId(requestId);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(projectLifecycle).lockActiveForWrite(tenantId, projectId);
        verify(repository, never()).completeRequest(any(), any(), any());
        verifyNoInteractions(outboxRepository);
    }

    /** 每次只领取一个poll，先建立其可信范围，再读取资格并追加Outbox；空领取另开事务后停止。 */
    @Test
    void sendsDuePollWithinItsOwnTrustedScopeAndRequiresNewTransaction() {
        UUID typeId = Uuid7.generate();
        ModbusPoll poll = pollWithAttempt(0);
        when(repository.claimOneDue(10)).thenReturn(Optional.of(poll), Optional.empty());
        when(deviceRepository.findById(projectId, gatewayId)).thenReturn(Optional.of(new Device(
                gatewayId, tenantId, projectId, typeId, null, "gw_01", "网关", null,
                Device.Status.ONLINE, null, Instant.now(), Instant.now())));
        when(typeRepository.findById(projectId, typeId)).thenReturn(Optional.of(new DeviceType(
                typeId, tenantId, projectId, "modbus_gateway", "Modbus网关", DeviceType.DeviceKind.GATEWAY,
                DeviceType.PayloadProtocol.MODBUS_RTU_CLOUD_GATEWAY, DeviceType.NetworkType.RS485,
                1, DeviceType.Status.PUBLISHED, null, null, Instant.now())));
        when(projectLifecycle.lockActiveForWrite(tenantId, projectId)).thenReturn(true);

        assertThat(service.scanDue(10)).isEqualTo(1);

        InOrder order = inOrder(repository, transactionLocalRlsScope, deviceRepository, typeRepository,
                projectLifecycle, outboxRepository);
        order.verify(repository).claimOneDue(10);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(deviceRepository).findById(projectId, gatewayId);
        order.verify(typeRepository).findById(projectId, typeId);
        order.verify(projectLifecycle).lockActiveForWrite(tenantId, projectId);
        order.verify(repository).markRequest(eq(pollId), any(), eq(1));
        order.verify(outboxRepository).append(any());
        order.verify(repository).claimOneDue(10);

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(definition -> {
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        });
    }

    /** 重试耗尽后释放轮询行。 */
    @Test
    void releasesWhenAttemptsExhausted() {
        ModbusPoll poll = pollWithAttempt(3);
        when(repository.claimOneExpiredInFlight(10))
                .thenReturn(Optional.of(poll), Optional.empty());

        assertThat(service.scanTimeout(10)).isEqualTo(1);

        verify(transactionLocalRlsScope).establish(tenantId, projectId);
        verify(repository).release(any(), any());
    }

    /** 无可领取超时行时只提交空领取事务，不建立或猜测任何RLS范围。 */
    @Test
    void stopsTimeoutScanWithoutScopeWhenNoPollCanBeClaimed() {
        when(repository.claimOneExpiredInFlight(10)).thenReturn(Optional.empty());

        assertThat(service.scanTimeout(10)).isZero();

        verifyNoInteractions(transactionLocalRlsScope);
        verify(repository, never()).release(any(), any());
    }

    /** 授权三元组的独立边界；不把租户、项目与网关视为可以互相替代的字段。 */
    private enum OwnershipField {
        /** 租户隔离必须独立于项目及网关匹配成立。 */
        TENANT,
        /** 项目范围不能因同租户而省略。 */
        PROJECT,
        /** 同项目内另一个合法网关仍无权完成他人的请求。 */
        GATEWAY
    }

    /** 耗尽分支保留独立有效关联，不改变原有重试上限验收。 */
    private ModbusPoll pollWithAttempt(int attempt) {
        return poll(ModbusPointMapping.DataType.INT16, ModbusPointMapping.ByteOrder.BIG_ENDIAN, null, null, attempt, Uuid7.generate());
    }

    /** 响应夹具显式复用查询关联，避免随机 requestId 造成不可能的仓储返回。 */
    private ModbusPoll poll(ModbusPointMapping.DataType dataType, ModbusPointMapping.ByteOrder byteOrder,
                            BigDecimal scale, BigDecimal offset, UUID requestId) {
        return poll(dataType, byteOrder, scale, offset, 0, requestId);
    }

    /** 保留真实轮询归属及在途关联，测试只替换当前分支需要的点位和尝试次数。 */
    private ModbusPoll poll(ModbusPointMapping.DataType dataType, ModbusPointMapping.ByteOrder byteOrder,
                            BigDecimal scale, BigDecimal offset, int attempt, UUID requestId) {
        return new ModbusPoll(pollId, tenantId, projectId, gatewayId, subId, "project_1", "gw_01", "temperature",
                1, ModbusPointMapping.FunctionCode.READ_HOLDING_REGISTERS, 100, dataType, byteOrder, scale, offset,
                1000, Instant.now(), null, attempt, ModbusPoll.Status.IN_FLIGHT, requestId,
                Instant.now(), Instant.now());
    }
}
