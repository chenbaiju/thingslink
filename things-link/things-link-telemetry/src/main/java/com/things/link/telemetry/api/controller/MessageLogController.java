package com.things.link.telemetry.api.controller;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.page.CursorPage;
import com.things.link.telemetry.api.dto.response.MessageLogDetailResponse;
import com.things.link.telemetry.api.dto.response.MessageLogResponse;
import com.things.link.telemetry.api.support.MessageLogApiAuthorization;
import com.things.link.telemetry.application.MessageLogService;
import com.things.link.telemetry.domain.DeviceMessageLog;
import com.things.link.telemetry.domain.MessageLogQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** 控制台设备消息日志只读查询接口。 */
@Tag(name = "消息日志", description = "查询设备上下行消息记录，支持按方向、协议、时间范围筛选")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/messages")
public class MessageLogController {
    /** 消息日志应用服务。 */
    private final MessageLogService service;
    /** HTTP 入口设备归属授权守卫。 */
    private final MessageLogApiAuthorization authorization;

    /**
     * 创建控制器。
     *
     * @param service 消息日志应用服务
     * @param authorization HTTP 入口设备归属授权守卫
     */
    public MessageLogController(MessageLogService service, MessageLogApiAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    /**
     * 读取一条消息详情。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param logId 日志 ID
     * @param format 摘要格式：{@code JSON}（默认）或 {@code HEX}
     * @return 已脱敏的消息详情
     */
    @Operation(summary = "消息详情", description = "已脱敏摘要（JSON 或 HEX）、消息类型与阶段时刻、错误码与截断／采样标记")
    @GetMapping("/{logId}")
    public ResponseEntity<MessageLogDetailResponse> detail(
            @PathVariable UUID projectId,
            @PathVariable UUID deviceId,
            @PathVariable UUID logId,
            @RequestParam(defaultValue = MessageLogDetailResponse.FORMAT_JSON) @Size(max = 8) String format) {
        authorization.requireDeviceRead(projectId, deviceId);
        return ResponseEntity.ok(MessageLogDetailResponse.from(
                service.detail(projectId, deviceId, logId), format));
    }

    /**
     * 按方向、协议、时间和 traceId 查询消息日志。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param direction 可选方向
     * @param protocol 可选协议
     * @param from 可选开始时刻，包含
     * @param to 可选结束时刻，不包含
     * @param traceId 可选精确 traceId
     * @param messageType 可选消息类型（调试时间线按类型筛选）
     * @param cursor 可选下一页游标
     * @param limit 每页数量
     * @return 消息日志分页
     */
    @Operation(summary = "消息日志查询", description = "查询设备消息日志，支持按方向、协议、时间范围和 traceId 筛选，游标分页")
    @GetMapping
    public ResponseEntity<CursorPage<MessageLogResponse>> list(
            @PathVariable UUID projectId,
            @PathVariable UUID deviceId,
            @RequestParam(required = false) DeviceMessageLog.Direction direction,
            @RequestParam(required = false) TransportProtocol protocol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @Size(max = 64) String traceId,
            @RequestParam(required = false) @Size(max = 32) String messageType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireDeviceRead(projectId, deviceId);
        MessageLogQuery query = new MessageLogQuery(projectId, deviceId, direction, protocol,
                from, to, traceId, cursor, limit, messageType);
        return ResponseEntity.ok(service.list(query).map(MessageLogResponse::from));
    }
}
