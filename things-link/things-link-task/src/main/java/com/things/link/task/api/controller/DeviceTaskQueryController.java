package com.things.link.task.api.controller;

import com.things.link.task.application.DeviceTaskQueryService;
import com.things.link.task.api.dto.response.DeviceTaskJobResponse;
import com.things.link.task.api.dto.response.DeviceTaskExecutionResponse;
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
@Tag(name="任务调度")
@SecurityRequirement(name="consoleAccessBearer")
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}")
public class DeviceTaskQueryController {
    private final DeviceTaskQueryService service;
    public DeviceTaskQueryController(DeviceTaskQueryService service) { this.service=service; }
    /**
     * 分页读取当前目标包含设备的任务。
     * 服务端按当前静态或动态设备组筛选；含暂停定义，仅反映当前配置。limit为1至50。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/task-jobs")
    @Operation(operationId="listConsoleDeviceTaskJobs",summary="分页读取当前目标包含设备的任务",
            description="服务端按当前静态或动态设备组筛选；含暂停定义，仅反映当前配置。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceTaskJobResponse>> jobs(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,@io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.jobs(projectId,deviceId,cursor,limit).map(DeviceTaskJobResponse::from));
    }
    /**
     * 分页读取设备任务执行目标历史。
     * 按原执行目标快照归属；区分整体执行和该设备状态，不用当前组或任务配置回写历史。limit为1至50。
     *
     * @param projectId 接口指定的项目标识
     * @param deviceId 目标设备标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/task-executions")
    @Operation(operationId="listConsoleDeviceTaskExecutions",summary="分页读取设备任务执行目标历史",
            description="按原执行目标快照归属；区分整体执行和该设备状态，不用当前组或任务配置回写历史。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceTaskExecutionResponse>> history(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "目标设备标识") @PathVariable UUID deviceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,@io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(projectId,deviceId,cursor,limit).map(DeviceTaskExecutionResponse::from));
    }
    private void validate(HttpServletRequest request) {
        if (!Set.of("cursor","limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v->v.length!=1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
