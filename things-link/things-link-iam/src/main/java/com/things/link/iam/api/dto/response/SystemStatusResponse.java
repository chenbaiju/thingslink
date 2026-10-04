package com.things.link.iam.api.dto.response;

import com.things.link.iam.application.SystemHealthReader;
import com.things.link.iam.application.SystemStatusService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 控制台系统状态响应。
 *
 * @param status 总体状态
 * @param observedAt UTC 观测时间
 * @param dependencies 真实运行依赖状态
 */
@Schema(description = "控制台系统状态快照")
public record SystemStatusResponse(
        @Schema(description = "总体状态", allowableValues = {"UP", "DEGRADED", "DOWN"})
        String status,
        @Schema(description = "UTC 观测时间")
        Instant observedAt,
        @Schema(description = "已注册的关键运行依赖")
        List<DependencyStatusResponse> dependencies) {

    /**
     * 从应用层快照创建 API 契约。
     *
     * @param snapshot 应用层快照
     * @return API 响应
     */
    public static SystemStatusResponse from(SystemStatusService.Snapshot snapshot) {
        return new SystemStatusResponse(snapshot.status(), snapshot.observedAt(),
                snapshot.dependencies().stream().map(DependencyStatusResponse::from).toList());
    }

    /**
     * 单个依赖的脱敏状态。
     *
     * @param code 稳定机器标识
     * @param name 简体中文名称
     * @param status 健康状态
     */
    @Schema(description = "运行依赖健康状态")
    public record DependencyStatusResponse(
            @Schema(description = "稳定机器标识") String code,
            @Schema(description = "简体中文名称") String name,
            @Schema(description = "健康状态",
                    allowableValues = {"UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN"})
            String status) {

        /**
         * @param health 应用层脱敏状态
         * @return API 明细
         */
        private static DependencyStatusResponse from(SystemHealthReader.DependencyHealth health) {
            return new DependencyStatusResponse(health.code(), health.name(), health.status());
        }
    }
}
