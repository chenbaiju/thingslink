package com.things.link.device.api.controller;

import com.things.link.device.api.dto.response.DeviceConnectionResponse;
import com.things.link.device.application.DeviceConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 设备连接历史查询接口；写入只接受 Broker 事件，不向控制台开放。 */
@Tag(name = "连接记录", description = "设备 MQTT 连接与断开历史")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/connections")
public class DeviceConnectionController {
    /** 连接记录查询服务。 */ private final DeviceConnectionService service;
    /** @param service 连接记录查询服务 */
    public DeviceConnectionController(DeviceConnectionService service) { this.service = service; }

    /**
     * 查询当前项目设备的最近一百条连接记录。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 最近一百条连接记录
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "查询设备最近连接记录", description = "返回当前项目可见设备的最近一百条连接记录，不提供游标分页。")
    @GetMapping
    public ResponseEntity<List<DeviceConnectionResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                                @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId) {
        return ResponseEntity.ok(service.list(projectId, deviceId).stream()
                .map(DeviceConnectionResponse::from).toList());
    }
}
