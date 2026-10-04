package com.things.link.project.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑项目请求。
 *
 * <p>只能改项目名称，不能改区域。区域决定设备接入域名和数据落点，创建后不可变更
 * （架构文档 7.1）；若允许在编辑接口里带 region，前端迟早会把它做成可编辑字段。
 *
 * @param name 项目名称。不要求唯一，重名由项目 ID 区分
 */
@Schema(description = "编辑项目请求")
public record UpdateProjectRequest(

        @Schema(description = "项目名称", example = "厂区环境监测")
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 128, message = "项目名称过长")
        String name) {
}
