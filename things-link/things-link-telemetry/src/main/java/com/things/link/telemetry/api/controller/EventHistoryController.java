package com.things.link.telemetry.api.controller;

import com.things.link.telemetry.api.dto.response.DeviceEventPageResponse;
import com.things.link.telemetry.api.dto.response.DeviceEventResponse;
import com.things.link.telemetry.application.EventHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** 平台项目成员专用事件历史读取；不新增App、匿名、写入或重放入口。 */
@RestController
@Tag(name = "设备事件历史", description = "平台项目成员读取当前许可窗口内的原事件发生事实与只读参数投影")
@SecurityRequirement(name = "consoleAccessBearer")
public class EventHistoryController {
    private final EventHistoryService service;
    /** @param service 每次请求重新授权及计算窗口的历史服务 */
    public EventHistoryController(EventHistoryService service) { this.service = service; }

    /**
     * 读取当前设备范围内原发生事实，四种项目成员角色均可读归档项目。
     * @param projectId 项目归属标识
     * @param deviceId 当前设备标识
     * @param eventKey 精确事件键过滤，缺省不过滤
     * @param level INFO、WARNING或ERROR，缺省不过滤
     * @param thingModelVersionId 原不可变模型版本标识，缺省不过滤
     * @param from 包含的四位年RFC3339起点，缺省采用当前有效历史窗口
     * @param to 排他的四位年RFC3339终点，缺省采用当前数据库时刻
     * @param cursor 绑定全部原始过滤与资源的版本一键集游标，缺省首页
     * @param limit 默认20，最少1、最多100；不承诺总数或跨请求快照
     * @param request 封闭且不可重复的实际query
     * @return 不缓存的五字段分页，空有效交集的两端相等
     */
    @GetMapping("/api/v1/projects/{projectId}/devices/{deviceId}/events")
    @Operation(operationId = "listConsoleDeviceEvents", summary = "分页读取设备事件发生历史",
            description = "四角色可读ACTIVE/ARCHIVED；每页重新授权并与owner套餐HISTORY_WINDOW及数据库时刻90天窗口求交；原模型不重新解释，键集不保证跨请求快照。未知/重复query、非法时间或游标10001。")
    public ResponseEntity<DeviceEventPageResponse> list(
            @Parameter(description = "项目标识") @PathVariable UUID projectId,
            @Parameter(description = "当前设备标识") @PathVariable UUID deviceId,
            @Parameter(description = "精确事件键，[A-Za-z0-9][A-Za-z0-9_-]{0,63}") @RequestParam(required = false) String eventKey,
            @Parameter(description = "原发生级别：INFO、WARNING、ERROR") @RequestParam(required = false) String level,
            @Parameter(description = "原不可变模型版本UUID") @RequestParam(required = false) String thingModelVersionId,
            @Parameter(description = "包含起点，四位年RFC3339，最多9位小数") @RequestParam(required = false) String from,
            @Parameter(description = "排他终点，四位年RFC3339，必须晚于起点") @RequestParam(required = false) String to,
            @Parameter(description = "Base64URL版本一游标，最多8192字符，缺省首页") @RequestParam(required = false) String cursor,
            @Parameter(description = "默认20，1至100", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")) @RequestParam(required = false) String limit,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(projectId, deviceId, request.getParameterMap()));
    }

    /**
     * 读取当前授权设备及有效窗口内单条原事件事实，不接受任何query过滤。
     * @param projectId 项目归属标识
     * @param deviceId 当前设备标识
     * @param messageId 原消息标识
     * @param request 实际query，必须为空
     * @return 不缓存的十三字段原事实，消息未知、跨设备或窗外统一404/30072
     */
    @GetMapping("/api/v1/projects/{projectId}/devices/{deviceId}/events/{messageId}")
    @Operation(operationId = "getConsoleDeviceEvent", summary = "读取单条设备事件发生事实",
            description = "先复核项目成员与当前设备归属；原模型字段及参数投影只读。消息未知、跨设备/项目或历史窗口外统一404/30072，不泄露存在性；不允许query。")
    public ResponseEntity<DeviceEventResponse> detail(
            @Parameter(description = "项目标识") @PathVariable UUID projectId,
            @Parameter(description = "当前设备标识") @PathVariable UUID deviceId,
            @Parameter(description = "原消息UUID") @PathVariable UUID messageId,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(projectId, deviceId, messageId, request.getParameterMap()));
    }
}
