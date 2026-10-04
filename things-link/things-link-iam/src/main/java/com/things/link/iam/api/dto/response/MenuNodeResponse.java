package com.things.link.iam.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.iam.application.MenuItem;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 菜单树节点。
 *
 * <p>结构与控制台前端的 {@code AppRouteRecord} 一致，前端会直接把它注册成
 * Vue Router 路由。
 *
 * <p><b>不下发 {@code requiredPermission}。</b>树在服务端已经按权限过滤完毕，
 * 把「这个菜单需要什么权限」一并告诉客户端，等于给出一份完整的功能地图 ——
 * 包括调用方无权使用的部分。
 *
 * @param name      路由名，全局唯一
 * @param path      路由路径。一级菜单以 {@code /} 开头，子级为相对路径
 * @param component 前端组件路径，相对 {@code src/views} 且不含扩展名
 * @param meta      菜单元信息
 * @param children  子菜单；叶子节点不带此字段
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "菜单树节点")
public record MenuNodeResponse(
        @Schema(description = "路由名", example = "Overview")
        String name,

        @Schema(description = "路由路径", example = "/dashboard")
        String path,

        @Schema(description = "前端组件路径", example = "/dashboard/overview")
        String component,

        @Schema(description = "菜单元信息")
        MenuMetaResponse meta,

        /*
         * 自引用属性必须手写 $ref。springdoc 推导不出 record 的自引用组件类型，
         * 四种写法实测结果如下：
         *
         *   1. 不加任何注解
         *      → children 这个属性在契约里**整个消失**。最坏的一种：接口照常返回
         *        子菜单，契约却说没有这个字段。
         *   2. @Schema(description = "子菜单")
         *      → 只剩一个 description，看不出是数组，openapi-typescript 生成
         *        `children?: unknown`。菜单树里唯一的递归结构，恰好成了前端唯一
         *        没有类型约束的地方。
         *   3. @ArraySchema(schema = @Schema(implementation = 自己))
         *      → springdoc 递归展开自身，/v3/api-docs 直接返回 500。
         *   4. @ArraySchema(schema = @Schema(ref = "..."))  ← 当前写法
         *        显式给出 $ref，绕开推导，产出正确的自引用数组。
         *        代价是 arraySchema 上的 description 会被丢弃，属性没有说明文字。
         *
         * 前三种都不会让任何测试变红 —— 它们只是让契约悄悄失真。
         */
        @ArraySchema(schema = @Schema(ref = "#/components/schemas/MenuNodeResponse"),
                arraySchema = @Schema(description = "子菜单"))
        List<MenuNodeResponse> children) {

    /**
     * 由应用层模型递归转换。
     *
     * @param item 应用层菜单节点
     * @return HTTP 响应节点
     */
    public static MenuNodeResponse from(MenuItem item) {
        return new MenuNodeResponse(
                item.name(),
                item.path(),
                item.component(),
                MenuMetaResponse.from(item.meta()),
                item.children() == null
                        ? null
                        : item.children().stream().map(MenuNodeResponse::from).toList());
    }

}
