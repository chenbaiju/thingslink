package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.UpdateDeviceLocationPointRequest;
import com.things.link.device.api.dto.response.DeviceLocationPointResponse;
import com.things.link.device.application.DeviceLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 基础坐标独立于旧设备PUT，旧客户端不会意外清空当前点。 */
@RestController
@RequestMapping(value="/api/v1/projects/{projectId}/devices/{id}/location-point", produces="application/json")
@Tag(name="设备实例")
public class DeviceLocationController {
    private final DeviceLocationService service;
    public DeviceLocationController(DeviceLocationService service) { this.service=service; }
    @GetMapping
    @Operation(operationId="getDeviceLocationPoint", summary="读取设备当前坐标", description="WGS84经纬度及独立版本；未设置返回null坐标对")
    public DeviceLocationPointResponse get(@PathVariable UUID projectId, @PathVariable UUID id) {
        return DeviceLocationPointResponse.from(service.get(projectId,id));
    }
    @PutMapping
    @Operation(operationId="updateDeviceLocationPoint", summary="更新设备当前坐标", description="OWNER/ADMIN更新或清除坐标；版本冲突409，旧文本位置不变")
    public DeviceLocationPointResponse put(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody UpdateDeviceLocationPointRequest request) {
        return DeviceLocationPointResponse.from(service.put(projectId,id,request.longitude(),request.latitude(),request.version()));
    }
}
