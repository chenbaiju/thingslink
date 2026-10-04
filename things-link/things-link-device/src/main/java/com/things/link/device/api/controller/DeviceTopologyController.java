package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.BindTopologyRequest;
import com.things.link.device.api.dto.response.DeviceTopologyResponse;
import com.things.link.device.application.DeviceTopologyService;
import com.things.link.device.domain.DeviceTopology;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 网关-子设备拓扑绑定接口。 */
@Tag(name = "设备拓扑", description = "管理网关与子设备之间的绑定关系")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/device-topologies")
public class DeviceTopologyController {
    private final DeviceTopologyService service;
    public DeviceTopologyController(DeviceTopologyService service) { this.service = service; }

    /** @param projectId 项目 ID @param gatewayId 可选网关 ID，省略时返回项目内全部有效绑定 @return 有效子设备绑定 */
    @GetMapping
    @Operation(summary = "拓扑绑定列表", description = "查询项目内全部有效绑定；指定 gatewayId 时仅返回该网关挂载的子设备")
    public ResponseEntity<List<DeviceTopologyResponse>> list(@PathVariable UUID projectId,
                                                             @RequestParam(required = false) UUID gatewayId) {
        List<DeviceTopology> topologies = gatewayId == null
                ? service.listAll(projectId)
                : service.listByGateway(projectId, gatewayId);
        return ResponseEntity.ok(topologies.stream().map(DeviceTopologyResponse::from).toList());
    }

    /** @param projectId 项目 ID @param request 绑定请求 @return 有效绑定 */
    @PostMapping
    @Operation(summary = "绑定子设备", description = "把子设备绑定到网关，或把它从一个网关换绑到另一个网关")
    public ResponseEntity<DeviceTopologyResponse> bind(@PathVariable UUID projectId,
                                                       @Valid @RequestBody BindTopologyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DeviceTopologyResponse.from(
                service.bind(projectId, request.subDeviceId(), request.gatewayId())));
    }

    /** @param projectId 项目 ID @param subDeviceId 子设备 ID @return 空响应 */
    @DeleteMapping("/{subDeviceId}")
    @Operation(summary = "解绑子设备", description = "解除子设备与网关的绑定关系，幂等")
    public ResponseEntity<Void> unbind(@PathVariable UUID projectId,
                                       @PathVariable UUID subDeviceId) {
        service.unbind(projectId, subDeviceId);
        return ResponseEntity.noContent().build();
    }
}
