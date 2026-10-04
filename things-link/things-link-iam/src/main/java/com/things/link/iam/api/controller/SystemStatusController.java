package com.things.link.iam.api.controller;

import com.things.link.iam.api.dto.response.SystemStatusResponse;
import com.things.link.iam.application.SystemStatusService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台系统状态只读接口。
 *
 * <p>接口只返回白名单状态，不返回 Actuator 详情。默认安全链要求登录，但不要求当前项目：
 * 平台依赖状态不是租户数据，且用户在尚未选择项目时也需要判断服务是否可用。</p>
 */
@RestController
@RequestMapping("/api/v1/system/status")
@Tag(name = "控制台", description = "菜单、权限点与系统状态")
public class SystemStatusController {

    /** 系统状态查询用例。 */
    private final SystemStatusService systemStatusService;

    /**
     * @param systemStatusService 系统状态查询用例
     */
    public SystemStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    /**
     * 读取当前应用节点与关键运行依赖状态。
     *
     * @return 同一观测时刻的状态快照
     */
    @GetMapping
    @Operation(summary = "系统状态", description = "读取当前应用节点已注册健康探针的脱敏实时状态。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<SystemStatusResponse> get() {
        return ResponseEntity.ok(SystemStatusResponse.from(systemStatusService.get()));
    }
}
