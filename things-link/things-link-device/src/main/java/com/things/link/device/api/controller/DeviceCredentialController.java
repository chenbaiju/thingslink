package com.things.link.device.api.controller;

import com.things.link.device.api.dto.response.CredentialCreatedResponse;
import com.things.link.device.api.dto.response.DeviceCredentialResponse;
import com.things.link.device.application.DeviceCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 设备凭据管理接口。明文密钥仅在 POST 创建时一次性返回，列表与详情均不含明文。 */
@Tag(name = "设备凭据", description = "管理设备的 MQTT 连接认证凭据，密钥仅在创建时一次性明文返回")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/credentials")
public class DeviceCredentialController {
    /** @param service 凭据应用服务 */
    /** 凭据应用服务。 */ private final DeviceCredentialService service;
    public DeviceCredentialController(DeviceCredentialService service) { this.service = service; }

    /**
     * 凭据列表。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 凭据列表（不含明文密钥）
     */
    @GetMapping
    @Operation(summary = "凭据列表", description = "获取设备的有效凭据列表，不含明文密钥")
    public ResponseEntity<List<DeviceCredentialResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                                @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId) {
        return ResponseEntity.ok(service.list(projectId, deviceId).stream()
                .map(DeviceCredentialResponse::from).toList());
    }

    /**
     * 生成凭据。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 新凭据，这是唯一一次返回明文密钥
     */
    @PostMapping
    @Operation(summary = "生成凭据", description = "为设备生成新的一机一密凭据，明文密钥仅在本次响应中返回，关闭后不可再次查看")
    public ResponseEntity<CredentialCreatedResponse> generate(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                               @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CredentialCreatedResponse.from(service.generate(projectId, deviceId)));
    }

    /**
     * 作废凭据。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @param id 凭据 ID
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "作废凭据", description = "作废指定凭据，使用该密钥的设备将无法连接")
    public ResponseEntity<Void> revoke(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "凭据 ID") @PathVariable UUID id) {
        service.revoke(projectId, deviceId, id);
        return ResponseEntity.noContent().build();
    }
}
