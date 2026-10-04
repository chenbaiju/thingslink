package com.things.link.device.application;

import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证接入会话的配置裁决、代次建立、归属刷新与关闭，不触碰未开通协议的既有会话。 */
class DeviceAccessSessionServiceTests {

    /** 认证租户。 */ private static final UUID TENANT = UUID.randomUUID();
    /** 认证项目。 */ private static final UUID PROJECT = UUID.randomUUID();
    /** 认证设备。 */ private static final UUID DEVICE = UUID.randomUUID();
    /** 会话行 ID。 */ private static final UUID ROW = UUID.randomUUID();

    /** 会话与配置仓储替身。 */
    private DeviceAccessSessionRepository repository;
    /** RLS 范围组件替身。 */
    private TransactionLocalRlsScope rlsScope;
    /** 被测用例。 */
    private DeviceAccessSessionService service;

    /** 每个用例重建替身与可控事务。 */
    @BeforeEach
    void setUp() {
        repository = mock(DeviceAccessSessionRepository.class);
        rlsScope = mock(TransactionLocalRlsScope.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any()))
                .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(UUID.class),any(),any(),any())).thenReturn(java.util.List.of(DEVICE));
        when(jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class)).thenReturn(java.sql.Timestamp.from(Instant.parse("2026-09-21T00:00:00Z")));
        var projects=mock(com.things.link.project.application.ProjectLifecycleAccessService.class);
        when(projects.lockActiveForWrite(any(),any())).thenReturn(true);
        when(projects.lockReadableGeneration(any(),any())).thenReturn(java.util.OptionalLong.of(0));
        when(projects.snapshot(any(),any())).thenReturn(new com.things.link.project.application.ProjectAccessPolicy(true,true,0));
        var source=mock(DevicePresenceWebhookSource.class);
        service = new DeviceAccessSessionService(repository, rlsScope, transactionTemplate, mock(DeviceAccessActivityService.class),
            new DeviceTcpSessionService(repository,jdbc,rlsScope,projects,source));
    }

    /** 没有配置行的存量设备默认是 MQTT：新协议连接必须被拒，且不得关闭其既有会话。 */
    @Test
    void legacyDeviceRejectsNewProtocolWithoutTouchingSessions() {
        when(repository.findBinding(PROJECT, DEVICE)).thenReturn(Optional.empty());

        assertThat(service.establish(request(TransportProtocol.TCP, "conn-1", "node-a")))
                .isEqualTo(new DeviceAccessSessionPort.Establishment.Rejected(
                        DeviceAccessSessionPort.Rejection.PROTOCOL_MISMATCH));

        verify(repository, org.mockito.Mockito.never()).establish(any(), any(), any(), any(), any(), any(), anyLong(),
                any(), any(), any());
    }

    /** MQTT 会话由 Broker 回调路径写入，接入面二次建立会形成两个会话写者。 */
    @Test
    void rejectsMqttBecauseBrokerPathOwnsThatSession() {
        assertThat(service.establish(request(TransportProtocol.MQTT, "conn-1", "node-a")))
                .isEqualTo(new DeviceAccessSessionPort.Establishment.Rejected(
                        DeviceAccessSessionPort.Rejection.MQTT_OWNED_BY_BROKER));

        verifyNoInteractions(repository);
    }

    /** 配置关闭后新会话一律拒绝，且不关闭既有会话。 */
    @Test
    void disabledBindingRejectsEstablishment() {
        when(repository.findBinding(PROJECT, DEVICE)).thenReturn(Optional.of(binding(TransportProtocol.TCP, 2, false)));

        assertThat(service.establish(request(TransportProtocol.TCP, "conn-1", "node-a")))
                .isEqualTo(new DeviceAccessSessionPort.Establishment.Rejected(
                        DeviceAccessSessionPort.Rejection.DISABLED));
    }

    /** 协议不一致时拒绝：一台设备同一时刻只有一份生效接入配置。 */
    @Test
    void protocolMismatchRejectsEstablishment() {
        when(repository.findBinding(PROJECT, DEVICE)).thenReturn(Optional.of(binding(TransportProtocol.HTTP, 1, true)));

        assertThat(service.establish(request(TransportProtocol.TCP, "conn-1", "node-a")))
                .isEqualTo(new DeviceAccessSessionPort.Establishment.Rejected(
                        DeviceAccessSessionPort.Rejection.PROTOCOL_MISMATCH));
    }

    /** 允许建立时带出配置代次与会话代次，接管标记原样透出。 */
    @Test
    void allowedEstablishmentExposesConfigAndSessionGeneration() {
        when(repository.findBinding(PROJECT, DEVICE)).thenReturn(Optional.of(binding(TransportProtocol.TCP, 3, true)));
        when(repository.establish(any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(new DeviceAccessSessionRepository.EstablishedSession(ROW, "conn-1", 7, 3, true));

        DeviceAccessSessionPort.Establishment result = service.establish(request(TransportProtocol.TCP, "conn-1", "node-a"));

        assertThat(result).isInstanceOf(DeviceAccessSessionPort.Establishment.Allowed.class);
        DeviceAccessSessionPort.Established established =
                ((DeviceAccessSessionPort.Establishment.Allowed) result).established();
        assertThat(established.rowId()).isEqualTo(ROW);
        assertThat(established.generation()).isEqualTo(7);
        assertThat(established.configVersion()).isEqualTo(3);
        assertThat(established.replacedPriorSession()).isTrue();
        verify(rlsScope).establish(TENANT, PROJECT);
    }

    /** 心跳与关闭都必须带归属实例：被接管的实例调用只能得到 false，不能改他人会话。 */
    @Test
    void touchAndCloseRequireCurrentOwner() {
        when(repository.touch(any(), any(), any(), any(), any())).thenReturn(false);
        when(repository.close(any(), any(), any(), any(), any(), any())).thenReturn(true);

        assertThat(service.touch(TENANT, PROJECT, DEVICE, ROW, "node-b")).isFalse();
        assertThat(service.close(TENANT, PROJECT, DEVICE, ROW, "node-b", "client_disconnect")).isTrue();
        verify(rlsScope, org.mockito.Mockito.times(2)).establish(TENANT, PROJECT);
    }

    /** 活跃会话视图映射为端口类型，供跨实例路由使用。 */
    @Test
    void mapsActiveSessionForRouting() {
        Instant connectedAt = Instant.parse("2026-09-18T08:00:00Z");
        Instant lastSeen = Instant.parse("2026-09-18T08:00:05Z");
        when(repository.findActive(PROJECT, DEVICE)).thenReturn(Optional.of(
                new DeviceAccessSessionRepository.ActiveSession(ROW, DEVICE, TransportProtocol.TCP, "conn-9",
                        "node-b", 4, 2, connectedAt, lastSeen)));

        Optional<DeviceAccessSessionPort.Active> active = service.active(TENANT, PROJECT, DEVICE);

        assertThat(active).isPresent();
        assertThat(active.orElseThrow().ownerInstance()).isEqualTo("node-b");
        assertThat(active.orElseThrow().generation()).isEqualTo(4);
        assertThat(active.orElseThrow().connectedAt()).isEqualTo(connectedAt);
        assertThat(active.orElseThrow().lastSeenAt()).isEqualTo(lastSeen);
    }

    /** 建立请求缺归属或会话标识时在构造点失败。 */
    @Test
    void rejectsIncompleteEstablishmentRequest() {
        assertThatThrownBy(() -> new DeviceAccessSessionPort.EstablishmentRequest(TENANT, PROJECT, DEVICE,
                TransportProtocol.TCP, "  ", "node-a", null, 30_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话标识与归属实例不能为空");
        assertThatThrownBy(() -> new DeviceAccessSessionPort.EstablishmentRequest(TENANT, PROJECT, null,
                TransportProtocol.TCP, "conn-1", "node-a", null, 30_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话建立归属与协议不能为空");
    }

    /**
     * 构造会话建立请求。
     *
     * @param protocol 传输协议
     * @param sessionId 会话标识
     * @param ownerInstance 归属实例
     * @return 会话建立请求
     */
    private static DeviceAccessSessionPort.EstablishmentRequest request(TransportProtocol protocol,
                                                                       String sessionId, String ownerInstance) {
        return new DeviceAccessSessionPort.EstablishmentRequest(TENANT, PROJECT, DEVICE, protocol, sessionId,
                ownerInstance, "203.0.113.7", protocol==TransportProtocol.TCP?30_000L:null);
    }

    /**
     * 构造接入配置。
     *
     * @param protocol 协议
     * @param configVersion 配置代次
     * @param enabled 是否启用
     * @return 接入配置
     */
    private static DeviceAccessBinding binding(TransportProtocol protocol, long configVersion, boolean enabled) {
        return new DeviceAccessBinding(DEVICE, TENANT, PROJECT, protocol, configVersion, 30, enabled, null,
                Instant.parse("2026-09-18T07:00:00Z"), Instant.parse("2026-09-18T07:00:00Z"));
    }
}
