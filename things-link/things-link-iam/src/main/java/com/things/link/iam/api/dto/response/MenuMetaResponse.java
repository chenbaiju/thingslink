package com.things.link.iam.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.iam.application.MenuItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 菜单元信息。
 *
 * <p>字段名与控制台前端的 {@code RouteMeta} 逐字对应，后端下发后前端原样塞进
 * Vue Router 的 {@code meta}。
 *
 * <p>{@code isHide} / {@code isHideTab} / {@code isFullPage} 三项的 JSON 名<b>必须
 * 原样保留</b>。Jackson 对普通 JavaBean 的 {@code isXxx()} 布尔 getter 会去掉 is
 * 前缀，若在这里发生，字段会变成 {@code hide}，前端读不到 —— 不报错，只是开关不
 * 起作用。实测 Jackson 3 对 record 走的是组件名，不套用那条 bean 命名规则，因此
 * <b>不需要</b> {@code @JsonProperty}；这一行为由
 * {@code MenuEndpointTests#preservesIsPrefixedFieldNames} 钉住，将来换序列化器或
 * 改了命名策略会立刻变红。
 *
 * @param title      标题的 i18n key，由前端翻译
 * @param icon       图标名
 * @param keepAlive  是否缓存组件实例
 * @param fixedTab   是否固定标签页
 * @param isHide     是否在菜单中隐藏
 * @param isHideTab  是否不生成标签页
 * @param isFullPage 是否全屏页
 * @param authList   当前用户在本页面上拥有的按钮级权限点
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "菜单元信息")
public record MenuMetaResponse(
        @Schema(description = "标题的 i18n key", example = "menus.dashboard.overview")
        String title,

        @Schema(description = "图标名", example = "ri:home-smile-2-line")
        String icon,

        @Schema(description = "是否缓存组件实例")
        Boolean keepAlive,

        @Schema(description = "是否固定标签页")
        Boolean fixedTab,

        @Schema(description = "是否在菜单中隐藏")
        Boolean isHide,

        @Schema(description = "是否不生成标签页")
        Boolean isHideTab,

        @Schema(description = "是否全屏页")
        Boolean isFullPage,

        @Schema(description = "本页面上的按钮级权限点")
        List<MenuAuthResponse> authList) {

    /**
     * 由应用层模型转换。
     *
     * @param meta 应用层元信息
     * @return HTTP 响应元信息
     */
    static MenuMetaResponse from(MenuItem.Meta meta) {
        return new MenuMetaResponse(
                meta.title(),
                meta.icon(),
                meta.keepAlive(),
                meta.fixedTab(),
                meta.isHide(),
                meta.isHideTab(),
                meta.isFullPage(),
                meta.authList() == null
                        ? null
                        : meta.authList().stream().map(MenuAuthResponse::from).toList());
    }

}
