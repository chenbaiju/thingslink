package com.things.link.telemetry.api.controller;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.page.CursorPage;
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

/** 项目级消息日志查询接口，允许跨设备排障并按设备继续收窄。 */
@Tag(name = "消息日志", description = "查询设备上下行消息记录，支持按方向、协议、时间范围筛选")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/messages")
public class ProjectMessageLogController {
    /** 消息日志应用服务。 */
    private final MessageLogService service;
    /** HTTP 入口授权守卫。 */
    private final MessageLogApiAuthorization authorization;

    /**
     * 创建项目级消息日志控制器。
     *
     * @param service 消息日志应用服务
     * @param authorization HTTP 入口授权守卫
     */
    public ProjectMessageLogController(MessageLogService service, MessageLogApiAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    /**
     * 查询项目内消息日志；只返回写入时已经受控截断的载荷摘要，不暴露原始报文存储接口。
     *
     * @param projectId 项目 ID
     * @param deviceId 可选设备 ID
     * @param direction 可选消息方向
     * @param protocol 可选传输协议
     * @param from 可选开始时刻，包含
     * @param to 可选结束时刻，不包含
     * @param traceId 可选精确追踪 ID
     * @param cursor 可选下一页游标
     * @param limit 单页数量
     * @return 消息日志键集分页结果
     */
    @Operation(summary = "项目消息日志查询",
            description = "按设备、方向、协议、时间和 traceId 组合筛选项目日志摘要，使用 (ts,id) 键集分页")
    @GetMapping
    public ResponseEntity<CursorPage<MessageLogResponse>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) DeviceMessageLog.Direction direction,
            @RequestParam(required = false) TransportProtocol protocol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @Size(max = 64) String traceId,
            @RequestParam(required = false) @Size(max = 32) String messageType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        if (deviceId == null) {
            authorization.requireProjectRead(projectId);
        } else {
            // 指定设备时，HTTP 层也必须校验资源归属；不能只依赖应用服务的第二道守卫。
            authorization.requireDeviceRead(projectId, deviceId);
        }
        MessageLogQuery query = new MessageLogQuery(projectId, deviceId, direction, protocol,
                from, to, traceId, cursor, limit, messageType);
        return ResponseEntity.ok(service.list(query).map(MessageLogResponse::from));
    }
}
