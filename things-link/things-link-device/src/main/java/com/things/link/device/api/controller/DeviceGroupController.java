package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.PutDeviceTagRequest;
import com.things.link.device.api.dto.request.ReplaceDeviceGroupMembersRequest;
import com.things.link.device.api.dto.request.SaveDeviceGroupRequest;
import com.things.link.device.api.dto.response.DeviceGroupResponse;
import com.things.link.device.api.dto.response.DeviceResponse;
import com.things.link.device.api.dto.response.DeviceTagResponse;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.application.DeviceGroupService;
import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceGroupRule;
import com.things.link.shared.error.BusinessException;
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

/** 项目设备组、静态成员与设备键值标签管理接口。 */
@Tag(name = "设备组与标签", description = "管理静态/动态设备组、静态成员和设备键值标签")
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class DeviceGroupController {
    /** 设备组应用服务。 */
    private final DeviceGroupService service;
    /** Controller 第一层项目授权。 */
    private final DeviceApiAuthorization authorization;

    /** @param service 应用服务 @param authorization HTTP 授权守卫 */
    public DeviceGroupController(DeviceGroupService service, DeviceApiAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    /** @return 项目有效设备组 */
    @GetMapping("/device-groups")
    @Operation(summary = "设备组列表", description = "读取项目内静态组和动态组；动态成员仅在成员查询时实时求值")
    public ResponseEntity<List<DeviceGroupResponse>> list(@PathVariable UUID projectId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.list(projectId).stream().map(DeviceGroupResponse::from).toList());
    }

    /** 创建一个静态或动态设备组。 */
    @PostMapping("/device-groups")
    @Operation(summary = "创建设备组")
    public ResponseEntity<DeviceGroupResponse> create(@PathVariable UUID projectId,
                                                       @Valid @RequestBody SaveDeviceGroupRequest request) {
        authorization.requireDeviceWrite(projectId);
        DeviceGroup group = service.create(projectId, request.name(), request.description(), request.type(),
                toRule(request.rule()));
        return ResponseEntity.status(HttpStatus.CREATED).body(DeviceGroupResponse.from(group));
    }

    /** 更新设备组；组类型创建后不可修改。 */
    @PutMapping("/device-groups/{groupId}")
    @Operation(summary = "修改设备组")
    public ResponseEntity<DeviceGroupResponse> update(@PathVariable UUID projectId,
                                                       @PathVariable UUID groupId,
                                                       @Valid @RequestBody SaveDeviceGroupRequest request) {
        authorization.requireDeviceWrite(projectId);
        DeviceGroup group = service.update(projectId, groupId, request.type(), request.name(), request.description(),
                toRule(request.rule()));
        return ResponseEntity.ok(DeviceGroupResponse.from(group));
    }

    /** 软删除设备组。 */
    @DeleteMapping("/device-groups/{groupId}")
    @Operation(summary = "删除设备组")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID groupId) {
        authorization.requireDeviceWrite(projectId);
        service.delete(projectId, groupId);
        return ResponseEntity.noContent().build();
    }

    /** 读取静态成员或动态规则的实时结果。 */
    @GetMapping("/device-groups/{groupId}/devices")
    @Operation(summary = "设备组成员（兼容）",
            description = "最多兼容返回 200 条；请使用 /devices/search?groupId=... 键集分页", deprecated = true)
    public ResponseEntity<List<DeviceResponse>> members(@PathVariable UUID projectId,
                                                         @PathVariable UUID groupId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.members(projectId, groupId).stream().map(DeviceResponse::from).toList());
    }

    /** 原子替换静态组全部成员。 */
    @PutMapping("/device-groups/{groupId}/devices")
    @Operation(summary = "替换静态组成员")
    public ResponseEntity<Void> replaceMembers(@PathVariable UUID projectId,
                                               @PathVariable UUID groupId,
                                               @Valid @RequestBody ReplaceDeviceGroupMembersRequest request) {
        authorization.requireDeviceWrite(projectId);
        service.replaceMembers(projectId, groupId, request.deviceIds());
        return ResponseEntity.noContent().build();
    }

    /** 读取单台设备的全部键值标签。 */
    @GetMapping("/devices/{deviceId}/tags")
    @Operation(summary = "设备标签列表")
    public ResponseEntity<List<DeviceTagResponse>> tags(@PathVariable UUID projectId,
                                                         @PathVariable UUID deviceId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.tags(projectId, deviceId).stream().map(DeviceTagResponse::from).toList());
    }

    /** 新增或覆盖一个设备标签。 */
    @PutMapping("/devices/{deviceId}/tags")
    @Operation(summary = "设置设备标签")
    public ResponseEntity<Void> putTag(@PathVariable UUID projectId,
                                       @PathVariable UUID deviceId,
                                       @Valid @RequestBody PutDeviceTagRequest request) {
        authorization.requireDeviceWrite(projectId);
        service.putTag(projectId, deviceId, request.key(), request.value());
        return ResponseEntity.noContent().build();
    }

    /** 删除一个设备标签键。 */
    @DeleteMapping("/devices/{deviceId}/tags/{key}")
    @Operation(summary = "删除设备标签")
    public ResponseEntity<Void> deleteTag(@PathVariable UUID projectId,
                                          @PathVariable UUID deviceId,
                                          @PathVariable String key) {
        authorization.requireDeviceWrite(projectId);
        service.deleteTag(projectId, deviceId, key);
        return ResponseEntity.noContent().build();
    }

    /** 把跨字段规则错误稳定映射为已登记业务码，而不是泄露构造器异常。 */
    private static DeviceGroupRule toRule(SaveDeviceGroupRequest.Rule rule) {
        if (rule == null) {
            return null;
        }
        try {
            return rule.toDomain();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_RULE_INVALID);
        }
    }
}
