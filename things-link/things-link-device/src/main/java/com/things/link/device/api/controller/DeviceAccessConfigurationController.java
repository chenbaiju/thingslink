package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.ChangeDeviceAccessConfigurationRequest;
import com.things.link.device.api.dto.response.DeviceAccessConfigurationResponse;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.application.DeviceAccessControlService;
import com.things.link.shared.message.TransportProtocol;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** Console接入配置管理；HTTP声明权限，应用事务仍在持锁后重复当前授权。 */
@Tag(name = "接入配置", description = "设备当前协议、开关与配置版本管理")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/access-config")
public class DeviceAccessConfigurationController {
    private final DeviceApiAuthorization authorization;
    private final DeviceAccessControlService control;

    /** @param authorization HTTP层项目授权 @param control 持锁配置控制服务 */
    public DeviceAccessConfigurationController(DeviceApiAuthorization authorization, DeviceAccessControlService control) {
        this.authorization = authorization;
        this.control = control;
    }

    /**
     * 读取设备接入配置。
     *
     * @param projectId 项目
     * @param deviceId 设备
     * @return 当前七字段视图，不返回任何秘密
     */
    @GetMapping
    @Operation(operationId = "readDeviceAccessConfiguration", summary = "读取设备接入配置", description = "项目成员可读；版本为十进制字符串，不可配置设备返回空协议列表")
    public ResponseEntity<DeviceAccessConfigurationResponse> get(@io.swagger.v3.oas.annotations.Parameter(description = "项目") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "设备") @PathVariable UUID deviceId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(DeviceAccessConfigurationResponse.from(control.view(projectId, deviceId)));
    }

    /**
     * 修改设备接入配置。
     *
     * @param projectId 项目
     * @param deviceId 设备
     * @param request 严格类型与CAS请求
     * @return 本次事务提交的配置视图
     */
    @PutMapping
    @Operation(operationId = "changeDeviceAccessConfiguration", summary = "修改设备接入配置", description = "仅OWNER/ADMIN；先校验期望版本，同值不推进，冲突读取最新视图后决定重试")
    public ResponseEntity<DeviceAccessConfigurationResponse> put(@io.swagger.v3.oas.annotations.Parameter(description = "项目") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "设备") @PathVariable UUID deviceId,
            @RequestBody ChangeDeviceAccessConfigurationRequest request) {
        authorization.requireDeviceWrite(projectId);
        return ResponseEntity.ok(DeviceAccessConfigurationResponse.from(control.changeView(projectId, deviceId,
                Long.parseLong(request.expectedConfigVersion()), TransportProtocol.valueOf(request.protocol()), request.enabled())));
    }
}
