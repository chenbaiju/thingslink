package com.things.link.project.api.dto.response;

import com.things.link.project.domain.DeletedProject;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 项目回收站条目。
 *
 * <p>不返回tenantId、生命周期代次和MQTT projectKey；这些是内部归属或能力边界，
 * 回收站页面只需要识别项目并判断是否仍可恢复。</p>
 *
 * @param id 项目ID
 * @param name 项目名称
 * @param region 项目区域
 * @param timezone IANA项目时区
 * @param deletedAt 删除时刻
 * @param restoreDeadline 恢复期限
 * @param restorable 查询时是否仍在恢复窗口内
 */
@Schema(description = "项目回收站条目")
public record ProjectRecycleBinResponse(
        @Schema(description = "项目 ID") String id,
        @Schema(description = "项目名称") String name,
        @Schema(description = "区域") String region,
        @Schema(description = "IANA 项目时区") String timezone,
        @Schema(description = "删除时刻") String deletedAt,
        @Schema(description = "恢复期限") String restoreDeadline,
        @Schema(description = "当前是否仍可恢复") boolean restorable) {

    /**
     * 把数据库墙钟计算的删除投影转换为HTTP响应，不在JVM重新判断期限。
     * @param deletedProject 删除项目投影
     * @return 回收站响应
     */
    public static ProjectRecycleBinResponse from(DeletedProject deletedProject) {
        return new ProjectRecycleBinResponse(
                deletedProject.project().id().toString(),
                deletedProject.project().name(),
                deletedProject.project().region(),
                deletedProject.project().timezone(),
                deletedProject.deletedAt().toString(),
                deletedProject.restoreDeadline().toString(),
                deletedProject.restorable());
    }
}
