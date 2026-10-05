package com.things.link.device.api.controller;

import com.things.link.device.api.dto.response.DeviceAccessDiagnosticsResponse;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.application.DeviceAccessDiagnosticsPort;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 设备接入连接诊断（接入合同 §7.1／§7.2 的只读调试面）。
 *
 * <p>权限复用既有项目读权限，**不新增权限点**（§7.1）：控制台成员即可查看；跨租户协作者不获得本租户调试流——
 * 归属由项目成员关系与设备确权共同约束，租户从项目权威事实解析，绝不取请求自报值。</p>
 *
 * <p>本端点是只读的：不产生业务事实、不写影子、不触发规则、不真实下发（§7.2）；响应里没有任何凭据字段，
 * 归属实例只给脱敏短哈希。</p>
 */
@Tag(name = "接入诊断", description = "设备接入连接诊断（MQTT／HTTP／TCP／CoAP 统一口径）")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/access-diagnostics")
public class DeviceAccessDiagnosticsController {

    /** 接入连接诊断读取用例。 */
    private final DeviceAccessDiagnosticsPort diagnosticsPort;

    /** 第一层项目读权限守卫。 */
    private final DeviceApiAuthorization authorization;

    /** 项目应用服务：提供权威归属租户。 */
    private final ProjectService projectService;

    /**
     * @param diagnosticsPort 接入连接诊断读取用例
     * @param authorization 项目读权限守卫
     * @param projectService 项目应用服务
     */
    public DeviceAccessDiagnosticsController(DeviceAccessDiagnosticsPort diagnosticsPort,
                                             DeviceApiAuthorization authorization,
                                             ProjectService projectService) {
        this.diagnosticsPort = diagnosticsPort;
        this.authorization = authorization;
        this.projectService = projectService;
    }

    /**
     * 读取一台设备的接入连接诊断。
     *
     * @param projectId 项目 ID
     * @param deviceId 设备 ID
     * @return 诊断快照；设备不在该项目内或不属于当前租户时 404
     */
    @Operation(summary = "接入连接诊断",
            description = "协议、接入配置版本、会话代次、认证与活动时刻、在线状态与断开原因；归属实例为脱敏短哈希")
    @GetMapping
    public ResponseEntity<DeviceAccessDiagnosticsResponse> get(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                              @io.swagger.v3.oas.annotations.Parameter(description = "设备 ID") @PathVariable UUID deviceId) {
        authorization.requireRead(projectId);
        UUID tenantId = projectService.requireProjectTenant(projectId);
        return diagnosticsPort.connection(tenantId, projectId, deviceId)
                .map(DeviceAccessDiagnosticsResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "设备不存在"));
    }
}
