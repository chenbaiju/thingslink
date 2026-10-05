package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.BatchCurrentValuesRequest;
import com.things.link.device.api.dto.response.BatchCurrentValuesResponse;
import com.things.link.device.api.dto.response.DeviceCurrentValuesResponse;
import com.things.link.device.application.DeviceCurrentValueService;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.domain.DeviceCurrentValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 为看板首屏与实时重连补拉提供多设备、多属性当前值。 */
@Tag(name = "设备当前值", description = "先核验PG接受序号再读取热影子，缓存缺失或故障自动回源")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices/current-values")
public class DeviceCurrentValuesController {
    /** 批量当前值应用服务。 */
    private final DeviceCurrentValueService service;
    /** HTTP 层显式读授权，应用服务保留第二层防线。 */
    private final DeviceApiAuthorization authorization;

    /** @param service 批量当前值应用服务 @param authorization 设备 API 授权守卫 */
    public DeviceCurrentValuesController(DeviceCurrentValueService service, DeviceApiAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    /**
     * 批量读取当前值。
     *
     * @param projectId 项目 ID
     * @param request 多设备、多属性查询条件
     * @return 每台请求设备的当前值
     */
    @PostMapping("/query")
    @Operation(summary = "批量读取当前值",
            description = "一次查询最多100台设备×50个属性；PG核验接受序号，Redis缺失或故障时回源；历史未知序号不缓存")
    public ResponseEntity<BatchCurrentValuesResponse> query(
            @io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Valid @RequestBody BatchCurrentValuesRequest request) {
        authorization.requireRead(projectId);
        List<DeviceCurrentValue> values = service.findAll(projectId, request.deviceIds(), request.propertyKeys());
        Map<UUID, MutableDeviceValues> grouped = new LinkedHashMap<>();
        new LinkedHashSet<>(request.deviceIds()).forEach(deviceId -> grouped.put(deviceId, new MutableDeviceValues()));
        for (DeviceCurrentValue value : values) {
            MutableDeviceValues item = grouped.get(value.deviceId());
            item.values.put(value.propertyKey(), value.value());
            item.occurredAt.put(value.propertyKey(), value.occurredAt());
            item.version = Math.max(item.version, value.shadowVersion());
            if (value.reportedRevision() != null) {
                item.reportedRevisions.put(value.propertyKey(), value.reportedRevision());
            }
            if (value.thingModelVersionId() != null) {
                item.thingModelVersionIds.put(value.propertyKey(), value.thingModelVersionId());
            }
        }
        List<DeviceCurrentValuesResponse> items = grouped.entrySet().stream()
                .map(entry -> new DeviceCurrentValuesResponse(entry.getKey(), entry.getValue().values,
                        entry.getValue().occurredAt, entry.getValue().version, entry.getValue().reportedRevisions,
                        entry.getValue().thingModelVersionIds)).toList();
        return ResponseEntity.ok(new BatchCurrentValuesResponse(items));
    }

    /** 控制器内聚合同一设备的属性，避免应用层依赖 HTTP 响应形状。 */
    private static final class MutableDeviceValues {
        /** 属性值保持请求顺序。 */
        private final Map<String, JsonNode> values = new LinkedHashMap<>();
        /** 属性采集时间与值使用相同键。 */
        private final Map<String, Instant> occurredAt = new LinkedHashMap<>();
        /** 序号保持字符串，客户端不得经Number损失Long精度。 */
        private final Map<String, String> reportedRevisions = new LinkedHashMap<>();
        /** 每属性使用写入时来源，不能用设备当前绑定覆盖历史来源。 */
        private final Map<String, UUID> thingModelVersionIds = new LinkedHashMap<>();
        /** desired快照代次取最大值，不把它当作属性接受顺序。 */
        private int version;
    }
}
