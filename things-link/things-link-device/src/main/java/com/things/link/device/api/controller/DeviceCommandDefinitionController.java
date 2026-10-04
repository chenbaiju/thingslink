package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.SaveDeviceCommandDefinitionRequest;
import com.things.link.device.api.dto.response.DeviceCommandDefinitionResponse;
import com.things.link.device.application.DeviceCommandDefinitionService;
import com.things.link.device.api.support.DeviceApiAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 设备类型命令定义接口，输入和输出 Schema 随命令主体整体保存。 */
@Tag(name = "命令定义", description = "云端向设备发起的 RPC 命令，含输入输出 JSON Schema 和超时")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/device-types/{deviceTypeId}/commands")
public class DeviceCommandDefinitionController {
    /** 命令定义服务。 */ private final DeviceCommandDefinitionService service;
    /** HTTP 授权守卫。 */ private final DeviceApiAuthorization authorization;
    /** @param service 命令定义服务 */
    public DeviceCommandDefinitionController(DeviceCommandDefinitionService service, DeviceApiAuthorization authorization) { this.service = service; this.authorization = authorization; }

    @Operation(summary = "命令列表", description = "获取设备类型下的全部命令定义")
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 命令列表 */
    @GetMapping
    public ResponseEntity<List<DeviceCommandDefinitionResponse>> list(@PathVariable UUID projectId,
                                                                       @PathVariable UUID deviceTypeId) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.list(projectId, deviceTypeId).stream()
                .map(DeviceCommandDefinitionResponse::from).toList());
    }

    @Operation(summary = "创建命令", description = "为设备类型添加命令定义，支持 JSON Schema")
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param request 创建请求 @return 新命令 */
    @PostMapping
    public ResponseEntity<DeviceCommandDefinitionResponse> create(@PathVariable UUID projectId,
                                                                   @PathVariable UUID deviceTypeId,
                                                                   @Valid @RequestBody SaveDeviceCommandDefinitionRequest request) {
        authorization.requireWrite(projectId); return ResponseEntity.status(HttpStatus.CREATED).body(DeviceCommandDefinitionResponse.from(service.create(
                projectId, deviceTypeId, request.commandKey(), request.name(), request.description(),
                request.inputSchema(), request.outputSchema(), request.timeoutSeconds(), request.sortOrder())));
    }

    @Operation(summary = "修改命令", description = "修改草稿设备类型的命令定义")
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 命令 ID @param request 请求 @return 命令 */
    @PutMapping("/{id}")
    public ResponseEntity<DeviceCommandDefinitionResponse> update(@PathVariable UUID projectId,
                                                                   @PathVariable UUID deviceTypeId,
                                                                   @PathVariable UUID id,
                                                                   @Valid @RequestBody SaveDeviceCommandDefinitionRequest request) {
        authorization.requireWrite(projectId); return ResponseEntity.ok(DeviceCommandDefinitionResponse.from(service.update(projectId, deviceTypeId, id,
                request.commandKey(), request.name(), request.description(),
                request.inputSchema(), request.outputSchema(), request.timeoutSeconds(), request.sortOrder())));
    }

    @Operation(summary = "删除命令", description = "软删除草稿设备类型的命令定义")
    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 命令 ID @return 空响应 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId,
                                       @PathVariable UUID deviceTypeId,
                                       @PathVariable UUID id) {
        authorization.requireWrite(projectId); service.delete(projectId, deviceTypeId, id);
        return ResponseEntity.noContent().build();
    }
}
