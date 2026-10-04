package com.things.link.telemetry.application;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandClaimRepository;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证统一回复入口的四分类、状态机委托映射与越权不落事实。 */
class DeviceCommandAccessReplyServiceTests {

    /** 认证租户。 */ private static final UUID TENANT = UUID.randomUUID();
    /** 认证项目。 */ private static final UUID PROJECT = UUID.randomUUID();
    /** 认证设备。 */ private static final UUID DEVICE = UUID.randomUUID();
    /** 命令 ID，必须是 UUIDv7 形状。 */ private static final UUID COMMAND = Uuid7.generate();
    /** 回复消息 ID，必须是 UUIDv7 形状。 */ private static final UUID REPLY_MESSAGE = UUID.fromString(
            "01a0b4c2-33e0-7bb4-84f1-13811349ec3f");
    /** 设备回复时刻。 */ private static final Instant OCCURRED_AT = Instant.parse("2026-09-18T08:00:00Z");
    /** 平台接收时刻。 */ private static final Instant RECEIVED_AT = Instant.parse("2026-09-18T08:00:01Z");
    /** 链路标识。 */ private static final String TRACE = "0123456789abcdef0123456789abcdef";

    /** 命令事实仓储替身。 */
    private DeviceCommandRepository commandRepository;
    /** 投递事实仓储替身。 */
    private DeviceCommandClaimRepository claimRepository;
    /** 命令状态机替身。 */
    private DeviceCommandService commandService;
    /** RLS 范围组件替身。 */
    private TransactionLocalRlsScope rlsScope;
    /** 被测服务。 */
    private DeviceCommandAccessReplyService service;

