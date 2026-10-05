package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.CreateDeviceTypeRequest;
import com.things.link.device.api.dto.request.UpdateDeviceTypeRequest;
import com.things.link.device.api.dto.response.DeviceTypeResponse;
import com.things.link.device.api.dto.response.ProductCredentialCreatedResponse;
import com.things.link.device.api.dto.response.ThingModelVersionResponse;
import com.things.link.device.application.DeviceTypeService;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/** 设备类型管理接口；服务层会再次校验项目成员关系与角色，防止内部调用绕过授权。 */
@Tag(name = "设备类型", description = "定义设备物模型模板，包括分类、报文协议、属性/事件/命令定义和发布管理")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/device-types")
public class DeviceTypeController {
    private final DeviceTypeService service;
    private final DeviceApiAuthorization authorization;
    public DeviceTypeController(DeviceTypeService service, DeviceApiAuthorization authorization) { this.service = service; this.authorization = authorization; }

    /**
     * 设备类型列表（兼容）。
     *
     * @param projectId 项目 ID
     * @return 当前项目设备类型列表
     */
    @GetMapping
    @Operation(summary = "设备类型列表（兼容）", description = "最多兼容返回 200 条；大项目请使用 /device-types/search 键集分页",
            deprecated = true)
    public ResponseEntity<List<DeviceTypeResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.list(projectId).stream().map(DeviceTypeResponse::from).toList());
    }

    /**
     * 设备类型分页。
     *
     * @param projectId 项目 ID
     * @param cursor 上一页游标
     * @param limit 单页数量
     * @return 设备类型键集分页
     */
    @GetMapping("/search")
    @Operation(summary = "设备类型分页", description = "按创建时间与 UUID 倒序键集分页")
    public ResponseEntity<CursorPage<DeviceTypeResponse>> search(
            @io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "上一页游标") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "单页数量") @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.search(projectId, cursor, limit).map(DeviceTypeResponse::from));
    }

    /**
     * 创建设备类型。
     *
     * @param projectId 项目 ID
     * @param request 创建请求
     * @return 新建草稿
     */
    @PostMapping
    @Operation(summary = "创建设备类型", description = "创建一个新的草稿设备类型，默认版本 1。自定义数据流控制面已下线，不随类型创建默认数据流")
    public ResponseEntity<DeviceTypeResponse> create(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                      @Valid @RequestBody CreateDeviceTypeRequest request) {
        authorization.requireCreate(projectId); DeviceTypeResponse response = DeviceTypeResponse.from(service.create(projectId, request.typeKey(),
                request.name(), request.deviceKind(), request.payloadProtocol(), request.networkType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 修改设备类型。
     *
     * @param projectId 项目 ID
     * @param id 类型 ID
     * @param request 修改请求
     * @return 修改后的类型
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改设备类型", description = "修改草稿状态设备类型的基础信息。已发布类型不可原地修改，只能创建新版本演进")
    public ResponseEntity<DeviceTypeResponse> update(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                      @io.swagger.v3.oas.annotations.Parameter(description = "类型 ID") @PathVariable UUID id,
                                                      @Valid @RequestBody UpdateDeviceTypeRequest request) {
        authorization.requireWrite(projectId); return ResponseEntity.ok(DeviceTypeResponse.from(service.update(projectId, id, request.typeKey(),
                request.name(), request.deviceKind(), request.payloadProtocol(), request.networkType())));
    }

    /**
     * 删除设备类型。
     *
     * @param projectId 项目 ID
     * @param id 类型 ID
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除设备类型", description = "软删除草稿状态的设备类型。已发布类型不可删除")
    public ResponseEntity<Void> delete(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "类型 ID") @PathVariable UUID id) {
        authorization.requireWrite(projectId); service.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 发布设备类型。
     *
     * @param projectId 项目 ID
     * @param id 类型 ID
     * @return 发布后的类型
     */
    @PostMapping("/{id}/publish")
    @Operation(summary = "发布设备类型", description = "将草稿发布为正式版本。发布后全部物模型子资源冻结，不可新增、修改或删除")
    public ResponseEntity<DeviceTypeResponse> publish(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                      @io.swagger.v3.oas.annotations.Parameter(description = "类型 ID") @PathVariable UUID id) {
        authorization.requireWrite(projectId); return ResponseEntity.ok(DeviceTypeResponse.from(service.publish(projectId, id)));
    }

    /**
     * 生成产品凭据。
     *
     * @param projectId 项目 ID
     * @param id 类型 ID
     * @return 仅此一次携带产品密钥明文
     */
    @PostMapping("/{id}/product-credential")
    @Operation(summary = "生成产品凭据", description = "为已发布设备类型生成或轮换一型一密产品密钥，明文仅在本次响应中返回")
    public ResponseEntity<ProductCredentialCreatedResponse> generateProductCredential(
            @io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "类型 ID") @PathVariable UUID id) {
        authorization.requireWrite(projectId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProductCredentialCreatedResponse.from(service.generateProductCredential(projectId, id)));
    }

    /**
     * 读取该类型最新的已发布物模型版本。
     *
     * <p>OTA 固件草稿必须绑定一个物模型版本身份，而设备类型详情不含版本身份，控制台因此
     * 需要一个只读入口。本接口不创建版本、不改变类型状态：未发布时以 30052 报告不存在，
     * 由设备类型发布流程而不是本读取补上事实。
     *
     * @param projectId 项目 ID
     * @param id 类型 ID
     * @return 最新不可变版本
     */
    @GetMapping("/{id}/thing-model-versions/latest")
    @Operation(operationId = "getLatestThingModelVersion", summary = "读取设备类型最新物模型版本",
            description = "OTA固件草稿绑定用；未发布时返回30052，不由本接口创建版本")
    public ResponseEntity<ThingModelVersionResponse> latestThingModelVersion(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                                             @io.swagger.v3.oas.annotations.Parameter(description = "类型 ID") @PathVariable UUID id) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(ThingModelVersionResponse.from(service.latestThingModelVersion(projectId, id)));
    }
}
