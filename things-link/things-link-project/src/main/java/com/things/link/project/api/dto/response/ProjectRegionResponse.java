package com.things.link.project.api.dto.response;

import com.things.link.project.domain.ProjectRegion;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 项目区域响应。
 *
 * @param code                   可用区编码
 * @param name                   可用区展示名
 * @param areaCode               地域编码
 * @param areaName               地域展示名
 * @param enabled                是否可见
 * @param projectCreationEnabled 是否允许新建项目选择
 * @param displayOrder           展示顺序
 */
@Schema(description = "项目区域")
public record ProjectRegionResponse(
        @Schema(description = "可用区编码", example = "sh-1") String code,
        @Schema(description = "可用区展示名", example = "上海1区") String name,
        @Schema(description = "地域编码", example = "shanghai") String areaCode,
        @Schema(description = "地域展示名", example = "上海") String areaName,
        @Schema(description = "是否可见", example = "true") boolean enabled,
        @Schema(description = "是否允许新建项目选择", example = "true") boolean projectCreationEnabled,
        @Schema(description = "展示顺序", example = "10") int displayOrder) {

    /**
     * 从领域对象转换响应。
     *
     * @param region 领域区域
     * @return HTTP 响应
     */
    public static ProjectRegionResponse from(ProjectRegion region) {
        return new ProjectRegionResponse(
                region.code(),
                region.name(),
                region.areaCode(),
                region.areaName(),
                region.enabled(),
                region.projectCreationEnabled(),
                region.displayOrder());
    }

}
