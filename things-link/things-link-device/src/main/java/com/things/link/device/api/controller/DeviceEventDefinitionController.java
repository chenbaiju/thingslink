package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.SaveDeviceEventDefinitionRequest;
import com.things.link.device.api.dto.response.DeviceEventDefinitionResponse;
import com.things.link.device.application.DeviceEventDefinitionService;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.domain.DeviceEventDefinition;
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

/** 设备类型事件定义接口，参数 Schema 随事件主体整体保存。 */
@Tag(name = "事件定义", description = "定义设备主动上报的事件类型及其结构化参数 Schema")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/device-types/{deviceTypeId}/events")
public class DeviceEventDefinitionController {
    /** 事件定义服务。 */ private final DeviceEventDefinitionService service;
    /** HTTP 授权守卫。 */ private final DeviceApiAuthorization authorization;
    /** @param service 事件定义服务 */
    public DeviceEventDefinitionController(DeviceEventDefinitionService service, DeviceApiAuthorization authorization) { this.service = service; this.authorization = authorization; }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 事件列表 */
    @GetMapping
    @Operation(summary = "事件列表", description = "获取设备类型下的全部事件定义及其参数 Schema")
    public ResponseEntity<List<DeviceEventDefinitionResponse>> list(@PathVariable UUID projectId,
                                                                     @PathVariable UUID deviceTypeId) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.list(projectId, deviceTypeId).stream()
                .map(DeviceEventDefinitionResponse::from).toList());
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param request 创建请求 @return 新事件 */
    @PostMapping
    @Operation(summary = "创建事件", description = "为设备类型添加新的事件定义，支持 INFO/WARNING/ERROR 三级和结构化参数")
    public ResponseEntity<DeviceEventDefinitionResponse> create(@PathVariable UUID projectId,
                                                                 @PathVariable UUID deviceTypeId,
                                                                 @Valid @RequestBody SaveDeviceEventDefinitionRequest request) {
        authorization.requireWrite(projectId); return ResponseEntity.status(HttpStatus.CREATED).body(DeviceEventDefinitionResponse.from(service.create(
                projectId, deviceTypeId, request.eventKey(), request.name(), request.level(), request.description(),
                request.sortOrder(), drafts(request))));
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 事件 ID @param request 请求 @return 事件 */
    @PutMapping("/{id}")
    @Operation(summary = "修改事件", description = "修改草稿设备类型的事件定义及参数 Schema。已发布类型不可修改")
    public ResponseEntity<DeviceEventDefinitionResponse> update(@PathVariable UUID projectId,
                                                                 @PathVariable UUID deviceTypeId,
                                                                 @PathVariable UUID id,
                                                                 @Valid @RequestBody SaveDeviceEventDefinitionRequest request) {
        authorization.requireWrite(projectId); return ResponseEntity.ok(DeviceEventDefinitionResponse.from(service.update(projectId, deviceTypeId, id,
                request.eventKey(), request.name(), request.level(), request.description(), request.sortOrder(),
                drafts(request))));
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @param id 事件 ID @return 空响应 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除事件", description = "软删除草稿设备类型的事件定义")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId,
                                       @PathVariable UUID deviceTypeId,
                                       @PathVariable UUID id) {
        authorization.requireWrite(projectId); service.delete(projectId, deviceTypeId, id);
        return ResponseEntity.noContent().build();
    }

    /** @param request HTTP 请求 @return 不依赖 API DTO 的应用输入 */
    private static List<DeviceEventDefinition.ParameterDraft> drafts(SaveDeviceEventDefinitionRequest request) {
        return request.parameters() == null ? List.of() : request.parameters().stream()
                .map(SaveDeviceEventDefinitionRequest.ParameterRequest::toDraft).toList();
    }
}
