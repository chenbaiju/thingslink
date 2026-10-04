package com.things.link.iam.api.dto.response;

import com.things.link.iam.application.MenuItem;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 按钮级权限点。
 *
 * <p>前端 {@code useAuth().hasAuth('device:control')} 按 {@code authMark} 匹配，
 * 字段名由前端模板定死。
 *
 * <p><b>它只控制按钮显不显示。</b>服务端必须对同一个权限点独立校验 ——
 * 藏起来的按钮拦不住直接调接口的人（架构文档 7.2）。
 *
 * @param title    按钮说明
 * @param authMark 权限点标识，形如 {@code resource:action}
 */
@Schema(description = "按钮级权限点")
public record MenuAuthResponse(
        @Schema(description = "按钮说明", example = "移除成员") String title,
        @Schema(description = "权限点标识", example = "member:remove") String authMark) {

    /**
     * 由应用层模型转换。
     *
     * @param auth 应用层权限点
     * @return HTTP 响应权限点
     */
    static MenuAuthResponse from(MenuItem.AuthPoint auth) {
        return new MenuAuthResponse(auth.title(), auth.code());
    }

}
