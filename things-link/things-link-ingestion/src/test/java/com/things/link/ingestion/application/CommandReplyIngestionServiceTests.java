package com.things.link.ingestion.application;

import com.things.link.device.application.DeviceAccessScopeService;
import com.things.link.device.application.DeviceAccessScopeService.ResolvedDeviceAccessScope;
import com.things.link.ingestion.api.dto.request.EmqxMessagePublishedRequest;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.telemetry.application.DeviceCommandService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 命令回复接入测试，固定 Topic 关联、可信身份派生和重复回复吸收语义。 */
class CommandReplyIngestionServiceTests {

    /** 合法 SUCCESS 必须从 Topic 提取 commandId，并采用 device application 确权的归属 ID。 */
    @Test
    void appliesAuthenticatedSuccessReply() {
        DeviceAccessScopeService scopeService = mock(DeviceAccessScopeService.class);
        DeviceCommandService commandService = mock(DeviceCommandService.class);
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID connectionId = Uuid7.generate();
        when(scopeService.resolve("project_1", "device_1"))
                .thenReturn(Optional.of(new ResolvedDeviceAccessScope(tenantId, projectId, connectionId)));
        when(commandService.applyReply(any())).thenReturn(true);
        CommandReplyIngestionService service = new CommandReplyIngestionService(
                scopeService, commandService, new ObjectMapper());
        UUID commandId = Uuid7.generate();
        UUID messageId = Uuid7.generate();

        boolean accepted = service.ingest(request(commandId, messageId, "SUCCESS"));

        assertThat(accepted).isTrue();
        ArgumentCaptor<DeviceCommandReply> reply = ArgumentCaptor.forClass(DeviceCommandReply.class);
        verify(commandService).applyReply(reply.capture());
        assertThat(reply.getValue().commandId()).isEqualTo(commandId);
        assertThat(reply.getValue().messageId()).isEqualTo(messageId);
        assertThat(reply.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(reply.getValue().projectId()).isEqualTo(projectId);
        assertThat(reply.getValue().connectionDeviceId()).isEqualTo(connectionId);
        assertThat(reply.getValue().status()).isEqualTo(DeviceCommandReply.Status.SUCCESS);
        assertThat(reply.getValue().outputJson()).isEqualTo("{}");
    }

    /** telemetry 返回 false 表示重复或终态 no-op，协议仍已消费，不能让 EMQX 重试放大流量。 */
    @Test
    void acceptsDuplicateNoopReply() {
        DeviceAccessScopeService scopeService = mock(DeviceAccessScopeService.class);
        DeviceCommandService commandService = mock(DeviceCommandService.class);
        when(scopeService.resolve("project_1", "device_1")).thenReturn(Optional.of(
                new ResolvedDeviceAccessScope(Uuid7.generate(), Uuid7.generate(), Uuid7.generate())));
        when(commandService.applyReply(any())).thenReturn(false);
        CommandReplyIngestionService service = new CommandReplyIngestionService(
                scopeService, commandService, new ObjectMapper());

        assertThat(service.ingest(request(Uuid7.generate(), Uuid7.generate(), "ACK"))).isTrue();
    }

    /** 属性设置没有命令 ID Topic 段，必须从冻结的 requestId 关联同一个设备操作事实。 */
    @Test
    void appliesPropertySetReplyByRequestId() {
        DeviceAccessScopeService scopeService = mock(DeviceAccessScopeService.class);
        DeviceCommandService commandService = mock(DeviceCommandService.class);
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID connectionId = Uuid7.generate();
        UUID requestId = Uuid7.generate();
        when(scopeService.resolve("project_1", "device_1"))
                .thenReturn(Optional.of(new ResolvedDeviceAccessScope(tenantId, projectId, connectionId)));
        when(commandService.applyReply(any())).thenReturn(true);
        CommandReplyIngestionService service = new CommandReplyIngestionService(
                scopeService, commandService, new ObjectMapper());

        assertThat(service.ingest(propertySetRequest(requestId, Uuid7.generate()))).isTrue();

        ArgumentCaptor<DeviceCommandReply> reply = ArgumentCaptor.forClass(DeviceCommandReply.class);
        verify(commandService).applyReply(reply.capture());
        assertThat(reply.getValue().commandId()).isEqualTo(requestId);
        assertThat(reply.getValue().connectionDeviceId()).isEqualTo(connectionId);
    }

    /** commandId 不是 UUIDv7 时必须在查设备前拒绝，避免公开回调变成设备存在性枚举器。 */
    @Test
    void rejectsNonV7CommandIdBeforeIdentityLookup() {
        DeviceAccessScopeService scopeService = mock(DeviceAccessScopeService.class);
        DeviceCommandService commandService = mock(DeviceCommandService.class);
        CommandReplyIngestionService service = new CommandReplyIngestionService(
                scopeService, commandService, new ObjectMapper());

        assertThat(service.ingest(request(UUID.randomUUID(), Uuid7.generate(), "SUCCESS"))).isFalse();
        verifyNoInteractions(scopeService, commandService);
    }

    /** 创建冻结的 EMQX 回调外层与设备回复 JSON。 */
    private static EmqxMessagePublishedRequest request(UUID commandId, UUID messageId, String status) {
        String json = """
                {"messageId":"%s","occurredAt":"2026-08-08T03:00:00Z",\
                "status":"%s","output":{},"errorCode":null,"message":null}
                """.formatted(messageId, status);
        return new EmqxMessagePublishedRequest("project_1/device_1",
                "tc/v1/project_1/device_1/up/command/" + commandId + "/reply",
                Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)),
                1, false, "client-1", Instant.parse("2026-08-08T03:00:01Z").toEpochMilli());
    }

    /** 构造属性设置回复；requestId 是下行操作 ID，messageId 仍是设备回复幂等键。 */
    private static EmqxMessagePublishedRequest propertySetRequest(UUID requestId, UUID messageId) {
        String json = """
                {"messageId":"%s","requestId":"%s","occurredAt":"2026-08-08T03:00:00Z",\
                "status":"SUCCESS","output":{},"errorCode":null,"message":null}
                """.formatted(messageId, requestId);
        return new EmqxMessagePublishedRequest("project_1/device_1",
                "tc/v1/project_1/device_1/up/property/set/reply",
                Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)),
                1, false, "client-1", Instant.parse("2026-08-08T03:00:01Z").toEpochMilli());
    }
}