    /** 每个用例重建替身与可控事务。 */
    @BeforeEach
    void setUp() {
        commandRepository = mock(DeviceCommandRepository.class);
        claimRepository = mock(DeviceCommandClaimRepository.class);
        commandService = mock(DeviceCommandService.class);
        rlsScope = mock(TransactionLocalRlsScope.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any()))
                .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        service = new DeviceCommandAccessReplyService(commandRepository, claimRepository, commandService, rlsScope,
                transactionTemplate, new ObjectMapper());
    }

    /** 命令不存在时按找不到处理，绝不写入任何事实。 */
    @Test
    void unknownCommandIsNotFoundWithoutTouchingStateMachine() {
        when(commandRepository.findById(PROJECT, DEVICE, COMMAND)).thenReturn(Optional.empty());

        assertThat(service.apply(reply(DeviceCommandAccessReplyPort.Status.SUCCESS, Map.of("ok", true), null)))
                .isEqualTo(DeviceCommandAccessReplyPort.Outcome.NOT_FOUND);

        verifyNoInteractions(commandService, claimRepository);
    }

    /** 命令属于其他连接设备时同样按找不到处理：网关代报路径不在首批范围。 */
    @Test
    void foreignConnectionDeviceIsNotFound() {
        when(commandRepository.findById(PROJECT, DEVICE, COMMAND))
                .thenReturn(Optional.of(command(UUID.randomUUID())));

        assertThat(service.apply(reply(DeviceCommandAccessReplyPort.Status.SUCCESS, Map.of(), null)))
                .isEqualTo(DeviceCommandAccessReplyPort.Outcome.NOT_FOUND);

        verifyNoInteractions(commandService, claimRepository);
    }

    /** 回复被状态机接受时返回已应用，并把身份、输出对象与链路原样交给状态机。 */
    @Test
    void appliedReplyDelegatesTrustedIdentityAndOutput() {
        when(commandRepository.findById(PROJECT, DEVICE, COMMAND)).thenReturn(Optional.of(command(DEVICE)));
        when(commandService.applyReply(any())).thenReturn(true);

        assertThat(service.apply(reply(DeviceCommandAccessReplyPort.Status.SUCCESS, Map.of("speed", 3), null)))
                .isEqualTo(DeviceCommandAccessReplyPort.Outcome.APPLIED);

        ArgumentCaptor<DeviceCommandReply> captor = ArgumentCaptor.forClass(DeviceCommandReply.class);
        verify(commandService).applyReply(captor.capture());
        DeviceCommandReply delegated = captor.getValue();
        assertThat(delegated.tenantId()).isEqualTo(TENANT);
        assertThat(delegated.projectId()).isEqualTo(PROJECT);
        assertThat(delegated.connectionDeviceId()).isEqualTo(DEVICE);
        assertThat(delegated.commandId()).isEqualTo(COMMAND);
        assertThat(delegated.messageId()).isEqualTo(REPLY_MESSAGE);
        assertThat(delegated.status()).isEqualTo(DeviceCommandReply.Status.SUCCESS);
        assertThat(delegated.outputJson()).isEqualTo("{\"speed\":3}");
        assertThat(delegated.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(delegated.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(delegated.traceId()).isEqualTo(TRACE);
    }

    /** 状态机拒绝但同键同结果：判为重放，首次结果不变。 */
    @Test
    void sameMessageIdWithSameResultIsDuplicate() {
        when(commandRepository.findById(PROJECT, DEVICE, COMMAND)).thenReturn(Optional.of(command(DEVICE)));
        when(commandService.applyReply(any())).thenReturn(false);
        when(claimRepository.findStoredReply(PROJECT, COMMAND, REPLY_MESSAGE)).thenReturn(Optional.of(
                new DeviceCommandClaimRepository.StoredReply("SUCCEEDED", "{\"speed\":3}", null)));

        assertThat(service.apply(reply(DeviceCommandAccessReplyPort.Status.SUCCESS, Map.of("speed", 3), null)))
                .isEqualTo(DeviceCommandAccessReplyPort.Outcome.DUPLICATE);
    }

    /** 同键换了结果：必须判冲突，不能把新结果写进事实。 */
    @Test
    void sameMessageIdWithDifferentResultIsConflict() {
        when(commandRepository.findById(PROJECT, DEVICE, COMMAND)).thenReturn(Optional.of(command(DEVICE)));
        when(commandService.applyReply(any())).thenReturn(false);
        when(claimRepository.findStoredReply(PROJECT, COMMAND, REPLY_MESSAGE)).thenReturn(Optional.of(
                new DeviceCommandClaimRepository.StoredReply("SUCCEEDED", "{\"speed\":3}", null)));

        assertThat(service.apply(reply(DeviceCommandAccessReplyPort.Status.FAILED, Map.of("speed", 4), "E1")))
                .isEqualTo(DeviceCommandAccessReplyPort.Outcome.CONFLICT);
    }

    /** 命令已终态而该 messageId 从未记录：按重放处理，不改变既有事实。 */
    @Test
    void unrecordedMessageAfterTerminalIsDuplicate() {
        when(commandRepository.findById(PROJECT, DEVICE, COMMAND)).thenReturn(Optional.of(command(DEVICE)));
        when(commandService.applyReply(any())).thenReturn(false);
        when(claimRepository.findStoredReply(PROJECT, COMMAND, REPLY_MESSAGE)).thenReturn(Optional.empty());

        assertThat(service.apply(reply(DeviceCommandAccessReplyPort.Status.ACK, Map.of(), null)))
                .isEqualTo(DeviceCommandAccessReplyPort.Outcome.DUPLICATE);
    }

    /** 标识与长度边界在构造点拒绝，避免非法回复进入数据库。 */
    @Test
    void rejectsMalformedReplyAtConstruction() {
        assertThatThrownBy(() -> new DeviceCommandAccessReplyPort.Reply(TENANT, PROJECT, DEVICE, COMMAND,
                UUID.randomUUID(), DeviceCommandAccessReplyPort.Status.ACK, Map.of(), null, null,
                OCCURRED_AT, RECEIVED_AT, TRACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7");
        assertThatThrownBy(() -> new DeviceCommandAccessReplyPort.Reply(TENANT, PROJECT, DEVICE, COMMAND,
                REPLY_MESSAGE, DeviceCommandAccessReplyPort.Status.FAILED, Map.of(), "x".repeat(65), null,
                OCCURRED_AT, RECEIVED_AT, TRACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("错误码长度");
        assertThatThrownBy(() -> new DeviceCommandAccessReplyPort.Reply(TENANT, PROJECT, DEVICE, COMMAND,
                REPLY_MESSAGE, DeviceCommandAccessReplyPort.Status.FAILED, Map.of(), null, "m".repeat(501),
                OCCURRED_AT, RECEIVED_AT, TRACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("错误说明长度");
    }

    /**
     * 构造命令事实替身。
     *
     * @param connectionDeviceId 命令的连接设备
     * @return 命令事实
     */
    private static DeviceCommand command(UUID connectionDeviceId) {
        return new DeviceCommand(COMMAND, TENANT, PROJECT, DEVICE, connectionDeviceId, UUID.randomUUID(),
                com.things.link.shared.message.DeviceCommandDispatch.OperationType.COMMAND, "reboot", "{}", "{}",
                "{}", null, DeviceCommand.Status.DISPATCHED, "idem", UUID.randomUUID(), null, 30, 1, 3, null,
                Instant.parse("2026-09-18T08:00:30Z"), null, null, TRACE, Instant.parse("2026-09-18T07:59:00Z"),
                null, null, null, Instant.parse("2026-09-18T07:59:00Z"));
    }

    /**
     * 构造设备回复。
     *
     * @param status 回复状态
     * @param output 输出对象
     * @param errorCode 失败码
     * @return 回复
     */
    private static DeviceCommandAccessReplyPort.Reply reply(DeviceCommandAccessReplyPort.Status status,
                                                            Map<String, Object> output, String errorCode) {
        return new DeviceCommandAccessReplyPort.Reply(TENANT, PROJECT, DEVICE, COMMAND, REPLY_MESSAGE, status,
                output, errorCode, null, OCCURRED_AT, RECEIVED_AT, TRACE);
    }
}
