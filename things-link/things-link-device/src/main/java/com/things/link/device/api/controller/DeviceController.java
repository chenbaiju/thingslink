package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.CreateDeviceRequest;
import com.things.link.device.api.dto.request.UpdateDeviceRequest;
import com.things.link.device.api.dto.response.DeviceResponse;
import com.things.link.device.application.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 设备实例管理接口。 */
@Tag(name = "设备实例", description = "管理项目中的设备实体，包括创建、查询、修改和软删除")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices")
public class DeviceController {
    private final DeviceService service;
    public DeviceController(DeviceService service) { this.service = service; }

    /** @param projectId 项目 ID @return 设备列表 */
    @GetMapping
    @Operation(summary = "设备列表（兼容）", description = "最多兼容返回 200 条；大项目请使用 /devices/search 键集分页",
            deprecated = true)
    public ResponseEntity<List<DeviceResponse>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(service.list(projectId).stream().map(DeviceResponse::from).toList());
    }

    /** @param projectId 项目 ID @param id 设备 ID @return 设备详情 */
    @GetMapping("/{id}")
    @Operation(summary = "设备详情", description = "按设备 ID 查询单个设备的基础信息")
    public ResponseEntity<DeviceResponse> detail(@PathVariable UUID projectId,
                                                 @PathVariable UUID id) {
        return ResponseEntity.ok(DeviceResponse.from(service.detail(projectId, id)));
    }

    /** @param projectId 项目 ID @param request 创建请求 @return 新设备 */
    @PostMapping
    @Operation(summary = "创建设备", description = "在项目中创建一个新设备实例。deviceKey 是 MQTT Topic 中的设备标识段，创建后不可变更")
    public ResponseEntity<DeviceResponse> create(@PathVariable UUID projectId,
                                                  @Valid @RequestBody CreateDeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DeviceResponse.from(
                service.create(projectId, request.deviceTypeId(), request.deviceKey(),
                        request.name(), request.description(), request.location())));
    }

    /** @param projectId 项目 ID @param id 设备 ID @param request 修改请求 @return 更新后的设备 */
    @PutMapping("/{id}")
    @Operation(summary = "修改设备", description = "更新设备名称、位置、说明或绑定的设备类型。deviceKey 不可变更")
    public ResponseEntity<DeviceResponse> update(@PathVariable UUID projectId,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody UpdateDeviceRequest request) {
        return ResponseEntity.ok(DeviceResponse.from(
                service.update(projectId, id, request.deviceTypeId(), request.name(), request.description(), request.location())));
    }

    /** @param projectId 项目 ID @param id 设备 ID @return 空响应 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除设备", description = "软删除指定设备。已删除的设备在列表中不可见，但历史数据和审计日志保留")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId,
                                       @PathVariable UUID id) {
        service.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
