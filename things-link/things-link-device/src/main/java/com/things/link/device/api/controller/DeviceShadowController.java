package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.UpdateDesiredRequest;
import com.things.link.device.api.dto.response.DeviceShadowResponse;
import com.things.link.device.application.DeviceShadowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 设备影子接口。 */
@Tag(name = "设备影子", description = "设备当前状态的云端镜像，desired 为期望状态，reported 为上报状态")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/shadow")
public class DeviceShadowController {
    /** 影子应用服务。 */ private final DeviceShadowService service;
    /** @param service 影子应用服务 */
    public DeviceShadowController(DeviceShadowService service) { this.service = service; }

    /**
     * 读取影子。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 当前影子
     */
    @GetMapping
    @Operation(summary = "读取影子",
            description = "获取设备当前影子状态；ACTIVE 项目首次访问创建空影子，归档项目缺失时返回不落库的只读空快照")
    public ResponseEntity<DeviceShadowResponse> get(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                     @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId) {
        return ResponseEntity.ok(DeviceShadowResponse.from(service.get(projectId, deviceId)));
    }

    /**
     * 更新期望状态。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param request 更新请求
     * @return 更新后的影子
     */
    @PutMapping("/desired")
    @Operation(summary = "更新期望状态", description = "更新设备 desired 期望属性值，使用乐观锁版本号防止并发覆盖")
    public ResponseEntity<DeviceShadowResponse> updateDesired(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                               @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId,
                                                               @Valid @RequestBody UpdateDesiredRequest request) {
        return ResponseEntity.ok(DeviceShadowResponse.from(
                service.updateDesired(projectId, deviceId, request.desired(), request.version())));
    }
}
