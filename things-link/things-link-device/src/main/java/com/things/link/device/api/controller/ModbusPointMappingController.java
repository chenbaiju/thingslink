package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.SaveModbusPointMappingRequest;
import com.things.link.device.api.dto.response.ModbusPointMappingResponse;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.application.ModbusPointMappingService;
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

/** 网关 Modbus 点位映射接口；所有写规则由服务层再次执行。 */
@Tag(name = "Modbus 点位映射", description = "把 Modbus 寄存器点位映射到子设备属性，发布后不可变")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}/modbus-points")
public class ModbusPointMappingController {
    /** 点位服务。 */ private final ModbusPointMappingService service;
    /** HTTP 授权守卫。 */ private final DeviceApiAuthorization authorization;

    /** @param service 点位服务 */
    public ModbusPointMappingController(ModbusPointMappingService service, DeviceApiAuthorization authorization) {
        this.service = service; this.authorization = authorization;
    }

    /**
     * 点位列表。
     * 获取网关下的全部 Modbus 点位（含草稿与已发布）
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @return 符合当前查询条件的结果列表
     */
    @Operation(summary = "点位列表", description = "获取网关下的全部 Modbus 点位（含草稿与已发布）")
    /** @param projectId 项目 ID @param deviceId 网关设备 ID @return 点位列表 */
    @GetMapping
    public ResponseEntity<List<ModbusPointMappingResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
                                                                 @io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.list(projectId, deviceId).stream()
                .map(ModbusPointMappingResponse::from).toList());
    }

    /**
     * 创建草稿点位。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param request 本次操作的请求数据，结构见 {@code SaveModbusPointMappingRequest}
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<ModbusPointMappingResponse>}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建草稿点位", description = "创建草稿点位。")
    @PostMapping
    public ResponseEntity<ModbusPointMappingResponse> create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
                                                             @io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId,
                                                             @Valid @RequestBody SaveModbusPointMappingRequest request) {
        authorization.requireWrite(projectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ModbusPointMappingResponse.from(service.create(
                projectId, deviceId, request.subDeviceId(), request.propertyKey(), request.slaveAddress(),
                request.functionCode(), request.registerAddress(), request.dataType(), request.byteOrder(),
                request.scale(), request.offset(), request.pollingIntervalMs())));
    }

    /**
     * 修改草稿点位。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param id Modbus 点位映射标识
     * @param request 本次操作的请求数据，结构见 {@code SaveModbusPointMappingRequest}
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<ModbusPointMappingResponse>}
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "修改草稿点位", description = "修改草稿点位。")
    @PutMapping("/{id}")
    public ResponseEntity<ModbusPointMappingResponse> update(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
                                                             @io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId,
                                                             @io.swagger.v3.oas.annotations.Parameter(description = "Modbus 点位映射标识") @PathVariable UUID id,
                                                             @Valid @RequestBody SaveModbusPointMappingRequest request) {
        authorization.requireWrite(projectId);
        return ResponseEntity.ok(ModbusPointMappingResponse.from(service.update(projectId, id,
                request.subDeviceId(), request.propertyKey(), request.slaveAddress(), request.functionCode(),
                request.registerAddress(), request.dataType(), request.byteOrder(), request.scale(),
                request.offset(), request.pollingIntervalMs())));
    }

    /**
     * 删除草稿点位。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param id Modbus 点位映射标识
     * @return 操作完成后的 HTTP 响应，正文为空
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除草稿点位", description = "删除草稿点位。")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "Modbus 点位映射标识") @PathVariable UUID id) {
        authorization.requireWrite(projectId);
        service.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 发布冻结网关全部草稿点位。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @return 操作完成后的 HTTP 响应，正文为空
     */
    @Operation(summary = "发布点位", description = "冻结网关全部草稿点位为已发布不可变版本")
    @PostMapping("/publish")
    public ResponseEntity<Void> publish(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId) {
        authorization.requireWrite(projectId);
        service.publish(projectId, deviceId);
        return ResponseEntity.noContent().build();
    }
}
