package com.things.link.device.application;

import com.things.link.device.domain.DeviceAccessRequest;
import com.things.link.device.domain.DeviceAccessRequestRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证接入幂等受理的判定分类、首次写入与并发收敛，全部在项目 RLS 范围内完成。 */
class DeviceAccessAcceptanceServiceTests {

    /** 认证租户。 */ private static final UUID TENANT = UUID.randomUUID();
    /** 认证项目。 */ private static final UUID PROJECT = UUID.randomUUID();
    /** 认证设备。 */ private static final UUID DEVICE = UUID.randomUUID();
    /** 设备消息标识。 */ private static final UUID MESSAGE = UUID.randomUUID();
    /** 首次接收时刻。 */ private static final Instant RECEIVED_AT = Instant.parse("2026-09-18T08:00:01Z");
    /** 载荷摘要。 */ private static final String DIGEST = "a".repeat(64);
    /** 另一个载荷摘要。 */ private static final String OTHER_DIGEST = "b".repeat(64);

    /** 事实仓储替身。 */
    private DeviceAccessRequestRepository repository;
    /** RLS 范围组件替身。 */
    private TransactionLocalRlsScope rlsScope;
    /** 被测服务。 */
    private DeviceAccessAcceptanceService service;

    /** 每个用例重建替身与可控事务。 */
    @BeforeEach
    void setUp() {
        repository = mock(DeviceAccessRequestRepository.class);
        rlsScope = mock(TransactionLocalRlsScope.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any()))
                .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        service = new DeviceAccessAcceptanceService(repository, rlsScope, transactionTemplate);
    }

    /** 未受理过：判定为 Fresh，先建立认证项目范围再读事实。 */
    @Test
    void decidesFreshAndEstablishesProjectScope() {
        when(repository.find(PROJECT, DEVICE, MESSAGE)).thenReturn(Optional.empty());

        assertThat(service.decide(attempt())).isInstanceOf(DeviceAccessAcceptancePort.Decision.Fresh.class);
        verify(rlsScope).establish(TENANT, PROJECT);
        verify(repository).find(PROJECT, DEVICE, MESSAGE);
    }

    /** 同键同摘要：判定为重放并原样返回首次事实（含首次接收时刻）。 */
    @Test
    void decidesDuplicateForSameDigest() {
        when(repository.find(PROJECT, DEVICE, MESSAGE)).thenReturn(Optional.of(fact(DIGEST)));

        DeviceAccessAcceptancePort.Decision decision = service.decide(attempt());

        assertThat(decision).isInstanceOf(DeviceAccessAcceptancePort.Decision.Duplicate.class);
        DeviceAccessAcceptancePort.Acceptance acceptance =
                ((DeviceAccessAcceptancePort.Decision.Duplicate) decision).acceptance();
        assertThat(acceptance.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(acceptance.acceptedAt()).isEqualTo(Instant.parse("2026-09-18T08:00:02Z"));
    }

    /** 同键异摘要：判定为冲突，且不得改动任何事实。 */
    @Test
    void decidesConflictForDifferentDigest() {
        when(repository.find(PROJECT, DEVICE, MESSAGE)).thenReturn(Optional.of(fact(OTHER_DIGEST)));

        assertThat(service.decide(attempt())).isInstanceOf(DeviceAccessAcceptancePort.Decision.Conflict.class);
    }

    /** 首次写入成功：返回 firstRecording=true 与数据库写入时刻。 */
    @Test
    void recordsFirstFactWithDatabaseTimestamp() {
        when(repository.insertIfAbsent(any())).thenReturn(Optional.of(fact(DIGEST)));

        DeviceAccessAcceptancePort.Recording recording = service.record(attempt());

        assertThat(recording.firstRecording()).isTrue();
        assertThat(recording.acceptance().payloadDigest()).isEqualTo(DIGEST);
        assertThat(recording.acceptance().acceptedAt()).isEqualTo(Instant.parse("2026-09-18T08:00:02Z"));
    }

    /** 并发同键：后写入者不得覆盖首次事实，必须按既有事实收敛。 */
    @Test
    void convergesToExistingFactWhenConcurrentInsertWins() {
        when(repository.insertIfAbsent(any())).thenReturn(Optional.empty());
        when(repository.find(PROJECT, DEVICE, MESSAGE)).thenReturn(Optional.of(fact(OTHER_DIGEST)));

        DeviceAccessAcceptancePort.Recording recording = service.record(attempt());

        assertThat(recording.firstRecording()).isFalse();
        assertThat(recording.acceptance().payloadDigest()).isEqualTo(OTHER_DIGEST);
        verify(repository).find(eq(PROJECT), eq(DEVICE), eq(MESSAGE));
    }

    /** 写入与读取都不允许匿名的尝试：归属为空时在构造点失败。 */
    @Test
    void rejectsIncompleteAttempt() {
        assertThatThrownBy(() -> new DeviceAccessAcceptancePort.Attempt(TENANT, PROJECT, DEVICE, null,
                TransportProtocol.HTTP, DIGEST, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归属与消息标识不能为空");
        assertThatThrownBy(() -> new DeviceAccessAcceptancePort.Attempt(TENANT, PROJECT, DEVICE, MESSAGE,
                TransportProtocol.MQTT, DIGEST, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只面向");
    }

    /**
     * 构造本次尝试。
     *
     * @return 幂等尝试
     */
    private static DeviceAccessAcceptancePort.Attempt attempt() {
        return new DeviceAccessAcceptancePort.Attempt(TENANT, PROJECT, DEVICE, MESSAGE,
                TransportProtocol.HTTP, DIGEST, RECEIVED_AT);
    }

    /**
     * 构造既有受理事实。
     *
     * @param digest 载荷摘要
     * @return 受理事实
     */
    private static DeviceAccessRequest fact(String digest) {
        return new DeviceAccessRequest(DEVICE, MESSAGE, TENANT, PROJECT, TransportProtocol.HTTP, digest,
                RECEIVED_AT, Instant.parse("2026-09-18T08:00:02Z"));
    }
}
