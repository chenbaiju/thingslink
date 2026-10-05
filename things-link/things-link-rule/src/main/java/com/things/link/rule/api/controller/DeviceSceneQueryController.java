package com.things.link.rule.api.controller;

import com.things.link.rule.application.scene.DeviceSceneQueryService;
import com.things.link.rule.api.dto.response.DeviceSceneResponse;
import com.things.link.rule.api.dto.response.DeviceSceneExecutionResponse;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import java.util.UUID;

/** 设备当前目标与执行快照使用不同端点，不从定义猜测历史参与。 */
@RestController
@Tag(name="场景", description = "设备关联的可见场景与执行事实")
@SecurityRequirement(name="consoleAccessBearer")
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}")
public class DeviceSceneQueryController {
    private final DeviceSceneQueryService service;
    public DeviceSceneQueryController(DeviceSceneQueryService service) { this.service=service; }
    /**
     * 分页读取可用于设备上下文的项目场景候选。
     * 仅OWNER/ADMIN可读；PROJECT_CANDIDATE不表示设备已绑定或可执行，暂停不可执行。limit为1至50。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/scene-candidates")
    @Operation(operationId="listConsoleDeviceSceneCandidates",summary="分页读取可用于设备上下文的项目场景候选",
            description="仅OWNER/ADMIN可读；PROJECT_CANDIDATE不表示设备已绑定或可执行，暂停不可执行。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceSceneResponse>> definitions(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,@io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.definitions(projectId,deviceId,cursor,limit).map(DeviceSceneResponse::from));
    }
    /**
     * 分页读取设备场景原执行历史。
     * 项目成员可读原执行版本和设备动作记录数；DISPATCHED不表示设备成功。limit为1至50。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/scene-executions")
    @Operation(operationId="listConsoleDeviceSceneExecutions",summary="分页读取设备场景原执行历史",
            description="项目成员可读原执行版本和设备动作记录数；DISPATCHED不表示设备成功。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceSceneExecutionResponse>> history(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,@io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(projectId,deviceId,cursor,limit).map(DeviceSceneExecutionResponse::from));
    }
    private void validate(HttpServletRequest request) {
        if (!Set.of("cursor","limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v->v.length!=1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
