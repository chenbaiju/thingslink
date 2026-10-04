package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.SaveDevicePropertyDefinitionRequest;
import com.things.link.device.api.dto.response.DevicePropertyDefinitionResponse;
import com.things.link.device.application.DevicePropertyDefinitionService;
import com.things.link.device.api.support.DeviceApiAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 设备类型属性定义接口；写操作仅允许项目 OWNER/ADMIN 修改草稿类型。 */
@Tag(name = "属性定义", description = "定义设备类型的属性字段，包括数据类型、量程、枚举值和读写方向")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/device-types/{deviceTypeId}/properties")
public class DevicePropertyDefinitionController {
    /** 属性定义服务。 */ private final DevicePropertyDefinitionService service;
    /** HTTP 授权守卫。 */ private final DeviceApiAuthorization authorization;
    /** @param service 属性定义服务 */
    public DevicePropertyDefinitionController(DevicePropertyDefinitionService service, DeviceApiAuthorization authorization) { this.service = service; this.authorization = authorization; }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 属性定义列表 */
    @GetMapping
    @Operation(summary = "属性列表", description = "获取设备类型下的全部属性定义")
    public ResponseEntity<List<DevicePropertyDefinitionResponse>> list(@PathVariable UUID projectId,
                                                                        @PathVariable UUID deviceTypeId) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.list(projectId, deviceTypeId).stream()
                .map(DevicePropertyDefinitionResponse::from).toList());
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param request 创建请求 @return 新属性 */
    @PostMapping
    @Operation(summary = "创建属性", description = "为设备类型添加新的属性定义。Number 可配量程和精度，Enum 可配枚举选项，Switch 可配开关文字，Object/List 必须提供受限 JSON Schema")
    public ResponseEntity<DevicePropertyDefinitionResponse> create(@PathVariable UUID projectId,
                                                                    @PathVariable UUID deviceTypeId,
                                                                    @Valid @RequestBody SaveDevicePropertyDefinitionRequest request) {
        authorization.requireWrite(projectId); return ResponseEntity.status(HttpStatus.CREATED).body(DevicePropertyDefinitionResponse.from(service.create(
                projectId, deviceTypeId, request.propertyKey(), request.name(), request.accessType(), request.dataType(),
                request.unit(), request.decimalPlaces(), request.minimumValue(), request.maximumValue(),
                request.enumOptions(), request.onLabel(), request.offLabel(), request.schema(), request.sortOrder())));
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 属性 ID @param request 修改请求 @return 属性 */
    @PutMapping("/{id}")
    @Operation(summary = "修改属性", description = "修改草稿设备类型的属性定义。已发布类型不可修改")
    public ResponseEntity<DevicePropertyDefinitionResponse> update(@PathVariable UUID projectId,
                                                                    @PathVariable UUID deviceTypeId,
                                                                    @PathVariable UUID id,
                                                                    @Valid @RequestBody SaveDevicePropertyDefinitionRequest request) {
        authorization.requireWrite(projectId); return ResponseEntity.ok(DevicePropertyDefinitionResponse.from(service.update(projectId, deviceTypeId, id,
                request.propertyKey(), request.name(), request.accessType(), request.dataType(), request.unit(),
                request.decimalPlaces(), request.minimumValue(), request.maximumValue(), request.enumOptions(),
                request.onLabel(), request.offLabel(), request.schema(), request.sortOrder())));
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 属性 ID @return 空响应 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除属性", description = "软删除草稿设备类型的属性定义")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId,
                                       @PathVariable UUID deviceTypeId,
                                       @PathVariable UUID id) {
        authorization.requireWrite(projectId); service.delete(projectId, deviceTypeId, id);
        return ResponseEntity.noContent().build();
    }
}
