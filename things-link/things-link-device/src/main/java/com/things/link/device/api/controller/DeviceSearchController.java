package com.things.link.device.api.controller;

import com.things.link.device.api.dto.response.DeviceResponse;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.application.DeviceSearchService;
import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceSearchQuery;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 控制台设备高级筛选接口；与旧的全量设备列表并存以保持兼容。 */
@Tag(name = "设备实例", description = "管理项目中的设备实体，包括创建、查询、修改和软删除")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/search")
public class DeviceSearchController {
    /** 设备组合筛选应用服务。 */
    private final DeviceSearchService service;
    /** HTTP 入口项目授权守卫。 */
    private final DeviceApiAuthorization authorization;

    /**
     * 创建设备高级筛选控制器。
     *
     * @param service 设备组合筛选应用服务
     * @param authorization HTTP 入口项目授权守卫
     */
    public DeviceSearchController(DeviceSearchService service, DeviceApiAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    /**
     * 使用固定白名单条件组合筛选设备。
     *
     * @param projectId 项目 ID
     * @param keyword 可选名称、设备标识或位置关键词
     * @param deviceTypeIds 可重复设备类型 ID，集合内部为 OR
     * @param statuses 可重复状态，集合内部为 OR
     * @param groupId 可选设备组 ID
     * @param tagKey 可选精确标签键，必须与标签值成对
     * @param tagValue 可选精确标签值，必须与标签键成对
     * @param cursor 可选下一页游标
     * @param limit 单页数量
     * @return 设备键集分页结果
     */
    @Operation(summary = "组合筛选设备",
            description = "关键词、设备类型、状态、设备组和标签维度间使用 AND；类型和状态集合内使用 OR，并按创建时间键集分页")
    @GetMapping
    public ResponseEntity<CursorPage<DeviceResponse>> search(
            @PathVariable UUID projectId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) List<UUID> deviceTypeIds,
            @RequestParam(required = false) List<Device.Status> statuses,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) @Size(max = 64) String tagKey,
            @RequestParam(required = false) @Size(max = 128) String tagValue,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireRead(projectId);
        DeviceSearchQuery query = new DeviceSearchQuery(projectId, keyword,
                deviceTypeIds == null ? Set.of() : Set.copyOf(deviceTypeIds),
                statuses == null ? Set.of() : Set.copyOf(statuses),
                groupId, tagKey, tagValue, cursor, limit);
        return ResponseEntity.ok(service.search(query).map(DeviceResponse::from));
    }
}
