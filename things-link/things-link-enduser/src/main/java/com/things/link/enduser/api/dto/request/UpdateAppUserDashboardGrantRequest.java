package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Console更新终端用户看板READ授权的严格请求信封。
 *
 * <p>S12-2a3b只在HTTP层确认两个字段均为字符串；revision数值范围、状态闭集与当前事实冲突由
 * 管理服务统一映射为60026或60027，避免解析层提前改变业务错误分类。</p>
 *
 * @param expectedRevision 缺行首次授权为字符串0，既有授权为当前正revision
 * @param status 目标授权状态，业务闭集为ACTIVE或REVOKED
 */
@Schema(description = "终端用户看板授权更新请求")
public record UpdateAppUserDashboardGrantRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "0") String expectedRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "REVOKED"})
        String status) {
}
